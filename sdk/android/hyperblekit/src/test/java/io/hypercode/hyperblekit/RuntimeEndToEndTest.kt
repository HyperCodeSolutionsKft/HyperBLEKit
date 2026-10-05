package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.fake.CounterGame
import io.hypercode.hyperblekit.fake.FakeDevice
import io.hypercode.hyperblekit.fake.FakeRadio
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime end to end: several kits in one JVM, each a phone of its own, talking over the fake
 * transports of [FakeRadio] on virtual time. Everything between the public API and the transport
 * interfaces is the real code: sessions, runtimes, timers, reconnect, takeover, role switching.
 */
class RuntimeEndToEndTest {
    private val radio = FakeRadio()
    private val config = HyperBleKitConfig(games = listOf(CounterGame))

    @After
    fun tearDown() {
        radio.shutDown()
        HyperBleLog.sink = null
    }

    private fun phone(name: String) = FakeDevice(radio, name, config)

    private val FakeDevice.session: GameSession get() = checkNotNull(kit.session.value) { "no session" }
    private val FakeDevice.state: SessionState get() = session.state.value

    private fun FakeDevice.joinTheOnlyGame(): GameSession {
        kit.startDiscovery()
        radio.advance(SHORT)
        val joined = kit.join(kit.discoveredGames.value.single())
        radio.advance(SHORT)
        return joined
    }

    /** Anna hosts; Bence, then Csaba join: p1, p2, p3. */
    private fun lobbyOfThree(): Triple<FakeDevice, FakeDevice, FakeDevice> {
        val anna = phone("Anna")
        val bence = phone("Bence")
        val csaba = phone("Csaba")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        csaba.joinTheOnlyGame()
        return Triple(anna, bence, csaba)
    }

    private fun count(device: FakeDevice): Int = checkNotNull(device.state.view).toJson().getInt("count")

    private fun connectedIds(device: FakeDevice): List<String> = device.state.players.filter { it.connected }.map { it.id }

    // --- Joining ---

    @Test
    fun `a host and two clients end up in one lobby with the same players`() {
        val (anna, bence, csaba) = lobbyOfThree()

        assertEquals(SessionRole.HOST, anna.state.role)
        assertEquals(SessionRole.CLIENT, bence.state.role)
        assertEquals(SessionRole.CLIENT, csaba.state.role)
        for (device in listOf(anna, bence, csaba)) {
            assertEquals(ConnectionState.Joined, device.state.connection)
            assertEquals(listOf("p1", "p2", "p3"), device.state.players.map { it.id })
            assertEquals(listOf("Anna", "Bence", "Csaba"), device.state.players.map { it.name })
            assertTrue(device.state.players.all { it.connected })
            assertEquals("p1", device.state.hostId)
            assertEquals(1, device.state.hostEpoch)
            assertEquals(GamePhase.LOBBY, device.state.phase)
            assertEquals(anna.session.gameCode, device.state.gameCode)
        }
        assertEquals(listOf("p1", "p2", "p3"), listOf(anna, bence, csaba).map { it.state.playerId })
        assertEquals(listOf("p2", "p3"), anna.state.successors)
        assertTrue(anna.state.advertising is AdvertisingState.Advertising)
    }

    @Test
    fun `a hosted session is a host from its first state and a joining one passes through Joining`() {
        val anna = phone("Anna")
        val hosted = anna.kit.host()
        assertEquals(SessionRole.HOST, hosted.state.value.role)
        assertEquals(ConnectionState.Joined, hosted.state.value.connection)
        assertSame(hosted, anna.kit.session.value)
        radio.advance(SHORT)

        val bence = phone("Bence")
        bence.kit.startDiscovery()
        radio.advance(SHORT)
        val joining = bence.kit.join(bence.kit.discoveredGames.value.single())
        val seen = mutableListOf<ConnectionState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            joining.state.map { it.connection }.distinctUntilChanged().collect { seen += it }
        }
        radio.advance(SHORT)
        watcher.cancel()
        assertEquals(
            listOf(ConnectionState.Connecting, ConnectionState.Joining, ConnectionState.Joined),
            seen,
        )
        assertEquals(ScanningState.Idle, bence.kit.scanningState.value)
    }

    // --- Playing ---

    @Test
    fun `a game is played with an accepted and a rejected move and everyone ends with the same state`() {
        val (anna, bence, csaba) = lobbyOfThree()
        val all = listOf(anna, bence, csaba)

        assertNull(anna.session.startGameProblem(CounterGame.id))
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        for (device in all) {
            assertEquals(GamePhase.PLAYING, device.state.phase)
            assertEquals(CounterGame.id, device.state.gameId)
            assertEquals(0, count(device))
        }

        // The host's own move, then a client's: both accepted.
        anna.session.act(CounterGame.add())
        radio.advance(SHORT)
        bence.session.act(CounterGame.add())
        radio.advance(SHORT)
        all.forEach { assertEquals(2, count(it)) }

        // Out of turn: rejected, nothing changes anywhere, only the sender is told.
        bence.session.act(CounterGame.add())
        radio.advance(SHORT)
        all.forEach { assertEquals(2, count(it)) }
        assertEquals("Move rejected: not_your_turn (it is p3's turn)", bence.state.gameNotice)
        assertNull(anna.state.gameNotice)
        assertNull(csaba.state.gameNotice)

        // The next accepted move clears the notice; the game runs to its end.
        csaba.session.act(CounterGame.add())
        radio.advance(SHORT)
        assertNull(bence.state.gameNotice)
        anna.session.act(CounterGame.add())
        radio.advance(SHORT)
        bence.session.act(CounterGame.add())
        radio.advance(SHORT)

        for (device in all) {
            assertEquals(GamePhase.FINISHED, device.state.phase)
            assertEquals(CounterGame.TARGET, count(device))
            assertEquals(device.state.playerId, checkNotNull(device.state.view).toJson().getString("you"))
        }
        // Every client holds the state version the host last sent, on the wire and after a heartbeat.
        radio.advance(HEARTBEAT)
        val sent = assertNotNullAnd(anna.host.lastStateVersionSent)
        assertEquals(sent, bence.clients.single().lastStateVersionReceived)
        assertEquals(sent, csaba.clients.single().lastStateVersionReceived)
        assertFalse("nobody asked for a resync", (bence.clients + csaba.clients).any { client ->
            client.received.count { it.body is Message.Body.StateSync } > 1
        })

        // The host can only end what exists, and only the host can.
        csaba.session.endGame()
        radio.advance(SHORT)
        assertEquals(GamePhase.FINISHED, anna.state.phase)
        anna.session.endGame()
        radio.advance(SHORT)
        all.forEach { assertEquals(GamePhase.LOBBY, it.state.phase) }
    }

    @Test
    fun `a game that cannot be started says why and stays in the lobby`() {
        val anna = phone("Anna")
        val session = anna.kit.host()
        radio.advance(SHORT)

        assertEquals("Counter needs 2–3 connected players", session.startGameProblem(CounterGame.id))
        assertEquals("This app has no game \"chess\"", session.startGameProblem("chess"))
        session.startGame(CounterGame.id)
        radio.advance(SHORT)
        assertEquals(GamePhase.LOBBY, session.state.value.phase)
        assertEquals("Counter needs 2–3 connected players, 1 connected now", session.state.value.gameNotice)

        val bence = phone("Bence")
        bence.joinTheOnlyGame()
        assertNull(session.startGameProblem(CounterGame.id))
        assertEquals("Only the host can start a game", bence.session.startGameProblem(CounterGame.id))
        bence.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        assertEquals(GamePhase.LOBBY, session.state.value.phase)
    }

    // --- Leaving ---

    @Test
    fun `a client that leaves is gone from every list and its session is dead`() {
        val (anna, bence, csaba) = lobbyOfThree()
        val left = csaba.session

        csaba.kit.leave()
        assertNull(csaba.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), left.state.value.connection)
        radio.advance(SECOND)

        assertEquals(listOf("p1", "p2"), anna.state.players.map { it.id })
        assertEquals(listOf("p1", "p2"), bence.state.players.map { it.id })
        assertEquals(ConnectionState.Ended(EndReason.Left), left.state.value.connection)
        // A dead session ignores everything.
        left.act(CounterGame.add())
        left.startGame(CounterGame.id)
        radio.advance(SECOND)
        assertEquals(GamePhase.LOBBY, anna.state.phase)
    }

    @Test
    fun `a host that leaves hands the game to the first successor and the others follow`() {
        val (anna, bence, csaba) = lobbyOfThree()
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        anna.session.act(CounterGame.add())
        radio.advance(SECOND)
        val benceSession = bence.session
        val annaSession = anna.session

        anna.kit.leave()
        radio.advance(5 * SECOND)

        assertNull(anna.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), annaSession.state.value.connection)
        // The last state of a host that left does not claim it is still advertising.
        assertEquals(SessionRole.HOST, annaSession.state.value.role)
        assertNull(annaSession.state.value.advertising)
        assertEquals(AdvertisingState.Idle, anna.host.state.value)
        // The same session object, now the host, with the game where it was.
        assertSame(benceSession, bence.session)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(ConnectionState.Joined, bence.state.connection)
        assertEquals("p2", bence.state.playerId)
        assertEquals("p2", bence.state.hostId)
        assertEquals(2, bence.state.hostEpoch)
        assertEquals(GamePhase.PLAYING, bence.state.phase)
        assertEquals(1, count(bence))
        // The third phone found the new host by game code and has the same game.
        assertEquals(SessionRole.CLIENT, csaba.state.role)
        assertEquals(ConnectionState.Joined, csaba.state.connection)
        assertEquals("p2", csaba.state.hostId)
        assertEquals(2, csaba.state.hostEpoch)
        assertEquals(1, count(csaba))
        assertNotNull(csaba.state.lastReconnect)
        assertEquals(listOf("p2", "p3"), connectedIds(bence))

        // The game goes on under the new host: it is p2's turn.
        bence.session.act(CounterGame.add())
        radio.advance(SHORT)
        assertEquals(2, count(bence))
        assertEquals(2, count(csaba))
    }

    @Test
    fun `a host that leaves with nobody to hand over to ends the game for everyone`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        bence.host.canHost = false
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        assertEquals(emptyList<String>(), anna.state.successors)

        anna.kit.leave()
        radio.advance(SECOND)

        assertEquals(ConnectionState.Ended(EndReason.HostLeft), bence.state.connection)
        assertEquals(SessionRole.CLIENT, bence.state.role)
    }

    // --- Host migration ---

    @Test
    fun `when the host is lost without a word the first successor takes over after the delay`() {
        val (anna, bence, csaba) = lobbyOfThree()
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        // Long enough for the successors to hold a snapshot.
        radio.advance(HEARTBEAT)
        val benceSession = bence.session

        anna.die()
        radio.advance(SECOND)
        assertTrue(bence.state.connection is ConnectionState.Reconnecting)
        assertTrue(csaba.state.connection is ConnectionState.Reconnecting)

        // Not before the takeover delay of 5 s …
        radio.advance(3 * SECOND)
        assertEquals(SessionRole.CLIENT, bence.state.role)
        assertEquals(0, bence.host.startCount)

        // … and soon after it.
        radio.advance(2 * SECOND)
        assertSame(benceSession, bence.session)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(2, bence.state.hostEpoch)
        assertEquals(GamePhase.PLAYING, bence.state.phase)
        assertEquals(1, bence.host.startCount)

        radio.advance(3 * SECOND)
        assertEquals(SessionRole.CLIENT, csaba.state.role)
        assertEquals(ConnectionState.Joined, csaba.state.connection)
        assertEquals("p2", csaba.state.hostId)
        assertEquals(0, csaba.host.startCount)
        // The lost host keeps its seat for the reconnect window, then it is removed.
        assertEquals(listOf("p2", "p3"), connectedIds(bence))
        assertTrue("p1" in bence.state.offlineSecondsLeft)
        radio.advance(RECONNECT_WINDOW + SECOND)
        assertEquals(listOf("p2", "p3"), bence.state.players.map { it.id })
        assertEquals(listOf("p2", "p3"), csaba.state.players.map { it.id })
    }

    @Test
    fun `a client that never finds a host again gives up when the reconnect window ends`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        bence.host.canHost = false
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()

        anna.die()
        radio.advance(RECONNECT_WINDOW - 2 * SECOND)
        assertEquals(ConnectionState.Reconnecting(secondsLeft = 2), bence.state.connection)
        radio.advance(2 * SECOND)
        assertEquals(ConnectionState.Ended(EndReason.HostLeft), bence.state.connection)
        assertEquals(0, bence.host.startCount)
    }

    @Test
    fun `a host that was cut off steps down for the host that took over and rejoins as a client`() {
        val (anna, bence, csaba) = lobbyOfThree()
        anna.session.startGame(CounterGame.id)
        radio.advance(HEARTBEAT)
        val annaSession = anna.session

        // Anna's phone loses everyone but keeps running: she still believes she hosts.
        anna.goOutOfRange()
        radio.advance(8 * SECOND)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(ConnectionState.Joined, csaba.state.connection)
        assertEquals(SessionRole.HOST, anna.state.role)

        // Back in range, her probe finds the game under a host with a higher epoch.
        anna.comeBack()
        radio.advance(15 * SECOND)

        assertSame(annaSession, anna.session)
        assertEquals(SessionRole.CLIENT, anna.state.role)
        assertEquals(ConnectionState.Joined, anna.state.connection)
        assertEquals("p1", anna.state.playerId)
        assertEquals("p2", anna.state.hostId)
        assertEquals(2, anna.state.hostEpoch)
        assertEquals(AdvertisingState.Idle, anna.host.state.value)
        assertNull(anna.state.advertising)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(listOf("p1", "p2", "p3"), connectedIds(bence))
        assertEquals(GamePhase.PLAYING, anna.state.phase)
    }

    @Test
    fun `a host that goes to the background hands the game over and rejoins it in the foreground`() {
        val (anna, bence, csaba) = lobbyOfThree()
        val annaSession = anna.session

        anna.kit.onBackground()
        // Android may report it twice; the second call must not lose the hand-over.
        anna.kit.onBackground()
        radio.advance(5 * SECOND)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(ConnectionState.Joined, csaba.state.connection)
        assertEquals("p2", csaba.state.hostId)
        assertEquals(AdvertisingState.Idle, anna.host.state.value)

        anna.kit.onForeground()
        radio.advance(3 * SECOND)

        assertSame(annaSession, anna.session)
        assertEquals(SessionRole.CLIENT, anna.state.role)
        assertEquals(ConnectionState.Joined, anna.state.connection)
        assertEquals("p1", anna.state.playerId)
        assertEquals("p2", anna.state.hostId)
        assertEquals(listOf("p1", "p2", "p3"), connectedIds(bence))
    }

    @Test
    fun `a host with nobody to hand over to keeps hosting in the background`() {
        val anna = phone("Anna")
        val session = anna.kit.host()
        radio.advance(SHORT)

        anna.kit.onBackground()
        radio.advance(SECOND)
        anna.kit.onForeground()
        radio.advance(SECOND)

        assertEquals(SessionRole.HOST, session.state.value.role)
        assertTrue(anna.host.state.value is AdvertisingState.Advertising)
    }

    // --- A session that was left stays dead ---

    @Test
    fun `a HOST_LEAVING that arrives after leave does not turn the dead session into a host`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        val left = bence.joinTheOnlyGame()
        // Bence holds a snapshot and is the successor Anna will name.
        radio.advance(HEARTBEAT)
        assertEquals(listOf("p2"), anna.state.successors)

        // Both leave in the same moment: Bence's link stays up while his LEAVE is flushed, and
        // Anna's HOST_LEAVING, naming him, arrives on it.
        bence.kit.leave()
        anna.kit.leave()
        radio.advance(10 * SECOND)

        assertTrue(
            "the HOST_LEAVING reached the client transport after leave",
            bence.clients.single().received.any { (it.body as? Message.Body.HostLeaving)?.nextHostId == "p2" },
        )
        assertNull(bence.kit.session.value)
        assertEquals(SessionRole.CLIENT, left.state.value.role)
        assertEquals(ConnectionState.Ended(EndReason.Left), left.state.value.connection)
        assertEquals(0, bence.host.startCount)
        assertEquals(AdvertisingState.Idle, bence.host.state.value)
        assertNull(bence.host.gameCode)
    }

    @Test
    fun `a HOST_LEAVING that names someone else does not make a client that left reconnect`() {
        val (anna, bence, csaba) = lobbyOfThree()
        val left = csaba.session

        // Csaba leaves; while his LEAVE is still being flushed, Anna leaves too and tells
        // everyone, him included, that Bence is the next host.
        csaba.kit.leave()
        anna.kit.leave()
        radio.advance(10 * SECOND)

        assertTrue(
            "the HOST_LEAVING reached the client transport after leave",
            csaba.clients.single().received.any { (it.body as? Message.Body.HostLeaving)?.nextHostId == "p2" },
        )
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(listOf("p2"), connectedIds(bence))
        assertEquals("the left device asked for no second connection", 1, csaba.clients.single().connectCalls)
        assertEquals(1, csaba.clients.size)
        assertTrue(bence.host.links.isEmpty())
        assertEquals(ConnectionState.Ended(EndReason.Left), left.state.value.connection)
        assertNull(csaba.kit.session.value)
    }

    @Test
    fun `a client that left and is named the next host without holding a snapshot stays gone`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        val csaba = phone("Csaba")
        anna.kit.host()
        radio.advance(SHORT)
        // Bence never gets a snapshot, so he could not take over although he is first in line.
        bence.kit.startDiscovery()
        radio.advance(SHORT)
        val left = bence.kit.join(bence.kit.discoveredGames.value.single())
        bence.clients.single().dropIncoming = { it.body is Message.Body.HostSnapshot }
        radio.advance(SHORT)
        csaba.joinTheOnlyGame()
        radio.advance(HEARTBEAT)
        assertEquals(listOf("p2", "p3"), anna.state.successors)

        bence.kit.leave()
        anna.kit.leave()
        // Long enough for Csaba, second in line, to take over (5 s + 8 s after the loss).
        radio.advance(20 * SECOND)

        assertTrue(
            "the HOST_LEAVING naming him reached the client transport after leave",
            bence.clients.single().received.any { (it.body as? Message.Body.HostLeaving)?.nextHostId == "p2" },
        )
        assertEquals(SessionRole.HOST, csaba.state.role)
        assertEquals(listOf("p3"), connectedIds(csaba))
        assertEquals("the left device asked for no second connection", 1, bence.clients.single().connectCalls)
        assertEquals(1, bence.clients.size)
        assertTrue(csaba.host.links.isEmpty())
        assertEquals(0, bence.host.startCount)
        assertEquals(ConnectionState.Ended(EndReason.Left), left.state.value.connection)
    }

    @Test
    fun `hosting or joining again leaves the open session first`() {
        val anna = phone("Anna")
        val first = anna.kit.host()
        radio.advance(SHORT)
        val second = anna.kit.host()
        radio.advance(SHORT)

        assertEquals(ConnectionState.Ended(EndReason.Left), first.state.value.connection)
        assertSame(second, anna.kit.session.value)
        assertEquals(SessionRole.HOST, second.state.value.role)
        assertEquals(second.gameCode, anna.host.gameCode)
    }

    @Test
    fun `a closed kit has left its session and refuses to start another`() {
        val (anna, bence, _) = lobbyOfThree()
        val session = bence.session

        bence.kit.close()
        bence.kit.close()
        radio.advance(SECOND)

        assertNull(bence.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), session.state.value.connection)
        assertEquals(listOf("p1", "p3"), anna.state.players.map { it.id })
        bence.kit.startDiscovery()
        assertEquals(ScanningState.Idle, bence.kit.scanningState.value)
        assertTrue(runCatching { bence.kit.host() }.exceptionOrNull() is IllegalStateException)
    }

    // --- Diagnostics ---

    @Test
    fun `the JSON test frame crosses the link in both directions`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        anna.session.diagnostics.sendTestJson()
        radio.advance(SHORT)
        assertEquals("Test JSON: no joined client to send to", anna.session.diagnostics.testResult.value)
        bence.joinTheOnlyGame()

        anna.session.diagnostics.sendTestJson()
        radio.advance(SHORT)
        assertTrue(checkNotNull(anna.session.diagnostics.testResult.value).startsWith("Test JSON: sent "))
        assertTrue(checkNotNull(bence.session.diagnostics.testResult.value).endsWith("match"))
        assertTrue(checkNotNull(bence.session.diagnostics.testJsonReceived.value).contains("\"gameId\""))

        bence.session.diagnostics.sendTestJson()
        radio.advance(SHORT)
        assertTrue(checkNotNull(anna.session.diagnostics.testResult.value).endsWith("match"))
    }

    @Test
    fun `the speed test runs in the lobby only and its stop line says why it stopped`() {
        val lines = mutableListOf<String>()
        HyperBleLog.sink = { lines += it.message }
        val anna = phone("Anna")
        val bence = phone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        val diagnostics = anna.session.diagnostics
        assertFalse(diagnostics.canRunSpeedTest.value)
        bence.joinTheOnlyGame()
        assertTrue(diagnostics.canRunSpeedTest.value)
        assertFalse(bence.session.diagnostics.canRunSpeedTest.value)
        assertNull(bence.session.diagnostics.hostSpeedTest.value)

        diagnostics.startSpeedTest(frameRate = 15, payloadSize = 64)
        radio.advance(2 * SECOND)
        assertTrue(checkNotNull(diagnostics.hostSpeedTest.value).isRunning)
        assertNotNull(bence.session.diagnostics.clientSpeedTest.value)
        bence.session.diagnostics.tapSpeedTest()
        radio.advance(SHORT)
        assertEquals(1, checkNotNull(bence.session.diagnostics.clientSpeedTest.value).taps)

        diagnostics.stopSpeedTest()
        assertTrue("-- RT speed test stopped: stopped on the host" in lines)
        diagnostics.startSpeedTest(frameRate = 15, payloadSize = 64)
        radio.advance(SECOND)
        diagnostics.stopSpeedTest(screenClosed = true)
        radio.advance(SHORT)
        assertTrue("-- RT speed test stopped: host screen closed" in lines)
        assertFalse(checkNotNull(diagnostics.hostSpeedTest.value).isRunning)
        // The client's view closes by itself two seconds after the last frame.
        radio.advance(3 * SECOND)
        assertNull(bence.session.diagnostics.clientSpeedTest.value)

        // No speed test while a game is on.
        diagnostics.startSpeedTest(frameRate = 15, payloadSize = 64)
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        assertTrue("-- RT speed test stopped: a game started" in lines)
        assertFalse(diagnostics.canRunSpeedTest.value)
        diagnostics.startSpeedTest(frameRate = 15, payloadSize = 64)
        radio.advance(SHORT)
        assertFalse(checkNotNull(diagnostics.hostSpeedTest.value).isRunning)
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        /** Long enough for everything queued to be delivered and answered. */
        const val SHORT = 50L
        const val SECOND = 1_000L

        /** One host heartbeat (3 s) and a bit. */
        const val HEARTBEAT = 3_500L

        /** The reconnect window: a client looks for a lost host this long. */
        const val RECONNECT_WINDOW = 45_000L
    }
}
