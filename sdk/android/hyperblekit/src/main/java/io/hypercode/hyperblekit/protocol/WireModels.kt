package io.hypercode.hyperblekit.protocol

import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.GameConstants

/**
 * Data models as they appear on the wire. See the wire specification "Data models" and "GameInfo".
 * Optional fields are omitted when null, never sent as `null`.
 */

/**
 * Host-owned state snapshot: what the engine knows. Everything about the game itself lives in the
 * game module's own JSON (wire specification "Game modules").
 */
internal data class GameState(
    val gameId: String,
    val stateVersion: Int,
    val phase: String,
    val players: List<Player>,
    /** Player ID of the current host. */
    val hostId: String = GameConstants.HOST_PLAYER_ID,
    /** Starts at 1, +1 on every takeover. */
    val hostEpoch: Int = GameConstants.FIRST_HOST_EPOCH,
)

/**
 * What a successor needs to become the host: the state plus which device owns which seat (every
 * seat, the host's own included) and, while a game exists, the game module's full state.
 * Kept in memory only and never shown.
 */
internal data class HostSnapshot(
    val state: GameState,
    /** Player ID → `deviceId`. */
    val seats: Map<String, String>,
    /** The clients this round of snapshots went to, in takeover order. */
    val successors: List<String> = emptyList(),
    /** The running game module; absent in the lobby. */
    val game: String? = null,
    /** That module's full authoritative state, hidden cards included; absent in the lobby. */
    val gameState: JsonPayload? = null,
) {
    /** Newer means a higher `hostEpoch`, then a higher `stateVersion`. */
    fun isNewerThan(other: HostSnapshot?): Boolean =
        other == null ||
            state.hostEpoch > other.state.hostEpoch ||
            (state.hostEpoch == other.state.hostEpoch && state.stateVersion >= other.state.stateVersion)
}

/** Value of the host's game summary: plain JSON, not chunked. */
internal data class GameInfo(
    val v: Int,
    val gameId: String,
    val hostName: String,
    val players: Int,
    val maxPlayers: Int,
    val lobbyOpen: Boolean,
    val hostPlatform: String,
    val hostId: String = GameConstants.HOST_PLAYER_ID,
    val hostEpoch: Int = GameConstants.FIRST_HOST_EPOCH,
)

/** `PLAYER_LIST` `data.event` values. */
internal object PlayerListEvent {
    const val JOINED = "joined"
    const val LEFT = "left"
    const val DISCONNECTED = "disconnected"
    const val RECONNECTED = "reconnected"

    /** The seat is gone: offline too long, or removed by the host. */
    const val REMOVED = "removed"
}

/** `ERROR` `data.code` values of the engine itself; every other code comes from the game module. */
internal object GameErrorCode {
    const val NO_GAME = "no_game"
    const val WRONG_GAME = "wrong_game"
}

/** `GAME_OVER` `data.reason` values this build acts on. */
internal object GameOverReason {
    const val HOST_LEFT = "host_left"
}

/** The seat number in a player ID ("p3" → 3); IDs that don't parse sort last. */
internal fun seatNumber(playerId: String): Int =
    playerId.removePrefix(GameConstants.PLAYER_ID_PREFIX).toIntOrNull() ?: Int.MAX_VALUE

/**
 * Who takes over when the host is gone, first to last: every connected player that can host, in
 * ascending seat order, without the current host. Host and clients compute it the same way.
 */
internal fun successorOrder(players: List<Player>, hostId: String): List<String> =
    players.filter { it.connected && it.canHost && it.id != hostId }
        .sortedBy { seatNumber(it.id) }
        .map { it.id }

/** Cuts a player name to [GameConstants.MAX_NAME_LENGTH] characters without splitting a code point. */
internal fun String.truncatedPlayerName(): String =
    if (codePointCount(0, length) <= GameConstants.MAX_NAME_LENGTH) {
        this
    } else {
        substring(0, offsetByCodePoints(0, GameConstants.MAX_NAME_LENGTH))
    }
