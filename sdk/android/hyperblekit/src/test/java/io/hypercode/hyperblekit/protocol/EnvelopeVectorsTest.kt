package io.hypercode.hyperblekit.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the Android envelope encode/decode against the shared `/protocol/test-vectors/envelope.json`.
 * JSON key order is not significant on the wire, so `data` is compared structurally.
 */
class EnvelopeVectorsTest {

    private val vectors = TestVectors.load("envelope.json")

    private fun assertEnvelopeFields(case: JSONObject, message: Message) {
        val name = case.getString("name")
        assertEquals("$name: v", case.getInt("v"), message.v)
        assertEquals("$name: type", case.getString("type"), message.type)
        assertEquals("$name: seq", case.getInt("seq"), message.seq)
        assertEquals("$name: from", case.getString("from"), message.from)
        assertEquals("$name: ts", case.getLong("ts"), message.ts)
    }

    private fun dataOf(message: Message): JSONObject =
        JSONObject(String(WireJson.encode(message), Charsets.UTF_8)).getJSONObject("data")

    @Test
    fun `decode cases parse the fields and the data payload`() {
        val cases = vectors.getJSONArray("decode")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val decoded = WireJson.decodeMessage(case.getString("json").toByteArray(Charsets.UTF_8))
                ?: error("$name: expected a decodable envelope")
            assertEnvelopeFields(case, decoded)
            val expectedData = JSONObject(case.getString("dataJson"))
            assertTrue("$name: data payload", jsonStructurallyEqual(dataOf(decoded), expectedData))
        }
    }

    @Test
    fun `round-trip cases survive encode then decode unchanged`() {
        val cases = vectors.getJSONArray("roundTrip")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val built = JSONObject()
                .put("v", case.getInt("v"))
                .put("type", case.getString("type"))
                .put("seq", case.getInt("seq"))
                .put("from", case.getString("from"))
                .put("ts", case.getLong("ts"))
                .put("data", JSONObject(case.getString("dataJson")))
            val first = WireJson.decodeMessage(built.toString().toByteArray(Charsets.UTF_8))
                ?: error("$name: expected a decodable envelope")
            assertEnvelopeFields(case, first)
            val second = WireJson.decodeMessage(WireJson.encode(first))
            assertEquals("$name: round trip", first, second)
        }
    }

    @Test
    fun `invalid decode cases are rejected`() {
        val cases = vectors.getJSONArray("invalidDecode")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            assertNull("$name: should not decode", WireJson.decodeMessage(case.getString("json").toByteArray(Charsets.UTF_8)))
        }
    }
}
