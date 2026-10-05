package io.hypercode.hyperblekit.runtime

import android.os.SystemClock
import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.RealtimeFrame
import io.hypercode.hyperblekit.RealtimeLink
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogDirection
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.game.GameModule
import io.hypercode.hyperblekit.game.RealtimeSpec
import io.hypercode.hyperblekit.protocol.GameInfo
import io.hypercode.hyperblekit.GamePhase
import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.diagnostics.TestJson
import io.hypercode.hyperblekit.protocol.WireJson
import io.hypercode.hyperblekit.realtimeFrameFlow
import io.hypercode.hyperblekit.protocol.seatNumber
import io.hypercode.hyperblekit.session.HostSession
import io.hypercode.hyperblekit.PingStats
import io.hypercode.hyperblekit.session.RealtimeTxStats
import io.hypercode.hyperblekit.session.TickSchedule
import io.hypercode.hyperblekit.diagnostics.HostSpeedTest
import io.hypercode.hyperblekit.diagnostics.SpeedTestConstants
import io.hypercode.hyperblekit.diagnostics.SpeedTestPayloads
import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.transport.ClientTransportEvent
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.transport.GameHostTransport
import io.hypercode.hyperblekit.transport.HostTransportEvent
import io.hypercode.hyperblekit.transport.LinkId
import io.hypercode.hyperblekit.transport.RealtimeFrames
import io.hypercode.hyperblekit.diagnostics.HostSpeedTestState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/** What the game part of a session shows: the phase and this device's view of the game. */
internal data class GameUi(
    val phase: String,
    /** Wire value of `game`; null in the lobby. */
    val moduleId: String?,
    /** In the game module's own format; only that module's screen reads it. */
    val view: JsonPayload?,
)

/** What a host that steps down hands to the client role it continues in. */
internal data class StepDown(
    val gameCode: String,
    /** The epoch this device hosted at: a host advertising a lower one is stale. */
    val hostEpoch: Int,
    /** The last player list, shown until the new host's `STATE_SYNC` arrives. */
    val players: List<Player>,
    val reason: String,
)

/**
 * Runs a hosted session: connects the transport to [HostSession], drives the heartbeat, passes
 * the host's own game requests (start, end, its moves) to the session and writes everything to
 * the debug log.
 *
 * **Stepping down** (host migration). The host probes when it has seated clients but no link to
 * any of them for [GameConstants.HOST_ALONE_PROBE_AFTER_MILLIS], when its radio just came back,
 * or when a seat that held a snapshot has been disconnected for that long (it may have taken
 * over). Probing means: with a client transport, look for its own game code advertised by another
 * device and read that host's GameInfo — while still serving the clients it has. A higher
 * `hostEpoch` — or the same one with a lower `hostId` — means the game moved on without it: it
 * just stops hosting (its clients find the winning host through their reconnect loop) and
 * [onStepDown] continues as a client. Otherwise it keeps hosting and probes again while the
 * reason lasts.
 *
 * **Speed test** (test equipment for the real-time lane): in the lobby,
 * a dot moved 30 times a second and sent as real-time frames to every linked client, with the
 * clients' inputs and once-a-second stats coming back. It stays out of the session and stops when
 * a game starts or hosting stops.
 *
 * **Real-time games** (wire specification "Real-time games"): while one is being played, a timer asks the
 * session for a tick on the game's fixed schedule ([TickSchedule]: late ticks back to back, missed
 * ticks dropped after a stall), sends every n-th world frame to every linked client, hands every
 * frame to the host's own screen through [realtime], and logs `RT tx` once a second. The session
 * decides everything else: pausing, inputs, the host's input delay, coarse state changes.
 * Frames of the speed-test kinds still go to the speed test; every frame counts as traffic.
 *
 * Everything runs on the main thread: the transports deliver their events there and [scope] must
 * use the main dispatcher, so the session is only ever touched from one thread.
 */
internal class HostRuntime(
    private val transport: GameHostTransport,
    private val session: HostSession,
    /** A client transport for each probe. */
    private val probeTransportFactory: () -> GameClientTransport,
    private val scope: CoroutineScope,
    private val onStepDown: (StepDown) -> Unit,
    /** The monotonic clock every duration is measured with, in ms. */
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    val gameCode: String = session.gameId
    val hostId: String = session.hostId
    val hostEpoch: Int = session.hostEpoch

    private var heartbeat: Job? = null

    /** Monotonic time of the previous heartbeat / probe check, to notice that the app was stalled. */
    private var lastHeartbeatAtMillis = 0L
    private var lastAloneCheckAtMillis = 0L
    private var aloneCheck: Job? = null
    private var hasStopped = false

    /** Monotonic time since when no client has a link; null while not alone. */
    private var aloneSinceMillis: Long? = null

    /** Monotonic time before which no further probe is logged or started; 0 = at once. */
    private var nextProbeAtMillis = 0L
    private var probeCount = 0

    /** The probe in progress: searching, or connected and reading GameInfo. */
    private var probe: GameClientTransport? = null

    val advertisingState: StateFlow<AdvertisingState> = transport.state

    private val _players = MutableStateFlow(session.players)
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _pingStats = MutableStateFlow(session.pingStats)
    val pingStats: StateFlow<PingStats> = _pingStats.asStateFlow()

    /** Who would take over, first to last. */
    private val _successors = MutableStateFlow(session.successorIds)
    val successors: StateFlow<List<String>> = _successors.asStateFlow()

    /** Every offline player → whole seconds until the session removes them; refreshed every second. */
    private val _offlineSecondsLeft = MutableStateFlow(session.offlineSecondsLeft)
    val offlineSecondsLeft: StateFlow<Map<String, Long>> = _offlineSecondsLeft.asStateFlow()

    /** The phase and the host's own view of the game. */
    private val _game = MutableStateFlow(gameUi())
    val game: StateFlow<GameUi> = _game.asStateFlow()

    /** Why the host's last game request was refused; null once the game moves on. */
    private val _gameNotice = MutableStateFlow<String?>(null)
    val gameNotice: StateFlow<String?> = _gameNotice.asStateFlow()

    /** Outcome of the last debug JSON test, sent or received; null until there is one. */
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** The last received test frame, indented for reading; null until one arrives. */
    private val _testJsonReceived = MutableStateFlow<String?>(null)
    val testJsonReceived: StateFlow<String?> = _testJsonReceived.asStateFlow()

    private val speedTest = HostSpeedTest()
    private var speedTestJob: Job? = null

    /** Chunk size per linked client, for saying why a world frame does not fit. */
    private val linkChunkLengths = HashMap<LinkId, Int>()

    /** The stats lines of the last second, one per client. */
    private var speedTestLines = emptyList<String>()

    private val _speedTest = MutableStateFlow(speedTestUi())
    val speedTestUi: StateFlow<HostSpeedTestState> = _speedTest.asStateFlow()

    /** The game screen's link while the game is a real-time one; null otherwise. */
    private val _realtime = MutableStateFlow<HostRealtimeLink?>(null)
    val realtime: StateFlow<RealtimeLink?> = _realtime.asStateFlow()

    /** Runs the real-time game's ticks while one is being played. */
    private var realtimeJob: Job? = null
    private val realtimeStats = RealtimeTxStats()

    fun start() {
        if (heartbeat != null || hasStopped) return
        HyperBleLog.event(
            "hosting $gameCode as $hostId (${session.players.first { it.id == hostId }.name}), " +
                "epoch $hostEpoch, stateVersion ${session.stateVersion}"
        )
        session.restoreNote?.let { HyperBleLog.event(it, LogLevel.WARN) }
        // A restored real-time game: its ticking starts (paused until the players are back).
        syncRealtime()
        transport.setListener(::handle)
        publishGameInfo()
        transport.start(gameCode)
        lastHeartbeatAtMillis = elapsedMillis()
        lastAloneCheckAtMillis = lastHeartbeatAtMillis
        heartbeat = scope.launch {
            while (true) {
                delay(GameConstants.PING_INTERVAL_MILLIS)
                heartbeatTick(elapsedMillis())
            }
        }
        aloneCheck = scope.launch {
            while (true) {
                delay(GameConstants.HOST_ALONE_CHECK_INTERVAL_MILLIS)
                aloneTick(elapsedMillis())
            }
        }
    }

    /**
     * A tick that comes much later than its interval means this app was stalled (screen off, in
     * the background): the clients' silence was really the host's own, so nobody is judged on it.
     */
    private fun heartbeatTick(monotonicNow: Long) {
        val gap = monotonicNow - lastHeartbeatAtMillis
        lastHeartbeatAtMillis = monotonicNow
        val stalled = gap > GameConstants.STALL_FACTOR * GameConstants.PING_INTERVAL_MILLIS
        if (stalled) {
            HyperBleLog.event("host was stalled for ${gap / MILLIS_PER_SECOND} s, not judging clients", LogLevel.WARN)
        }
        apply(session.heartbeat(System.currentTimeMillis(), judgeClients = !stalled))
    }

    /** Same idea for the probe timers: after a stall "alone" and "successor gone" count from now. */
    private fun aloneTick(monotonicNow: Long) {
        val gap = monotonicNow - lastAloneCheckAtMillis
        lastAloneCheckAtMillis = monotonicNow
        if (gap > GameConstants.STALL_FACTOR * GameConstants.PING_INTERVAL_MILLIS) {
            HyperBleLog.event("host was stalled for ${gap / MILLIS_PER_SECOND} s, probe timers start over", LogLevel.WARN)
            aloneSinceMillis = null
            session.restartGoneTimers()
        }
        // The removal countdown on screen; the removal itself happens on the heartbeat.
        _offlineSecondsLeft.value = session.offlineSecondsLeft
        checkAlone(monotonicNow)
    }

    /**
     * The app goes to the background while hosting, where it cannot be relied on to keep serving:
     * hand the game to the first successor with a live link, exactly like leaving on purpose.
     * Returns what is needed to rejoin as a client later, or null if nobody could take over — then
     * hosting simply continues.
     */
    fun handOverForBackground(): StepDown? {
        if (hasStopped) return null
        val successor = session.successorIds.firstOrNull()
        if (successor == null) {
            HyperBleLog.event("app in background: no successor with a live link, nothing handed over, still hosting", LogLevel.WARN)
            return null
        }
        HyperBleLog.event("app in background: handing over to $successor", LogLevel.WARN)
        val handover = StepDown(gameCode, hostEpoch, session.players, "handed over to $successor in the background")
        leave()
        return handover
    }

    /**
     * The user leaves the host screen: hands the game to the first successor (`HOST_SNAPSHOT`,
     * then `HOST_LEAVING`) or ends it (`GAME_OVER` `host_left`), lets that go out, then stops
     * advertising and closes the GATT server.
     */
    fun leave() {
        if (hasStopped) return
        val output = session.hostLeaving(System.currentTimeMillis())
        if (output.outgoing.isEmpty()) HyperBleLog.event("host leaving: no client to tell")
        apply(output)
        halt()
        transport.stopAfterFlush()
    }

    /** Stops hosting at once, without telling anyone: for stepping down. */
    private fun stop() {
        if (hasStopped) return
        halt()
        transport.stop()
    }

    private fun halt() {
        stopSpeedTest("hosting stopped")
        realtimeJob?.cancel()
        realtimeJob = null
        hasStopped = true
        heartbeat?.cancel()
        heartbeat = null
        aloneCheck?.cancel()
        aloneCheck = null
        endProbe()
        transport.setListener(null)
    }

    /** Starts a game of [module] with everyone who is connected now. */
    fun startGame(module: GameModule) {
        if (hasStopped) return
        val output = session.startGame(module, System.currentTimeMillis(), Random.Default) { link ->
            linkChunkLengths[link]?.let(RealtimeFrames::maxPayload) ?: 0
        }
        // A new game: its screen gets a fresh link, without the last frame of the game before.
        if (output.refusal == null) _realtime.value = null
        apply(output)
    }

    /** Ends the game, running or finished: everyone is back in the lobby. */
    fun endGame() {
        if (hasStopped) return
        apply(session.endGame(System.currentTimeMillis()))
    }

    /** Removes an offline player at once instead of waiting for the countdown. */
    fun removePlayer(playerId: String) {
        if (hasStopped) return
        HyperBleLog.event("host removes $playerId")
        apply(session.removePlayer(playerId, System.currentTimeMillis()))
    }

    /** The host's own move: through the same game module as a client's, without BLE. */
    fun gameAction(action: JSONObject) {
        if (hasStopped) return
        apply(session.hostAction(action, System.currentTimeMillis(), Random.Default))
    }

    /** Debug: sends the wire specification's game-state snapshot, as raw bytes, to every joined client. */
    fun sendTestJson() {
        val recipients = session.clientRecipients
        if (recipients.isEmpty()) {
            HyperBleLog.event("test JSON not sent: no joined client", LogLevel.WARN)
            _testResult.value = "Test JSON: no joined client to send to"
            return
        }
        val bytes = TestJson.bytes()
        for (recipient in recipients) {
            val chunks = transport.send(bytes, recipient.link)
            HyperBleLog.frame(LogDirection.SENT, TestJson.LOG_TYPE, bytes.size, chunks, "to=${recipient.label}")
        }
        _testResult.value = "Test JSON: sent ${bytes.size} B to ${recipients.size} client(s)"
    }

    private fun handle(event: HostTransportEvent) {
        val now = System.currentTimeMillis()
        when (event) {
            is HostTransportEvent.LinkUp -> {
                HyperBleLog.event("client ${event.link} subscribed, chunk size ${event.maxChunkLength}")
                speedTest.linkUp(event.link)
                linkChunkLengths[event.link] = event.maxChunkLength
                publishSpeedTest()
            }
            is HostTransportEvent.LinkDown -> {
                HyperBleLog.event("client ${event.link} link down")
                speedTest.linkDown(event.link)
                realtimeStats.linkDown(event.link)
                linkChunkLengths.remove(event.link)
                publishSpeedTest()
                apply(session.linkDown(event.link, now))
            }
            is HostTransportEvent.FrameReceived -> frameReceived(event.link, event.payload, now)
            is HostTransportEvent.MessageReceived -> {
                if (TestJson.isTestFrame(event.bytes)) {
                    testJsonReceived(event, now)
                    return
                }
                val message = WireJson.decodeMessage(event.bytes)
                if (message == null) {
                    HyperBleLog.event(
                        "RX undecodable message from ${event.link} bytes=${event.bytes.size} chunks=${event.chunks}",
                        LogLevel.WARN,
                    )
                    return
                }
                val output = session.receive(message, event.link, now, Random.Default)
                HyperBleLog.message(
                    LogDirection.RECEIVED, message, event.bytes.size, event.chunks,
                    extra = output.roundTripMs?.let { "rtt=${it}ms" },
                )
                apply(output)
            }
            HostTransportEvent.Restarted -> {
                // Others may have moved on while the radio was off: look right away.
                if (session.players.size > 1) {
                    val monotonicNow = elapsedMillis()
                    // As if it had been alone for the whole probe delay already.
                    aloneSinceMillis = monotonicNow - GameConstants.HOST_ALONE_PROBE_AFTER_MILLIS
                    nextProbeAtMillis = monotonicNow
                    checkAlone(monotonicNow)
                }
            }
        }
    }

    /**
     * A frame from a client: speed-test inputs go to the speed test, the rest to the session (a
     * real-time game's input). Every frame counts as traffic. Not logged one by one.
     */
    private fun frameReceived(link: LinkId, payload: ByteArray, nowMillis: Long) {
        val monotonicNow = elapsedMillis()
        if (payload.isNotEmpty() && (payload[0].toInt() and BYTE_MASK) == SpeedTestPayloads.KIND_INPUT) {
            speedTest.inputReceived(link, payload, monotonicNow)
        }
        val output = session.frameReceived(link, payload, nowMillis)
        if (output.inputTaken) realtimeStats.inputReceived(link, monotonicNow)
        // Usually nothing: only a client that had gone silent brings a change.
        if (output.outgoing.isNotEmpty() || output.notes.isNotEmpty()) apply(output)
    }

    /** A debug test frame: compared and logged, never applied to the session. */
    private fun testJsonReceived(event: HostTransportEvent.MessageReceived, nowMillis: Long) {
        val matches = TestJson.matches(event.bytes)
        val expected = TestJson.bytes().size
        HyperBleLog.frame(
            LogDirection.RECEIVED, TestJson.LOG_TYPE, event.bytes.size, event.chunks,
            "from=${session.labelFor(event.link)} " + if (matches) "match=yes" else "match=no expected=$expected",
        )
        val verdict = if (matches) "match" else "MISMATCH (expected $expected B)"
        _testResult.value = "Test JSON: received ${event.bytes.size} B in ${event.chunks} chunks — $verdict"
        _testJsonReceived.value = TestJson.pretty(event.bytes)
        // It still proves the client is alive.
        apply(session.trafficReceived(event.link, nowMillis))
    }

    /** Sends what the session decided, one log line per recipient. */
    private fun apply(output: HostSession.Output) {
        output.notes.forEach { HyperBleLog.event(it) }
        for (outgoing in output.outgoing) {
            val bytes = WireJson.encode(outgoing.message)
            for (recipient in outgoing.recipients) {
                val chunks = transport.send(bytes, recipient.link)
                HyperBleLog.message(LogDirection.SENT, outgoing.message, bytes.size, chunks, extra = "to=${recipient.label}")
            }
        }
        val refusal = output.refusal
        if (refusal != null) HyperBleLog.event("refused: $refusal", LogLevel.WARN)
        val players = session.players
        val game = gameUi()
        // GameInfo carries the player count and, through the phase, whether the lobby is open.
        val gameInfoChanged = _players.value != players || _game.value.phase != game.phase
        _players.value = players
        if (refusal != null) {
            _gameNotice.value = refusal
        } else if (_game.value != game) {
            _gameNotice.value = null
        }
        _game.value = game
        if (game.phase != GamePhase.LOBBY) stopSpeedTest("a game started")
        syncRealtime()
        if (gameInfoChanged) publishGameInfo()
        _pingStats.value = session.pingStats
        _successors.value = session.successorIds
        _offlineSecondsLeft.value = session.offlineSecondsLeft
    }

    private fun gameUi() = GameUi(session.phase, session.gameModuleId, session.ownView)

    private fun publishGameInfo() {
        val bytes = WireJson.encode(session.gameInfo)
        transport.setGameInfo(bytes)
        HyperBleLog.event("GameInfo ${String(bytes, Charsets.UTF_8)}")
    }

    // --- Stepping down ---

    /**
     * Why the host should look for another host of its game right now, or null:
     *  - `alone`: clients hold seats but none has had a link for the probe delay (or the radio
     *    just came back);
     *  - `successor pN gone`: a seat that held a snapshot when it dropped has been disconnected
     *    for the probe delay — it may have taken over. The other clients are served meanwhile.
     */
    private fun probeReason(monotonicNow: Long): String? {
        val alone = session.players.size > 1 && session.clientRecipients.isEmpty()
        if (!alone) {
            aloneSinceMillis = null
        } else if (aloneSinceMillis == null) {
            aloneSinceMillis = monotonicNow
        }
        val aloneSince = aloneSinceMillis
        if (aloneSince != null && monotonicNow - aloneSince >= GameConstants.HOST_ALONE_PROBE_AFTER_MILLIS) {
            return "alone"
        }
        val gone = session.successorsGone(GameConstants.HOST_ALONE_PROBE_AFTER_MILLIS)
        return gone.firstOrNull()?.let { "successor $it gone" }
    }

    /** Probes while there is a reason to, again every probe delay; ends the probe when there is none. */
    private fun checkAlone(monotonicNow: Long) {
        val reason = probeReason(monotonicNow)
        if (reason == null) {
            nextProbeAtMillis = 0
            if (probe != null) {
                HyperBleLog.event("probe stopped: clients are linked and no successor is missing")
                endProbe()
            }
            return
        }
        if (monotonicNow < nextProbeAtMillis) return
        nextProbeAtMillis = monotonicNow + GameConstants.HOST_ALONE_PROBE_AFTER_MILLIS
        probeCount += 1
        if (probe != null) {
            // One scan serves many probes: Android throttles apps that start scans too often.
            HyperBleLog.event("probe $probeCount ($reason): no other host of $gameCode found so far, still scanning")
            return
        }
        HyperBleLog.event("probe $probeCount ($reason): looking for another host of $gameCode")
        val probeTransport = probeTransportFactory()
        probe = probeTransport
        probeTransport.setListener { handleProbe(probeTransport, it) }
        probeTransport.connectByGameCode(gameCode)
    }

    private fun handleProbe(source: GameClientTransport, event: ClientTransportEvent) {
        if (source !== probe) return
        when (event) {
            is ClientTransportEvent.HostFound -> Unit
            is ClientTransportEvent.GameInfoRead -> {
                val info = WireJson.decodeGameInfo(event.bytes)
                when {
                    info == null -> {
                        HyperBleLog.event("probe $probeCount: the other host's GameInfo could not be read", LogLevel.WARN)
                        endProbe()
                    }
                    outranks(info) -> stepDown(info)
                    else -> {
                        HyperBleLog.event(
                            "probe $probeCount: found host ${info.hostId} epoch ${info.hostEpoch}, " +
                                "keeping the host role ($hostId epoch $hostEpoch wins)"
                        )
                        endProbe()
                    }
                }
            }
            is ClientTransportEvent.Ready -> {
                HyperBleLog.event("probe $probeCount: the other host has no readable GameInfo", LogLevel.WARN)
                endProbe()
            }
            is ClientTransportEvent.MessageReceived, is ClientTransportEvent.FrameReceived -> Unit
            is ClientTransportEvent.Disconnected -> {
                HyperBleLog.event("probe $probeCount failed: ${event.reason ?: "disconnected"}", LogLevel.WARN)
                endProbe()
            }
        }
    }

    /** A higher epoch wins; at the same epoch the lower seat number does. */
    private fun outranks(other: GameInfo): Boolean =
        other.hostEpoch > hostEpoch ||
            (other.hostEpoch == hostEpoch && seatNumber(other.hostId) < seatNumber(hostId))

    // --- Speed test ---

    /** Starts the speed test, in the lobby only; [payloadSize] is the world payload in bytes. */
    fun startSpeedTest(frameRate: Int, payloadSize: Int) {
        if (hasStopped || speedTestJob != null || session.phase != GamePhase.LOBBY) return
        speedTest.start(frameRate, payloadSize)
        speedTestLines = emptyList()
        HyperBleLog.event("RT speed test started: $frameRate frames/s, $payloadSize B, ${speedTest.links.size} client(s)")
        speedTestJob = scope.launch { runSpeedTest() }
        publishSpeedTest()
    }

    fun stopSpeedTest(reason: String) {
        val job = speedTestJob ?: return
        speedTestJob = null
        job.cancel()
        speedTest.stop()
        HyperBleLog.event("RT speed test stopped: $reason")
        publishSpeedTest()
    }

    /**
     * The 30 Hz tick, kept to a fixed schedule so that delays do not add up. After a stall (app
     * frozen) the schedule starts over instead of catching up with a burst of ticks.
     */
    private suspend fun runSpeedTest() {
        var startedAt = elapsedMillis()
        var ticks = 0L
        var nextStatsAt = startedAt + SpeedTestConstants.STATS_INTERVAL_MILLIS
        while (true) {
            ticks += 1
            val dueAt = startedAt + ticks * MILLIS_PER_SECOND / SpeedTestConstants.TICKS_PER_SECOND
            delay(dueAt - elapsedMillis())
            val now = elapsedMillis()
            if (now - dueAt > SpeedTestConstants.TICK_RESYNC_AFTER_MILLIS) {
                startedAt = now
                ticks = 0
                nextStatsAt = now + SpeedTestConstants.STATS_INTERVAL_MILLIS
            }
            speedTestTick(now)
            if (now >= nextStatsAt) {
                nextStatsAt += SpeedTestConstants.STATS_INTERVAL_MILLIS
                speedTestLines = speedTest.secondElapsed(::speedTestLabel)
                speedTestLines.forEach { HyperBleLog.stats("RT tx $it") }
            }
            publishSpeedTest()
        }
    }

    /** Moves the dot; on a sending tick, a world frame to every linked client. Frames are not logged. */
    private fun speedTestTick(monotonicNow: Long) {
        if (!speedTest.advance()) return
        for (link in speedTest.links) {
            val result = transport.sendFrame(speedTest.worldFor(link, monotonicNow), link)
            if (speedTest.frameSent(link, result)) {
                HyperBleLog.event(
                    "RT tx ${speedTestLabel(link)}: world frame of ${speedTest.payloadSize} B not sent " +
                        "(chunk size ${linkChunkLengths[link] ?: "unknown"}, frame header 3 B)",
                    LogLevel.WARN,
                )
            }
        }
    }

    /** The client's player ID if it holds a seat, else its link. */
    private fun speedTestLabel(link: LinkId): String =
        session.labelFor(link).takeIf { it != GameConstants.UNASSIGNED_PLAYER_ID } ?: link

    private fun speedTestUi() = HostSpeedTestState(
        linkedClients = speedTest.links.size,
        isRunning = speedTest.isRunning,
        frameRate = speedTest.frameRate,
        payloadSize = speedTest.payloadSize,
        dotX = speedTest.dotX,
        dotY = speedTest.dotY,
        lines = speedTestLines,
    )

    private fun publishSpeedTest() {
        _speedTest.value = speedTestUi()
    }

    // --- Real-time games ---

    /** The screen's link follows the game; the tick timer runs while a real-time game is being played. */
    private fun syncRealtime() {
        val spec = session.realtimeSpec
        if (spec == null) {
            _realtime.value = null
        } else if (_realtime.value?.spec != spec) {
            _realtime.value = HostRealtimeLink(spec)
        }
        val playing = session.realtimeGame != null
        if (playing && realtimeJob?.isActive != true && !hasStopped) {
            HyperBleLog.event("RT game loop started: a tick every ${spec?.tickMillis} ms, a frame every ${spec?.framePeriodTicks} ticks")
            realtimeJob = scope.launch { runRealtime() }
        } else if (!playing && realtimeJob != null) {
            realtimeJob?.cancel()
            realtimeJob = null
            HyperBleLog.event("RT game loop stopped")
        }
    }

    /**
     * The fixed-step loop. While the session says the game may not move (a player offline, or the
     * resume delay), it only waits; when the game moves again the schedule starts afresh.
     */
    private suspend fun runRealtime() {
        var schedule: TickSchedule? = null
        var nextStatsAt = 0L
        while (true) {
            val spec = session.realtimeGame?.realtime ?: return
            val now = elapsedMillis()
            if (!session.realtimeRunning) {
                schedule = null
                delay(spec.tickMillis)
                continue
            }
            val current = schedule ?: TickSchedule(spec.tickMillis, GameConstants.REALTIME_STALL_MILLIS, now).also {
                schedule = it
                realtimeStats.restart()
                nextStatsAt = now + GameConstants.REALTIME_STATS_INTERVAL_MILLIS
            }
            repeat(current.due(now)) { realtimeTick() }
            if (now >= nextStatsAt) {
                nextStatsAt += GameConstants.REALTIME_STATS_INTERVAL_MILLIS
                if (nextStatsAt <= now) nextStatsAt = now + GameConstants.REALTIME_STATS_INTERVAL_MILLIS
                realtimeStats.secondElapsed(::speedTestLabel).forEach { HyperBleLog.stats("RT tx $it") }
            }
            delay(maxOf(1L, current.nextDueAtMillis - elapsedMillis()))
        }
    }

    /** One tick: the frame to the host's screen, every n-th one to the clients; a coarse change as usual. */
    private fun realtimeTick() {
        val output = session.realtimeTick(System.currentTimeMillis())
        val frame = output.worldFrame ?: return
        _realtime.value?.frames?.tryEmit(RealtimeFrame(frame, elapsedMillis()))
        if (output.sendWorldFrame) {
            for (recipient in session.clientRecipients) {
                val result = transport.sendFrame(frame, recipient.link)
                if (realtimeStats.frameSent(recipient.link, result)) {
                    HyperBleLog.event(
                        "RT tx ${recipient.label}: world frame of ${frame.size} B not sent " +
                            "(chunk size ${linkChunkLengths[recipient.link] ?: "unknown"}, frame header 3 B)",
                        LogLevel.WARN,
                    )
                }
            }
        }
        if (output.outgoing.isNotEmpty() || output.notes.isNotEmpty()) apply(output)
    }

    /** The host's side of [RealtimeLink]: frames from its own simulation, its input into the session. */
    private inner class HostRealtimeLink(override val spec: RealtimeSpec) : RealtimeLink {
        val frames = realtimeFrameFlow()
        override val worldFrames = frames.asSharedFlow()

        override fun sendInput(payload: ByteArray) {
            val copy = payload.copyOf()
            scope.launch(Dispatchers.Main.immediate) {
                if (!hasStopped) session.realtimeHostInput(copy)
            }
        }
    }

    /** Closes the probe link and stops its search. The next probe starts a new one. */
    private fun endProbe() {
        val ending = probe ?: return
        probe = null
        ending.setListener(null)
        ending.disconnect()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val BYTE_MASK = 0xFF
    }

    private fun stepDown(other: GameInfo) {
        val reason = "host ${other.hostId} epoch ${other.hostEpoch} outranks $hostId epoch $hostEpoch"
        HyperBleLog.event("probe $probeCount: stepping down, $reason", LogLevel.WARN)
        val handover = StepDown(gameCode, hostEpoch, session.players, "stepped down: $reason")
        stop()
        onStepDown(handover)
    }
}
