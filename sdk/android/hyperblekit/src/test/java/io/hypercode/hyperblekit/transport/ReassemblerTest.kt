package io.hypercode.hyperblekit.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reassembly rules from wire specification "Reassembly": in-order, drop-and-log on anything unexpected. */
class ReassemblerTest {

    private val sink: (String) -> Unit = {}

    private fun chunk(msgId: Int, index: Int, count: Int, vararg payload: Byte): ByteArray =
        byteArrayOf(msgId.toByte(), index.toByte(), count.toByte(), *payload)

    @Test
    fun `a single-chunk message is returned whole with its chunk count`() {
        val result = Reassembler().receive(chunk(0, 0, 1, 7, 8, 9), nowMillis = 0, log = sink) as ReassembledMessage
        assertArrayEquals(byteArrayOf(7, 8, 9), result.bytes)
        assertEquals(1, result.chunks)
    }

    @Test
    fun `a multi-chunk message returns null until its last chunk`() {
        val r = Reassembler()
        assertNull(r.receive(chunk(0, 0, 3, 1, 2), 0, sink))
        assertNull(r.receive(chunk(0, 1, 3, 3, 4), 0, sink))
        val result = r.receive(chunk(0, 2, 3, 5), 0, sink) as ReassembledMessage
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result.bytes)
        assertEquals(3, result.chunks)
    }

    @Test
    fun `an out-of-order chunk drops the whole message`() {
        val r = Reassembler()
        assertNull(r.receive(chunk(0, 0, 3, 1, 2), 0, sink)) // expects index 1 next
        assertNull(r.receive(chunk(0, 2, 3, 5), 0, sink))    // got index 2 -> dropped
        assertEquals(1, r.droppedMessageCount)
    }

    @Test
    fun `a missing middle chunk drops the message rather than splicing`() {
        val r = Reassembler()
        assertNull(r.receive(chunk(0, 0, 4, 1), 0, sink))
        assertNull(r.receive(chunk(0, 1, 4, 2), 0, sink))
        // index 2 is lost; index 3 arrives next -> mismatch -> dropped.
        assertNull(r.receive(chunk(0, 3, 4, 4), 0, sink))
        assertEquals(1, r.droppedMessageCount)
    }

    @Test
    fun `a new msgId mid-message drops the partial and starts fresh`() {
        val r = Reassembler()
        assertNull(r.receive(chunk(5, 0, 3, 1, 2), 0, sink)) // partial msgId 5
        // New single-chunk message with a different msgId arrives first.
        val result = r.receive(chunk(6, 0, 1, 9), 0, sink) as ReassembledMessage
        assertArrayEquals(byteArrayOf(9), result.bytes)
        assertEquals(1, r.droppedMessageCount)
    }

    @Test
    fun `a partial message is dropped after the 5s timeout`() {
        val r = Reassembler(timeoutMillis = 5_000)
        assertNull(r.receive(chunk(0, 0, 2, 1), nowMillis = 0, log = sink))
        // Next chunk arrives 6s later: the partial has expired.
        assertNull(r.receive(chunk(0, 1, 2, 2), nowMillis = 6_000, log = sink))
        assertEquals(1, r.droppedMessageCount)
    }

    @Test
    fun `a partial message within the timeout still completes`() {
        val r = Reassembler(timeoutMillis = 5_000)
        assertNull(r.receive(chunk(0, 0, 2, 1), nowMillis = 0, log = sink))
        val result = r.receive(chunk(0, 1, 2, 2), nowMillis = 4_999, log = sink) as ReassembledMessage
        assertArrayEquals(byteArrayOf(1, 2), result.bytes)
        assertEquals(0, r.droppedMessageCount)
    }

    @Test
    fun `malformed chunks are ignored and not counted as dropped messages`() {
        val r = Reassembler()
        assertNull(r.receive(byteArrayOf(0, 0), 0, sink))       // shorter than the header
        assertNull(r.receive(chunk(0, 1, 0), 0, sink))          // chunkCount 0 with an index (index 0 is a frame)
        assertNull(r.receive(chunk(0, 3, 2), 0, sink))          // index >= count
        assertEquals(0, r.droppedMessageCount)
    }

    @Test
    fun `two complete messages in a row both reassemble`() {
        val r = Reassembler()
        assertArrayEquals(byteArrayOf(1), (r.receive(chunk(0, 0, 1, 1), 0, sink) as ReassembledMessage).bytes)
        assertArrayEquals(byteArrayOf(2), (r.receive(chunk(1, 0, 1, 2), 0, sink) as ReassembledMessage).bytes)
        assertEquals(0, r.droppedMessageCount)
    }

    @Test
    fun `byte values above 127 survive as unsigned msgId, index and count`() {
        val r = Reassembler()
        // msgId 200, a 200-chunk message: feed index 0 then nothing else should crash on the mask.
        assertNull(r.receive(chunk(200, 0, 200, 0xFF.toByte()), 0, sink))
        assertEquals(0, r.droppedMessageCount)
    }
}
