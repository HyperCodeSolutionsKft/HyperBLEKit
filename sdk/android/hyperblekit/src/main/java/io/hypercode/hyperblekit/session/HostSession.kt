package io.hypercode.hyperblekit.session

import io.hypercode.hyperblekit.PingStats
import io.hypercode.hyperblekit.PingReply
import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.game.GameInstance
import io.hypercode.hyperblekit.game.GameModule
import io.hypercode.hyperblekit.game.GameOutcome
import io.hypercode.hyperblekit.game.RealtimeGameInstance
import io.hypercode.hyperblekit.game.RealtimeSpec
import io.hypercode.hyperblekit.protocol.GameErrorCode
import io.hypercode.hyperblekit.protocol.GameInfo
import io.hypercode.hyperblekit.protocol.GameOverReason
import io.hypercode.hyperblekit.GamePhase
import io.hypercode.hyperblekit.protocol.GameState
import io.hypercode.hyperblekit.protocol.HostSnapshot
import io.hypercode.hyperblekit.JoinRejectCode
import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.protocol.Message
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.protocol.PlayerListEvent
import io.hypercode.hyperblekit.protocol.seatNumber
import io.hypercode.hyperblekit.protocol.truncatedPlayerName
import io.hypercode.hyperblekit.transport.LinkId
import org.json.JSONObject
import kotlin.random.Random

/**
 * The host's engine rules: seats, sequence numbers, `stateVersion`, heartbeat, running a game
 * module, and keeping the successors supplied with snapshots so one of them can take over (host
 * migration).
 *
 * The game itself is a [GameModule] handed in from outside: the session starts it, passes actions
 * to it and sends its views on, without ever reading a game's JSON (wire specification "Game modules").
 *
 * The host is whichever player currently hosts: `p1` for a game made with [create], the
 * successor's own player ID after [restore]. Player IDs never change.
 *
 * Pure Kotlin, no I/O: it is told what happened and answers with the messages to send. See the wire specification
 * "Players and Seats", "`seq` rules", "`stateVersion` rules" and "Reliability Rules".
 *
 * Not thread-safe: the owner calls it from a single thread. `nowMillis` is wall-clock time in ms
 * (it goes into `ts` / `pingTs` and nothing else). Every duration — silence, how long a successor
 * has been gone — is measured with [elapsedMillis], a monotonic clock that keeps running while
 * the device sleeps.
 */
internal class HostSession private constructor(
    val gameId: String,
    private val host: Player,
    private val hostDeviceId: String,
    /** Starts at 1, +1 on every takeover. */
    val hostEpoch: Int,
    initialStateVersion: Int,
    initialSeats: List<Seat>,
    initialPhase: String,
    initialModule: GameModule?,
    initialGame: GameInstance?,
    /** Set by [restore] when the snapshot's game could not be continued: why. For the log. */
    val restoreNote: String?,
    private val elapsedMillis: () -> Long,
    /** Seats in the game, the host included (`HyperBleKitConfig.maxPlayers`). */
    private val maxPlayers: Int,
) {

    data class Recipient(
        val link: LinkId,
        /** Player ID for the debug log, "?" for a link without a seat. */
        val label: String,
    )

    /** One message and everyone who gets it. A broadcast has several recipients and one `seq`. */
    data class Outgoing(val message: Message, val recipients: List<Recipient>)

    /** One `LOG_UPLOAD` batch from a client, surfaced for the runtime to reassemble. */
    data class LogUploadReceived(val playerId: String, val part: Int, val parts: Int, val lines: List<String>)

    class Output {
        val outgoing = mutableListOf<Outgoing>()
        val notes = mutableListOf<String>()

        /** Set when the handled message was a `PONG`. */
        var roundTripMs: Long? = null

        /** Set when the host's own request (start or end a game, its own move) was refused: why. */
        var refusal: String? = null

        /** After [realtimeTick]: the world frame payload of the new world. */
        var worldFrame: ByteArray? = null

        /** After [realtimeTick]: [worldFrame] goes to every linked client on this tick. */
        var sendWorldFrame = false

        /** After [frameReceived]: the frame was taken as an input of the running real-time game. */
        var inputTaken = false

        /** Set when the handled message was a `LOG_UPLOAD`: one batch of a client's debug log. */
        var logUpload: LogUploadReceived? = null
    }

    private class Seat(
        var player: Player,
        val deviceId: String,
        /** null while the player has no link to the host. */
        var link: LinkId?,
        /** On the monotonic clock. */
        var lastTrafficAtMillis: Long,
        /** Client `seq` values already handled on the current connection. */
        var seenSeqs: MutableSet<Int>,
    ) {
        /**
         * When (monotonic) this seat was marked disconnected while it was a snapshot target — it
         * may have taken the game over. Null once it reconnects, and for every other seat.
         */
        var droppedAsSuccessorAtMillis: Long? = null

        /** When (monotonic) this seat is removed unless it comes back; null while connected. */
        var removeAtMillis: Long? = null
    }

    /**
     * One ping-all in progress (see [pingAll] / [finishPingAll]). [expected] are the clients pinged
     * and [answers] fills with their round trips as `PONG`s arrive. [sendTs] is the ping's `pingTs`,
     * so only the `PONG`s of this round are counted.
     */
    private class PingAllRound(val sendTs: Long, val expected: List<String>) {
        val answers = mutableMapOf<String, Long>()
    }

    val hostId: String get() = host.id

    /** 1 = just the first host in the lobby. */
    var stateVersion = initialStateVersion
        private set
    var phase: String = initialPhase
        private set

    /** The game module in use and the game in its current state; both null in the lobby. */
    private var module: GameModule? = initialModule
    private var game: GameInstance? = initialGame

    /** Wire value of `game` while a game exists (phase `playing` or `finished`). */
    val gameModuleId: String? get() = module?.id

    /** The host's own view of the game, for its screen. */
    val ownView: JsonPayload? get() = viewFor(host.id)
    var pingStats = PingStats()
        private set

    /** The ping-all round in progress ([pingAll]); null when none is running. */
    private var pingAllRound: PingAllRound? = null

    /** Every seat but the host's own, ordered by seat number. */
    private val seats = initialSeats.toMutableList()

    /** One counter for everything this host sends. */
    private var nextSeq = 1

    /** Real-time game: a player of it was offline at the last check. */
    private var realtimeHeld = false

    /** Real-time game: when (monotonic) it moves again after everyone came back; null when not waiting. */
    private var realtimeResumeAtMillis: Long? = null

    /** The host's newest input since the last tick, and the earlier ones still held back, oldest first. */
    private var hostInputPending: ByteArray? = null
    private val hostInputsDelayed = ArrayDeque<ByteArray?>()

    /** Ticks run since the real-time game started here: every n-th one sends a world frame. */
    private var realtimeTicks = 0L

    /** What the last round of snapshots covered: sent again when either changes. */
    private var lastSnapshotVersion: Int? = null
    private var lastSnapshotTargets: List<String> = emptyList()

    val players: List<Player> get() = (seats.map { it.player } + host).sortedBy { seatNumber(it.id) }

    /** The real-time game being played; null in the lobby, when finished, or for a turn-based game. */
    val realtimeGame: RealtimeGameInstance?
        get() = if (phase == GamePhase.PLAYING) game as? RealtimeGameInstance else null

    /** The numbers of the real-time game while one exists (playing or finished); null otherwise. */
    val realtimeSpec: RealtimeSpec? get() = (game as? RealtimeGameInstance)?.realtime

    /**
     * Whether the real-time world moves now (wire specification "Real-time games"): it is being played, every
     * player of it is connected, and [GameConstants.REALTIME_RESUME_DELAY_MILLIS] have passed since
     * the last of them came back.
     */
    val realtimeRunning: Boolean
        get() {
            if (realtimeGame == null || realtimeHeld) return false
            val resumeAt = realtimeResumeAtMillis ?: return true
            return elapsedMillis() >= resumeAt
        }

    val gameInfo: GameInfo
        get() = GameInfo(
            v = GameConstants.PROTOCOL_VERSION,
            gameId = gameId,
            hostName = host.name,
            players = players.size,
            maxPlayers = maxPlayers,
            lobbyOpen = phase == GamePhase.LOBBY,
            hostPlatform = host.platform,
            hostId = host.id,
            hostEpoch = hostEpoch,
        )

    /** Every joined client that has a link right now. */
    val clientRecipients: List<Recipient> get() = recipients(seats)

    /** Who would take over, first to last: linked, connected, able to host; ascending seat. */
    val successorIds: List<String> get() = successors().map { it.player.id }

    /**
     * Seats that held a snapshot when they dropped and have stayed disconnected (not left) for at
     * least [forMillis]: each of them may be hosting this game by now.
     */
    fun successorsGone(forMillis: Long): List<String> {
        val now = elapsedMillis()
        return seats.filter { seat ->
            val droppedAt = seat.droppedAsSuccessorAtMillis
            !seat.player.connected && droppedAt != null && now - droppedAt >= forMillis
        }.map { it.player.id }
    }

    /**
     * Every offline seat with the whole seconds left until it is removed (rounded up, never below
     * 0), in seat order. The removal itself happens in [heartbeat].
     */
    val offlineSecondsLeft: Map<String, Long>
        get() {
            val now = elapsedMillis()
            val left = LinkedHashMap<String, Long>()
            for (seat in seats) {
                val removeAt = seat.removeAtMillis ?: continue
                if (seat.player.connected) continue
                left[seat.player.id] = ((removeAt - now).coerceAtLeast(0) + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
            }
            return left
        }

    /** The host itself was stalled: how long a successor has been gone counts from now again. */
    fun restartGoneTimers() {
        val now = elapsedMillis()
        for (seat in seats) if (seat.droppedAsSuccessorAtMillis != null) seat.droppedAsSuccessorAtMillis = now
    }

    // --- Input ---

    /** [random] is what a game may use when a `GAME_ACTION` needs randomness (a new deal). */
    fun receive(message: Message, link: LinkId, nowMillis: Long, random: Random = Random.Default): Output =
        withSnapshots(handle(message, link, nowMillis, random), nowMillis)

    /**
     * Something that is not a protocol message arrived on [link] (the debug JSON test frame).
     * It counts as traffic for the silence rule and changes nothing else: no dedup, no ACK.
     */
    fun trafficReceived(link: LinkId, nowMillis: Long): Output {
        val output = Output()
        seats.firstOrNull { it.link == link }?.let { noteTraffic(it, nowMillis, output) }
        return withSnapshots(output, nowMillis)
    }

    /**
     * A real-time frame arrived on [link]. It counts as traffic, like a message (wire specification "Real-time
     * frames"). When a real-time game is being played, the sender is one of its players and the
     * payload is of the game's input kind, it is that player's newest input ([Output.inputTaken]):
     * it counts at the next tick.
     */
    fun frameReceived(link: LinkId, payload: ByteArray, nowMillis: Long): Output {
        val output = Output()
        val seat = seats.firstOrNull { it.link == link } ?: return output
        noteTraffic(seat, nowMillis, output)
        val running = realtimeGame
        if (running != null && payload.isNotEmpty() && (payload[0].toInt() and BYTE_MASK) == running.realtime.inputKind &&
            seat.player.id in running.players
        ) {
            game = running.input(seat.player.id, payload)
            output.inputTaken = true
        }
        return withSnapshots(output, nowMillis)
    }

    /**
     * The host's own input for the real-time game being played. It counts
     * [GameConstants.REALTIME_HOST_INPUT_DELAY_TICKS] ticks after a client input given at the same
     * moment. Ignored when the host does not play it or the payload is not of its input kind.
     */
    fun realtimeHostInput(payload: ByteArray) {
        val running = realtimeGame ?: return
        if (host.id !in running.players || payload.isEmpty() || (payload[0].toInt() and BYTE_MASK) != running.realtime.inputKind) return
        hostInputPending = payload.copyOf()
    }

    /**
     * One tick of the real-time game, on the owner's fixed schedule; nothing while it is not
     * [realtimeRunning]. [Output.worldFrame] is the new world; every
     * [RealtimeSpec.framePeriodTicks]-th tick it goes to every linked client ([Output.sendWorldFrame]).
     * A tick that changes the coarse state is one state change: `stateVersion` +1, a `GAME_STATE`
     * to every linked client, snapshots to the successors; the phase is `finished` if the game is.
     */
    fun realtimeTick(nowMillis: Long): Output {
        val output = Output()
        val running = realtimeGame
        if (running == null || !realtimeRunning) return output
        hostInputsDelayed.addLast(hostInputPending)
        hostInputPending = null
        var current: RealtimeGameInstance = running
        if (hostInputsDelayed.size > GameConstants.REALTIME_HOST_INPUT_DELAY_TICKS) {
            hostInputsDelayed.removeFirst()?.let { current = current.input(host.id, it) }
        }
        val result = current.tick()
        game = result.next
        realtimeTicks += 1
        output.worldFrame = result.next.worldFrame()
        // A tick that changes the coarse state also goes out, so the clients see the deciding
        // tick even when the game finishes on it and ticking stops.
        output.sendWorldFrame = realtimeTicks % running.realtime.framePeriodTicks == 0L || result.coarseChanged
        if (result.coarseChanged) {
            stateVersion += 1
            if (result.next.finished) {
                phase = GamePhase.FINISHED
                output.notes += "${module?.title ?: "the game"} is finished"
            }
            output.outgoing += gameStates(nowMillis)
        }
        return withSnapshots(output, nowMillis)
    }

    /** The seat behind [link] for the debug log, "?" for a link without a seat. */
    fun labelFor(link: LinkId): String =
        seats.firstOrNull { it.link == link }?.player?.id ?: GameConstants.UNASSIGNED_PLAYER_ID

    /** The link is gone (the client unsubscribed or disconnected). The seat is kept. */
    fun linkDown(link: LinkId, nowMillis: Long): Output {
        val output = Output()
        val seat = seats.firstOrNull { it.link == link } ?: return output
        seat.link = null
        if (seat.player.connected) markDisconnected(seat, nowMillis, output)
        return withSnapshots(output, nowMillis)
    }

    /**
     * Call every [GameConstants.PING_INTERVAL_MILLIS]: times out silent clients, removes the seats
     * that stayed offline past their deadline, then pings everyone.
     *
     * @param judgeClients false when the host itself was stalled since the last call: the silence
     * was its own, so no client is timed out or removed, every linked client's silence starts over
     * and no offline seat is removed sooner than [GameConstants.OFFLINE_REMOVE_AFTER_MILLIS] from now.
     */
    fun heartbeat(nowMillis: Long, judgeClients: Boolean = true): Output {
        val output = Output()
        val now = elapsedMillis()
        for (seat in seats) {
            if (!judgeClients) {
                if (seat.link != null) seat.lastTrafficAtMillis = now
                seat.removeAtMillis?.let { seat.removeAtMillis = maxOf(it, now + GameConstants.OFFLINE_REMOVE_AFTER_MILLIS) }
            } else if (seat.player.connected && now - seat.lastTrafficAtMillis > GameConstants.SILENCE_TIMEOUT_MILLIS) {
                output.notes += "${seat.player.id} silent for ${GameConstants.SILENCE_TIMEOUT_MILLIS / MILLIS_PER_SECOND} s"
                markDisconnected(seat, nowMillis, output)
            }
        }
        if (judgeClients) {
            for (seat in seats.filter { !it.player.connected && (it.removeAtMillis ?: Long.MAX_VALUE) <= now }) {
                removeSeat(seat, "offline too long", nowMillis, output)
            }
        }
        val everyone = recipients(seats)
        if (everyone.isNotEmpty()) {
            // The version lets a client that missed something notice and ask for a STATE_SYNC.
            output.outgoing += Outgoing(makeMessage(Message.Body.Ping(nowMillis, stateVersion), nowMillis), everyone)
        }
        return withSnapshots(output, nowMillis)
    }

    /**
     * Pings every linked client at once and starts collecting their round trips, for an on-demand
     * liveness and latency check (`SessionDiagnostics.pingAll`). The answers arrive as `PONG`s like
     * any ping; read them with [finishPingAll] after a short wait. A new call replaces any round
     * still open.
     */
    fun pingAll(nowMillis: Long): Output {
        val output = Output()
        val everyone = recipients(seats)
        if (everyone.isEmpty()) {
            pingAllRound = null
            output.notes += "ping_all: no linked client to ping"
            return output
        }
        val expected = seats.filter { it.link != null }.map { it.player.id }
        pingAllRound = PingAllRound(nowMillis, expected)
        output.outgoing += Outgoing(makeMessage(Message.Body.Ping(nowMillis, stateVersion), nowMillis), everyone)
        output.notes += "ping_all: pinged ${expected.size} client(s)"
        return output
    }

    /**
     * Asks every linked client to upload its debug log (`SessionDiagnostics.requestClientLogs`):
     * a `LOG_REQUEST` to each. The batches come back as `LOG_UPLOAD` messages ([Output.logUpload]).
     * Returns the player IDs that were asked, in seat order, so the runtime knows who to expect.
     */
    fun requestClientLogs(nowMillis: Long): Pair<Output, List<String>> {
        val output = Output()
        val everyone = recipients(seats)
        if (everyone.isEmpty()) {
            output.notes += "collect logs: no linked client to ask"
            return output to emptyList()
        }
        output.outgoing += Outgoing(makeMessage(Message.Body.LogRequest, nowMillis), everyone)
        val asked = everyone.map { it.label }.sortedBy { seatNumber(it) }
        output.notes += "collect logs: asked ${asked.size} client(s) to upload their log"
        return output to asked
    }

    /**
     * Ends the ping-all started by [pingAll] and returns one reply per pinged client, in seat
     * order; a client that did not answer has a null time. Empty when no round was open.
     */
    fun finishPingAll(): List<PingReply> {
        val round = pingAllRound ?: return emptyList()
        pingAllRound = null
        return round.expected
            .sortedBy { seatNumber(it) }
            .map { PingReply(it, round.answers[it]) }
    }

    /**
     * The host starts a game of [module], from the lobby or after a finished game. Everyone who is
     * connected right now plays, in ascending seat order. If [module] does not support that many
     * players, or it is a real-time game whose largest world frame does not fit every player's
     * link, nothing is sent and [Output.refusal] says why.
     */
    fun startGame(
        module: GameModule,
        nowMillis: Long,
        random: Random,
        /** The largest frame payload each client link carries; a real-time game needs its largest world frame on every player's link. */
        framePayloadLimit: (LinkId) -> Int = { Int.MAX_VALUE },
    ): Output {
        val output = Output()
        if (phase == GamePhase.PLAYING) {
            output.refusal = "a game is already running"
            return output
        }
        val playerIds = players.filter { it.connected }.map { it.id }
        if (!module.supports(playerIds.size)) {
            output.refusal = "${module.title} needs ${module.playerCountLabel} connected players, " +
                "${playerIds.size} connected now"
            return output
        }
        val started = module.start(playerIds, random)
        if (started is RealtimeGameInstance) {
            val needed = started.realtime.maxWorldFrameBytes
            val tooSmall = seats.firstOrNull { seat ->
                seat.player.id in playerIds && (seat.link?.let(framePayloadLimit) ?: 0) < needed
            }
            if (tooSmall != null) {
                output.refusal = "${module.title} needs a better connection to ${tooSmall.player.id}"
                return output
            }
        }
        this.module = module
        game = started
        phase = GamePhase.PLAYING
        stateVersion += 1
        resetRealtime()
        output.notes += "started ${module.id} with ${playerIds.joinToString(", ")}"
        output.outgoing += gameStates(nowMillis)
        return withSnapshots(output, nowMillis)
    }

    /** The host ends the game, running or finished: back to the lobby, the game is dropped. */
    fun endGame(nowMillis: Long): Output {
        val output = Output()
        if (phase == GamePhase.LOBBY) {
            output.refusal = "no game to end"
            return output
        }
        module = null
        game = null
        phase = GamePhase.LOBBY
        stateVersion += 1
        output.outgoing += gameStates(nowMillis)
        return withSnapshots(output, nowMillis)
    }

    /**
     * The host removes [playerId] at once instead of waiting for its deadline. Only an offline
     * player, never the host itself; otherwise nothing is sent and [Output.refusal] says why.
     */
    fun removePlayer(playerId: String, nowMillis: Long): Output {
        val output = Output()
        val seat = seats.firstOrNull { it.player.id == playerId }
        when {
            playerId == host.id -> output.refusal = "the host cannot be removed"
            seat == null -> output.refusal = "$playerId has no seat"
            seat.player.connected -> output.refusal = "$playerId is connected: only an offline player can be removed"
            else -> {
                removeSeat(seat, "removed by the host", nowMillis, output)
                return withSnapshots(output, nowMillis)
            }
        }
        return output
    }

    /**
     * The host's own move. It goes through the same game module as a client's `GAME_ACTION`, but
     * locally: a rejection sends nothing and is reported in [Output.refusal].
     */
    fun hostAction(action: JSONObject, nowMillis: Long, random: Random): Output {
        val output = Output()
        val rejection = play(host.id, module?.id, action, random)
        if (rejection != null) {
            output.refusal = rejection.code + rejection.reason?.let { ": $it" }.orEmpty()
            return output
        }
        output.outgoing += gameStates(nowMillis)
        return withSnapshots(output, nowMillis)
    }

    /**
     * The host leaves on purpose. With a successor: a final `HOST_SNAPSHOT` for it, then
     * `HOST_LEAVING` naming it to everyone. Without one: `GAME_OVER` `host_left`.
     * The session must not be used afterwards.
     */
    fun hostLeaving(nowMillis: Long): Output {
        val output = Output()
        val everyone = recipients(seats)
        val targetIds = successors().take(GameConstants.SNAPSHOT_SUCCESSORS).map { it.player.id }
        val successor = successors().firstOrNull()
        if (successor != null) {
            output.outgoing += Outgoing(makeMessage(snapshotBody(targetIds), nowMillis), recipients(listOf(successor)))
            output.outgoing += Outgoing(makeMessage(Message.Body.HostLeaving(successor.player.id), nowMillis), everyone)
        } else if (everyone.isNotEmpty()) {
            output.outgoing += Outgoing(makeMessage(Message.Body.GameOver(GameOverReason.HOST_LEFT), nowMillis), everyone)
        }
        return output
    }

    private fun handle(message: Message, link: LinkId, nowMillis: Long, random: Random): Output {
        val output = Output()
        val body = message.body
        if (body is Message.Body.JoinRequest) {
            join(body, message, link, nowMillis, output)
            return output
        }
        val seat = seats.firstOrNull { it.link == link }
        if (seat == null) {
            output.notes += "ignored ${message.type}: sender has not joined"
            return output
        }
        if (message.v != GameConstants.PROTOCOL_VERSION) {
            output.notes += "ignored ${message.type}: protocol version ${message.v}"
            return output
        }

        noteTraffic(seat, nowMillis, output)
        if (!seat.seenSeqs.add(message.seq)) {
            output.notes += "ignored duplicate ${message.type} seq=${message.seq} from ${seat.player.id}"
            return output
        }

        when (body) {
            Message.Body.Leave -> leave(seat, nowMillis, output)
            is Message.Body.Ping ->
                output.outgoing += Outgoing(makeMessage(Message.Body.Pong(body.pingTs), nowMillis), recipients(listOf(seat)))
            is Message.Body.Pong -> {
                val roundTrip = nowMillis - body.pingTs
                if (roundTrip >= 0) {
                    pingStats = pingStats.record(roundTrip)
                    output.roundTripMs = roundTrip
                    pingAllRound?.let { round ->
                        if (body.pingTs == round.sendTs && seat.player.id in round.expected) {
                            round.answers[seat.player.id] = roundTrip
                        }
                    }
                }
            }
            // The host never resends: an ACK only shows up in the log, where lost messages are counted.
            is Message.Body.Ack -> Unit
            is Message.Body.GameAction -> gameAction(seat, body, message.seq, nowMillis, random, output)
            is Message.Body.SyncRequest -> output.outgoing += stateSync(seat, nowMillis)
            is Message.Body.LogUpload -> {
                output.logUpload = LogUploadReceived(seat.player.id, body.part, body.parts, body.lines)
                output.notes += "log from ${seat.player.id}: batch ${body.part + 1}/${body.parts}, ${body.lines.size} line(s)"
            }
            is Message.Body.Unknown -> output.notes += "ignored unknown message type ${body.type}"
            is Message.Body.JoinRequest, is Message.Body.JoinAccept, is Message.Body.JoinReject,
            is Message.Body.StateSync, is Message.Body.PlayerList, is Message.Body.HostSnapshot,
            is Message.Body.HostLeaving, is Message.Body.GameOver, is Message.Body.GameUpdate,
            is Message.Body.Error, Message.Body.LogRequest ->
                output.notes += "ignored ${message.type}: not a client message"
        }
        return output
    }

    // --- Rules ---

    private fun noteTraffic(seat: Seat, nowMillis: Long, output: Output) {
        seat.lastTrafficAtMillis = elapsedMillis()
        if (!seat.player.connected) {
            // It went silent but the link survived: any traffic brings it back with a fresh state.
            seat.player = seat.player.copy(connected = true)
            seat.droppedAsSuccessorAtMillis = null
            seat.removeAtMillis = null
            stateVersion += 1
            output.outgoing += stateSync(seat, nowMillis)
            output.outgoing += playerList(PlayerListEvent.RECONNECTED, seat.player.id, nowMillis)
        }
    }

    private fun join(request: Message.Body.JoinRequest, envelope: Message, link: LinkId, nowMillis: Long, output: Output) {
        if (envelope.v != GameConstants.PROTOCOL_VERSION) {
            reject(JoinRejectCode.VERSION_MISMATCH, link, nowMillis, output)
            return
        }
        val name = request.name.truncatedPlayerName()
        val known = seats.firstOrNull { it.deviceId == request.deviceId }
        val seat: Seat
        val event: String
        if (known != null) {
            // A known device always gets its old seat back, on whatever link it arrives now. A
            // link it had before is simply never addressed again.
            known.player = known.player.copy(
                name = name, platform = request.platform, connected = true, canHost = request.canHost,
            )
            known.link = link
            known.droppedAsSuccessorAtMillis = null
            known.removeAtMillis = null
            known.lastTrafficAtMillis = elapsedMillis()
            known.seenSeqs = mutableSetOf(envelope.seq)
            seat = known
            event = PlayerListEvent.RECONNECTED
        } else {
            if (phase != GamePhase.LOBBY) {
                reject(JoinRejectCode.GAME_STARTED, link, nowMillis, output)
                return
            }
            val playerId = lowestFreeSeat()
            if (playerId == null) {
                reject(JoinRejectCode.LOBBY_FULL, link, nowMillis, output)
                return
            }
            seat = Seat(
                player = Player(
                    id = playerId, name = name, platform = request.platform, connected = true,
                    canHost = request.canHost,
                ),
                deviceId = request.deviceId,
                link = link,
                lastTrafficAtMillis = elapsedMillis(),
                seenSeqs = mutableSetOf(envelope.seq),
            )
            seats += seat
            seats.sortBy { seatNumber(it.player.id) }
            event = PlayerListEvent.JOINED
        }
        stateVersion += 1
        val accept = Message.Body.JoinAccept(playerId = seat.player.id, stateVersion = stateVersion)
        output.outgoing += Outgoing(makeMessage(accept, nowMillis), recipients(listOf(seat)))
        output.outgoing += stateSync(seat, nowMillis)
        output.outgoing += playerList(event, seat.player.id, nowMillis)
    }

    private fun reject(code: String, link: LinkId, nowMillis: Long, output: Output) {
        val message = makeMessage(Message.Body.JoinReject(code), nowMillis)
        output.outgoing += Outgoing(message, listOf(Recipient(link, GameConstants.UNASSIGNED_PLAYER_ID)))
    }

    private fun leave(seat: Seat, nowMillis: Long, output: Output) {
        if (phase == GamePhase.LOBBY) {
            seats.remove(seat)
        } else {
            // During a game the seat is kept for now, like a player who went silent.
            seat.player = seat.player.copy(connected = false)
            seat.link = null
            seat.removeAtMillis = elapsedMillis() + GameConstants.OFFLINE_REMOVE_AFTER_MILLIS
        }
        stateVersion += 1
        output.outgoing += playerList(PlayerListEvent.LEFT, seat.player.id, nowMillis)
    }

    /** A client's move: every linked seat gets its new view, or the sender alone gets `ERROR`. */
    private fun gameAction(
        seat: Seat, body: Message.Body.GameAction, clientSeq: Int, nowMillis: Long, random: Random, output: Output,
    ) {
        val rejection = play(seat.player.id, body.game, body.action.toJson(), random)
        if (rejection != null) {
            val error = Message.Body.Error(refSeq = clientSeq, code = rejection.code, reason = rejection.reason)
            output.outgoing += Outgoing(makeMessage(error, nowMillis), recipients(listOf(seat)))
            return
        }
        output.outgoing += gameStates(nowMillis)
    }

    /**
     * Runs [action] of [playerId] through the running game. Accepted: one state change, and the
     * phase is `finished` when the game says so; returns null. Rejected: nothing changes.
     */
    private fun play(playerId: String, gameId: String?, action: JSONObject, random: Random): GameOutcome.Rejected? {
        val running = game
        val runningModule = module
        if (running == null || runningModule == null || phase != GamePhase.PLAYING) {
            return GameOutcome.Rejected(GameErrorCode.NO_GAME, "no game is running")
        }
        if (gameId != runningModule.id) {
            return GameOutcome.Rejected(GameErrorCode.WRONG_GAME, "the running game is ${runningModule.id}")
        }
        return when (val outcome = running.apply(playerId, action, random)) {
            is GameOutcome.Rejected -> outcome
            is GameOutcome.Accepted -> {
                game = outcome.next
                stateVersion += 1
                if (outcome.next.finished) phase = GamePhase.FINISHED
                null
            }
        }
    }

    private fun markDisconnected(seat: Seat, nowMillis: Long, output: Output) {
        // Still the targets of the last snapshot round: this runs before the next one.
        if (seat.player.id in lastSnapshotTargets) seat.droppedAsSuccessorAtMillis = elapsedMillis()
        seat.player = seat.player.copy(connected = false)
        seat.removeAtMillis = elapsedMillis() + GameConstants.OFFLINE_REMOVE_AFTER_MILLIS
        stateVersion += 1
        output.outgoing += playerList(PlayerListEvent.DISCONNECTED, seat.player.id, nowMillis)
    }

    /**
     * The seat is gone for good (wire specification "Players and Seats"): a `JOIN_REJECT` `removed` to it if
     * it still has a link, then one state change with `PLAYER_LIST` `removed` to everyone else. If
     * the player is in the running game, the module is told, as a second state change: the game
     * goes on without them (`finished` if that ends it) or, when it cannot, it ends as with
     * [endGame]. Either way everyone gets a `GAME_STATE`.
     */
    private fun removeSeat(seat: Seat, why: String, nowMillis: Long, output: Output) {
        val playerId = seat.player.id
        seat.link?.let { link ->
            val reject = makeMessage(Message.Body.JoinReject(JoinRejectCode.REMOVED), nowMillis)
            output.outgoing += Outgoing(reject, listOf(Recipient(link, playerId)))
        }
        seats.remove(seat)
        stateVersion += 1
        output.notes += "removed $playerId ($why)"
        output.outgoing += playerList(PlayerListEvent.REMOVED, playerId, nowMillis)

        val running = game
        val runningModule = module
        if (phase != GamePhase.PLAYING || running == null || runningModule == null) return
        val next = running.removePlayer(playerId)
        // The same instance: a spectator, the game does not change.
        if (next === running) return
        // A separate change: clients drop a second message with the same version as already applied.
        stateVersion += 1
        if (next == null) {
            output.notes += "${runningModule.title} cannot continue without $playerId: game ended, back to the lobby"
            module = null
            game = null
            phase = GamePhase.LOBBY
        } else {
            game = next
            if (next.finished) {
                phase = GamePhase.FINISHED
                output.notes += "${runningModule.title} goes on without $playerId and is finished"
            } else {
                output.notes += "${runningModule.title} goes on without $playerId"
            }
        }
        output.outgoing += gameStates(nowMillis)
    }

    /**
     * A real-time game pauses while a player of it is offline (or has no seat), and may move again
     * [GameConstants.REALTIME_RESUME_DELAY_MILLIS] after the last of them is back. Checked after
     * every change, so the delay counts from the change that brought the last one back.
     */
    private fun refreshRealtimePause(output: Output?) {
        val running = realtimeGame
        if (running == null) {
            realtimeHeld = false
            realtimeResumeAtMillis = null
            return
        }
        val offline = running.players.filter { id -> players.firstOrNull { it.id == id }?.connected != true }
        if (offline.isNotEmpty()) {
            if (!realtimeHeld) output?.notes?.add("${module?.title ?: "the game"} paused: ${offline.joinToString(", ")} offline")
            realtimeHeld = true
            realtimeResumeAtMillis = null
        } else if (realtimeHeld) {
            realtimeHeld = false
            realtimeResumeAtMillis = elapsedMillis() + GameConstants.REALTIME_RESUME_DELAY_MILLIS
            output?.notes?.add(
                "everyone is back: ${module?.title ?: "the game"} resumes in " +
                    "${GameConstants.REALTIME_RESUME_DELAY_MILLIS / MILLIS_PER_SECOND} s"
            )
        }
    }

    /** A real-time game starts here: no held-back inputs, the frame count from 0, not paused. */
    private fun resetRealtime() {
        hostInputPending = null
        hostInputsDelayed.clear()
        realtimeTicks = 0
        realtimeHeld = false
        realtimeResumeAtMillis = null
    }

    /** New players get the lowest free client seat; a seat someone holds (the host's too) is never reused. */
    private fun lowestFreeSeat(): String? =
        (GameConstants.FIRST_CLIENT_SEAT..maxPlayers)
            .map { GameConstants.PLAYER_ID_PREFIX + it }
            .firstOrNull { id -> id != host.id && seats.none { it.player.id == id } }

    private fun successors(): List<Seat> =
        seats.filter { it.link != null && it.player.connected && it.player.canHost }

    /**
     * Keeps the first [GameConstants.SNAPSHOT_SUCCESSORS] successors up to date: a `HOST_SNAPSHOT`
     * to each after every `stateVersion` change and whenever those successors change.
     */
    private fun withSnapshots(output: Output, nowMillis: Long): Output {
        refreshRealtimePause(output)
        val targets = successors().take(GameConstants.SNAPSHOT_SUCCESSORS)
        val targetIds = targets.map { it.player.id }
        if (stateVersion == lastSnapshotVersion && targetIds == lastSnapshotTargets) return output
        lastSnapshotVersion = stateVersion
        lastSnapshotTargets = targetIds
        for (seat in targets) {
            output.outgoing += Outgoing(makeMessage(snapshotBody(targetIds), nowMillis), recipients(listOf(seat)))
        }
        return output
    }

    // --- Messages ---

    private fun makeMessage(body: Message.Body, nowMillis: Long): Message =
        Message(seq = nextSeq++, from = host.id, ts = nowMillis, body = body)

    private fun state(): GameState =
        GameState(gameId, stateVersion, phase, players, host.id, hostEpoch)

    private fun viewFor(playerId: String): JsonPayload? = game?.view(playerId)?.let(::JsonPayload)

    /**
     * One `GAME_STATE` per linked seat: each has its own `seq` and its own view, all carry the
     * same `stateVersion`. Without a game there is no `game` and no `view`.
     */
    private fun gameStates(nowMillis: Long): List<Outgoing> =
        seats.filter { it.link != null }.map { seat ->
            val data = Message.Body.GameUpdate(stateVersion, phase, module?.id, viewFor(seat.player.id))
            Outgoing(makeMessage(data, nowMillis), recipients(listOf(seat)))
        }

    /** Broadcast to every client that has a link. */
    private fun playerList(event: String, playerId: String, nowMillis: Long): Outgoing {
        val data = Message.Body.PlayerList(event, playerId, stateVersion, players)
        return Outgoing(makeMessage(data, nowMillis), recipients(seats))
    }

    /** Always targeted: while a game exists it carries the recipient's own view of it. */
    private fun stateSync(seat: Seat, nowMillis: Long): Outgoing {
        val data = Message.Body.StateSync(state(), module?.id, viewFor(seat.player.id))
        return Outgoing(makeMessage(data, nowMillis), recipients(listOf(seat)))
    }

    /**
     * The state, the device behind every seat (the host's own included), who got this round and,
     * while a game exists, the game module's full state.
     */
    private fun snapshotBody(targetIds: List<String>): Message.Body.HostSnapshot {
        val owners = LinkedHashMap<String, String>()
        owners[host.id] = hostDeviceId
        for (seat in seats) owners[seat.player.id] = seat.deviceId
        return Message.Body.HostSnapshot(
            HostSnapshot(
                state = state(),
                seats = owners.toSortedMap(compareBy { seatNumber(it) }),
                successors = targetIds,
                game = module?.id,
                gameState = game?.state()?.let(::JsonPayload),
            )
        )
    }

    private fun recipients(seats: List<Seat>): List<Recipient> =
        seats.mapNotNull { seat -> seat.link?.let { Recipient(it, seat.player.id) } }

    init {
        // After a takeover every other seat is offline: a restored real-time game starts paused.
        refreshRealtimePause(null)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val BYTE_MASK = 0xFF

        /** A new game: this device is `p1`, alone in the lobby, epoch 1. */
        fun create(
            gameId: String,
            hostName: String,
            hostDeviceId: String,
            maxPlayers: Int = GameConstants.MAX_PLAYERS,
            elapsedMillis: () -> Long,
        ): HostSession =
            HostSession(
                gameId = gameId,
                host = Player(
                    id = GameConstants.HOST_PLAYER_ID,
                    name = hostName.truncatedPlayerName(),
                    platform = GameConstants.PLATFORM,
                    connected = true,
                    canHost = true,
                ),
                hostDeviceId = hostDeviceId,
                hostEpoch = GameConstants.FIRST_HOST_EPOCH,
                initialStateVersion = 1,
                initialSeats = emptyList(),
                initialPhase = GamePhase.LOBBY,
                initialModule = null,
                initialGame = null,
                restoreNote = null,
                elapsedMillis = elapsedMillis,
                maxPlayers = maxPlayers,
            )

        /**
         * Takes a game over from [snapshot]: [ownPlayerId] becomes the host, `hostEpoch` and
         * `stateVersion` go up by one, and every other seat (the old host's included) starts
         * disconnected, bound to its `deviceId`, so each player gets its seat back when it
         * rejoins within [GameConstants.RECONNECT_WINDOW_MILLIS]; after that the seat is removed.
         * `seq` starts at 1 again. Returns null if the snapshot has no such player.
         *
         * The phase and the game continue where the snapshot left them: the module is looked up
         * with [findModule] and restored from `gameState`. If this build has no such module, or
         * the state cannot be read, the session falls back to the lobby ([restoreNote] says why).
         */
        fun restore(
            snapshot: HostSnapshot,
            ownPlayerId: String,
            ownDeviceId: String,
            findModule: (String) -> GameModule?,
            maxPlayers: Int = GameConstants.MAX_PLAYERS,
            elapsedMillis: () -> Long,
        ): HostSession? {
            val own = snapshot.state.players.firstOrNull { it.id == ownPlayerId } ?: return null
            // Every player gets the full reconnect window to find the new host before removal.
            val removeAt = elapsedMillis() + GameConstants.RECONNECT_WINDOW_MILLIS
            val others = snapshot.state.players
                .filter { it.id != ownPlayerId }
                .sortedBy { seatNumber(it.id) }
                .map { player ->
                    Seat(
                        player = player.copy(connected = false),
                        // A seat whose device is unknown can never be claimed again.
                        deviceId = snapshot.seats[player.id].orEmpty(),
                        link = null,
                        lastTrafficAtMillis = 0,
                        seenSeqs = mutableSetOf(),
                    ).apply { removeAtMillis = removeAt }
                }
            var phase = snapshot.state.phase
            var module: GameModule? = null
            var game: GameInstance? = null
            var note: String? = null
            if (phase != GamePhase.LOBBY) {
                module = snapshot.game?.let(findModule)
                game = snapshot.gameState?.let { module?.restore(it.toJson()) }
                if (game == null) {
                    note = when {
                        module == null -> "no module for game ${snapshot.game}"
                        else -> "the state of game ${snapshot.game} could not be restored"
                    } + ": back to the lobby"
                    phase = GamePhase.LOBBY
                    module = null
                }
            }
            return HostSession(
                gameId = snapshot.state.gameId,
                host = own.copy(platform = GameConstants.PLATFORM, connected = true, canHost = true),
                hostDeviceId = ownDeviceId,
                hostEpoch = snapshot.state.hostEpoch + 1,
                initialStateVersion = snapshot.state.stateVersion + 1,
                initialSeats = others,
                initialPhase = phase,
                initialModule = module,
                initialGame = game,
                restoreNote = note,
                elapsedMillis = elapsedMillis,
                maxPlayers = maxPlayers,
            )
        }
    }
}
