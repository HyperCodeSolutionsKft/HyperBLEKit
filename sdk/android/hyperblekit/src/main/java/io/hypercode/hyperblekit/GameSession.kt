package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.protocol.HostSnapshot
import io.hypercode.hyperblekit.runtime.ClientRuntime
import io.hypercode.hyperblekit.runtime.ClientStatus
import io.hypercode.hyperblekit.runtime.HostRuntime
import io.hypercode.hyperblekit.runtime.StepDown
import io.hypercode.hyperblekit.session.HostSession
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.transport.GameHostTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One game this device is in, from [HyperBleKit.host] or [HyperBleKit.join] until
 * [HyperBleKit.leave].
 *
 * The object stays the same for the whole game while the role this device plays can change
 * without the user doing anything. This is host migration: when the host disappears or leaves, a
 * client takes the game over and becomes the host; a host that finds a higher-ranked host of its
 * own game, or hands the game over because the app went to the background, becomes a client.
 * [state] always describes the current role, so a screen only has to follow [state].
 *
 * A session that was left is finished for good: its last state has
 * [ConnectionState.Ended] with [EndReason.Left], and every later call on it does nothing. Get a
 * new session from [HyperBleKit.host] or [HyperBleKit.join].
 *
 * Threading: call every function on the main thread. [state], [realtime] and the flows of
 * [diagnostics] change on the main thread.
 */
public class GameSession internal constructor(
    /**
     * The game's short code, e.g. "7K2F": four characters, shown to the players so they can pick
     * the right game. It never changes, whichever device hosts the game.
     */
    public val gameCode: String,
    initialRole: SessionRole,
    private val config: HyperBleKitConfig,
    private val identity: LocalIdentity,
    private val hostTransport: GameHostTransport,
    /** One transport per client role, so one that is still flushing its `LEAVE` is left alone. */
    private val clientTransportFactory: () -> GameClientTransport,
    private val scope: CoroutineScope,
    /**
     * Where a change of a runtime's state is passed on to [state]: a dispatcher that always
     * queues, never runs inline. A runtime changes its state in the middle of its own work, and
     * an app that reacts to [state] at once — by leaving, say — must not get to run there.
     */
    private val publishDispatcher: CoroutineDispatcher,
    /** The monotonic clock every duration is measured with, in ms. */
    private val elapsedMillis: () -> Long,
) {
    /** The runtime of the current role: exactly one of the two is set between start and [leave]. */
    private var host: HostRuntime? = null
    private var client: ClientRuntime? = null

    /** Copies the current runtime's flows into this session's. */
    private var mirror: Job? = null

    /** Set while a host that handed the game over in the background waits to rejoin it as a client. */
    private var rejoinOnForeground: StepDown? = null

    /** The session was left: it is dead, and no role may start on it again. */
    private var hasLeft = false

    private val _state = MutableStateFlow(
        SessionState(
            role = initialRole,
            connection = if (initialRole == SessionRole.HOST) ConnectionState.Joined else ConnectionState.Connecting,
            gameCode = gameCode,
        )
    )

    /**
     * Everything a session screen shows, for the role this device plays now: the role, the
     * connection, the players, the game phase and this player's view of the game. A new value is
     * published on the main thread whenever any part changes.
     */
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _realtime = MutableStateFlow<RealtimeLink?>(null)

    /**
     * The link a real-time game's screen uses for world frames and inputs. Set while the running
     * game is a real-time one ([io.hypercode.hyperblekit.game.RealtimeGameModule]), in the phases
     * `playing` and `finished`; null in the lobby and for every other game. Each game gets a new
     * link object, also when the same game is started again and when this device changes role.
     */
    public val realtime: StateFlow<RealtimeLink?> = _realtime.asStateFlow()

    /** The debug tools of this session: the JSON test frame and the speed test. */
    public val diagnostics: SessionDiagnostics = SessionDiagnostics({ host }, { client })

    /**
     * Starts a game with every player who is connected now. Host only; on a client, and on a
     * session that was left, it does nothing.
     *
     * If the game cannot be started — see [startGameProblem], or a real-time game whose frames do
     * not fit a player's connection — nothing changes and the reason appears in
     * [SessionState.gameNotice]. An id that is not in [HyperBleKitConfig.games] is only logged.
     * A game that has finished can be started again, or another one instead, without [endGame].
     *
     * @param gameId the [io.hypercode.hyperblekit.game.GameModule.id] of the game to start.
     */
    public fun startGame(gameId: String) {
        val host = host ?: return
        val module = config.findGame(gameId)
        if (module == null) {
            HyperBleLog.event("game not started: this app has no game module \"$gameId\"", LogLevel.WARN)
            return
        }
        host.startGame(module)
    }

    /**
     * Why [startGame] would be refused right now, as a short English sentence for the screen, or
     * null if the game can be started. For example "Chess needs 2 connected players" while too
     * few or too many players are connected. Use it to disable a start button and to say why.
     *
     * It reads the current [state], so ask again when [state] changes. On a client the answer is
     * always a problem: only the host starts games.
     *
     * @param gameId the [io.hypercode.hyperblekit.game.GameModule.id] of the game.
     */
    public fun startGameProblem(gameId: String): String? {
        val host = host ?: return "Only the host can start a game"
        val module = config.findGame(gameId) ?: return "This app has no game \"$gameId\""
        if (host.game.value.phase == GamePhase.PLAYING) return "a game is already running"
        val connected = host.players.value.count { it.connected }
        if (!module.supports(connected)) return "${module.title} needs ${module.playerCountLabel} connected players"
        return null
    }

    /**
     * Ends the game, whether it is running or finished: every player is back in the lobby and the
     * game's state is dropped. Host only; on a client it does nothing. In the lobby the request is
     * refused with a text in [SessionState.gameNotice].
     */
    public fun endGame() {
        host?.endGame()
    }

    /**
     * Removes an offline player from the session at once instead of waiting for the countdown in
     * [SessionState.offlineSecondsLeft]. A running game goes on without that player if its rules
     * allow, otherwise it ends. A player who is connected, the host itself or an unknown ID is not
     * removed, and [SessionState.gameNotice] says why. Host only; on a client it does nothing.
     *
     * @param playerId the [Player.id] of the player to remove, e.g. "p3".
     */
    public fun removePlayer(playerId: String) {
        host?.removePlayer(playerId)
    }

    /**
     * Makes this player's move. The same call on the host and on a client.
     *
     * The host applies the move through the game module; a client sends it to the host. Either
     * way nothing changes here until the result is known: an accepted move shows as a new
     * [SessionState.view], a refused one as a text in [SessionState.gameNotice] (for instance when
     * it is not this player's turn, or when a client is not connected to the host at the moment).
     *
     * @param action the move in the game module's own JSON format; the kit passes it on unread.
     */
    public fun act(action: JSONObject) {
        val host = host
        if (host != null) host.gameAction(action) else client?.gameAction(action)
    }

    // --- Roles ---

    internal fun startHosting(session: HostSession) {
        if (hasLeft) return
        val runtime = HostRuntime(hostTransport, session, clientTransportFactory, scope, ::steppedDown, elapsedMillis)
        client = null
        host = runtime
        mirror(
            runtime.advertisingState, runtime.players, runtime.pingStats, runtime.successors,
            runtime.offlineSecondsLeft, runtime.game, runtime.gameNotice, runtime.testResult,
            runtime.testJsonReceived, runtime.speedTestUi, runtime.realtime,
        )
        // The first state of the new role is out; an app may have left in answer to it.
        if (hasLeft) return
        runtime.start()
    }

    /** [gameId] is the scanner's handle for the host; null for a host that stepped down. */
    internal fun startClient(gameId: String?, steppedDown: StepDown?) {
        if (hasLeft) return
        val runtime = ClientRuntime(
            gameCode = gameCode,
            gameId = gameId,
            transport = clientTransportFactory(),
            deviceId = identity.deviceId,
            playerName = identity.playerName,
            canHost = hostTransport.canHost,
            scope = scope,
            findModule = config::findGame,
            onTakeover = ::takeOver,
            steppedDown = steppedDown,
            elapsedMillis = elapsedMillis,
        )
        host = null
        client = runtime
        mirror(
            runtime.status, runtime.players, runtime.playerId, runtime.hostId, runtime.hostEpoch,
            runtime.migrationInfo, runtime.game, runtime.gameNotice, runtime.notice,
            runtime.lastReconnect, runtime.testResult, runtime.testJsonReceived, runtime.speedTestUi,
            runtime.realtime,
        )
        // The first state of the new role is out; an app may have left in answer to it.
        if (hasLeft) return
        runtime.start()
    }

    /** Client → host: the client runtime already ended itself; host the same game from [snapshot]. */
    private fun takeOver(snapshot: HostSnapshot, ownPlayerId: String) {
        // A session that was left never hosts: its client runtime may still hear the host while
        // its LEAVE is being flushed, and a HOST_LEAVING then names a device that is gone.
        if (hasLeft) return
        // The game in the snapshot continues if this app has its module; the configuration decides.
        val session = HostSession.restore(
            snapshot, ownPlayerId, identity.deviceId, config::findGame, config.maxPlayers,
            elapsedMillis,
        )
        if (session == null) {
            HyperBleLog.event("takeover failed: $ownPlayerId is not in the snapshot", LogLevel.ERROR)
            return
        }
        startHosting(session)
    }

    /** Host → client: the host runtime already stopped hosting; rejoin the game as a client. */
    private fun steppedDown(handover: StepDown) {
        if (hasLeft) return
        startClient(gameId = null, steppedDown = handover)
    }

    /**
     * Leaves on purpose. A host hands the game over or ends it, then stops hosting; a client sends
     * `LEAVE`, then disconnects. The session is dead afterwards: no role starts on it again.
     */
    internal fun leave() {
        if (hasLeft) return
        hasLeft = true
        rejoinOnForeground = null
        mirror?.cancel()
        mirror = null
        // Detached first, so nothing a runtime reports while it leaves reaches this session.
        val host = host
        val client = client
        this.host = null
        this.client = null
        host?.leave()
        client?.leave()
        // The last state: a host that left is not advertising, whatever it last reported.
        _state.value = _state.value.copy(connection = ConnectionState.Ended(EndReason.Left), advertising = null)
        _realtime.value = null
        diagnostics.hostSpeedTestState.value = null
        diagnostics.clientSpeedTestState.value = null
        diagnostics.canRunSpeedTestState.value = false
    }

    /**
     * The app left the foreground. A host cannot be relied on to keep serving from there, so it
     * hands the game over if a successor is linked; the client role it continues in starts when
     * the app is back in front. A second call before [onForeground] changes nothing.
     */
    internal fun onBackground() {
        if (hasLeft || rejoinOnForeground != null) return
        rejoinOnForeground = host?.handOverForBackground()
    }

    /** The app is in front again, possibly after being frozen. */
    internal fun onForeground() {
        if (hasLeft) return
        val handover = rejoinOnForeground
        rejoinOnForeground = null
        if (handover != null && host != null) {
            // Handed over while in the background: come back as a client of that game.
            steppedDown(handover)
            return
        }
        client?.onForeground()
    }

    // --- State ---

    /** [state] and the other flows follow [sources], the flows of the runtime that just became current. */
    private fun mirror(vararg sources: StateFlow<*>) {
        mirror?.cancel()
        publish()
        // One collector per flow, subscribed before this returns, so no change is missed. Each
        // change is published from the queue of [publishDispatcher]: after the runtime finished
        // what it was doing, and before anything that was queued later.
        mirror = scope.launch(publishDispatcher, start = CoroutineStart.UNDISPATCHED) {
            for (source in sources) {
                launch(start = CoroutineStart.UNDISPATCHED) { source.drop(1).collect { publish() } }
            }
        }
    }

    private fun publish() {
        if (hasLeft) return
        val host = host
        val client = client
        if (host != null) {
            val game = host.game.value
            val speedTest = host.speedTestUi.value
            _state.value = SessionState(
                role = SessionRole.HOST,
                connection = ConnectionState.Joined,
                gameCode = gameCode,
                playerId = host.hostId,
                players = host.players.value,
                hostId = host.hostId,
                hostEpoch = host.hostEpoch,
                phase = game.phase,
                gameId = game.moduleId,
                view = game.view,
                gameNotice = host.gameNotice.value,
                advertising = host.advertisingState.value,
                pingStats = host.pingStats.value,
                successors = host.successors.value,
                offlineSecondsLeft = host.offlineSecondsLeft.value,
            )
            _realtime.value = host.realtime.value
            diagnostics.testResultState.value = host.testResult.value
            diagnostics.testJsonReceivedState.value = host.testJsonReceived.value
            diagnostics.hostSpeedTestState.value = speedTest
            diagnostics.clientSpeedTestState.value = null
            diagnostics.canRunSpeedTestState.value = game.phase == GamePhase.LOBBY && speedTest.linkedClients > 0
        } else if (client != null) {
            val game = client.game.value
            _state.value = SessionState(
                role = SessionRole.CLIENT,
                connection = connectionState(client.status.value),
                gameCode = gameCode,
                playerId = client.playerId.value,
                players = client.players.value,
                hostId = client.hostId.value,
                hostEpoch = client.hostEpoch.value,
                phase = game.phase,
                gameId = game.moduleId,
                view = game.view,
                notice = client.notice.value,
                gameNotice = client.gameNotice.value,
                migrationInfo = client.migrationInfo.value,
                lastReconnect = client.lastReconnect.value,
            )
            _realtime.value = client.realtime.value
            diagnostics.testResultState.value = client.testResult.value
            diagnostics.testJsonReceivedState.value = client.testJsonReceived.value
            diagnostics.hostSpeedTestState.value = null
            diagnostics.clientSpeedTestState.value = client.speedTestUi.value
            diagnostics.canRunSpeedTestState.value = false
        }
    }

    private fun connectionState(status: ClientStatus): ConnectionState = when (status) {
        ClientStatus.Connecting -> ConnectionState.Connecting
        ClientStatus.Joining -> ConnectionState.Joining
        ClientStatus.Joined -> ConnectionState.Joined
        is ClientStatus.Reconnecting -> ConnectionState.Reconnecting(status.secondsLeft)
        is ClientStatus.Rejected -> ConnectionState.Ended(EndReason.Rejected(status.code))
        ClientStatus.GameOver -> ConnectionState.Ended(EndReason.HostLeft)
        is ClientStatus.Disconnected -> ConnectionState.Ended(EndReason.Disconnected(status.reason))
    }
}
