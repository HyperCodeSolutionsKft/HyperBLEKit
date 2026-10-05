package io.hypercode.hyperblekit

/**
 * One player of a session, as the host reports them ([SessionState.players]). The same on every
 * phone in the game. An app may construct values itself, for previews and tests.
 *
 * @property id the player's ID in this game: "p1" to "p10". The first host is "p1"; everyone else
 *   gets the lowest free number when they join. A player keeps their ID for the whole game, also
 *   across reconnects and when the host changes.
 * @property name the player's name, at most 12 characters.
 * @property platform the player's operating system as sent by their phone: "android" or "ios".
 * @property connected false while the player's phone has no connection to the host. The player
 *   keeps their seat for a while and can come back.
 * @property handSize a number a host may attach to a player (the size of their hand in a card
 *   game). The kit itself never sets it; it is null unless the host's app does.
 * @property canHost whether this player's phone could take the game over if the host disappeared.
 */
public data class Player(
    val id: String,
    val name: String,
    val platform: String,
    val connected: Boolean,
    val handSize: Int? = null,
    val canHost: Boolean = false,
)

/** The values of [SessionState.phase]: where a session stands between games. */
public object GamePhase {
    /** No game exists: players can join, and the host can start a game. */
    public const val LOBBY: String = "lobby"

    /** A game is being played. Only players who were in it before can (re)join. */
    public const val PLAYING: String = "playing"

    /**
     * The game is over and its final view is still shown. The host can start another game or go
     * back to the lobby.
     */
    public const val FINISHED: String = "finished"
}

/** The reasons a host gives for refusing a player ([EndReason.Rejected.code]). */
public object JoinRejectCode {
    /** Every seat is taken ([HyperBleKitConfig.maxPlayers]). */
    public const val LOBBY_FULL: String = "lobby_full"

    /** A game is running or finished, and this device was not in it. */
    public const val GAME_STARTED: String = "game_started"

    /** The host's app speaks another version of the protocol: one of the two apps is out of date. */
    public const val VERSION_MISMATCH: String = "version_mismatch"

    /** The host removed this player from the game. */
    public const val REMOVED: String = "removed"
}
