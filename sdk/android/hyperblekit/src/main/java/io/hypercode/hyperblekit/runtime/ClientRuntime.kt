package io.hypercode.hyperblekit.runtime

import android.os.SystemClock
import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogDirection
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.RealtimeFrame
import io.hypercode.hyperblekit.RealtimeLink
import io.hypercode.hyperblekit.game.GameModule
import io.hypercode.hyperblekit.game.RealtimeGameModule
import io.hypercode.hyperblekit.game.RealtimeSpec
import io.hypercode.hyperblekit.GamePhase
import io.hypercode.hyperblekit.protocol.GameOverReason
import io.hypercode.hyperblekit.protocol.HostSnapshot
import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.JoinRejectCode
import io.hypercode.hyperblekit.protocol.Message
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.diagnostics.TestJson
import io.hypercode.hyperblekit.protocol.WireJson
import io.hypercode.hyperblekit.realtimeFrameFlow
import io.hypercode.hyperblekit.session.ClientSession
import io.hypercode.hyperblekit.session.RealtimeRxStats
import io.hypercode.hyperblekit.diagnostics.ClientSpeedTest
import io.hypercode.hyperblekit.diagnostics.SpeedTestConstants
import io.hypercode.hyperblekit.diagnostics.SpeedTestPayloads
import io.hypercode.hyperblekit.transport.ClientTransportEvent
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.diagnostics.ClientSpeedTestState
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
import java.util.Locale

/** Where a joined (or joining) session stands. */
internal sealed interface ClientStatus {
    data object Connecting : ClientStatus

    /** Subscribed, `JOIN_REQUEST` sent. */
    data object Joining : ClientStatus
    data object Joined : ClientStatus
    data class Rejected(val code: String) : ClientStatus

    /** The host was lost; looking for it again until the reconnect window ends. */
    data class Reconnecting(val secondsLeft: Long) : ClientStatus

    /** The host was not found again within [GameConstants.RECONNECT_WINDOW_MILLIS]. Final. */
    data object GameOver : ClientStatus

    /** The first connection could not be set up, or the user left. */
    data class Disconnected(val reason: String?) : ClientStatus
}

/**
 * Runs one joined session: connects the transport to [ClientSession], watches for a silent host,
 * reconnects when the host is lost, sends this player's game moves and writes everything to the
 * debug log.
 *
 * **Reconnect** (wire specification "Reliability Rules" → Reconnection). Once joining or joined, any loss the
 * user did not ask for — link down, the host's service gone, 10 s of silence — opens a window of
 * [GameConstants.RECONNECT_WINDOW_MILLIS]. Within it, attempts follow each other: find the host
 * by game code, connect, send `JOIN_REQUEST` (`seq` 1, same `deviceId`). It ends when the
 * `STATE_SYNC` after the rejoin is applied, when the host answers `JOIN_REJECT`, when the user
 * leaves, or when the window runs out ("Host left, game over").
 *
 * **Host migration.** A host advertising a lower `hostEpoch` than the one already applied is
 * stale and is skipped. If this device is successor #1 or #2 and holds a `HOST_SNAPSHOT`, it
 * takes the game over ([onTakeover]) when the host has not come back
 * [GameConstants.TAKEOVER_DELAY_MILLIS] (+ [GameConstants.TAKEOVER_STEP_MILLIS] for #2) after the
 * loss — or at once when the host says `HOST_LEAVING` and names it.
 *
 * **Speed test** (test equipment for the real-time lane): no message
 * starts it. The view opens when the host's world frames arrive and closes
 * [SpeedTestConstants.CLIENT_CLOSE_AFTER_MILLIS] after the last one; meanwhile inputs go back
 * every [SpeedTestConstants.INPUT_INTERVAL_MILLIS] and at once on a tap. It stays out of the session.
 *
 * **Real-time games** (wire specification "Real-time games"): while the session's game is a real-time one,
 * world frames of its kind go to the game screen through [realtime], and the screen's inputs go to
 * the host as frames. `RT rx` stats are logged once a second while world frames arrive.
 *
 * Everything runs on the main thread: the transport delivers its events there and [scope] must
 * use the main dispatcher, so the session is only ever touched from one thread.
 */
internal class ClientRuntime(
    val gameCode: String,
    /** The scanner's handle for the host, for the first connect; null for a host that stepped down. */
    private val gameId: String?,
    private val transport: GameClientTransport,
    deviceId: String,
    playerName: String,
    canHost: Boolean,
    private val scope: CoroutineScope,
    /** The game modules of this app, by id (`HyperBleKitConfig.games`). */
    private val findModule: (String) -> GameModule?,
    /** This device becomes the host: the snapshot to restore from and its own player ID. */
    private val onTakeover: (HostSnapshot, String) -> Unit,
    /** Set for a host that stepped down: what it knew as host. */
    steppedDown: StepDown? = null,
    /** The monotonic clock every duration is measured with, in ms. */
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val session = ClientSession(
        deviceId, playerName, canHost,
        knownHostEpoch = steppedDown?.hostEpoch ?: 0,
        initialPlayers = steppedDown?.players.orEmpty(),
        elapsedMillis = elapsedMillis,
    )
    private val steppedDownReason = steppedDown?.reason
    private var silenceCheck: Job? = null
    private var hasStarted = false
    private var hasLeft = false

    /** Between the transport's Ready and the end of that link: `JOIN_REQUEST` is out, the host should talk. */
    private var linkReady = false

    /** A search found a host and the link to it is being set up: the attempt is mid-flight. */
    private var linkInProgress = false

    /** This device's takeover time has come; it happens as soon as no attempt is in flight. */
    private var takeoverDue = false
    private var takeoverWaitLogged = false

    /** Monotonic time at which this device takes over unless a host is found; null if it will not. */
    private var takeoverAtMillis: Long? = null

    /** Monotonic time the current loss was detected; null while not reconnecting. */
    private var reconnectStartedAtMillis: Long? = null
    private var reconnectAttempt = 0

    /** Counts the window down and ends it. */
    private var reconnectWindow: Job? = null

    /** The pause before the next attempt. */
    private var reconnectRetry: Job? = null

    private val _status = MutableStateFlow<ClientStatus>(ClientStatus.Connecting)

    private val _hostId = MutableStateFlow<String?>(null)
    val hostId: StateFlow<String?> = _hostId.asStateFlow()

    /** The highest `hostEpoch` applied; 0 before the first `STATE_SYNC`. */
    private val _hostEpoch = MutableStateFlow(session.knownHostEpoch)
    val hostEpoch: StateFlow<Int> = _hostEpoch.asStateFlow()

    /** Whether a host snapshot is held (never its content) and this device's successor position. */
    private val _migrationInfo = MutableStateFlow(migrationText())
    val migrationInfo: StateFlow<String> = _migrationInfo.asStateFlow()
    val status: StateFlow<ClientStatus> = _status.asStateFlow()

    private val _players = MutableStateFlow(session.players)
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _playerId = MutableStateFlow<String?>(null)
    val playerId: StateFlow<String?> = _playerId.asStateFlow()

    /** The phase and this player's own view of the game, as the host last sent them. */
    private val _game = MutableStateFlow(gameUi())
    val game: StateFlow<GameUi> = _game.asStateFlow()

    /** Why the last move was not made (not sent, or rejected by the host); null once the game moves on. */
    private val _gameNotice = MutableStateFlow<String?>(null)
    val gameNotice: StateFlow<String?> = _gameNotice.asStateFlow()

    /** What GameInfo said that the user should know before the host answers. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Outcome of the last debug JSON test, sent or received; null until there is one. */
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** The last received test frame, indented for reading; null until one arrives. */
    private val _testJsonReceived = MutableStateFlow<String?>(null)
    val testJsonReceived: StateFlow<String?> = _testJsonReceived.asStateFlow()

    /** How the last successful reconnect went, e.g. "2.4 s (attempt 1)"; null until there was one. */
    private val _lastReconnect = MutableStateFlow<String?>(null)
    val lastReconnect: StateFlow<String?> = _lastReconnect.asStateFlow()

    private val isReconnecting: Boolean get() = reconnectStartedAtMillis != null

    private val speedTest = ClientSpeedTest()

    /** Sends inputs, logs the stats and closes the view; runs while the speed test is open. */
    private var speedTestJob: Job? = null

    /** The stats of the last second; null until the first second is over. */
    private var speedTestLine: String? = null

    /** The speed-test view; null while no world frames arrive. */
    private val _speedTest = MutableStateFlow<ClientSpeedTestState?>(null)
    val speedTestUi: StateFlow<ClientSpeedTestState?> = _speedTest.asStateFlow()

    /** The game screen's link while the game is a real-time one; null otherwise. */
    private val _realtime = MutableStateFlow<ClientRealtimeLink?>(null)
    val realtime: StateFlow<RealtimeLink?> = _realtime.asStateFlow()
    private val realtimeStats = RealtimeRxStats()

    /** Logs the `RT rx` stats once a second while world frames arrive. */
    private var realtimeStatsJob: Job? = null

    /** The phase when the link was last checked: a game that starts again after it finished gets a new link. */
    private var linkedPhase = GamePhase.LOBBY

    /** The own view as JSON for the input echo, parsed once per view. */
    private var echoView: Pair<JsonPayload, JSONObject>? = null

    fun start() {
        if (hasStarted) return
        hasStarted = true
        HyperBleLog.event("joining $gameCode as ${session.name}, deviceId ${session.deviceId}")
        transport.setListener(::handle)
        silenceCheck = scope.launch {
            while (true) {
                delay(GameConstants.SILENCE_CHECK_INTERVAL_MILLIS)
                transport.checkTimeouts()
                checkHostSilence()
            }
        }
        if (gameId != null) {
            transport.connect(gameId)
        } else {
            // A host that stepped down: the new host is found by game code, like any reconnect.
            startReconnect(steppedDownReason ?: "stepped down")
        }
    }

    /**
     * Tells the host we are going (when it can still hear us), then disconnects once that is
     * flushed. During a reconnect it just cancels it: no more attempts, search stopped, link closed.
     */
    fun leave() {
        if (!hasStarted || hasLeft) return
        hasLeft = true
        stopTimers()
        if (isReconnecting) HyperBleLog.event("reconnect cancelled: leaving")
        reconnectStartedAtMillis = null
        if (_status.value == ClientStatus.Joined && linkReady) {
            send(session.leave(System.currentTimeMillis()))
        }
        linkReady = false
        transport.disconnect()
    }

    /**
     * This player's move, sent to the host as `GAME_ACTION`. Nothing changes here until the host
     * answers: with `GAME_STATE` (the new view) or with `ERROR` (shown as a notice).
     */
    fun gameAction(action: JSONObject) {
        if (!hasStarted || hasLeft) return
        if (_status.value != ClientStatus.Joined || !linkReady) {
            HyperBleLog.event("move not sent: not connected to the host", LogLevel.WARN)
            _gameNotice.value = "Not connected to the host, move not sent"
            return
        }
        val message = session.gameAction(action, System.currentTimeMillis())
        if (message == null) {
            HyperBleLog.event("move not sent: no game is running", LogLevel.WARN)
            _gameNotice.value = "No game is running"
            return
        }
        send(message)
    }

    /** Debug: sends the wire specification's game-state snapshot, as raw bytes, to the host. Only once joined. */
    fun sendTestJson() {
        if (_status.value != ClientStatus.Joined) {
            HyperBleLog.event("test JSON not sent: not joined", LogLevel.WARN)
            return
        }
        val bytes = TestJson.bytes()
        val chunks = transport.send(bytes)
        HyperBleLog.frame(LogDirection.SENT, TestJson.LOG_TYPE, bytes.size, chunks, "to=${hostLabel()}")
        _testResult.value = "Test JSON: sent ${bytes.size} B in $chunks chunks"
    }

    /**
     * The host asked for this device's log (`LOG_REQUEST`): sends the current debug-log buffer to
     * the host as `LOG_UPLOAD` batches. The lines are snapshotted first, so the upload's own log
     * lines are not part of what is sent. An empty log still sends one (empty) batch, so the host
     * sees that this client answered.
     */
    private fun uploadLogs(nowMillis: Long) {
        if (!linkReady || _status.value != ClientStatus.Joined) {
            HyperBleLog.event("log not uploaded: not connected to the host", LogLevel.WARN)
            return
        }
        val lines = HyperBleLog.entries.value.map { it.line }
        val batches = lines.chunked(GameConstants.LOG_UPLOAD_BATCH_LINES).ifEmpty { listOf(emptyList()) }
        HyperBleLog.event("uploading ${lines.size} log line(s) to the host in ${batches.size} batch(es)")
        batches.forEachIndexed { index, batch ->
            send(session.logUpload(index, batches.size, batch, nowMillis))
        }
    }

    /** A debug test frame: compared and logged, never applied to the session. */
    private fun testJsonReceived(event: ClientTransportEvent.MessageReceived) {
        val matches = TestJson.matches(event.bytes)
        val expected = TestJson.bytes().size
        HyperBleLog.frame(
            LogDirection.RECEIVED, TestJson.LOG_TYPE, event.bytes.size, event.chunks,
            "from=${hostLabel()} " + if (matches) "match=yes" else "match=no expected=$expected",
        )
        val verdict = if (matches) "match" else "MISMATCH (expected $expected B)"
        _testResult.value = "Test JSON: received ${event.bytes.size} B in ${event.chunks} chunks — $verdict"
        _testJsonReceived.value = TestJson.pretty(event.bytes)
        // It still proves the host is alive.
        session.trafficReceived()
    }

    private fun handle(event: ClientTransportEvent) {
        // The client role has ended (left, or taken over as host). The transport still delivers
        // while it flushes; nothing but the end of the link may act on that.
        if (hasLeft && event !is ClientTransportEvent.Disconnected) return
        val now = System.currentTimeMillis()
        when (event) {
            is ClientTransportEvent.HostFound -> linkInProgress = true
            is ClientTransportEvent.GameInfoRead -> gameInfoRead(event.bytes)
            is ClientTransportEvent.Ready -> {
                HyperBleLog.event("subscribed, chunk size ${event.maxChunkLength}")
                linkInProgress = false
                linkReady = true
                // While reconnecting the status keeps counting down until the state is back.
                if (!isReconnecting) _status.value = ClientStatus.Joining
                send(session.joinRequest(now))
            }
            is ClientTransportEvent.MessageReceived -> {
                if (TestJson.isTestFrame(event.bytes)) {
                    testJsonReceived(event)
                    return
                }
                val message = WireJson.decodeMessage(event.bytes)
                if (message == null) {
                    HyperBleLog.event(
                        "RX undecodable message bytes=${event.bytes.size} chunks=${event.chunks}",
                        LogLevel.WARN,
                    )
                    return
                }
                HyperBleLog.message(LogDirection.RECEIVED, message, event.bytes.size, event.chunks)
                val output = session.receive(message, now)
                output.notes.forEach { HyperBleLog.event(it) }
                output.outgoing.forEach(::send)
                sessionChanged(output)
                if (output.logRequested) uploadLogs(now)
            }
            is ClientTransportEvent.FrameReceived ->
                if (event.payload.isNotEmpty() && (event.payload[0].toInt() and BYTE_MASK) == SpeedTestPayloads.KIND_WORLD) {
                    speedTestFrame(event.payload)
                } else {
                    realtimeFrame(event.payload)
                }
            is ClientTransportEvent.Disconnected -> linkEnded(event.reason)
        }
    }

    /** The transport reports the end of a link or of a connection attempt. It already logged the reason. */
    private fun linkEnded(reason: String?) {
        linkReady = false
        linkInProgress = false
        val status = _status.value
        when {
            // Final states stay on screen; the disconnect is only their consequence.
            status is ClientStatus.Rejected || status == ClientStatus.GameOver -> Unit
            hasLeft -> _status.value = ClientStatus.Disconnected(reason)
            isReconnecting -> attemptFailed(reason ?: "disconnected")
            status == ClientStatus.Joining || status == ClientStatus.Joined ->
                startReconnect(reason ?: "disconnected")
            else -> {
                // The very first connection never got as far as JOIN_REQUEST: nothing to return to.
                stopTimers()
                _status.value = ClientStatus.Disconnected(reason)
            }
        }
    }

    private fun gameInfoRead(bytes: ByteArray) {
        HyperBleLog.event("GameInfo ${String(bytes, Charsets.UTF_8)}")
        val info = WireJson.decodeGameInfo(bytes)
        if (info != null && info.hostEpoch < session.knownHostEpoch) {
            // A host the game has already moved on from: never join it, keep looking.
            HyperBleLog.event(
                "stale host ${info.hostId} epoch ${info.hostEpoch} (known epoch ${session.knownHostEpoch}): " +
                    "not joining, ignoring that device",
                LogLevel.WARN,
            )
            linkInProgress = false
            transport.skipCurrentHost()
            takeOverIfDue()
            return
        }
        val notice = when {
            info == null -> "The host's game info could not be read."
            info.v != GameConstants.PROTOCOL_VERSION ->
                "Host uses protocol v${info.v}, this app v${GameConstants.PROTOCOL_VERSION}."
            !info.lobbyOpen -> "The lobby is closed: only returning players can join."
            else -> null
        }
        _notice.value = notice
        if (notice != null) HyperBleLog.event(notice, LogLevel.WARN)
    }

    private fun send(message: Message) {
        val bytes = WireJson.encode(message)
        val chunks = transport.send(bytes)
        HyperBleLog.message(LogDirection.SENT, message, bytes.size, chunks)
    }

    /** Mirrors the session after a host message. */
    private fun sessionChanged(output: ClientSession.Output) {
        _players.value = session.players
        _playerId.value = session.playerId ?: session.lastPlayerId
        _hostId.value = session.hostId
        _hostEpoch.value = session.knownHostEpoch
        _migrationInfo.value = migrationText()
        val game = gameUi()
        val error = output.error
        if (error != null) {
            _gameNotice.value = "Move rejected: " + error.code + error.reason?.let { " ($it)" }.orEmpty()
        } else if (_game.value != game) {
            _gameNotice.value = null
        }
        _game.value = game
        syncRealtime()
        val rejectCode = session.rejectCode
        val nextHostId = output.nextHostId
        when {
            output.gameOverReason != null -> gameOver(output.gameOverReason)
            nextHostId != null -> hostLeaving(nextHostId)
            rejectCode != null -> {
                // Also ends a reconnect: the host does not want us back. `removed` ends the session the same way.
                if (rejectCode == JoinRejectCode.REMOVED) HyperBleLog.event("the host removed this player, session ended", LogLevel.WARN)
                if (isReconnecting) HyperBleLog.event("reconnect stopped: join rejected ($rejectCode)", LogLevel.WARN)
                stopTimers()
                reconnectStartedAtMillis = null
                linkReady = false
                _status.value = ClientStatus.Rejected(rejectCode)
                transport.disconnect()
            }
            isReconnecting -> if (output.stateSyncApplied && session.playerId != null) reconnected()
            session.playerId != null -> _status.value = ClientStatus.Joined
        }
    }

    private fun gameUi() = GameUi(session.phase, session.gameModuleId, session.view)

    /** The host as far as the log is concerned: its player ID once known. */
    private fun hostLabel(): String = session.hostId ?: GameConstants.UNASSIGNED_PLAYER_ID

    private fun migrationText(): String {
        val snapshot = session.snapshot?.let { "held (epoch ${it.state.hostEpoch}, sv ${it.state.stateVersion})" } ?: "none"
        val rank = session.takeoverRank?.let { "takeover rank #$it" } ?: "cannot take over"
        return "Snapshot: $snapshot · $rank"
    }

    /** `GAME_OVER`: with `host_left` the game ends here and now, without a reconnect. */
    private fun gameOver(reason: String?) {
        if (reason != GameOverReason.HOST_LEFT) {
            HyperBleLog.event("ignored GAME_OVER reason=$reason: only host_left ends the session")
            return
        }
        HyperBleLog.event("host left, game over", LogLevel.WARN)
        reconnectStartedAtMillis = null
        linkReady = false
        _status.value = ClientStatus.GameOver
        stopTimers()
        transport.disconnect()
    }

    /** `HOST_LEAVING`: the named successor takes over at once, everyone else goes looking for it. */
    private fun hostLeaving(nextHostId: String) {
        if (nextHostId == session.lastPlayerId && session.snapshot != null) {
            HyperBleLog.event("host is leaving and named this device as the next host")
            if (takeOver()) return
        }
        if (nextHostId == session.lastPlayerId) {
            HyperBleLog.event("named as the next host but no snapshot is held: cannot take over", LogLevel.ERROR)
        }
        linkReady = false
        if (!isReconnecting) startReconnect("host leaving, next host $nextHostId")
    }

    private fun checkHostSilence() {
        if (!linkReady || !session.isHostSilent()) return
        val reason = "no traffic for ${GameConstants.SILENCE_TIMEOUT_MILLIS / MILLIS_PER_SECOND} s"
        // The link may still be up; the next attempt closes it before searching again.
        linkReady = false
        if (isReconnecting) attemptFailed(reason) else startReconnect(reason)
    }

    /**
     * The app is in front again, possibly after being frozen: none of the timers ran meanwhile, so
     * everything that depends on elapsed time is evaluated now instead of at the next tick — a
     * host that went quiet sends this client straight into the reconnect loop, and a window that
     * ran out ends at once.
     */
    fun onForeground() {
        if (!hasStarted || hasLeft) return
        transport.checkTimeouts()
        checkHostSilence()
        reconnectTick()
    }

    // --- Reconnect ---

    private fun startReconnect(reason: String) {
        reconnectStartedAtMillis = elapsedMillis()
        reconnectAttempt = 0
        val windowSeconds = GameConstants.RECONNECT_WINDOW_MILLIS / MILLIS_PER_SECOND
        HyperBleLog.event("host lost ($reason), reconnecting for $windowSeconds s", LogLevel.WARN)
        _status.value = ClientStatus.Reconnecting(windowSeconds)
        scheduleTakeover()

        reconnectWindow?.cancel()
        reconnectWindow = scope.launch {
            while (true) {
                delay(GameConstants.RECONNECT_TICK_MILLIS)
                reconnectTick()
            }
        }
        startAttempt()
    }

    /**
     * Judges the reconnect against real elapsed time: the end of the window, the takeover deadline
     * and the countdown. The ticks only decide how soon this is noticed; a tick that comes late
     * (the app was frozen) still sees how much time really passed.
     */
    private fun reconnectTick() {
        val startedAt = reconnectStartedAtMillis ?: return
        val now = elapsedMillis()
        val left = GameConstants.RECONNECT_WINDOW_MILLIS - (now - startedAt)
        if (left <= 0) {
            // Also after a freeze that outlasted the window: over is over, no late takeover.
            reconnectWindowExpired()
            return
        }
        val takeoverAt = takeoverAtMillis
        if (takeoverAt != null && now >= takeoverAt) {
            takeoverAtMillis = null
            takeoverDue = true
        }
        if (takeOverIfDue()) return
        // Rounded up, so it reads "1 s left" until the very end.
        _status.value = ClientStatus.Reconnecting((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
    }

    private fun startAttempt() {
        reconnectAttempt += 1
        HyperBleLog.event("reconnect attempt $reconnectAttempt")
        linkReady = false
        linkInProgress = false
        // Closes whatever is left of the old link, then looks for the game code in fresh scan results.
        transport.connectByGameCode(gameCode)
    }

    private fun attemptFailed(reason: String) {
        HyperBleLog.event("reconnect attempt $reconnectAttempt failed: $reason", LogLevel.WARN)
        if (takeOverIfDue()) return
        reconnectRetry?.cancel()
        reconnectRetry = scope.launch {
            delay(GameConstants.RECONNECT_RETRY_PAUSE_MILLIS)
            if (isReconnecting) startAttempt()
        }
    }

    /** The `STATE_SYNC` after the rejoin was applied: this is the "Reconnection" measurement of the wire specification. */
    private fun reconnected() {
        val startedAt = reconnectStartedAtMillis ?: return
        val seconds = (elapsedMillis() - startedAt) / MILLIS_PER_SECOND.toDouble()
        val result = String.format(Locale.US, "%.1f s (attempt %d)", seconds, reconnectAttempt)
        reconnectStartedAtMillis = null
        reconnectWindow?.cancel()
        reconnectWindow = null
        reconnectRetry?.cancel()
        reconnectRetry = null
        takeoverAtMillis = null
        takeoverDue = false
        HyperBleLog.event("reconnected in $result")
        _lastReconnect.value = result
        _status.value = ClientStatus.Joined
    }

    private fun reconnectWindowExpired() {
        val windowSeconds = GameConstants.RECONNECT_WINDOW_MILLIS / MILLIS_PER_SECOND
        HyperBleLog.event(
            "host left, game over: not back within $windowSeconds s ($reconnectAttempt attempts)",
            LogLevel.ERROR,
        )
        reconnectStartedAtMillis = null
        linkReady = false
        _status.value = ClientStatus.GameOver
        stopTimers()
        // Stops the search and closes any half-open link.
        transport.disconnect()
    }

    // --- Takeover ---

    /**
     * A client that holds a snapshot takes over if no host is found in time; how long it waits
     * depends on its takeover rank at the moment of the loss. The deadline is a point in real
     * elapsed time, checked by [reconnectTick].
     */
    private fun scheduleTakeover() {
        takeoverAtMillis = null
        takeoverDue = false
        val startedAt = reconnectStartedAtMillis ?: return
        val rank = session.takeoverRank ?: return
        val delayMillis = GameConstants.TAKEOVER_DELAY_MILLIS + (rank - 1) * GameConstants.TAKEOVER_STEP_MILLIS
        takeoverAtMillis = startedAt + delayMillis
        HyperBleLog.event("takeover rank #$rank with a snapshot: taking over in ${delayMillis / MILLIS_PER_SECOND} s unless a host is found")
    }

    /**
     * Takes over if the time has come and no host is within reach right now. An attempt that is
     * mid-flight (a host was found, or `JOIN_REQUEST` is out) is allowed to resolve first; this is
     * called again when it does. Returns true if this device is now the host.
     */
    private fun takeOverIfDue(): Boolean {
        if (!takeoverDue || !isReconnecting) return false
        if (linkReady || linkInProgress) {
            if (!takeoverWaitLogged) {
                takeoverWaitLogged = true
                HyperBleLog.event("takeover is due, waiting for the attempt in flight to resolve")
            }
            return false
        }
        takeoverWaitLogged = false
        return takeOver()
    }

    private fun takeOver(): Boolean {
        val snapshot = session.snapshot
        val ownPlayerId = session.lastPlayerId
        if (snapshot == null || ownPlayerId == null) {
            takeoverDue = false
            return false
        }
        if (snapshot.state.players.none { it.id == ownPlayerId }) {
            HyperBleLog.event("cannot take over: $ownPlayerId is not in the snapshot", LogLevel.ERROR)
            takeoverDue = false
            return false
        }
        HyperBleLog.event(
            "taking over as host: epoch ${snapshot.state.hostEpoch + 1}, stateVersion ${snapshot.state.stateVersion + 1}",
            LogLevel.WARN,
        )
        // The client role ends here: no more attempts, search stopped, link closed.
        hasLeft = true
        stopTimers()
        reconnectStartedAtMillis = null
        linkReady = false
        linkInProgress = false
        transport.disconnect()
        onTakeover(snapshot, ownPlayerId)
        return true
    }

    // --- Real-time games ---

    /** The running game's module if it is a real-time one; null in the lobby and for other games. */
    private fun realtimeModule(): RealtimeGameModule? =
        if (session.phase == GamePhase.LOBBY) null else session.gameModuleId?.let(findModule) as? RealtimeGameModule

    /** A new link when a real-time game appears, changes or starts again after it finished; none without one. */
    private fun syncRealtime() {
        val module = realtimeModule()
        val restarted = linkedPhase == GamePhase.FINISHED && session.phase == GamePhase.PLAYING
        linkedPhase = session.phase
        if (module == null) {
            _realtime.value = null
        } else if (_realtime.value?.module != module || restarted) {
            _realtime.value = ClientRealtimeLink(module)
        }
    }

    /** A frame from the host that is not the speed test's: the running game's world, if of its kind. */
    private fun realtimeFrame(payload: ByteArray) {
        if (hasLeft) return
        val link = _realtime.value ?: return
        if (payload.isEmpty() || (payload[0].toInt() and BYTE_MASK) != link.spec.worldKind) return
        val now = elapsedMillis()
        link.frames.tryEmit(RealtimeFrame(payload, now))
        if (!realtimeStats.isOpen) openRealtimeStats()
        realtimeStats.frameReceived(now, payload.size, ownViewJson()?.let { link.module.echoedInputSeq(payload, it) })
    }

    private fun ownViewJson(): JSONObject? {
        val view = session.view ?: return null
        val cached = echoView
        if (cached != null && cached.first == view) return cached.second
        return view.toJson().also { echoView = view to it }
    }

    private fun openRealtimeStats() {
        realtimeStats.open(transport.staleFramesDropped)
        HyperBleLog.event("RT rx: world frames from the host")
        realtimeStatsJob?.cancel()
        realtimeStatsJob = scope.launch {
            while (true) {
                delay(GameConstants.REALTIME_STATS_INTERVAL_MILLIS)
                if (realtimeStats.closeIfIdle(elapsedMillis())) {
                    val seconds = GameConstants.REALTIME_RX_IDLE_MILLIS / MILLIS_PER_SECOND
                    HyperBleLog.event("RT rx: no world frame for $seconds s")
                    realtimeStatsJob = null
                    return@launch
                }
                HyperBleLog.stats("RT rx " + realtimeStats.secondElapsed(transport.staleFramesDropped))
            }
        }
    }

    /** This player's input to the host: one frame, not logged one by one. */
    private fun sendRealtimeInput(link: ClientRealtimeLink, payload: ByteArray) {
        if (hasLeft || !linkReady || _status.value != ClientStatus.Joined || _realtime.value !== link) return
        transport.sendFrame(payload)
        link.module.inputSeq(payload)?.let { realtimeStats.inputSent(it, elapsedMillis()) }
    }

    /** A client's side of [RealtimeLink]: the host's world frames in, this player's inputs out. */
    private inner class ClientRealtimeLink(val module: RealtimeGameModule) : RealtimeLink {
        override val spec: RealtimeSpec = module.realtime
        val frames = realtimeFrameFlow()
        override val worldFrames = frames.asSharedFlow()

        override fun sendInput(payload: ByteArray) {
            val copy = payload.copyOf()
            scope.launch(Dispatchers.Main.immediate) { sendRealtimeInput(this@ClientRealtimeLink, copy) }
        }
    }

    // --- Speed test ---

    /** A tap on the speed-test field: counted and sent at once. */
    fun tapSpeedTest() {
        if (!speedTest.isOpen) return
        val now = elapsedMillis()
        speedTest.tap()
        sendSpeedTestInput(now)
        publishSpeedTest(now)
    }

    private fun speedTestFrame(payload: ByteArray) {
        if (hasLeft) return
        val now = elapsedMillis()
        val wasOpen = speedTest.isOpen
        if (!speedTest.frameReceived(payload, now, transport.staleFramesDropped)) return
        if (!wasOpen) openSpeedTest(now)
        publishSpeedTest(now)
    }

    private fun openSpeedTest(monotonicNow: Long) {
        HyperBleLog.event("RT speed test: world frames from the host, view open")
        speedTestLine = null
        sendSpeedTestInput(monotonicNow)
        speedTestJob?.cancel()
        speedTestJob = scope.launch {
            var nextStatsAt = monotonicNow + SpeedTestConstants.STATS_INTERVAL_MILLIS
            while (true) {
                delay(SpeedTestConstants.INPUT_INTERVAL_MILLIS)
                val now = elapsedMillis()
                if (speedTest.closeIfIdle(now)) {
                    closeSpeedTest(now)
                    return@launch
                }
                sendSpeedTestInput(now)
                if (now >= nextStatsAt) {
                    nextStatsAt += SpeedTestConstants.STATS_INTERVAL_MILLIS
                    val line = speedTest.secondElapsed(transport.staleFramesDropped)
                    speedTestLine = line
                    HyperBleLog.stats("RT rx $line")
                }
                publishSpeedTest(now)
            }
        }
    }

    /** No world frame for a while: the view closes, with the totals in the log. */
    private fun closeSpeedTest(monotonicNow: Long) {
        val seconds = SpeedTestConstants.CLIENT_CLOSE_AFTER_MILLIS / MILLIS_PER_SECOND
        HyperBleLog.event(
            "RT speed test over (no frame for $seconds s), since start: " +
                speedTest.sinceStart(monotonicNow, transport.staleFramesDropped)
        )
        speedTestJob = null
        _speedTest.value = null
    }

    /** Inputs are not logged one by one, like every frame. */
    private fun sendSpeedTestInput(monotonicNow: Long) {
        if (!linkReady) return
        transport.sendFrame(speedTest.nextInput(monotonicNow))
    }

    private fun publishSpeedTest(monotonicNow: Long) {
        _speedTest.value = ClientSpeedTestState(
            dotX = speedTest.dotX,
            dotY = speedTest.dotY,
            taps = speedTest.taps,
            lastSecond = speedTestLine,
            sinceStart = speedTest.sinceStart(monotonicNow, transport.staleFramesDropped),
        )
    }

    private fun stopTimers() {
        speedTestJob?.cancel()
        speedTestJob = null
        realtimeStatsJob?.cancel()
        realtimeStatsJob = null
        realtimeStats.close()
        speedTest.close()
        _speedTest.value = null
        takeoverAtMillis = null
        takeoverDue = false
        silenceCheck?.cancel()
        silenceCheck = null
        reconnectWindow?.cancel()
        reconnectWindow = null
        reconnectRetry?.cancel()
        reconnectRetry = null
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val BYTE_MASK = 0xFF
    }
}
