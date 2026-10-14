package io.hypercode.hyperblekit

/**
 * Game- and protocol-level constants that are independent of any connectivity technology.
 *
 * These come from the wire specification. Transport-specific values (BLE UUIDs,
 * MTU, etc.) live next to their implementation, not here.
 */
internal object GameConstants {
    /** Protocol version `v` in the message envelope. Rejected on mismatch at join (Phase 2). */
    const val PROTOCOL_VERSION = 1

    /** Default of `HyperBleKitConfig.localNamePrefix`: the advertised local name is the prefix + the 4-char game code, e.g. "CG-7K2F". */
    const val LOCAL_NAME_PREFIX = "CG-"

    const val GAME_CODE_LENGTH = 4

    /** Default of `HyperBleKitConfig.maxPlayers`: host + 9 clients. */
    const val MAX_PLAYERS = 10

    /** A discovered game is dropped from the scan list after this long without being seen again. */
    const val DISCOVERY_STALE_TIMEOUT_MILLIS = 10_000L

    /** How often the scan list is re-pruned so stale entries disappear on time. */
    const val DISCOVERY_PRUNE_INTERVAL_MILLIS = 1_000L

    /** `platform` / `hostPlatform` value this build puts on the wire. */
    const val PLATFORM = "android"

    /** The host is always this player. */
    const val HOST_PLAYER_ID = "p1"

    /** Client seats are "p2" up to "p" + the configured maximum ([MAX_PLAYERS] by default). */
    const val PLAYER_ID_PREFIX = "p"
    const val FIRST_CLIENT_SEAT = 2

    /** Envelope `from` before the host has assigned a player ID. */
    const val UNASSIGNED_PLAYER_ID = "?"

    /** Longer names are truncated by the host (and capped in the name field). */
    const val MAX_NAME_LENGTH = 12

    /** The host sends `PING` to every client this often. */
    const val PING_INTERVAL_MILLIS = 3_000L

    /** How long an on-demand ping-all waits for answers before reporting who stayed silent. */
    const val PING_ALL_TIMEOUT_MILLIS = 2_000L

    /** No traffic for this long means the other side is gone. */
    const val SILENCE_TIMEOUT_MILLIS = 10_000L

    /**
     * A seat that stays `connected: false` this long is removed, in the lobby and during a game.
     * Seats restored by a takeover get [RECONNECT_WINDOW_MILLIS] instead.
     */
    const val OFFLINE_REMOVE_AFTER_MILLIS = 30_000L

    /** How often the client checks for host silence. */
    const val SILENCE_CHECK_INTERVAL_MILLIS = 1_000L

    /** After losing the host, the client tries to find and rejoin it for this long. */
    const val RECONNECT_WINDOW_MILLIS = 45_000L

    /** Pause between a failed reconnect attempt and the next one. */
    const val RECONNECT_RETRY_PAUSE_MILLIS = 1_000L

    /** How often the "Reconnecting… N s left" countdown is refreshed and the window checked. */
    const val RECONNECT_TICK_MILLIS = 250L

    /** `hostEpoch` of the first host of a game; +1 on every takeover. */
    const val FIRST_HOST_EPOCH = 1

    /** The host keeps this many successors supplied with `HOST_SNAPSHOT`. */
    const val SNAPSHOT_SUCCESSORS = 2

    /** Successor #1 takes over this long after it detected the loss of the host. */
    const val TAKEOVER_DELAY_MILLIS = 5_000L

    /** Each further successor waits this much longer than the one before it. */
    const val TAKEOVER_STEP_MILLIS = 8_000L

    /**
     * A host with seated clients but no link to any of them for this long looks for another host
     * of its game, and again every time this passes while it is still alone.
     */
    const val HOST_ALONE_PROBE_AFTER_MILLIS = 10_000L

    /**
     * A periodic check that runs this many times later than its interval means the app itself was
     * stalled (screen off, in the background): nothing is judged on such a tick.
     */
    const val STALL_FACTOR = 2

    /** How often the host checks whether it is alone. */
    const val HOST_ALONE_CHECK_INTERVAL_MILLIS = 1_000L

    /**
     * A real-time game: the host's own input counts this many ticks after a client input that
     * arrived at the same moment, so the host has no edge (wire specification "Real-time games").
     */
    const val REALTIME_HOST_INPUT_DELAY_TICKS = 1

    /** A paused real-time game moves again this long after the last of its players is connected again. */
    const val REALTIME_RESUME_DELAY_MILLIS = 3_000L

    /** After a stall longer than this the host drops the missed ticks instead of catching up. */
    const val REALTIME_STALL_MILLIS = 1_000L

    /** The `RT tx` / `RT rx` stats lines are logged once per this interval. */
    const val REALTIME_STATS_INTERVAL_MILLIS = 1_000L

    /** A real-time game screen sends its input when a control changes and at least this often. */
    const val REALTIME_INPUT_KEEPALIVE_MILLIS = 250L

    /** A gap between world frames longer than this counts in `over150`. */
    const val REALTIME_SLOW_GAP_MILLIS = 150L

    /** A client's `RT rx` stats stop this long after the last world frame (a pause, the end). */
    const val REALTIME_RX_IDLE_MILLIS = 2_000L

    /** Default of `HyperBleKitConfig.logCapacity`: the log keeps this many lines and drops the oldest. */
    const val DEBUG_LOG_CAPACITY = 500
}
