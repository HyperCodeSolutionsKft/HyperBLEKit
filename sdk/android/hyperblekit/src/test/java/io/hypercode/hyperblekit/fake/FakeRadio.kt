package io.hypercode.hyperblekit.fake

import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.DiscoveredGame
import io.hypercode.hyperblekit.HyperBleKit
import io.hypercode.hyperblekit.HyperBleKitConfig
import io.hypercode.hyperblekit.IdentityStore
import io.hypercode.hyperblekit.LocalIdentity
import io.hypercode.hyperblekit.Platform
import io.hypercode.hyperblekit.ScanningState
import io.hypercode.hyperblekit.protocol.Message
import io.hypercode.hyperblekit.protocol.WireJson
import io.hypercode.hyperblekit.transport.ClientTransportEvent
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.transport.GameHostTransport
import io.hypercode.hyperblekit.transport.GameScanner
import io.hypercode.hyperblekit.transport.HostTransportEvent
import io.hypercode.hyperblekit.transport.LinkId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The air between the phones of one test: every [FakeDevice] made from it can advertise, find and
 * connect to the others, in one JVM and on virtual time. Nothing is delivered inside the call that
 * sends it: every event is queued on [dispatcher], in order, like the real transports post to the
 * main thread. A test moves time with [advance].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FakeRadio(
    /**
     * True: the kits run on a dispatcher that, like `Dispatchers.Main.immediate` in the shipped
     * kit, runs a coroutine inline up to its first suspension. False: everything a kit launches
     * is queued. Either way the radio queues what it delivers, as the real transports post to the
     * main thread, and a session's state is published from that same queue, as `Dispatchers.Main`
     * does in the shipped kit.
     */
    immediateKits: Boolean = false,
) {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)

    /** What every kit's coroutines run on. */
    val kitDispatcher: CoroutineDispatcher = if (immediateKits) UnconfinedTestDispatcher(scheduler) else dispatcher
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val devices = mutableListOf<FakeDevice>()
    private var nextLink = 1

    /** The monotonic clock of every device, in ms. */
    val nowMillis: Long get() = scheduler.currentTime

    /** Runs everything that is due within the next [millis] of virtual time. */
    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    fun post(delayMillis: Long = 0, block: () -> Unit) {
        scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            block()
        }
    }

    fun newLink(): LinkId = "L${nextLink++}"

    /** The hosts [seeker] could find right now: advertising, in range, and not itself. */
    fun visibleHosts(seeker: FakeDevice): List<FakeHostTransport> =
        if (!seeker.reachable) {
            emptyList()
        } else {
            devices.filter { it !== seeker && it.reachable && it.host.gameCode != null }.map { it.host }
        }

    /** Someone started or stopped advertising, or came into or went out of range. */
    fun advertisingChanged() {
        post {
            for (device in devices) {
                device.scanner.refresh()
                device.clients.toList().forEach { it.search() }
            }
        }
    }

    fun shutDown() {
        devices.forEach { it.scope.cancel() }
        scope.cancel()
    }

    companion object {
        /** The chunk size every fake link reports. */
        const val CHUNK_LENGTH = 244

        /** How long a client transport that disconnects keeps its link up to send what is queued. */
        const val CLIENT_FLUSH_MILLIS = 200L
    }
}

/** One phone: its kit over fake transports, with its own identity and coroutine scope. */
internal class FakeDevice(val radio: FakeRadio, name: String, config: HyperBleKitConfig) {
    val address = "AA:${radio.devices.size + 1}"
    val scope = CoroutineScope(SupervisorJob() + radio.kitDispatcher)
    val host = FakeHostTransport(this)
    val scanner = FakeScanner(this)
    val clients = mutableListOf<FakeClientTransport>()

    /** False while the phone is switched off or out of range: it finds nobody and nobody finds it. */
    var reachable = true
        private set

    private val identity = LocalIdentity(
        object : IdentityStore {
            private val values = HashMap<String, String>()
            override fun get(key: String): String? = values[key]
            override fun put(key: String, value: String) {
                values[key] = value
            }
        }
    ).also { it.storedPlayerName = name }

    val kit = HyperBleKit(
        config, identity, host, scanner,
        clientTransportFactory = { FakeClientTransport(this).also { clients += it } },
        scope, publishDispatcher = radio.dispatcher, elapsedMillis = { radio.nowMillis },
    )

    init {
        radio.devices += this
    }

    /** Every link of this phone breaks and it cannot be found until [comeBack]; the app keeps running. */
    fun goOutOfRange() {
        reachable = false
        host.dropLinks("link lost", tellHost = true)
        clients.forEach { it.linkLost("link lost") }
        radio.advertisingChanged()
    }

    fun comeBack() {
        reachable = true
        radio.advertisingChanged()
    }

    /** The phone is gone without a word: battery out. Nothing of it runs any more. */
    fun die() {
        scope.cancel()
        host.setListener(null)
        clients.forEach { it.setListener(null) }
        goOutOfRange()
    }
}

internal class FakeHostTransport(private val device: FakeDevice) : GameHostTransport {
    private val radio = device.radio
    val address: String get() = device.address
    private val _state = MutableStateFlow<AdvertisingState>(AdvertisingState.Idle)
    override val state: StateFlow<AdvertisingState> = _state
    override var canHost = true
    private var listener: ((HostTransportEvent) -> Unit)? = null

    /** The code being advertised; null while not hosting. */
    var gameCode: String? = null
        private set
    var gameInfo = ByteArray(0)
        private set

    /** How often hosting was started on this phone. */
    var startCount = 0
        private set
    val links = LinkedHashMap<LinkId, FakeClientTransport>()

    /** The newest `stateVersion` the host put into a message, for comparing with what clients got. */
    var lastStateVersionSent: Int? = null
        private set

    override fun setListener(listener: ((HostTransportEvent) -> Unit)?) {
        this.listener = listener
    }

    fun emit(event: HostTransportEvent) {
        listener?.invoke(event)
    }

    override fun start(gameCode: String) {
        startCount += 1
        this.gameCode = gameCode
        _state.value = AdvertisingState.Advertising(gameCode)
        radio.advertisingChanged()
    }

    override fun stop() {
        gameCode = null
        _state.value = AdvertisingState.Idle
        dropLinks("link lost", tellHost = false)
        radio.advertisingChanged()
    }

    override fun stopAfterFlush() {
        gameCode = null
        _state.value = AdvertisingState.Idle
        radio.advertisingChanged()
        // What was sent before this is already queued and reaches the clients first.
        radio.post { dropLinks("link lost", tellHost = false) }
    }

    /** Every client loses its link. [tellHost]: this side hears `LinkDown` too, as when the radio breaks them. */
    fun dropLinks(reason: String, tellHost: Boolean) {
        val dropped = links.values.toList()
        if (!tellHost) links.clear()
        dropped.forEach { it.linkLost(reason) }
    }

    override fun setGameInfo(info: ByteArray) {
        gameInfo = info
    }

    override fun send(message: ByteArray, to: LinkId): Int {
        val client = links[to] ?: return 0
        WireJson.decodeMessage(message)?.stateVersion?.let { lastStateVersionSent = it }
        radio.post { if (links[to] === client) client.receive(ClientTransportEvent.MessageReceived(message, 1)) }
        return 1
    }

    override fun sendFrame(payload: ByteArray, to: LinkId): FrameSendResult {
        val client = links[to] ?: return FrameSendResult.FAILED
        radio.post { if (links[to] === client) client.receive(ClientTransportEvent.FrameReceived(payload)) }
        return FrameSendResult.QUEUED
    }

    override fun staleFramesDropped(link: LinkId): Int = 0
}

internal class FakeClientTransport(private val device: FakeDevice) : GameClientTransport {
    private val radio = device.radio
    private var listener: ((ClientTransportEvent) -> Unit)? = null

    /** The host this transport is linked to (or setting a link up with) and its handle for the link. */
    private var host: FakeHostTransport? = null
    private var link: LinkId? = null
    private var ready = false

    /** The game code being searched for; null while not searching. */
    private var searchCode: String? = null
    private val skipped = HashSet<String>()

    /** Counts link attempts, so a queued step of an attempt that was abandoned does nothing. */
    private var attempt = 0

    /** How often a connection was asked for: [connect] and [connectByGameCode] calls. */
    var connectCalls = 0
        private set

    /** A test may lose some of the host's messages on the way: return true to drop one. */
    var dropIncoming: (Message) -> Boolean = { false }

    /** Every message the host sent over this transport, decoded, oldest first. */
    val received = mutableListOf<Message>()

    /** The newest `stateVersion` in a message from the host. */
    val lastStateVersionReceived: Int? get() = received.lastOrNull { it.stateVersion != null }?.stateVersion

    override fun setListener(listener: ((ClientTransportEvent) -> Unit)?) {
        this.listener = listener
    }

    /** An event from the host side of the link. */
    fun receive(event: ClientTransportEvent) {
        if (event is ClientTransportEvent.MessageReceived) {
            val message = WireJson.decodeMessage(event.bytes)
            if (message != null) {
                if (dropIncoming(message)) return
                received += message
            }
        }
        listener?.invoke(event)
    }

    override fun connect(gameId: String) {
        connectCalls += 1
        closeSilently()
        searchCode = null
        val target = radio.visibleHosts(device).firstOrNull { it.address == gameId }
        if (target == null) {
            val current = ++attempt
            radio.post { if (attempt == current) listener?.invoke(ClientTransportEvent.Disconnected("Connect failed: $gameId not found")) }
        } else {
            linkTo(target, found = false)
        }
    }

    override fun connectByGameCode(gameCode: String) {
        connectCalls += 1
        closeSilently()
        searchCode = gameCode
        search()
    }

    /** Connects to a host that advertises the wanted code, if one is in range now. */
    fun search() {
        val code = searchCode ?: return
        if (host != null) return
        val target = radio.visibleHosts(device).firstOrNull { it.gameCode == code && it.address !in skipped } ?: return
        linkTo(target, found = true)
    }

    private fun linkTo(target: FakeHostTransport, found: Boolean) {
        val current = ++attempt
        val id = radio.newLink()
        host = target
        link = id
        radio.post {
            if (attempt != current) return@post
            if (found) listener?.invoke(ClientTransportEvent.HostFound(target.address))
            if (attempt != current) return@post
            listener?.invoke(ClientTransportEvent.GameInfoRead(target.gameInfo))
            // The listener may have skipped this host or disconnected.
            if (attempt != current) return@post
            if (target.gameCode == null) {
                host = null
                link = null
                listener?.invoke(ClientTransportEvent.Disconnected("Connect failed: the host is gone"))
                return@post
            }
            target.links[id] = this
            ready = true
            target.emit(HostTransportEvent.LinkUp(id, FakeRadio.CHUNK_LENGTH))
            listener?.invoke(ClientTransportEvent.Ready(FakeRadio.CHUNK_LENGTH))
        }
    }

    override fun skipCurrentHost() {
        val current = host ?: return
        if (searchCode == null) {
            closeSilently()
            radio.post { listener?.invoke(ClientTransportEvent.Disconnected("Connect failed: not the wanted host")) }
            return
        }
        skipped += current.address
        closeSilently()
        radio.post { search() }
    }

    override fun send(message: ByteArray): Int {
        val target = host
        val id = link
        if (!ready || target == null || id == null) return 0
        radio.post { if (target.links[id] === this) target.emit(HostTransportEvent.MessageReceived(id, message, 1)) }
        return 1
    }

    override fun sendFrame(payload: ByteArray): FrameSendResult {
        val target = host
        val id = link
        if (!ready || target == null || id == null) return FrameSendResult.FAILED
        radio.post { if (target.links[id] === this) target.emit(HostTransportEvent.FrameReceived(id, payload)) }
        return FrameSendResult.QUEUED
    }

    override val staleFramesDropped: Int = 0

    override fun checkTimeouts() = Unit

    /**
     * Like the real transport, the link stays up for a moment so that what is queued goes out —
     * and what the host sends meanwhile still arrives.
     */
    override fun disconnect() {
        searchCode = null
        skipped.clear()
        val current = ++attempt
        if (ready) {
            radio.post(FakeRadio.CLIENT_FLUSH_MILLIS) {
                if (attempt != current) return@post
                closeSilently()
                listener?.invoke(ClientTransportEvent.Disconnected(null))
            }
        } else {
            host = null
            link = null
            radio.post { if (attempt == current) listener?.invoke(ClientTransportEvent.Disconnected(null)) }
        }
    }

    /** The host dropped the link, or went out of range. */
    fun linkLost(reason: String) {
        val target = host ?: return
        val id = link
        val current = ++attempt
        host = null
        link = null
        ready = false
        if (id != null && target.links.remove(id) != null) radio.post { target.emit(HostTransportEvent.LinkDown(id)) }
        radio.post { if (attempt == current) listener?.invoke(ClientTransportEvent.Disconnected(reason)) }
    }

    /** Ends the link without telling this side's listener; the host hears that the link is down. */
    private fun closeSilently() {
        attempt += 1
        val target = host
        val id = link
        host = null
        link = null
        ready = false
        if (target != null && id != null && target.links.remove(id) != null) {
            radio.post { target.emit(HostTransportEvent.LinkDown(id)) }
        }
    }
}

internal class FakeScanner(private val device: FakeDevice) : GameScanner {
    private val _state = MutableStateFlow<ScanningState>(ScanningState.Idle)
    override val state: StateFlow<ScanningState> = _state
    private val _games = MutableStateFlow<List<DiscoveredGame>>(emptyList())
    override val discoveredGames: StateFlow<List<DiscoveredGame>> = _games
    override val timeToFirstResultMillis = MutableStateFlow<Long?>(null)

    override fun startScan() {
        _games.value = emptyList()
        _state.value = ScanningState.Scanning
        refresh()
    }

    fun refresh() {
        if (_state.value != ScanningState.Scanning) return
        val now = device.radio.nowMillis
        _games.value = device.radio.visibleHosts(device).map {
            DiscoveredGame(it.address, it.gameCode.orEmpty(), Platform.UNKNOWN, rssi = -50, firstSeenMillis = now, lastSeenMillis = now)
        }
    }

    override fun stopScan() {
        _state.value = ScanningState.Idle
    }
}
