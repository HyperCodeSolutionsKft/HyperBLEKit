package io.hypercode.hyperblekit.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Framing rules from wire specification "MTU and chunking": `[msgId][chunkIndex][chunkCount][payload]`. */
class ChunkerTest {

    @Test
    fun `single short message is one chunk with a header`() {
        val chunks = Chunker().split(byteArrayOf(104, 101, 108, 108, 111), maxChunkLength = 20)
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 104, 101, 108, 108, 111), chunks[0])
    }

    @Test
    fun `empty message is still one header-only chunk`() {
        val chunks = Chunker().split(ByteArray(0), maxChunkLength = 20)
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(0, 0, 1), chunks[0])
    }

    @Test
    fun `message is split at the payload boundary`() {
        // maxChunkLength 8 - 3 header = 5 payload bytes per chunk; 12 bytes -> 3 chunks.
        val chunks = Chunker().split(ByteArray(12) { it.toByte() }, maxChunkLength = 8)
        assertEquals(3, chunks.size)
        assertArrayEquals(byteArrayOf(0, 0, 3, 0, 1, 2, 3, 4), chunks[0])
        assertArrayEquals(byteArrayOf(0, 1, 3, 5, 6, 7, 8, 9), chunks[1])
        assertArrayEquals(byteArrayOf(0, 2, 3, 10, 11), chunks[2])
    }

    @Test
    fun `an exact payload multiple has no empty trailing chunk`() {
        val chunks = Chunker().split(ByteArray(10) { it.toByte() }, maxChunkLength = 8)
        assertEquals(2, chunks.size)
        assertEquals(2, chunks[0][2].toInt()) // chunkCount
    }

    @Test
    fun `msgId increments per message`() {
        val chunker = Chunker()
        assertEquals(0, chunker.split(byteArrayOf(1), 20)[0][0].toInt())
        assertEquals(1, chunker.split(byteArrayOf(1), 20)[0][0].toInt())
        assertEquals(2, chunker.split(byteArrayOf(1), 20)[0][0].toInt())
    }

    @Test
    fun `msgId wraps from 255 back to 0`() {
        val chunker = Chunker()
        repeat(255) { chunker.split(byteArrayOf(1), 20) } // consumes msgIds 0..254
        assertEquals(255, chunker.nextMessageId)
        assertEquals(255.toByte(), chunker.split(byteArrayOf(1), 20)[0][0]) // uses 255, wraps
        assertEquals(0, chunker.nextMessageId)
        assertEquals(0.toByte(), chunker.split(byteArrayOf(1), 20)[0][0]) // back to 0
    }

    @Test
    fun `every chunk of a message carries the same msgId and chunkCount`() {
        val chunks = Chunker().split(ByteArray(12) { it.toByte() }, maxChunkLength = 8)
        assertEquals(setOf(0), chunks.map { it[0].toInt() }.toSet())
        assertEquals(setOf(3), chunks.map { it[2].toInt() }.toSet())
        assertEquals(listOf(0, 1, 2), chunks.map { it[1].toInt() })
    }

    @Test
    fun `a chunk length that leaves no room for payload is rejected`() {
        assertThrows(ChunkException::class.java) { Chunker().split(byteArrayOf(1), maxChunkLength = 3) }
    }

    @Test
    fun `a message needing more than 255 chunks is rejected without consuming a msgId`() {
        val chunker = Chunker()
        // maxChunkLength 4 -> 1 payload byte per chunk; 256 bytes would need 256 chunks.
        assertThrows(ChunkException::class.java) { chunker.split(ByteArray(256), maxChunkLength = 4) }
        assertEquals(0, chunker.nextMessageId)
    }
}
