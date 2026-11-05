package io.hypercode.hyperblekit.protocol

import io.hypercode.hyperblekit.transport.aware.AwareControl
import io.hypercode.hyperblekit.transport.aware.AwareOffer
import io.hypercode.hyperblekit.transport.aware.AwareStream
import io.hypercode.hyperblekit.transport.aware.AwareStreamDecoder
import io.hypercode.hyperblekit.transport.aware.AwareStreamException
import io.hypercode.hyperblekit.transport.aware.AwareWire
import io.hypercode.hyperblekit.transport.aware.DataPath
import io.hypercode.hyperblekit.transport.aware.DeclineReason
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Wi-Fi Aware framing against `protocol/test-vectors/wifi-aware.json`, shared with iOS. */
class WifiAwareVectorsTest {
    private val vectors = TestVectors.load("wifi-aware.json")

    private fun JSONObject.cases(key: String): List<JSONObject> {
        val array = getJSONArray(key)
        return List(array.length(), array::getJSONObject)
    }

    private fun control(json: JSONObject): AwareControl = when (val type = json.getString("type")) {
        "hello" -> AwareControl.Hello(json.getInt("version"))
        "offer" -> AwareControl.Offer(
            AwareOffer(json.getJSONArray("instanceId").toByteArray(), json.getJSONArray("token").toByteArray(), json.getString("passphrase"))
        )
        "decline" -> AwareControl.Decline(DeclineReason.fromCode(json.getInt("reason")))
        "pathSwitch" -> AwareControl.PathSwitch(checkNotNull(DataPath.fromCode(json.getInt("path"))))
        "failed" -> AwareControl.Failed
        else -> error("unknown control type $type")
    }

    private fun assertSameControl(name: String, expected: AwareControl, actual: AwareControl?) {
        if (expected is AwareControl.Offer) {
            val offer = (actual as? AwareControl.Offer)?.offer ?: return fail("$name: not an offer: $actual")
            assertArrayEquals(name, expected.offer.instanceId, offer.instanceId)
            assertArrayEquals(name, expected.offer.token, offer.token)
            assertEquals(name, expected.offer.passphrase, offer.passphrase)
        } else {
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun `control messages encode and decode`() {
        for (case in vectors.cases("control")) {
            val name = case.getString("name")
            val expected = control(case.getJSONObject("control"))
            val bytes = case.getJSONArray("bytes").toByteArray()
            assertArrayEquals(name, bytes, AwareControl.encode(expected))
            assertTrue(name, AwareWire.isControl(bytes))
            assertSameControl(name, expected, AwareControl.decode(bytes))
        }
    }

    @Test
    fun `malformed control messages decode to null`() {
        for (case in vectors.cases("invalidControl")) {
            assertNull(case.getString("name"), AwareControl.decode(case.getJSONArray("bytes").toByteArray()))
        }
    }

    @Test
    fun `stream records encode`() {
        for (case in vectors.cases("records")) {
            val bytes = AwareStream.encode(case.getInt("kind"), case.getJSONArray("body").toByteArray())
            assertArrayEquals(case.getString("name"), case.getJSONArray("bytes").toByteArray(), bytes)
        }
    }

    @Test
    fun `a stream fed in pieces yields its records in order`() {
        for (case in vectors.cases("streamSplit")) {
            val name = case.getString("name")
            val stream = case.getJSONArray("stream").toByteArray()
            val pieceSize = case.getInt("pieceSize")
            val decoder = AwareStreamDecoder()
            val records = stream.toList().chunked(pieceSize).flatMap { decoder.feed(it.toByteArray()) }
            val expected = case.cases("records")
            assertEquals(name, expected.size, records.size)
            expected.zip(records).forEach { (want, got) ->
                assertEquals(name, want.getInt("kind"), got.kind)
                assertArrayEquals(name, want.getJSONArray("body").toByteArray(), got.body)
            }
        }
    }

    @Test
    fun `a stream with a bad record length fails`() {
        for (case in vectors.cases("invalidStream")) {
            try {
                AwareStreamDecoder().feed(case.getJSONArray("stream").toByteArray())
                fail("${case.getString("name")}: no failure")
            } catch (_: AwareStreamException) {
                // Expected.
            }
        }
    }

    @Test
    fun `service info and discovery message`() {
        val info = vectors.getJSONObject("serviceInfo")
        val instanceId = info.getJSONArray("instanceId").toByteArray()
        assertArrayEquals(info.getJSONArray("bytes").toByteArray(), AwareWire.serviceInfo(instanceId))
        assertArrayEquals(instanceId, AwareWire.parseServiceInfo(info.getJSONArray("bytes").toByteArray()))
        assertNull(AwareWire.parseServiceInfo(byteArrayOf(2) + instanceId))

        val hello = vectors.getJSONObject("discoveryHello")
        val token = hello.getJSONArray("token").toByteArray()
        assertArrayEquals(hello.getJSONArray("bytes").toByteArray(), AwareWire.discoveryHello(token))
        assertArrayEquals(token, AwareWire.parseDiscoveryHello(hello.getJSONArray("bytes").toByteArray()))
        assertNull(AwareWire.parseDiscoveryHello(token))
    }

    @Test
    fun `the GameInfo flag`() {
        for (case in vectors.cases("gameInfoFlag")) {
            val out = AwareWire.withAwareFlag(case.getString("in").toByteArray(Charsets.UTF_8))
            assertEquals(case.getString("name"), case.getString("out"), String(out, Charsets.UTF_8))
        }
        for (case in vectors.cases("hasGameInfoFlag")) {
            val has = AwareWire.hasAwareFlag(case.getString("json").toByteArray(Charsets.UTF_8))
            assertEquals(case.getString("name"), case.getBoolean("expected"), has)
        }
        for (case in vectors.cases("takesAwareFrom")) {
            val takes = AwareWire.takesAwareFrom(case.getString("json").toByteArray(Charsets.UTF_8), case.getString("platform"))
            assertEquals(case.getString("name"), case.getBoolean("expected"), takes)
        }
    }

    @Test
    fun `service names follow the safe DNS-SD subset`() {
        for (case in vectors.cases("serviceNames")) {
            val name = case.getString("name")
            val valid = runCatching {
                io.hypercode.hyperblekit.HyperBleKitConfig(games = emptyList(), wifiAwareServiceName = name)
            }.isSuccess
            assertEquals(name, case.getBoolean("valid"), valid)
        }
    }
}
