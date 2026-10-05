package io.hypercode.hyperblekit.protocol

import io.hypercode.hyperblekit.transport.Chunker
import io.hypercode.hyperblekit.transport.ReassembledMessage
import io.hypercode.hyperblekit.transport.Reassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Validates the Android chunker/reassembler against the shared `/protocol/test-vectors/chunking.json`,
 * so any drift from the iOS framing turns this test red.
 */
class ChunkingVectorsTest {

    private val vectors = TestVectors.load("chunking.json")

    @Test
    fun `split cases produce the exact chunks`() {
        val cases = vectors.getJSONArray("split")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val message = case.getJSONArray("message").toByteArray()
            val maxChunkLength = case.getInt("maxChunkLength")
            val expected = case.getJSONArray("chunks")

            val actual = Chunker().split(message, maxChunkLength)
            assertEquals("$name: chunk count", expected.length(), actual.size)
            for (c in 0 until expected.length()) {
                assertArrayEquals("$name: chunk $c", expected.getJSONArray(c).toByteArray(), actual[c])
            }
        }
    }

    @Test
    fun `round-trip cases reassemble to the original message`() {
        val cases = vectors.getJSONArray("roundTrip")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val message = case.getJSONArray("message").toByteArray()
            val maxChunkLength = case.getInt("maxChunkLength")

            val chunks = Chunker().split(message, maxChunkLength)
            val reassembler = Reassembler()
            chunks.forEachIndexed { index, chunk ->
                val result = reassembler.receive(chunk, nowMillis = 0, log = {})
                if (index < chunks.lastIndex) {
                    assertNull("$name: chunk $index should not complete the message", result)
                } else {
                    result as ReassembledMessage
                    assertArrayEquals("$name: final chunk", message, result.bytes)
                    assertEquals("$name: chunk count", chunks.size, result.chunks)
                }
            }
        }
    }
}
