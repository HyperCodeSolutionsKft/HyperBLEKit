package io.hypercode.hyperblekit.diagnostics

/**
 * What the host's speed-test panel shows
 * ([SessionDiagnostics.hostSpeedTest][io.hypercode.hyperblekit.SessionDiagnostics.hostSpeedTest]).
 * A new value is published on every tick of the test (30 times a second) while it runs. An app
 * may construct values itself, for previews and tests.
 *
 * @property linkedClients how many clients are connected right now and would get the test's frames.
 * @property isRunning whether a test is running.
 * @property frameRate world frames per second of the running or last test; the default before any.
 * @property payloadSize bytes per world frame of the running or last test; the default before any.
 * @property dotX the dot's horizontal position, 0 to [SpeedTestOptions.FIELD_WIDTH] − 1.
 * @property dotY the dot's vertical position, 0 to [SpeedTestOptions.FIELD_HEIGHT] − 1, 0 at the top.
 * @property lines the statistics of the last full second, one line per client, e.g.
 *   `p2 fps=15 replaced=0 inFps=4 inGapMax=260 taps=3`: frames sent to that client, frames that
 *   were replaced by a newer one before they could be sent, inputs received from it, the longest
 *   gap between two of its inputs in milliseconds, and its tap count. Empty during the first second.
 */
public data class HostSpeedTestState(
    public val linkedClients: Int,
    public val isRunning: Boolean,
    public val frameRate: Int,
    public val payloadSize: Int,
    public val dotX: Int,
    public val dotY: Int,
    public val lines: List<String>,
)

/**
 * What a client's speed-test view shows while the host's test frames arrive
 * ([SessionDiagnostics.clientSpeedTest][io.hypercode.hyperblekit.SessionDiagnostics.clientSpeedTest]).
 * A new value is published with every frame received. An app may construct values itself, for
 * previews and tests.
 *
 * @property dotX the dot's horizontal position as last received, 0 to [SpeedTestOptions.FIELD_WIDTH] − 1.
 * @property dotY the dot's vertical position as last received, 0 to [SpeedTestOptions.FIELD_HEIGHT] − 1.
 * @property taps how often the user tapped the field since the view opened.
 * @property lastSecond the statistics of the last full second, e.g.
 *   `fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=48/71 size=64`: frames received, frames
 *   dropped because a newer one had already arrived, the average and longest gap between frames in
 *   milliseconds, how many gaps were longer than 150 ms, the average and longest time in
 *   milliseconds for an input to come back from the host, and the frame size in bytes. Null
 *   during the first second.
 * @property sinceStart the same statistics over the whole time since the view opened, with the
 *   average frame rate as `fps` and the elapsed seconds added as `secs=`.
 */
public data class ClientSpeedTestState(
    public val dotX: Int,
    public val dotY: Int,
    public val taps: Int,
    public val lastSecond: String?,
    public val sinceStart: String,
)

/**
 * What a speed-test screen offers and draws: the frame rates and payload sizes a host can choose
 * for [SessionDiagnostics.startSpeedTest][io.hypercode.hyperblekit.SessionDiagnostics.startSpeedTest],
 * and the size of the field the dot moves in.
 */
public object SpeedTestOptions {
    /** The frame rates a host can choose, in world frames per second: 15, 20 and 30. */
    public val FRAME_RATES: List<Int> = SpeedTestConstants.FRAME_RATES

    /** The frame rate a panel starts with: 15 frames per second. */
    public const val DEFAULT_FRAME_RATE: Int = SpeedTestConstants.DEFAULT_FRAME_RATE

    /** The frame sizes a host can choose, in bytes per world frame: 32, 64 and 128. */
    public val PAYLOAD_SIZES: List<Int> = SpeedTestConstants.PAYLOAD_SIZES

    /** The frame size a panel starts with: 64 bytes. */
    public const val DEFAULT_PAYLOAD_SIZE: Int = SpeedTestConstants.DEFAULT_PAYLOAD_SIZE

    /** The width of the dot's field in its own units (320); a screen scales the field to fit. */
    public const val FIELD_WIDTH: Int = SpeedTestConstants.FIELD_WIDTH

    /** The height of the dot's field in its own units (180). */
    public const val FIELD_HEIGHT: Int = SpeedTestConstants.FIELD_HEIGHT
}
