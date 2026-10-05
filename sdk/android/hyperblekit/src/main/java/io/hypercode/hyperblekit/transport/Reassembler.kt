package io.hypercode.hyperblekit.transport

import java.io.ByteArrayOutputStream

/** What one incoming packet completed: a whole message, or a real-time frame. */
internal sealed interface Reassembled

/** A whole message and the number of chunks it arrived in. */
internal class ReassembledMessage(val bytes: ByteArray, val chunks: Int) : Reassembled

/**
 * A real-time frame (`chunkCount` 0, see [RealtimeFrames]): handed on as it is. Whether it is
 * stale is for the transport to decide ([FrameFilter]).
 */
internal class ReceivedFrame(val frameSeq: Int, val payload: ByteArray) : Reassembled

/**
 * Rebuilds messages from incoming chunks. Use one instance per connection and direction.
 *
 * Chunks arrive in order on a single GATT link, so anything unexpected drops the message in
 * progress instead of waiting for a fix that will never come (wire specification "Reassembly"). Real-time
 * frames may arrive between the chunks of a message: they are passed on and leave that message
 * alone.
 *
 * Pure Kotlin, clock injected per call. Not thread-safe: the owning transport calls it from a
 * single thread.
 */
internal class Reassembler(private val timeoutMillis: Long = Chunking.PARTIAL_TIMEOUT_MILLIS) {

    /** Number of unfinished messages dropped so far (for the debug log). */
    var droppedMessageCount = 0
        private set

    private class Pending(val msgId: Int, val chunkCount: Int, var lastChunkAtMillis: Long) {
        var nextIndex = 1
        val payload = ByteArrayOutputStream()
    }

    private var pending: Pending? = null

    /**
     * Feeds one packet. Returns the full message once its last chunk arrives, a frame at once,
     * otherwise null. Dropped partial messages and malformed chunks are reported through [log];
     * frames are not, there are too many of them.
     *
     * @param nowMillis a monotonic clock, used only for the partial-message timeout.
     */
    fun receive(chunk: ByteArray, nowMillis: Long, log: (String) -> Unit): Reassembled? {
        if (chunk.size < Chunking.HEADER_SIZE) {
            log("ignored malformed chunk bytes=${chunk.size}")
            return null
        }
        val msgId = chunk[0].toInt() and BYTE_MASK
        val index = chunk[1].toInt() and BYTE_MASK
        val count = chunk[2].toInt() and BYTE_MASK
        if (count == 0 && index == 0) {
            // A frame: the first byte is its frameSeq. The pending message is not touched.
            return ReceivedFrame(msgId, chunk.copyOfRange(Chunking.HEADER_SIZE, chunk.size))
        }
        if (count == 0 || index >= count) {
            log("ignored malformed chunk bytes=${chunk.size}")
            return null
        }

        val droppedBefore = droppedMessageCount
        val message = accept(chunk, msgId, index, count, nowMillis)
        if (droppedMessageCount != droppedBefore) {
            log("dropped partial message (dropped total=$droppedMessageCount)")
        }
        return message
    }

    private fun accept(chunk: ByteArray, msgId: Int, index: Int, count: Int, nowMillis: Long): ReassembledMessage? {
        pending?.let { if (nowMillis - it.lastChunkAtMillis > timeoutMillis) dropPending() }

        val payloadLength = chunk.size - Chunking.HEADER_SIZE
        if (index == 0) {
            // A new message starts: whatever was unfinished is lost.
            dropPending()
            if (count == 1) {
                return ReassembledMessage(chunk.copyOfRange(Chunking.HEADER_SIZE, chunk.size), count)
            }
            pending = Pending(msgId, count, nowMillis).also {
                it.payload.write(chunk, Chunking.HEADER_SIZE, payloadLength)
            }
            return null
        }

        val current = pending
        if (current == null || current.msgId != msgId || current.chunkCount != count || current.nextIndex != index) {
            dropPending()
            return null
        }

        current.payload.write(chunk, Chunking.HEADER_SIZE, payloadLength)
        current.nextIndex += 1
        current.lastChunkAtMillis = nowMillis

        if (current.nextIndex == current.chunkCount) {
            pending = null
            return ReassembledMessage(current.payload.toByteArray(), count)
        }
        return null
    }

    private fun dropPending() {
        if (pending != null) {
            droppedMessageCount += 1
            pending = null
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
