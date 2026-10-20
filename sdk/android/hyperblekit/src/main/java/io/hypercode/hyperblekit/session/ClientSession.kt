package io.hypercode.hyperblekit.session

import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.GamePhase
import io.hypercode.hyperblekit.protocol.HostSnapshot
import io.hypercode.hyperblekit.JoinRejectCode
import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.protocol.Message
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.protocol.successorOrder
import io.hypercode.hyperblekit.protocol.truncatedPlayerName
import org.json.JSONObject

/**
 * The client's view of the session for one connection to a host: the players, the phase and its
 * own view of the running game, which it carries without reading it (wire specification "Game modules").
 *
 * Pure Kotlin, no I/O: it is told what arrived and answers with the messages to send. The host is
 * authoritative, so state only changes when a host message says so.
 *
 * Not thread-safe: the owner calls it from a single thread. `nowMillis` is wall-clock time in ms
 * (it goes into `ts` and nothing else); host silence is measured with [elapsedMillis], a
 * monotonic clock that keeps running while the device sleeps.
 */
internal class ClientSession(
    val deviceId: String,
    name: String,
    /** Whether this device could take over as host; reported in `JOIN_REQUEST`. */
    private val canHost: Boolean,
    /** For a device that was the host itself until now: the epoch it hosted at. */
    knownHostEpoch: Int = 0,
    /** For the screen, until the first `STATE_SYNC` replaces it. */
    initialPlayers: List<Player> = emptyList(),
    private val elapsedMillis: () -> Long,
) {

    class Output {
        val outgoing = mutableListOf<Message>()
        val notes = mutableListOf<String>()

        /** The handled message was a `STATE_SYNC` and it replaced the state. */
        var stateSyncApplied = false

        /** The handled message was `HOST_LEAVING`: who takes over. */
        var nextHostId: String? = null

        /** The handled message was `GAME_OVER`: why. */
        var gameOverReason: String? = null

        /** The handled message was `ERROR`: the host rejected one of this client's actions. */
        var error: Message.Body.Error? = null

        /** The handled message was `LOG_REQUEST`: the host asked this device to upload its log. */
        var logRequested = false
    }

    val name: String = name.truncatedPlayerName()

    /** Assigned by the host in `JOIN_ACCEPT`. */
    var playerId: String? = null
        private set

    /** 0 until the first `STATE_SYNC`. */
    var stateVersion = 0
        private set
    var players: List<Player> = initialPlayers
        private set

    var phase: String = GamePhase.LOBBY
        private set

    /** Wire value of `game` while a game exists (phase `playing` or `finished`); null in the lobby. */
    var gameModuleId: String? = null
        private set

    /** This player's own view of the game, in the game module's format; null in the lobby. */
    var view: JsonPayload? = null
        private set

    /** The seat this device last held; survives a reconnect, unlike [playerId]. */
    var lastPlayerId: String? = null
        private set

    /** The current host as of the last applied `STATE_SYNC`; null before the first one. */
    var hostId: String? = null
        private set

    /** The highest `hostEpoch` applied so far. A host advertising a lower one is stale. */
    var knownHostEpoch = knownHostEpoch
        private set

    /** The newest `HOST_SNAPSHOT` received, if this device is (or was) a successor. Never shown. */
    var snapshot: HostSnapshot? = null
        private set

    /**
     * This device's takeover rank (1 = first); null if it cannot take over, which is the case
     * exactly when it holds no snapshot.
     *
     * The rank is its place in the held snapshot's `successors`. If it is not listed there, it is
     * its place among the players that can host, in seat order, without the host — whatever
     * their `connected` says: a host that stalls tends to declare everyone disconnected first,
     * and that must not leave the game without a successor.
     */
    val takeoverRank: Int?
        get() {
            val held = snapshot ?: return null
            val own = lastPlayerId ?: return null
            val listed = held.successors.indexOf(own)
            if (listed >= 0) return listed + 1
            val host = hostId ?: held.state.hostId
            val candidates = players.ifEmpty { held.state.players }.map { it.copy(connected = true) }
            val index = successorOrder(candidates, host).indexOf(own)
            return if (index < 0) null else index + 1
        }

    /** Set when the host answered with `JOIN_REJECT`. */
    var rejectCode: String? = null
        private set

    private var lastHostTrafficAtMillis = 0L

    /** Starts at 1 on every (re)connection. */
    private var nextSeq = 1

    /**
     * Call once the transport is ready (subscribed); starts a fresh connection. Also for a
     * reconnect: `seq` restarts at 1 and the known `stateVersion` is forgotten, so the
     * `STATE_SYNC` that follows is applied even if a restarted host counts from a lower version.
     * The last player list, phase and game view are kept for the screen until that `STATE_SYNC`
     * replaces all of them.
     */
    fun joinRequest(nowMillis: Long): Message {
        nextSeq = 1
        playerId = null
        stateVersion = 0
        rejectCode = null
        lastHostTrafficAtMillis = elapsedMillis()
        return makeMessage(Message.Body.JoinRequest(name, GameConstants.PLATFORM, deviceId, canHost), nowMillis)
    }

    fun leave(nowMillis: Long): Message = makeMessage(Message.Body.Leave, nowMillis)

    /**
     * One batch of this device's debug log for the host (answering a `LOG_REQUEST`): [part] of
     * [parts], carrying [lines]. A diagnostic message; it carries no state and expects no answer.
     */
    fun logUpload(part: Int, parts: Int, lines: List<String>, nowMillis: Long): Message =
        makeMessage(Message.Body.LogUpload(part, parts, lines), nowMillis)

    /**
     * This player's move as a `GAME_ACTION` for the running game. The state only changes when the
     * host answers with `GAME_STATE`. Returns null when no game is being played.
     */
    fun gameAction(action: JSONObject, nowMillis: Long): Message? {
        val game = gameModuleId ?: return null
        if (phase != GamePhase.PLAYING) return null
        return makeMessage(Message.Body.GameAction(game, JsonPayload(action)), nowMillis)
    }

    fun isHostSilent(): Boolean =
        elapsedMillis() - lastHostTrafficAtMillis > GameConstants.SILENCE_TIMEOUT_MILLIS

    /**
     * Something that is not a protocol message arrived from the host (the debug JSON test frame).
     * It counts as traffic for the silence rule and changes nothing else.
     */
    fun trafficReceived() {
        lastHostTrafficAtMillis = elapsedMillis()
    }

    fun receive(message: Message, nowMillis: Long): Output {
        val output = Output()
        lastHostTrafficAtMillis = elapsedMillis()
        val body = message.body
        if (body is Message.Body.JoinReject) {
            // Handled whatever its `v`: this is how a version mismatch is reported.
            rejectCode = body.code
            if (body.code == JoinRejectCode.REMOVED) {
                // The host removed this player: the seat and its game are gone for this device.
                phase = GamePhase.LOBBY
                gameModuleId = null
                view = null
            }
            return output
        }
        if (message.v != GameConstants.PROTOCOL_VERSION) {
            output.notes += "ignored ${message.type}: protocol version ${message.v}"
            return output
        }

        when (body) {
            // The version is taken from the STATE_SYNC that always follows, together with the state.
            is Message.Body.JoinAccept -> {
                playerId = body.playerId
                lastPlayerId = body.playerId
            }
            is Message.Body.StateSync ->
                // A full snapshot replaces whatever we have, unless it is older.
                if (body.state.stateVersion >= stateVersion) {
                    players = body.state.players
                    stateVersion = body.state.stateVersion
                    hostId = body.state.hostId
                    phase = body.state.phase
                    gameModuleId = body.game
                    view = body.view
                    knownHostEpoch = maxOf(knownHostEpoch, body.state.hostEpoch)
                    // A snapshot from an earlier host must never be taken over from: wait for
                    // the new host's own HOST_SNAPSHOT.
                    val held = snapshot
                    if (held != null && body.state.hostEpoch > held.state.hostEpoch) {
                        snapshot = null
                        output.notes += "discarded the snapshot of epoch ${held.state.hostEpoch}: host epoch is now ${body.state.hostEpoch}"
                    }
                    output.stateSyncApplied = true
                } else {
                    output.notes += "STATE_SYNC sv=${body.state.stateVersion} is older than sv=$stateVersion, not applied"
                }
            is Message.Body.PlayerList ->
                if (isNext(message, body.stateVersion, nowMillis, output)) {
                    players = body.players
                    stateVersion = body.stateVersion
                }
            is Message.Body.GameUpdate ->
                if (isNext(message, body.stateVersion, nowMillis, output)) {
                    phase = body.phase
                    gameModuleId = body.game
                    view = body.view
                    stateVersion = body.stateVersion
                }
            is Message.Body.Ping -> {
                output.outgoing += makeMessage(Message.Body.Pong(body.pingTs), nowMillis)
                // The host is ahead: something was missed.
                val hostVersion = body.stateVersion
                if (hostVersion != null && playerId != null && hostVersion > stateVersion) {
                    output.notes += "PING sv=$hostVersion is ahead of sv=$stateVersion, asking for a sync"
                    output.outgoing += makeMessage(Message.Body.SyncRequest(stateVersion), nowMillis)
                }
            }
            is Message.Body.Error -> output.error = body
            is Message.Body.Pong -> Unit
            is Message.Body.HostSnapshot ->
                if (body.snapshot.isNewerThan(snapshot)) {
                    snapshot = body.snapshot
                } else {
                    output.notes += "HOST_SNAPSHOT is older than the one held, not kept"
                }
            is Message.Body.HostLeaving -> output.nextHostId = body.nextHostId
            is Message.Body.GameOver -> output.gameOverReason = body.reason
            Message.Body.LogRequest -> {
                output.logRequested = true
                output.notes += "host asked for this device's log"
            }
            is Message.Body.Unknown -> output.notes += "ignored unknown message type ${body.type}"
            is Message.Body.JoinRequest, is Message.Body.JoinReject, Message.Body.Leave, is Message.Body.Ack,
            is Message.Body.GameAction, is Message.Body.SyncRequest, is Message.Body.LogUpload ->
                output.notes += "ignored ${message.type}: not a host message"
        }
        if (message.needsAck) {
            output.outgoing += makeMessage(Message.Body.Ack(message.seq), nowMillis)
        }
        return output
    }

    /**
     * wire specification "`stateVersion` rules" for a state-changing [message] with [version]. True when it is
     * the next change and must be applied. Otherwise it is already reflected in the state, or it
     * skips ahead: a gap, which is not applied but answered with `SYNC_REQUEST`. Before the first
     * `STATE_SYNC` of this connection everything skips ahead: that `STATE_SYNC` was lost.
     */
    private fun isNext(message: Message, version: Int, nowMillis: Long, output: Output): Boolean {
        if (version <= stateVersion) {
            output.notes += "${message.type} sv=$version already applied"
            return false
        }
        if (stateVersion == 0 || version > stateVersion + 1) {
            output.notes += "${message.type} sv=$version skips ahead of sv=$stateVersion, asking for a sync"
            // The host only answers a player it has seated.
            if (playerId != null) output.outgoing += makeMessage(Message.Body.SyncRequest(stateVersion), nowMillis)
            return false
        }
        return true
    }

    private fun makeMessage(body: Message.Body, nowMillis: Long): Message =
        Message(
            seq = nextSeq++,
            from = playerId ?: GameConstants.UNASSIGNED_PLAYER_ID,
            ts = nowMillis,
            body = body,
        )
}
