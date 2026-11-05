package io.hypercode.hyperblekit.transport.hybrid

import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.Chunking
import io.hypercode.hyperblekit.transport.ClientTransportEvent
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.transport.RealtimeFrames
import io.hypercode.hyperblekit.transport.aware.AwareClientEvent
import io.hypercode.hyperblekit.transport.aware.AwareClientPath
import io.hypercode.hyperblekit.transport.aware.AwareControl
import io.hypercode.hyperblekit.transport.aware.AwareWire
import io.hypercode.hyperblekit.transport.aware.DataPath
import io.hypercode.hyperblekit.transport.aware.MainScheduler

/**
 * Client side over BLE with Wi-Fi Aware as an upgrade (wire specification, "Wi-Fi Aware data
 * path"); see [HybridHostTransport] for the idea. The connection is set up and searched for
 * over [ble] exactly as without Wi-Fi Aware. Once it is ready, and if the host's GameInfo says
 * it takes Wi-Fi Aware, this side asks for a data path; once [aware] reports it up, messages
 * and frames to the host go over it, and back over BLE if it fails.
 *
 * The connection lives exactly as long as its BLE link: [ClientTransportEvent.Disconnected]
 * comes from BLE only.
 *
 * Main thread only.
 */
internal class HybridClientTransport(
    private val ble: GameClientTransport,
    private val aware: AwareClientPath,
    private val scheduler: MainScheduler,
) : GameClientTransport {

    private enum class AwareState { NONE, ASKED, CONNECTING, UP }

    private var listener: ((ClientTransportEvent) -> Unit)? = null
    private var hostTakesAware = false
    private var awareState = AwareState.NONE
    private var maxChunkLength = 0
    private var helloAttempts = 0

    /** Counts BLE connections, so a retry armed for an earlier one does nothing. */
    private var connection = 0

    /** [disconnect] waits for the data path to flush: nothing can be sent meanwhile. */
    private var isClosing = false

    private val gate = InboundGate(
        scheduler,
        deliver = { listener?.invoke(ClientTransportEvent.MessageReceived(it, 1)) },
        log = HyperBleLog::event,
    )

    init {
        ble.setListener(::bleEvent)
        aware.setListener(::awareEvent)
    }

    override fun setListener(listener: ((ClientTransportEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(gameId: String) {
        resetAware()
        ble.connect(gameId)
    }

    override fun connectByGameCode(gameCode: String) {
        resetAware()
        ble.connectByGameCode(gameCode)
    }

    override fun skipCurrentHost() {
        resetAware()
        ble.skipCurrentHost()
    }

    override fun send(message: ByteArray): Int {
        if (isClosing) return 0
        if (awareState != AwareState.UP) return ble.send(message)
        val maxLength = Chunking.MAX_CHUNKS * (maxChunkLength - Chunking.HEADER_SIZE)
        if (message.size > maxLength) {
            HyperBleLog.event("send failed: message of ${message.size} bytes exceeds $maxLength", LogLevel.ERROR)
            return 0
        }
        if (aware.send(message)) return 1
        return ble.send(message)
    }

    override fun sendFrame(payload: ByteArray): FrameSendResult {
        if (isClosing) return FrameSendResult.FAILED
        if (awareState != AwareState.UP) return ble.sendFrame(payload)
        if (payload.size > RealtimeFrames.maxPayload(maxChunkLength)) return FrameSendResult.FAILED
        return aware.sendFrame(payload)
    }

    override val staleFramesDropped: Int get() = ble.staleFramesDropped

    override fun checkTimeouts() = ble.checkTimeouts()

    override fun disconnect() {
        if (awareState == AwareState.UP && !isClosing) {
            // The last messages (a LEAVE) may be on the data path: they go out before BLE closes,
            // or the host could see the BLE link drop before they arrive.
            isClosing = true
            val armed = connection
            aware.closeAfterFlush {
                // BLE may have dropped meanwhile, and reported it: then there is nothing left to close.
                if (armed != connection) return@closeAfterFlush
                isClosing = false
                resetAware()
                ble.disconnect()
            }
            return
        }
        if (isClosing) return
        resetAware()
        ble.disconnect()
    }

    /** Forgets the data path of the current connection; a new connection starts on BLE only. */
    private fun resetAware() {
        connection += 1
        isClosing = false
        if (awareState == AwareState.CONNECTING || awareState == AwareState.UP) aware.close()
        awareState = AwareState.NONE
        hostTakesAware = false
        helloAttempts = 0
        gate.reset()
    }

    private fun sendControl(control: AwareControl) {
        ble.send(AwareControl.encode(control))
    }

    // --- BLE ---

    private fun bleEvent(event: ClientTransportEvent) {
        when (event) {
            is ClientTransportEvent.GameInfoRead -> {
                hostTakesAware = AwareWire.hasAwareFlag(event.bytes)
                listener?.invoke(event)
            }
            is ClientTransportEvent.Ready -> {
                maxChunkLength = event.maxChunkLength
                listener?.invoke(event)
                if (hostTakesAware && aware.isSupported && awareState == AwareState.NONE) sayHello()
            }
            is ClientTransportEvent.MessageReceived ->
                if (AwareWire.isControl(event.bytes)) control(event.bytes) else listener?.invoke(event)
            is ClientTransportEvent.Disconnected -> {
                // The host may have dropped the link while the data path was flushing.
                isClosing = false
                resetAware()
                listener?.invoke(event)
            }
            is ClientTransportEvent.HostFound, is ClientTransportEvent.FrameReceived -> listener?.invoke(event)
        }
    }

    private fun sayHello() {
        helloAttempts += 1
        awareState = AwareState.ASKED
        sendControl(AwareControl.Hello())
    }

    private fun control(bytes: ByteArray) {
        when (val control = AwareControl.decode(bytes)) {
            is AwareControl.Offer -> {
                if (awareState != AwareState.ASKED) return
                awareState = AwareState.CONNECTING
                HyperBleLog.event("PATH host offers Wi-Fi Aware, connecting")
                aware.connect(control.offer)
            }
            is AwareControl.Decline -> {
                if (awareState != AwareState.ASKED) return
                awareState = AwareState.NONE
                if (helloAttempts >= MAX_HELLO_ATTEMPTS) {
                    HyperBleLog.event("PATH host declined Wi-Fi Aware (${control.reason.name.lowercase()}), staying on BLE")
                    return
                }
                HyperBleLog.event(
                    "PATH host declined Wi-Fi Aware (${control.reason.name.lowercase()}), " +
                        "asking again in ${HELLO_RETRY_MILLIS / MILLIS_PER_SECOND} s",
                )
                val armed = connection
                scheduler.schedule(HELLO_RETRY_MILLIS) {
                    if (armed == connection && awareState == AwareState.NONE && !isClosing) sayHello()
                }
            }
            is AwareControl.PathSwitch ->
                if (control.path == DataPath.WIFI_AWARE) gate.open() else HyperBleLog.event("PATH host is back on BLE")
            is AwareControl.Hello, AwareControl.Failed, null ->
                HyperBleLog.event("PATH ignored transport-control message bytes=${bytes.size}", LogLevel.WARN)
        }
    }

    // --- Wi-Fi Aware ---

    private fun awareEvent(event: AwareClientEvent) {
        when (event) {
            AwareClientEvent.Up -> {
                if (awareState != AwareState.CONNECTING) return aware.close()
                awareState = AwareState.UP
                // After every message already queued on BLE, so the host can keep the order.
                sendControl(AwareControl.PathSwitch(DataPath.WIFI_AWARE))
                HyperBleLog.event("PATH up over Wi-Fi Aware")
            }
            is AwareClientEvent.Down -> {
                val previous = awareState
                if (previous != AwareState.CONNECTING && previous != AwareState.UP) return
                awareState = AwareState.NONE
                gate.reset()
                if (previous == AwareState.UP) {
                    sendControl(AwareControl.PathSwitch(DataPath.BLE))
                    HyperBleLog.event("PATH fallback to BLE (${event.reason})", LogLevel.WARN)
                } else {
                    sendControl(AwareControl.Failed)
                    HyperBleLog.event("PATH Wi-Fi Aware not set up (${event.reason}), staying on BLE", LogLevel.WARN)
                }
            }
            is AwareClientEvent.MessageReceived -> if (awareState == AwareState.UP) gate.fromAware(event.bytes)
            is AwareClientEvent.FrameReceived ->
                if (awareState == AwareState.UP) listener?.invoke(ClientTransportEvent.FrameReceived(event.payload))
        }
    }

    internal companion object {
        /** A declined client asks this many times in all per connection. */
        const val MAX_HELLO_ATTEMPTS = 3
        const val HELLO_RETRY_MILLIS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
