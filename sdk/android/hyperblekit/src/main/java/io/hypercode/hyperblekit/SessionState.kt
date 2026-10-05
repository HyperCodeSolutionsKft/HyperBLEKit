package io.hypercode.hyperblekit

/** The role this device plays in a session right now. It can change during a game (host migration). */
public enum class SessionRole {
    /**
     * This device runs the game: it advertises it, the other devices connect to it, and it alone
     * decides what happens in the game.
     */
    HOST,

    /** This device is connected, or connecting, to the host and shows what the host sends. */
    CLIENT,
}

/**
 * Where this device's connection to the game stands ([SessionState.connection]). A host is
 * [Joined] from the start until the session is left.
 */
public sealed interface ConnectionState {
    /** A client is setting up the Bluetooth link to the host. */
    public data object Connecting : ConnectionState

    /** The link is up and the join request is sent; the host has not answered yet. */
    public data object Joining : ConnectionState

    /** In the game: the host gave this device a player ID, or this device is the host. Moves are possible. */
    public data object Joined : ConnectionState

    /**
     * A client lost the host and is looking for it — or for the device that took the game over —
     * again. Moves are not possible meanwhile.
     *
     * @property secondsLeft whole seconds until the search is given up and the session ends with
     *   [EndReason.HostLeft]; rounded up, so it reads 1 until the very end.
     */
    public data class Reconnecting(val secondsLeft: Long) : ConnectionState

    /**
     * The session is over for this device and will not come back. Call [HyperBleKit.leave] to
     * close it.
     *
     * @property reason why it ended.
     */
    public data class Ended(val reason: EndReason) : ConnectionState
}

/** Why a session ended for this device ([ConnectionState.Ended]). */
public sealed interface EndReason {
    /**
     * The host refused the join request, or removed this player from the game.
     *
     * @property code the host's reason, one of the [JoinRejectCode] values.
     */
    public data class Rejected(val code: String) : EndReason

    /**
     * The host ended the game by leaving with nobody to hand it to, or no host was found again
     * before the reconnect time ran out.
     */
    public data object HostLeft : EndReason

    /**
     * The first connection to the host could not be set up, so there was never a game to return to.
     *
     * @property detail what went wrong, as English text for the screen or the log; null when unknown.
     */
    public data class Disconnected(val detail: String?) : EndReason

    /** This device left on purpose ([HyperBleKit.leave], [HyperBleKit.host], [HyperBleKit.join] or [HyperBleKit.close]). */
    public data object Left : EndReason
}

/**
 * Everything a session screen shows, as one immutable value published by [GameSession.state].
 * Properties that only one role has are null (or empty) in the other role.
 *
 * An app may also construct values itself, for previews and tests.
 *
 * @property role whether this device is the host or a client right now.
 * @property connection where the connection to the game stands; moves are possible only in
 *   [ConnectionState.Joined].
 * @property gameCode the game's four-character code, e.g. "7K2F".
 * @property playerId this device's player ID ("p1" … "p10"); null until the host assigned it.
 * @property players everyone in the session, in seat order, including players who are offline at
 *   the moment ([Player.connected] is false). Empty until a client received the first list.
 * @property hostId the player ID of the current host; null until a client learnt it.
 * @property hostEpoch how many hosts the game has had: 1 for the first host, one more after each
 *   takeover. 0 until a client learnt it.
 * @property phase one of the [GamePhase] values: lobby, playing or finished.
 * @property gameId the [io.hypercode.hyperblekit.game.GameModule.id] of the game being played or
 *   just finished; null in the lobby.
 * @property view this player's view of the game, in the game module's own JSON format; null in
 *   the lobby. Only what this player may see is in it.
 * @property notice client only: something the user should know about joining, e.g. that the lobby
 *   is closed or the host uses another protocol version. English text; null when there is nothing.
 * @property gameNotice why the last game request or move was refused, as English text; null again
 *   as soon as the game moves on.
 * @property advertising host only: whether the game is being advertised so others can find it.
 *   Null on a client.
 * @property pingStats host only: the round-trip times measured to the clients. Null on a client.
 * @property successors host only: the player IDs of the clients that would take the game over if
 *   this host disappeared, first to last. Empty on a client.
 * @property offlineSecondsLeft host only: for every offline player, the player ID mapped to the
 *   whole seconds left until the host removes that player. Empty on a client.
 * @property migrationInfo client only: one English line for a debug screen saying whether this
 *   device holds a copy of the game to take over with, and its place in the takeover order. Null
 *   on a host.
 * @property lastReconnect client only: how the last successful reconnect went, e.g.
 *   "2.4 s (attempt 1)". Null until there was one, and on a host.
 */
public data class SessionState(
    val role: SessionRole,
    val connection: ConnectionState,
    val gameCode: String,
    val playerId: String? = null,
    val players: List<Player> = emptyList(),
    val hostId: String? = null,
    val hostEpoch: Int = 0,
    val phase: String = GamePhase.LOBBY,
    val gameId: String? = null,
    val view: JsonPayload? = null,
    val notice: String? = null,
    val gameNotice: String? = null,
    val advertising: AdvertisingState? = null,
    val pingStats: PingStats? = null,
    val successors: List<String> = emptyList(),
    val offlineSecondsLeft: Map<String, Long> = emptyMap(),
    val migrationInfo: String? = null,
    val lastReconnect: String? = null,
)
