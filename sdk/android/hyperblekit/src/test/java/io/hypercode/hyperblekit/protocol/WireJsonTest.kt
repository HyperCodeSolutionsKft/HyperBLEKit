package io.hypercode.hyperblekit.protocol

import io.hypercode.hyperblekit.JoinRejectCode
import io.hypercode.hyperblekit.GamePhase
import io.hypercode.hyperblekit.Player
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envelope and GameInfo encode/decode, wire specification "JSON rules" and "Envelope". */
class WireJsonTest {

    private fun roundTrip(body: Message.Body): Message {
        val original = Message(seq = 7, from = "p1", ts = 1_759_415_400_000, body = body)
        val decoded = WireJson.decodeMessage(WireJson.encode(original))
        assertEquals(original, decoded)
        return decoded!!
    }

    @Test
    fun `every phase-2 body type survives an encode-decode round trip`() {
        roundTrip(Message.Body.JoinRequest(name = "Anna", platform = "ios", deviceId = "a1b2c3"))
        roundTrip(Message.Body.JoinAccept(playerId = "p2", stateVersion = 2))
        roundTrip(Message.Body.JoinReject(code = JoinRejectCode.LOBBY_FULL))
        roundTrip(Message.Body.Leave)
        roundTrip(Message.Body.Ping(pingTs = 1_759_415_610_000))
        roundTrip(Message.Body.Pong(pingTs = 1_759_415_610_000))
        roundTrip(Message.Body.Ack(ackSeq = 16))
    }

    @Test
    fun `state sync with a player list round-trips`() {
        val state = GameState(
            gameId = "7K2F",
            stateVersion = 2,
            phase = GamePhase.LOBBY,
            players = listOf(
                Player("p1", "Host", "android", connected = true),
                Player("p2", "Anna", "ios", connected = true),
            ),
        )
        roundTrip(Message.Body.StateSync(state))
    }

    @Test
    fun `player list round-trips including the event and players`() {
        roundTrip(
            Message.Body.PlayerList(
                event = PlayerListEvent.JOINED,
                playerId = "p2",
                stateVersion = 2,
                players = listOf(Player("p1", "Host", "android", connected = true)),
            ),
        )
    }

    @Test
    fun `the envelope carries v, type, seq, from and ts`() {
        val bytes = WireJson.encode(
            Message(seq = 42, from = "p2", ts = 1_759_415_400_123, body = Message.Body.Ack(1)),
        )
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(1, json.getInt("v"))
        assertEquals("ACK", json.getString("type"))
        assertEquals(42, json.getInt("seq"))
        assertEquals("p2", json.getString("from"))
        assertEquals(1_759_415_400_123L, json.getLong("ts"))
    }

    @Test
    fun `the encoding is compact with no whitespace`() {
        val bytes = WireJson.encode(
            Message(seq = 1, from = "p1", ts = 1, body = Message.Body.Ping(pingTs = 1)),
        )
        assertFalse(String(bytes, Charsets.UTF_8).contains(" "))
    }

    @Test
    fun `an absent handSize is omitted, never sent as null`() {
        val withNull = WireJson.encode(
            Message(
                seq = 1, from = "p1", ts = 1,
                body = Message.Body.PlayerList(
                    PlayerListEvent.JOINED, "p2", 2,
                    listOf(Player("p1", "Host", "android", connected = true, handSize = null)),
                ),
            ),
        )
        assertFalse(String(withNull, Charsets.UTF_8).contains("handSize"))
        assertFalse(String(withNull, Charsets.UTF_8).contains("null"))
    }

    @Test
    fun `an unknown type decodes to Unknown and is not rejected`() {
        val json = """{"v":1,"type":"CARD_PLAY","seq":9,"from":"p1","ts":1,"data":{"x":1}}"""
        val decoded = WireJson.decodeMessage(json.toByteArray(Charsets.UTF_8))
        assertTrue(decoded!!.body is Message.Body.Unknown)
        assertEquals("CARD_PLAY", decoded.type)
    }

    @Test
    fun `malformed or incomplete envelopes decode to null`() {
        assertNull(WireJson.decodeMessage("not json".toByteArray()))
        assertNull(WireJson.decodeMessage("[1,2,3]".toByteArray()))
        assertNull(WireJson.decodeMessage("""{"type":"PING","seq":1,"from":"p1","ts":1}""".toByteArray()))
        assertNull(WireJson.decodeMessage("""{"v":1,"seq":1,"from":"p1","ts":1,"data":{}}""".toByteArray()))
        // Known type missing its required field.
        assertNull(WireJson.decodeMessage("""{"v":1,"type":"PING","seq":1,"from":"p1","ts":1,"data":{}}""".toByteArray()))
    }

    @Test
    fun `game info round-trips`() {
        val info = GameInfo(
            v = 1, gameId = "7K2F", hostName = "Gabor",
            players = 2, maxPlayers = 5, lobbyOpen = true, hostPlatform = "ios",
        )
        assertEquals(info, WireJson.decodeGameInfo(WireJson.encode(info)))
    }

    @Test
    fun `game info decode rejects malformed json`() {
        assertNull(WireJson.decodeGameInfo("{".toByteArray()))
    }
}
