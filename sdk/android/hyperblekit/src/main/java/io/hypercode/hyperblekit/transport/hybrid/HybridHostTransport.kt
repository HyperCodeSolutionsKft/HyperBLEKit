package io.hypercode.hyperblekit.transport.hybrid

import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.Chunking
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.GameHostTransport
import io.hypercode.hyperblekit.transport.HostTransportEvent
import io.hypercode.hyperblekit.transport.LinkId
import io.hypercode.hyperblekit.transport.RealtimeFrames
import io.hypercode.hyperblekit.transport.aware.AwareControl
import io.hypercode.hyperblekit.transport.aware.AwareHostEvent
import io.hypercode.hyperblekit.transport.aware.AwareHostPath
import io.hypercode.hyperblekit.transport.aware.AwareWire
import io.hypercode.hyperblekit.transport.aware.DataPath
import io.hypercode.hyperblekit.transport.aware.DeclineReason
import io.hypercode.hyperblekit.transport.aware.MainScheduler
import kotlinx.coroutines.flow.StateFlow

/**
 * Host side over BLE with Wi-Fi Aware as an upgrade per link (wire specification, "Wi-Fi Aware
 * data path"). To the runtime it is one more [GameHostTransport]: the same links, the same
 * events, the same limits.
 *
 * Every link comes up over [ble], exactly as without Wi-Fi Aware, and lives exactly as long as
 * its BLE connection. A client that can use Wi-Fi Aware says so with `HELLO`; the host answers
 * with an `OFFER` (or `DECLINE`), and once [aware] reports the data path up, the host's messages
 * and frames to that client travel over it. When the data path fails, they go back to BLE
 * without the runtime noticing; what was lost on the way is recovered by the protocol's own
 * rules, as for a dropped BLE chunk.
 *
 * Limits do not change with the path: the frame payload limit and the largest message are those
 * of the link's BLE chunk size, so a game behaves the same when a link falls back.
 *
 * Main thread only, like the transports it wraps.
 */
internal class HybridHostTransport(
    private val ble: GameHostTransport,
    private val aware: AwareHostPath,
    private val scheduler: MainScheduler,
) : GameHostTransport {

    private class Link(val id: LinkId, val maxChunkLength: Int, val gate: InboundGate) {
        /** An offer was sent and the data path is not up yet. */
        var offered = false

        /** The data path is up: messages and frames to this client go over Wi-Fi Aware. */
        var awareUp = false
    }

    private var listener: ((HostTransportEvent) -> Unit)? = null
    private val links = LinkedHashMap<LinkId, Link>()

    override val state: StateFlow<AdvertisingState> get() = ble.state
    override val canHost: Boolean get() = ble.canHost

    init {
        ble.setListener(::bleEvent)
        aware.setListener(::awareEvent)
    }

    override fun setListener(listener: ((HostTransportEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun start(gameCode: String) {
        dropAwarePaths()
        if (aware.isSupported) aware.start() else HyperBleLog.event("Wi-Fi Aware not supported here: hosting over BLE only")
        ble.start(gameCode)
    }

    override fun stop() {
        dropAwarePaths()
        aware.stop()
        ble.stop()
    }

    override fun stopAfterFlush() {
        aware.stopAfterFlush()
        ble.stopAfterFlush()
    }

    /** A host that can take clients over Wi-Fi Aware says so in its GameInfo. */
    override fun setGameInfo(info: ByteArray) {
        ble.setGameInfo(if (aware.isSupported) AwareWire.withAwareFlag(info, paired = aware.acceptsPairedClients) else info)
    }

    override fun send(message: ByteArray, to: LinkId): Int {
        val link = links[to]
        if (link == null || !link.awareUp) return ble.send(message, to)
        val maxLength = Chunking.MAX_CHUNKS * (link.maxChunkLength - Chunking.HEADER_SIZE)
        if (message.size > maxLength) {
            // The same limit as on BLE, so a message that works here still works after a fallback.
            HyperBleLog.event("send failed to $to: message of ${message.size} bytes exceeds $maxLength", LogLevel.ERROR)
            return 0
        }
        if (aware.send(to, message)) return 1
        return ble.send(message, to)
    }

    override fun sendFrame(payload: ByteArray, to: LinkId): FrameSendResult {
        val link = links[to]
        if (link == null || !link.awareUp) return ble.sendFrame(payload, to)
        if (payload.size > RealtimeFrames.maxPayload(link.maxChunkLength)) return FrameSendResult.FAILED
        return aware.sendFrame(to, payload)
    }

    override fun staleFramesDropped(link: LinkId): Int = ble.staleFramesDropped(link)

    private fun emit(event: HostTransportEvent) {
        listener?.invoke(event)
    }

    private fun sendControl(link: LinkId, control: AwareControl) {
        ble.send(AwareControl.encode(control), link)
    }

    private fun dropAwarePaths() {
        links.values.forEach(::forget)
    }

    // --- BLE ---

    private fun bleEvent(event: HostTransportEvent) {
        when (event) {
            is HostTransportEvent.LinkUp -> {
                links[event.link]?.let(::forget)
                val id = event.link
                links[id] = Link(
                    id,
                    event.maxChunkLength,
                    InboundGate(scheduler, deliver = { emit(HostTransportEvent.MessageReceived(id, it, 1)) }, log = HyperBleLog::event),
                )
                emit(event)
            }
            is HostTransportEvent.LinkDown -> {
                links.remove(event.link)?.let(::forget)
                emit(event)
            }
            is HostTransportEvent.Restarted -> {
                // Every BLE link is gone with the radio, and their data paths with them.
                links.values.toList().forEach(::forget)
                links.clear()
                emit(event)
            }
            is HostTransportEvent.MessageReceived ->
                if (AwareWire.isControl(event.bytes)) control(event.link, event.bytes) else emit(event)
            is HostTransportEvent.FrameReceived -> emit(event)
        }
    }

    /** Drops the link's data path or offer; the link itself stays as BLE has it. */
    private fun forget(link: Link) {
        if (link.offered || link.awareUp) aware.close(link.id)
        link.offered = false
        link.awareUp = false
        link.gate.reset()
    }

    private fun control(id: LinkId, bytes: ByteArray) {
        val link = links[id] ?: return
        when (val control = AwareControl.decode(bytes)) {
            is AwareControl.Hello -> hello(link)
            is AwareControl.PathSwitch ->
                if (control.path == DataPath.WIFI_AWARE) {
                    link.gate.open()
                } else {
                    HyperBleLog.event("PATH $id: client is back on BLE")
                }
            AwareControl.Failed -> {
                HyperBleLog.event("PATH $id: client could not set up Wi-Fi Aware, staying on BLE", LogLevel.WARN)
                if (link.offered) aware.close(id)
                link.offered = false
            }
            is AwareControl.Offer, is AwareControl.Decline, null ->
                HyperBleLog.event("PATH $id: ignored transport-control message bytes=${bytes.size}", LogLevel.WARN)
        }
    }

    private fun hello(link: Link) {
        if (link.offered || link.awareUp) return
        val reason = when {
            !aware.isReady -> DeclineReason.UNAVAILABLE
            links.values.count { it.offered || it.awareUp } >= aware.maxDataPaths -> DeclineReason.NO_FREE_DATA_PATH
            else -> null
        }
        val offer = if (reason == null) aware.offer(link.id) else null
        if (offer == null) {
            val why = reason ?: DeclineReason.UNAVAILABLE
            HyperBleLog.event("PATH ${link.id}: Wi-Fi Aware declined (${why.name.lowercase()}), staying on BLE")
            sendControl(link.id, AwareControl.Decline(why))
            return
        }
        link.offered = true
        HyperBleLog.event("PATH ${link.id}: offering Wi-Fi Aware")
        sendControl(link.id, AwareControl.Offer(offer))
    }

    // --- Wi-Fi Aware ---

    private fun awareEvent(event: AwareHostEvent) {
        when (event) {
            is AwareHostEvent.PathUp -> {
                val link = links[event.link] ?: return aware.close(event.link)
                link.offered = false
                link.awareUp = true
                // After every message already queued on BLE, so the client can keep the order.
                sendControl(link.id, AwareControl.PathSwitch(DataPath.WIFI_AWARE))
                HyperBleLog.event("PATH ${link.id}: up over Wi-Fi Aware")
            }
            is AwareHostEvent.PathDown -> {
                val link = links[event.link] ?: return
                val wasUp = link.awareUp
                link.offered = false
                link.awareUp = false
                link.gate.reset()
                if (wasUp) {
                    sendControl(link.id, AwareControl.PathSwitch(DataPath.BLE))
                    HyperBleLog.event("PATH ${link.id}: fallback to BLE (${event.reason})", LogLevel.WARN)
                } else {
                    HyperBleLog.event("PATH ${link.id}: Wi-Fi Aware not set up (${event.reason}), staying on BLE", LogLevel.WARN)
                }
            }
            is AwareHostEvent.MessageReceived -> links[event.link]?.gate?.fromAware(event.bytes)
            is AwareHostEvent.FrameReceived ->
                if (links.containsKey(event.link)) emit(HostTransportEvent.FrameReceived(event.link, event.payload))
        }
    }
}
