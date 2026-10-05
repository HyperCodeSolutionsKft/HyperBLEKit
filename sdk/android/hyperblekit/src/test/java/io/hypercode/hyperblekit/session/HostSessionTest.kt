package io.hypercode.hyperblekit.session

import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.JoinRejectCode
import io.hypercode.hyperblekit.protocol.Message
import io.hypercode.hyperblekit.protocol.PlayerListEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host lobby rules: seats, `seq`, `stateVersion`, dedup, heartbeat. wire specification "Players and Seats",
 * "`seq` rules", "Reliability Rules". Pure logic, no BLE.
 */
class HostSessionTest {

    /** The monotonic clock the session measures silence with. */
    private var clock = 0L

    private fun newHost() = HostSession.create("7K2F", "Host", "devHost") { clock }

    private fun joinRequest(seq: Int, name: String, deviceId: String, v: Int = GameConstants.PROTOCOL_VERSION) =
        Message(
            v = v,
            seq = seq,
            from = GameConstants.UNASSIGNED_PLAYER_ID,
            ts = 0,
            body = Message.Body.JoinRequest(name = name, platform = "ios", deviceId = deviceId),
        )

    private fun Message.join() = body as Message.Body.JoinAccept
    private fun Message.reject() = body as Message.Body.JoinReject
    private fun Message.playerList() = body as Message.Body.PlayerList

    @Test
    fun `the first client is accepted as p2 with accept, state sync and player list`() {
        val host = newHost()
        val out = host.receive(joinRequest(1, "Anna", "devA"), link = "L2", nowMillis = 0)

        assertEquals(3, out.outgoing.size)
        assertEquals("p2", out.outgoing[0].message.join().playerId)
        assertEquals(2, out.outgoing[0].message.join().stateVersion)
        assertTrue(out.outgoing[1].message.body is Message.Body.StateSync)
        assertEquals(PlayerListEvent.JOINED, out.outgoing[2].message.playerList().event)
        assertEquals(listOf("p1", "p2"), host.players.map { it.id })
        assertEquals(2, host.stateVersion)
    }

    @Test
    fun `the host uses one shared seq counter across the accept, state sync and player list`() {
        val host = newHost()
        val out = host.receive(joinRequest(1, "Anna", "devA"), "L2", 0)
        assertEquals(listOf(1, 2, 3), out.outgoing.map { it.message.seq })
    }

    @Test
    fun `accept and state sync are targeted while the player list is broadcast`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0)
        val out = host.receive(joinRequest(1, "Bence", "devB"), "L3", 0)
        assertEquals(listOf("L3"), out.outgoing[0].recipients.map { it.link }) // accept
        assertEquals(listOf("L3"), out.outgoing[1].recipients.map { it.link }) // state sync
        assertEquals(setOf("L2", "L3"), out.outgoing[2].recipients.map { it.link }.toSet()) // player list
    }

    @Test
    fun `seats are handed out lowest-first and a freed lobby seat is reused`() {
        val host = newHost()
        host.receive(joinRequest(1, "A", "devA"), "L2", 0) // p2
        host.receive(joinRequest(1, "B", "devB"), "L3", 0) // p3
        host.receive(Message(seq = 2, from = "p2", ts = 0, body = Message.Body.Leave), "L2", 0)

        val out = host.receive(joinRequest(1, "C", "devC"), "L4", 0)
        assertEquals("p2", out.outgoing[0].message.join().playerId)
    }

    @Test
    fun `a player beyond MAX_PLAYERS is rejected with lobby_full`() {
        val host = newHost()
        // p2..p<MAX_PLAYERS> fill up (the host is p1).
        for (seat in 2..GameConstants.MAX_PLAYERS) host.receive(joinRequest(1, "P$seat", "dev$seat"), "L$seat", 0)

        val out = host.receive(joinRequest(1, "Late", "devLate"), "LLate", 0)
        assertEquals(JoinRejectCode.LOBBY_FULL, out.outgoing.single().message.reject().code)
        assertEquals(listOf("LLate"), out.outgoing.single().recipients.map { it.link })
    }

    @Test
    fun `a join with the wrong protocol version is rejected with version_mismatch`() {
        val host = newHost()
        val out = host.receive(joinRequest(1, "A", "devA", v = GameConstants.PROTOCOL_VERSION + 1), "L2", 0)
        assertEquals(JoinRejectCode.VERSION_MISMATCH, out.outgoing.single().message.reject().code)
    }

    @Test
    fun `a known deviceId reconnecting gets its old seat back`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0) // p2
        host.linkDown("L2", nowMillis = 0)

        val out = host.receive(joinRequest(1, "Anna", "devA"), link = "L2b", nowMillis = 100)
        assertEquals("p2", out.outgoing[0].message.join().playerId)
        assertEquals(PlayerListEvent.RECONNECTED, out.outgoing.last().message.playerList().event)
        assertTrue(host.players.first { it.id == "p2" }.connected)
    }

    @Test
    fun `a dropped link marks the player disconnected and broadcasts the player list`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0)
        val out = host.linkDown("L2", nowMillis = 0)
        assertFalse(host.players.first { it.id == "p2" }.connected)
        assertEquals(PlayerListEvent.DISCONNECTED, out.outgoing.single().message.playerList().event)
    }

    @Test
    fun `a duplicate client seq is ignored`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0) // seq 1 recorded

        val first = host.receive(Message(seq = 2, from = "p2", ts = 500, body = Message.Body.Ping(400)), "L2", 500)
        assertTrue(first.outgoing.any { it.message.body is Message.Body.Pong })

        val dup = host.receive(Message(seq = 2, from = "p2", ts = 500, body = Message.Body.Ping(400)), "L2", 500)
        assertTrue(dup.outgoing.isEmpty())
        assertTrue(dup.notes.any { it.contains("duplicate") })
    }

    @Test
    fun `a client silent for more than 10s is marked disconnected on heartbeat`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", nowMillis = 0)

        clock = GameConstants.SILENCE_TIMEOUT_MILLIS + 1
        val out = host.heartbeat(nowMillis = clock)
        assertFalse(host.players.first { it.id == "p2" }.connected)
        assertTrue(out.notes.any { it.contains("silent") })
        assertTrue(out.outgoing.any { (it.message.body as? Message.Body.PlayerList)?.event == PlayerListEvent.DISCONNECTED })
    }

    @Test
    fun `a client still inside the silence window stays connected`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", nowMillis = 0)
        clock = GameConstants.SILENCE_TIMEOUT_MILLIS - 1
        host.heartbeat(nowMillis = clock)
        assertTrue(host.players.first { it.id == "p2" }.connected)
    }

    @Test
    fun `a PONG records the round-trip time`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0)
        val out = host.receive(Message(seq = 2, from = "p2", ts = 1200, body = Message.Body.Pong(1000)), "L2", 1200)
        assertEquals(200L, out.roundTripMs)
        assertEquals(1, host.pingStats.count)
        assertEquals(200L, host.pingStats.averageMs)
    }

    @Test
    fun `leaving in the lobby frees the seat`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0)
        val out = host.receive(Message(seq = 2, from = "p2", ts = 0, body = Message.Body.Leave), "L2", 0)
        assertEquals(listOf("p1"), host.players.map { it.id })
        assertEquals(PlayerListEvent.LEFT, out.outgoing.single().message.playerList().event)
    }

    @Test
    fun `a message from a link that never joined is ignored`() {
        val host = newHost()
        val out = host.receive(Message(seq = 1, from = "p9", ts = 0, body = Message.Body.Leave), "ghost", 0)
        assertTrue(out.outgoing.isEmpty())
        assertTrue(out.notes.any { it.contains("has not joined") })
    }

    @Test
    fun `game info reflects the player count and open lobby`() {
        val host = newHost()
        host.receive(joinRequest(1, "Anna", "devA"), "L2", 0)
        val info = host.gameInfo
        assertEquals(2, info.players)
        assertEquals(GameConstants.MAX_PLAYERS, info.maxPlayers)
        assertTrue(info.lobbyOpen)
        assertEquals("7K2F", info.gameId)
    }

    @Test
    fun `an over-long player name is truncated to the max`() {
        val host = newHost()
        host.receive(joinRequest(1, "ThisNameIsWayTooLong", "devA"), "L2", 0)
        assertEquals(GameConstants.MAX_NAME_LENGTH, host.players.first { it.id == "p2" }.name.length)
    }

    @Test
    fun `ping stats start empty`() {
        assertNull(newHost().pingStats.averageMs)
    }
}
