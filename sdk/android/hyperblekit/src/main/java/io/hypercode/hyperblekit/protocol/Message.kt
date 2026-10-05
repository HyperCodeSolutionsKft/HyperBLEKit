package io.hypercode.hyperblekit.protocol

import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.GameConstants

/**
 * One protocol message: the envelope `{v,type,seq,from,ts,data}` with a typed payload.
 * See the wire specification "Envelope".
 */
internal data class Message(
    val v: Int = GameConstants.PROTOCOL_VERSION,
    val seq: Int,
    val from: String,
    /** Sender time in ms. For latency measurement only. */
    val ts: Long,
    val body: Body,
) {
    /** The type-specific `data` payload. */
    sealed interface Body {
        data class JoinRequest(
            val name: String,
            val platform: String,
            val deviceId: String,
            /** Whether the device could take over as host. */
            val canHost: Boolean = false,
        ) : Body
        data class JoinAccept(val playerId: String, val stateVersion: Int) : Body
        data class JoinReject(val code: String) : Body

        /**
         * Always targeted. [game] and the recipient's own [view] are present while a game exists
         * (phase `playing` or `finished`).
         */
        data class StateSync(val state: GameState, val game: String? = null, val view: JsonPayload? = null) : Body
        data class PlayerList(
            val event: String,
            val playerId: String,
            val stateVersion: Int,
            val players: List<Player>,
        ) : Body
        data object Leave : Body
        /** A host `PING` carries its current [stateVersion], for comparison only; a client's has none. */
        data class Ping(val pingTs: Long, val stateVersion: Int? = null) : Body

        /** Echoes the `pingTs` of the ping it answers. */
        data class Pong(val pingTs: Long) : Body
        data class Ack(val ackSeq: Int) : Body

        /** Host → a successor, targeted: everything needed to take over. */
        data class HostSnapshot(val snapshot: io.hypercode.hyperblekit.protocol.HostSnapshot) : Body

        /** Host → all: the host leaves on purpose and [nextHostId] takes over at once. */
        data class HostLeaving(val nextHostId: String) : Body
        data class GameOver(val reason: String) : Body

        /**
         * `GAME_STATE`, host → each player: the game changed. [game] names the game module and
         * [view] is the recipient's own view of it; both are absent when no game exists.
         */
        data class GameUpdate(
            val stateVersion: Int,
            val phase: String,
            val game: String? = null,
            val view: JsonPayload? = null,
        ) : Body

        /** Client → host: a player's move, in the format of the game module [game]. */
        data class GameAction(val game: String, val action: JsonPayload) : Body

        /** Host → client: the `GAME_ACTION` with client `seq` [refSeq] was rejected. */
        data class Error(val refSeq: Int, val code: String, val reason: String? = null) : Body

        /** Client → host: a `stateVersion` gap was detected; answered with `STATE_SYNC`. */
        data class SyncRequest(val haveVersion: Int) : Body

        /** A type this build doesn't know. Logged and ignored. */
        data class Unknown(val type: String) : Body
    }

    val type: String
        get() = when (body) {
            is Body.JoinRequest -> MessageType.JOIN_REQUEST
            is Body.JoinAccept -> MessageType.JOIN_ACCEPT
            is Body.JoinReject -> MessageType.JOIN_REJECT
            is Body.StateSync -> MessageType.STATE_SYNC
            is Body.PlayerList -> MessageType.PLAYER_LIST
            Body.Leave -> MessageType.LEAVE
            is Body.Ping -> MessageType.PING
            is Body.Pong -> MessageType.PONG
            is Body.Ack -> MessageType.ACK
            is Body.HostSnapshot -> MessageType.HOST_SNAPSHOT
            is Body.HostLeaving -> MessageType.HOST_LEAVING
            is Body.GameOver -> MessageType.GAME_OVER
            is Body.GameUpdate -> MessageType.GAME_STATE
            is Body.GameAction -> MessageType.GAME_ACTION
            is Body.Error -> MessageType.ERROR
            is Body.SyncRequest -> MessageType.SYNC_REQUEST
            is Body.Unknown -> body.type
        }

    /** Only state-changing messages carry one. */
    val stateVersion: Int?
        get() = when (body) {
            is Body.JoinAccept -> body.stateVersion
            is Body.StateSync -> body.state.stateVersion
            is Body.PlayerList -> body.stateVersion
            is Body.HostSnapshot -> body.snapshot.state.stateVersion
            is Body.GameUpdate -> body.stateVersion
            else -> null
        }

    /** Whether the receiving client must confirm this message with `ACK`. */
    val needsAck: Boolean
        get() = body is Body.JoinAccept || body is Body.GameUpdate || body is Body.StateSync ||
            body is Body.HostSnapshot || body is Body.GameOver

    /** Payload details worth a place in the debug log line. */
    val logDetail: String?
        get() = when (body) {
            is Body.JoinRequest -> "name=${body.name}"
            is Body.JoinAccept -> "player=${body.playerId}"
            is Body.JoinReject -> "code=${body.code}"
            is Body.PlayerList -> "event=${body.event} player=${body.playerId}"
            is Body.Ack -> "ackSeq=${body.ackSeq}"
            is Body.StateSync -> "host=${body.state.hostId} epoch=${body.state.hostEpoch}"
            is Body.HostSnapshot -> "epoch=${body.snapshot.state.hostEpoch} seats=${body.snapshot.seats.size}"
            is Body.HostLeaving -> "next=${body.nextHostId}"
            is Body.GameOver -> "reason=${body.reason}"
            is Body.GameUpdate -> "phase=${body.phase}" + (body.game?.let { " game=$it" } ?: "")
            is Body.GameAction -> "game=${body.game} ${actionSummary(body.action)}"
            is Body.Error -> "code=${body.code} refSeq=${body.refSeq}"
            is Body.SyncRequest -> "have=${body.haveVersion}"
            else -> null
        }

    private companion object {
        const val ACTION_TYPE_KEY = "type"

        /**
         * A short form of an action for the log line only ("play MA"): its `type` if it has one,
         * then its other plain values in key order. The engine never acts on any of it.
         */
        fun actionSummary(action: JsonPayload): String {
            val json = action.toJson()
            val values = mutableListOf<String>()
            if (json.has(ACTION_TYPE_KEY)) values += json.get(ACTION_TYPE_KEY).toString()
            for (key in json.keys().asSequence().sorted()) {
                val value = json.get(key)
                if (key != ACTION_TYPE_KEY && (value is String || value is Number || value is Boolean)) {
                    values += value.toString()
                }
            }
            return values.joinToString(" ")
        }
    }
}

/** Envelope `type` values of the messages this build knows. */
internal object MessageType {
    const val JOIN_REQUEST = "JOIN_REQUEST"
    const val JOIN_ACCEPT = "JOIN_ACCEPT"
    const val JOIN_REJECT = "JOIN_REJECT"
    const val STATE_SYNC = "STATE_SYNC"
    const val PLAYER_LIST = "PLAYER_LIST"
    const val LEAVE = "LEAVE"
    const val PING = "PING"
    const val PONG = "PONG"
    const val ACK = "ACK"
    const val HOST_SNAPSHOT = "HOST_SNAPSHOT"
    const val HOST_LEAVING = "HOST_LEAVING"
    const val GAME_OVER = "GAME_OVER"
    const val GAME_STATE = "GAME_STATE"
    const val GAME_ACTION = "GAME_ACTION"
    const val ERROR = "ERROR"
    const val SYNC_REQUEST = "SYNC_REQUEST"
}
