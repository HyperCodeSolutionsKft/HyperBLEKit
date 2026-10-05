package io.hypercode.hyperblekit

/**
 * A game found nearby that can be joined: one entry of [HyperBleKit.discoveredGames]. Pass it to
 * [HyperBleKit.join].
 *
 * An app may construct values itself, for previews and tests; only entries that came from
 * [HyperBleKit.discoveredGames] can be joined.
 *
 * @property id the kit's handle for the hosting phone while this scan lasts (its Bluetooth
 *   address). Use it only as a list key. It is not a player or device identity: phones change
 *   their address.
 * @property gameCode the game's four-character code, e.g. "7K2F", without the name prefix. The
 *   host's screen shows the same code, so players can pick the right game.
 * @property platform the host's operating system; currently always [Platform.UNKNOWN].
 * @property rssi the signal strength of the last advertisement in dBm (for example -45 for a
 *   phone next to this one, -90 for one far away); null if unknown.
 * @property firstSeenMillis when this game was first seen in the current scan, in milliseconds
 *   on `SystemClock.elapsedRealtime()`.
 * @property lastSeenMillis when it was last seen, on the same clock. A game not seen for 10
 *   seconds is removed from the list.
 */
public data class DiscoveredGame(
    val id: String,
    val gameCode: String,
    val platform: Platform,
    val rssi: Int?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
)

/**
 * Whether a hosted game is being advertised, so that other phones can find and join it
 * ([SessionState.advertising]).
 */
public sealed interface AdvertisingState {
    /** Not advertising: hosting has not started yet, or it has stopped. */
    public data object Idle : AdvertisingState

    /** Advertising was requested and Bluetooth has not confirmed it yet. */
    public data object Starting : AdvertisingState

    /**
     * The game is being advertised and can be found.
     *
     * @property gameCode the code being advertised.
     */
    public data class Advertising(val gameCode: String) : AdvertisingState

    /**
     * This phone's Bluetooth chip cannot advertise, so it can never host. It can still join games.
     *
     * @property reason an English sentence for the screen.
     */
    public data class Unsupported(val reason: String) : AdvertisingState

    /**
     * Advertising could not start: Bluetooth is off, the permissions in
     * [HyperBleKit.requiredPermissions] are not granted, or the system reported an error. After
     * Bluetooth was off the kit starts advertising again by itself when it comes back; after
     * missing permissions, leave the session and host again once they are granted.
     *
     * @property reason an English sentence for the screen.
     */
    public data class Failed(val reason: String) : AdvertisingState
}

/** Whether the kit is looking for games ([HyperBleKit.scanningState]). */
public sealed interface ScanningState {
    /** Not looking for games. */
    public data object Idle : ScanningState

    /** Looking; [HyperBleKit.discoveredGames] is kept up to date. */
    public data object Scanning : ScanningState

    /**
     * The search could not start or broke off: Bluetooth is off, the phone has no Bluetooth
     * scanner, the permissions in [HyperBleKit.requiredPermissions] are not all granted, or the
     * system reported an error. Call [HyperBleKit.startDiscovery] again once the cause is gone.
     *
     * @property reason an English sentence for the screen.
     */
    public data class Failed(val reason: String) : ScanningState
}
