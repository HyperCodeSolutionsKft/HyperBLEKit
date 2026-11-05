package io.hypercode.hyperblekit.transport

import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.transport.aware.AwareClientEvent
import io.hypercode.hyperblekit.transport.aware.AwareClientPath
import io.hypercode.hyperblekit.transport.aware.AwareControl
import io.hypercode.hyperblekit.transport.aware.AwareHostEvent
import io.hypercode.hyperblekit.transport.aware.AwareHostPath
import io.hypercode.hyperblekit.transport.aware.AwareOffer
import io.hypercode.hyperblekit.transport.aware.AwareWire
import io.hypercode.hyperblekit.transport.aware.DataPath
import io.hypercode.hyperblekit.transport.aware.DeclineReason
import io.hypercode.hyperblekit.transport.aware.MainScheduler
import io.hypercode.hyperblekit.transport.hybrid.HybridClientTransport
import io.hypercode.hyperblekit.transport.hybrid.HybridHostTransport
import io.hypercode.hyperblekit.transport.hybrid.InboundGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hybrid transports' own rules, over hand-driven fakes: what goes where, and in what order. */
class HybridTransportTest {

    private class ManualScheduler : MainScheduler {
        var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun schedule(delayMillis: Long, block: () -> Unit) {
            tasks += (now + delayMillis) to block
        }

        fun advance(millis: Long) {
            now += millis
            val due = tasks.filter { it.first <= now }
            tasks.removeAll(due)
            due.forEach { it.second() }
        }
    }

    private class BleClient : GameClientTransport {
        private var listener: ((ClientTransportEvent) -> Unit)? = null
        val sent = mutableListOf<ByteArray>()
        var disconnects = 0
        override fun setListener(listener: ((ClientTransportEvent) -> Unit)?) {
            this.listener = listener
        }
        fun emit(event: ClientTransportEvent) = listener?.invoke(event)
        override fun connect(gameId: String) = Unit
        override fun connectByGameCode(gameCode: String) = Unit
        override fun skipCurrentHost() = Unit
        override fun send(message: ByteArray): Int {
            sent += message
            return 1
        }
        override fun sendFrame(payload: ByteArray) = FrameSendResult.QUEUED
        override val staleFramesDropped = 0
        override fun checkTimeouts() = Unit
        override fun disconnect() {
            disconnects += 1
        }
        val controls get() = sent.filter(AwareWire::isControl).map { AwareControl.decode(it) }
    }

    private class AwareClient : AwareClientPath {
        private var listener: ((AwareClientEvent) -> Unit)? = null
        var connectedTo: AwareOffer? = null
        val sent = mutableListOf<ByteArray>()
        var flushThen: (() -> Unit)? = null
        var closes = 0
        override val isSupported = true
        override fun setListener(listener: ((AwareClientEvent) -> Unit)?) {
            this.listener = listener
        }
        fun emit(event: AwareClientEvent) = listener?.invoke(event)
        override fun connect(offer: AwareOffer) {
            connectedTo = offer
        }
        override fun send(message: ByteArray): Boolean {
            sent += message
            return true
        }
        override fun sendFrame(payload: ByteArray) = FrameSendResult.QUEUED
        override fun close() {
            closes += 1
        }
        override fun closeAfterFlush(then: () -> Unit) {
            flushThen = then
        }
    }

    private val scheduler = ManualScheduler()
    private val ble = BleClient()
    private val aware = AwareClient()
    private val client = HybridClientTransport(ble, aware, scheduler)
    private val received = mutableListOf<String>()
    private val offer = AwareOffer(ByteArray(8) { 1 }, ByteArray(8) { 2 }, "passphrase")

    init {
        client.setListener { event ->
            if (event is ClientTransportEvent.MessageReceived) received += String(event.bytes)
        }
    }

    private fun bytes(text: String) = text.toByteArray()
    private val awareInfo get() = AwareWire.withAwareFlag(bytes("{\"v\":1,\"hostPlatform\":\"android\"}"))
    private fun control(control: AwareControl) = ClientTransportEvent.MessageReceived(AwareControl.encode(control), 1)

    /** Ready with a host that takes Wi-Fi Aware, offered and up. */
    private fun upgradedClient() {
        ble.emit(ClientTransportEvent.GameInfoRead(awareInfo))
        ble.emit(ClientTransportEvent.Ready(CHUNK))
        ble.emit(control(AwareControl.Offer(offer)))
        aware.emit(AwareClientEvent.Up)
    }

    @Test
    fun `a client asks for Wi-Fi Aware only when the host's GameInfo has the flag`() {
        ble.emit(ClientTransportEvent.GameInfoRead(bytes("{\"v\":1}")))
        ble.emit(ClientTransportEvent.Ready(CHUNK))
        assertTrue(ble.controls.isEmpty())

        ble.emit(ClientTransportEvent.GameInfoRead(AwareWire.withAwareFlag(bytes("{\"v\":1,\"hostPlatform\":\"ios\"}"))))
        ble.emit(ClientTransportEvent.Ready(CHUNK))
        assertTrue("never across platforms", ble.controls.isEmpty())

        ble.emit(ClientTransportEvent.GameInfoRead(awareInfo))
        ble.emit(ClientTransportEvent.Ready(CHUNK))
        assertEquals(listOf(AwareControl.Hello()), ble.controls)
    }

    @Test
    fun `an upgraded client sends over Wi-Fi Aware after telling the host over BLE`() {
        upgradedClient()
        assertNotNull(aware.connectedTo)
        assertEquals(AwareControl.PathSwitch(DataPath.WIFI_AWARE), ble.controls.last())

        assertEquals(1, client.send(bytes("move")))
        assertEquals("move", String(aware.sent.single()))
        assertTrue(ble.sent.none { String(it) == "move" })
    }

    @Test
    fun `messages from Wi-Fi Aware wait until the BLE messages before the switch are in`() {
        upgradedClient()
        aware.emit(AwareClientEvent.MessageReceived(bytes("m3")))
        aware.emit(AwareClientEvent.MessageReceived(bytes("m4")))
        assertEquals(emptyList<String>(), received)

        ble.emit(ClientTransportEvent.MessageReceived(bytes("m1"), 1))
        ble.emit(ClientTransportEvent.MessageReceived(bytes("m2"), 1))
        ble.emit(control(AwareControl.PathSwitch(DataPath.WIFI_AWARE)))
        aware.emit(AwareClientEvent.MessageReceived(bytes("m5")))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), received)
    }

    @Test
    fun `held messages are delivered after the gate timeout if the switch never arrives`() {
        upgradedClient()
        aware.emit(AwareClientEvent.MessageReceived(bytes("m1")))
        scheduler.advance(InboundGate.TIMEOUT_MILLIS - 1)
        assertEquals(emptyList<String>(), received)
        scheduler.advance(1)
        assertEquals(listOf("m1"), received)
    }

    @Test
    fun `transport-control messages never reach the runtime`() {
        upgradedClient()
        ble.emit(control(AwareControl.PathSwitch(DataPath.WIFI_AWARE)))
        ble.emit(control(AwareControl.Decline(DeclineReason.UNAVAILABLE)))
        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun `when the data path fails the client goes back to BLE and says so`() {
        upgradedClient()
        aware.emit(AwareClientEvent.Down("lost"))
        assertEquals(AwareControl.PathSwitch(DataPath.BLE), ble.controls.last())
        client.send(bytes("after"))
        assertEquals("after", String(ble.sent.last()))
        assertTrue(aware.sent.isEmpty())
    }

    @Test
    fun `a data path that cannot be set up is reported as failed`() {
        ble.emit(ClientTransportEvent.GameInfoRead(awareInfo))
        ble.emit(ClientTransportEvent.Ready(CHUNK))
        ble.emit(control(AwareControl.Offer(offer)))
        aware.emit(AwareClientEvent.Down("refused"))
        assertEquals(AwareControl.Failed, ble.controls.last())
    }

    @Test
    fun `a declined client asks again later, three times in all`() {
        ble.emit(ClientTransportEvent.GameInfoRead(awareInfo))
        ble.emit(ClientTransportEvent.Ready(CHUNK))
        repeat(HybridClientTransport.MAX_HELLO_ATTEMPTS + 1) {
            ble.emit(control(AwareControl.Decline(DeclineReason.NO_FREE_DATA_PATH)))
            scheduler.advance(HybridClientTransport.HELLO_RETRY_MILLIS)
        }
        assertEquals(HybridClientTransport.MAX_HELLO_ATTEMPTS, ble.controls.count { it is AwareControl.Hello })
    }

    @Test
    fun `limits stay those of the BLE link on Wi-Fi Aware`() {
        upgradedClient()
        val maxMessage = Chunking.MAX_CHUNKS * (CHUNK - Chunking.HEADER_SIZE)
        assertEquals(1, client.send(ByteArray(maxMessage)))
        assertEquals(0, client.send(ByteArray(maxMessage + 1)))
        assertEquals(FrameSendResult.QUEUED, client.sendFrame(ByteArray(RealtimeFrames.maxPayload(CHUNK))))
        assertEquals(FrameSendResult.FAILED, client.sendFrame(ByteArray(RealtimeFrames.maxPayload(CHUNK) + 1)))
    }

    @Test
    fun `disconnect flushes the data path before BLE closes`() {
        upgradedClient()
        client.disconnect()
        assertEquals(0, ble.disconnects)
        assertEquals(0, client.send(bytes("late")))
        checkNotNull(aware.flushThen).invoke()
        assertEquals(1, ble.disconnects)
    }

    // --- Host ---

    private class BleHost : GameHostTransport {
        private var listener: ((HostTransportEvent) -> Unit)? = null
        val sent = mutableListOf<Pair<LinkId, ByteArray>>()
        var lastGameInfo = ByteArray(0)
        override val state: StateFlow<AdvertisingState> = MutableStateFlow(AdvertisingState.Idle)
        override val canHost = true
        override fun setListener(listener: ((HostTransportEvent) -> Unit)?) {
            this.listener = listener
        }
        fun emit(event: HostTransportEvent) = listener?.invoke(event)
        override fun start(gameCode: String) = Unit
        override fun stop() = Unit
        override fun stopAfterFlush() = Unit
        override fun setGameInfo(info: ByteArray) {
            lastGameInfo = info
        }
        override fun send(message: ByteArray, to: LinkId): Int {
            sent += to to message
            return 1
        }
        override fun sendFrame(payload: ByteArray, to: LinkId) = FrameSendResult.QUEUED
        override fun staleFramesDropped(link: LinkId) = 0
        fun controls(link: LinkId) = sent.filter { it.first == link && AwareWire.isControl(it.second) }.map { AwareControl.decode(it.second) }
    }

    private class AwareHost : AwareHostPath {
        private var listener: ((AwareHostEvent) -> Unit)? = null
        override var isSupported = true
        override var isReady = true
        override var maxDataPaths = 1
        val closed = mutableListOf<LinkId>()
        val sent = mutableListOf<Pair<LinkId, ByteArray>>()
        override fun setListener(listener: ((AwareHostEvent) -> Unit)?) {
            this.listener = listener
        }
        fun emit(event: AwareHostEvent) = listener?.invoke(event)
        override fun start() = Unit
        override fun stop() = Unit
        override fun stopAfterFlush() = Unit
        override fun offer(link: LinkId) = AwareOffer(ByteArray(8), ByteArray(8) { link.length.toByte() }, "passphrase")
        override fun send(link: LinkId, message: ByteArray): Boolean {
            sent += link to message
            return true
        }
        override fun sendFrame(link: LinkId, payload: ByteArray) = FrameSendResult.QUEUED
        override fun close(link: LinkId) {
            closed += link
        }
    }

    private fun hello(link: LinkId) = HostTransportEvent.MessageReceived(link, AwareControl.encode(AwareControl.Hello()), 1)

    @Test
    fun `a host flags its GameInfo only when it supports Wi-Fi Aware`() {
        val bleHost = BleHost()
        val awareHost = AwareHost()
        val host = HybridHostTransport(bleHost, awareHost, scheduler)
        host.setGameInfo(bytes("{\"v\":1}"))
        assertTrue(AwareWire.hasAwareFlag(bleHost.lastGameInfo))
        awareHost.isSupported = false
        host.setGameInfo(bytes("{\"v\":1}"))
        assertFalse(AwareWire.hasAwareFlag(bleHost.lastGameInfo))
    }

    @Test
    fun `a host offers, declines when it is not ready or full, and keeps control messages to itself`() {
        val bleHost = BleHost()
        val awareHost = AwareHost()
        val host = HybridHostTransport(bleHost, awareHost, scheduler)
        val runtime = mutableListOf<HostTransportEvent>()
        host.setListener { runtime += it }
        bleHost.emit(HostTransportEvent.LinkUp("a", CHUNK))
        bleHost.emit(HostTransportEvent.LinkUp("b", CHUNK))

        awareHost.isReady = false
        bleHost.emit(hello("a"))
        assertEquals(AwareControl.Decline(DeclineReason.UNAVAILABLE), bleHost.controls("a").last())

        awareHost.isReady = true
        bleHost.emit(hello("a"))
        assertTrue(bleHost.controls("a").last() is AwareControl.Offer)
        bleHost.emit(hello("b"))
        assertEquals(AwareControl.Decline(DeclineReason.NO_FREE_DATA_PATH), bleHost.controls("b").last())
        assertTrue(runtime.none { it is HostTransportEvent.MessageReceived })
    }

    @Test
    fun `an upgraded link sends over Wi-Fi Aware, and BLE going down closes its data path`() {
        val bleHost = BleHost()
        val awareHost = AwareHost()
        val host = HybridHostTransport(bleHost, awareHost, scheduler)
        val runtime = mutableListOf<HostTransportEvent>()
        host.setListener { runtime += it }
        bleHost.emit(HostTransportEvent.LinkUp("a", CHUNK))
        bleHost.emit(hello("a"))
        awareHost.emit(AwareHostEvent.PathUp("a"))
        assertEquals(AwareControl.PathSwitch(DataPath.WIFI_AWARE), bleHost.controls("a").last())

        host.send(bytes("state"), "a")
        assertEquals("state", String(awareHost.sent.single().second))

        awareHost.emit(AwareHostEvent.MessageReceived("a", bytes("early")))
        assertNull(runtime.filterIsInstance<HostTransportEvent.MessageReceived>().firstOrNull())
        bleHost.emit(HostTransportEvent.MessageReceived("a", AwareControl.encode(AwareControl.PathSwitch(DataPath.WIFI_AWARE)), 1))
        assertEquals("early", String(runtime.filterIsInstance<HostTransportEvent.MessageReceived>().single().bytes))

        bleHost.emit(HostTransportEvent.LinkDown("a"))
        assertEquals(listOf("a"), awareHost.closed)
        assertTrue(runtime.last() is HostTransportEvent.LinkDown)
    }

    private companion object {
        const val CHUNK = 20
    }
}
