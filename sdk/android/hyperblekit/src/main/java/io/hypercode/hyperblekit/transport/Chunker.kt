package io.hypercode.hyperblekit.transport

/** Framing limits from wire specification "MTU and chunking". */
internal object Chunking {
    /** Chunk header: `[msgId][chunkIndex][chunkCount]`. */
    const val HEADER_SIZE = 3

    /** `chunkCount` is one byte. */
    const val MAX_CHUNKS = 255

    /** `msgId` is one byte: after 255 it wraps to 0. */
    const val MESSAGE_ID_MODULUS = 256

    /** A partial message is dropped when its next chunk takes longer than this. */
    const val PARTIAL_TIMEOUT_MILLIS = 5_000L
}

/** The message cannot be framed: no room for payload, or it needs more than 255 chunks. */
internal class ChunkException(message: String) : Exception(message)

/**
 * Splits outgoing messages into chunks: `[msgId][chunkIndex][chunkCount][payload...]`.
 * Use one instance per connection and direction, since `msgId` is scoped to that.
 *
 * Pure Kotlin. Not thread-safe: the owning transport calls it from a single thread.
 */
internal class Chunker {
    var nextMessageId = 0
        private set

    /**
     * @param maxChunkLength max bytes per write / notification, header included (MTU − 3 on BLE).
     * @throws ChunkException if the message cannot be framed; `msgId` is not consumed then.
     */
    fun split(message: ByteArray, maxChunkLength: Int): List<ByteArray> {
        val payloadLength = maxChunkLength - Chunking.HEADER_SIZE
        if (payloadLength <= 0) throw ChunkException("chunk length $maxChunkLength leaves no room for payload")

        val chunkCount = maxOf(1, (message.size + payloadLength - 1) / payloadLength)
        if (chunkCount > Chunking.MAX_CHUNKS) {
            throw ChunkException("message of ${message.size} bytes needs $chunkCount chunks")
        }

        val msgId = nextMessageId
        nextMessageId = (nextMessageId + 1) % Chunking.MESSAGE_ID_MODULUS

        return List(chunkCount) { index ->
            val start = index * payloadLength
            val end = minOf(start + payloadLength, message.size)
            val chunk = ByteArray(Chunking.HEADER_SIZE + (end - start))
            chunk[0] = msgId.toByte()
            chunk[1] = index.toByte()
            chunk[2] = chunkCount.toByte()
            message.copyInto(chunk, Chunking.HEADER_SIZE, start, end)
            chunk
        }
    }
}
