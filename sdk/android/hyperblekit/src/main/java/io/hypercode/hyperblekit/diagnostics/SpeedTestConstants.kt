package io.hypercode.hyperblekit.diagnostics

/**
 * Rates, sizes and timers of the real-time speed test. The values must stay the same on Android
 * and iOS. The speed test is test equipment: it lives apart from the session and the
 * games and only uses the transport's frame lane.
 */
internal object SpeedTestConstants {
    /** The dot's field, in its own units; screens scale it to fit. */
    const val FIELD_WIDTH = 320
    const val FIELD_HEIGHT = 180

    /** The host moves the dot this many times a second. */
    const val TICKS_PER_SECOND = 30

    /** Dot speed per tick, in field units. */
    const val DOT_STEP_X = 3
    const val DOT_STEP_Y = 2

    /** World frames a second the host can send, and the default. */
    val FRAME_RATES = listOf(15, 20, 30)
    const val DEFAULT_FRAME_RATE = 15

    /** World payload sizes in bytes (kind byte included), and the default. */
    val PAYLOAD_SIZES = listOf(32, 64, 128)
    const val DEFAULT_PAYLOAD_SIZE = 64

    /** A client sends an input this often, and at once on a tap. */
    const val INPUT_INTERVAL_MILLIS = 250L

    /** A client's speed-test view closes this long after the last world frame. */
    const val CLIENT_CLOSE_AFTER_MILLIS = 2_000L

    /** Stats are logged and shown once per this interval. */
    const val STATS_INTERVAL_MILLIS = 1_000L

    /** A gap between frames longer than this counts in `over150`. */
    const val SLOW_GAP_MILLIS = 150L

    /** A tick that comes this much later than due means the app was stalled: the schedule starts over. */
    const val TICK_RESYNC_AFTER_MILLIS = 1_000L
}
