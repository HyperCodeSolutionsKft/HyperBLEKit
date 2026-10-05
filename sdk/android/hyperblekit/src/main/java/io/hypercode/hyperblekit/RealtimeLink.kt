package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.game.RealtimeSpec
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One world frame of a real-time game, as a game screen receives it from
 * [RealtimeLink.worldFrames].
 *
 * @property payload the frame exactly as the game module built it
 *   ([io.hypercode.hyperblekit.game.RealtimeGameInstance.worldFrame]): its first byte is the
 *   frame kind, the rest is in the game's own format. Treat it as read-only; the array is shared.
 * @property atMillis when the frame arrived (on a client) or was built (on the host), in
 *   milliseconds on `SystemClock.elapsedRealtime()`. A renderer uses it to time its interpolation
 *   between frames.
 */
public class RealtimeFrame(
    public val payload: ByteArray,
    public val atMillis: Long,
)

/**
 * What the screen of a real-time game talks to: world frames in, this player's inputs out. It is
 * the same on the host and on a client, so a screen does not need to know which it runs on.
 *
 * A real-time game is one whose world moves by itself many times a second (an action game), as
 * opposed to a turn-based one. Its fast-moving part does not travel as reliable messages but as
 * small unacknowledged frames, where a newer one simply replaces an older one that is late.
 *
 * [GameSession.realtime] holds the link while such a game is being played or has just finished.
 * Every game gets a new link, so keep per-game drawing state together with the link object.
 */
public interface RealtimeLink {
    /** The running game's timing and frame kinds, from its module. */
    public val spec: RealtimeSpec

    /**
     * The running game's world frames, oldest first.
     *
     * - On a client: every world frame that arrived from the host and was not older than one
     *   already delivered, stamped on arrival.
     * - On the host: every tick of its own simulation, stamped when built. That is more frames
     *   per second than a client gets, because only every n-th tick is sent.
     *
     * The newest frame is replayed to a new collector, and `worldFrames.replayCache.lastOrNull()`
     * is the newest frame at any moment, so a render loop may poll instead of collecting.
     * Emission never waits for a collector: one that falls more than 16 frames behind loses the
     * oldest ones. Frames are emitted on the main thread. Nothing arrives while the game is
     * paused (a player is offline) or after it has finished.
     */
    public val worldFrames: SharedFlow<RealtimeFrame>

    /**
     * Sends this player's input.
     *
     * On a client it goes to the host as one frame at once; if the previous input has not left
     * the phone yet, the new one replaces it. On the host it is the host's own input and is
     * applied one tick late, so the host has no advantage over the clients.
     *
     * The link does not repeat inputs, and one can get lost, so send when a control changes and
     * also at least every [INPUT_KEEPALIVE_MILLIS]. Inputs sent while the game is not running, on
     * a link that is no longer the current one, or while a client is not connected are dropped
     * silently. May be called from any thread; the array is copied before the call returns.
     *
     * @param payload the input in the game's own format, with the game's input kind
     *   ([RealtimeSpec.inputKind]) as its first byte. It must fit one Bluetooth packet: 17 bytes always
     *   fit, more only on a link that negotiated larger packets (most do). A payload that does
     *   not fit is not sent.
     */
    public fun sendInput(payload: ByteArray)

    /** Constants for screens of real-time games. */
    public companion object {
        /**
         * How often a real-time game screen should send its input even when no control changed:
         * every 250 milliseconds.
         */
        public const val INPUT_KEEPALIVE_MILLIS: Long = GameConstants.REALTIME_INPUT_KEEPALIVE_MILLIS
    }
}

/** The frame buffer behind [RealtimeLink.worldFrames]: the newest is replayed, older ones are dropped when a collector lags. */
internal fun realtimeFrameFlow(): MutableSharedFlow<RealtimeFrame> =
    MutableSharedFlow(replay = 1, extraBufferCapacity = REALTIME_FRAME_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/** World frames a lagging collector may fall behind before the oldest are dropped (about half a second). */
private const val REALTIME_FRAME_BUFFER = 16
