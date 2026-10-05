package io.hypercode.hyperblekit.transport

/**
 * The real-time lane (wire specification, "Real-time frames"): a frame is one unchunked
 * packet `[frameSeq][0x00][0x00][payload...]`. A chunk header never has `chunkCount` 0, so the
 * [Reassembler] tells the two apart, and a frame never disturbs a message being reassembled.
 *
 * Unlike messages, frames are not queued: only the newest one counts, and a late one is dropped.
 */
internal object RealtimeFrames {
    /** `frameSeq` is one byte: after 255 it wraps to 0. */
    const val SEQ_MODULUS = 256

    /** A frame is taken when it is 1 up to this many ahead of the last one taken (mod 256). */
    const val MAX_SEQ_AHEAD = 127

    /** The largest payload one frame can carry on a link with this chunk size (header included). */
    fun maxPayload(maxChunkLength: Int): Int = maxChunkLength - Chunking.HEADER_SIZE

    /** The packet for one frame: the 3-byte header, then [payload]. */
    fun build(frameSeq: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(Chunking.HEADER_SIZE + payload.size)
        packet[0] = frameSeq.toByte()
        // Bytes 1 and 2 (chunkIndex, chunkCount) stay 0: that is what marks a frame.
        payload.copyInto(packet, Chunking.HEADER_SIZE)
        return packet
    }

    /** Whether frame [seq] is newer than [last], wrap-aware: `(seq − last) mod 256` in 1…127. */
    fun isNewer(seq: Int, last: Int): Boolean =
        Math.floorMod(seq - last, SEQ_MODULUS) in 1..MAX_SEQ_AHEAD
}

/**
 * The outgoing frames of one link and direction: a single slot instead of a queue, so a newer
 * frame replaces one that has not been sent yet. `frameSeq` is given when the frame is taken for
 * sending, so the sequence on the air has no holes where frames were replaced.
 *
 * Use a new instance when the link comes up: `frameSeq` starts at 0. Pure Kotlin, not
 * thread-safe: the owning transport calls it from a single thread.
 */
internal class FrameSlot {
    private var nextSeq = 0
    private var waiting: ByteArray? = null

    val isEmpty: Boolean get() = waiting == null

    /** Puts [payload] in the slot. Returns true if it replaced a frame that was still waiting. */
    fun put(payload: ByteArray): Boolean {
        val replaced = waiting != null
        waiting = payload
        return replaced
    }

    /** Takes the waiting frame as a packet ready to send (header included), or null if none waits. */
    fun take(): ByteArray? {
        val payload = waiting ?: return null
        waiting = null
        val packet = RealtimeFrames.build(nextSeq, payload)
        nextSeq = (nextSeq + 1) % RealtimeFrames.SEQ_MODULUS
        return packet
    }

    fun clear() {
        waiting = null
    }
}

/**
 * The incoming frames of one link and direction: the first frame is taken, after that only a
 * newer one ([RealtimeFrames.isNewer]); a late or repeated frame is dropped and counted.
 *
 * Use a new instance when the link comes up. Pure Kotlin, not thread-safe.
 */
internal class FrameFilter {
    /** Frames dropped as stale or duplicated since this link came up. */
    var staleCount = 0
        private set

    /** `frameSeq` of the last frame taken; null before the first one. */
    private var last: Int? = null

    /** Whether to take the frame with this `frameSeq`; counts it as stale if not. */
    fun take(frameSeq: Int): Boolean {
        val previous = last
        if (previous != null && !RealtimeFrames.isNewer(frameSeq, previous)) {
            staleCount += 1
            return false
        }
        last = frameSeq
        return true
    }
}
