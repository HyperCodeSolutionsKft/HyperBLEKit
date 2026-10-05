package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.fake.CounterGame
import io.hypercode.hyperblekit.fake.FakeDevice
import io.hypercode.hyperblekit.fake.FakeRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime with the ordering of the shipped kit. There the kit's scope is
 * `Dispatchers.Main.immediate`: a coroutine started on the main thread runs inline, where
 * [RuntimeEndToEndTest] queues it. Here the kits run on an unconfined dispatcher, which behaves
 * like `Main.immediate` in this respect, and so do the app's collectors of a session's state:
 * they run inside the assignment that changed it. A session's state is published from the queue
 * (`Dispatchers.Main` in the shipped kit), so that such a collector never runs in the middle of a
 * runtime's work — except for the first state of a new role, which is published while the roles
 * are being switched. The tests below leave from inside those emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImmediateDispatcherEndToEndTest {
    private val radio = FakeRadio(immediateKits = true)
    private val config = HyperBleKitConfig(games = listOf(CounterGame))
    private val watchers = mutableListOf<Job>()

    @After
    fun tearDown() {
        watchers.forEach { it.cancel() }
        radio.shutDown()
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

    /** An app's collector: it runs inline, inside the assignment that changed the state. */
    private fun GameSession.onEachState(action: (SessionState) -> Unit) {
        watchers += CoroutineScope(UnconfinedTestDispatcher(radio.scheduler)).launch { state.collect { action(it) } }
    }

    private fun count(device: FakeDevice): Int = checkNotNull(device.state.view).toJson().getInt("count")

    private fun connectedIds(device: FakeDevice): List<String> = device.state.players.filter { it.connected }.map { it.id }

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

    @Test
    fun `join, play and a host hand-over work when state is published inline`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        val csaba = phone("Csaba")
        val hosted = anna.kit.host()
        // The state is complete as soon as host() returns: nothing was left queued.
        assertEquals(SessionRole.HOST, hosted.state.value.role)
        assertEquals("p1", hosted.state.value.playerId)
        assertEquals(listOf("p1"), hosted.state.value.players.map { it.id })
        radio.advance(SHORT)

        // Every state Bence's screen would get, in order, collected inline.
        bence.kit.startDiscovery()
        radio.advance(SHORT)
        val benceSession = bence.kit.join(bence.kit.discoveredGames.value.single())
        val connections = mutableListOf<ConnectionState>()
        val roles = mutableListOf<SessionRole>()
        benceSession.onEachState {
            if (connections.lastOrNull() != it.connection) connections += it.connection
            if (roles.lastOrNull() != it.role) roles += it.role
        }
        radio.advance(SHORT)
        csaba.joinTheOnlyGame()
        assertEquals(listOf(ConnectionState.Connecting, ConnectionState.Joining, ConnectionState.Joined), connections)
        for (device in listOf(anna, bence, csaba)) {
            assertEquals(listOf("p1", "p2", "p3"), connectedIds(device))
        }

        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        assertEquals(GamePhase.PLAYING, anna.state.phase)
        anna.session.act(CounterGame.add())
        radio.advance(SECOND)
        assertEquals(1, count(anna))
        assertEquals(1, count(bence))
        assertEquals(1, count(csaba))

        anna.kit.leave()
        radio.advance(5 * SECOND)

        assertSame(benceSession, bence.session)
        assertEquals(listOf(SessionRole.CLIENT, SessionRole.HOST), roles)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(ConnectionState.Joined, bence.state.connection)
        assertEquals(2, bence.state.hostEpoch)
        assertEquals(GamePhase.PLAYING, bence.state.phase)
        assertEquals(1, count(bence))
        assertEquals(ConnectionState.Joined, csaba.state.connection)
        assertEquals("p2", csaba.state.hostId)
        assertEquals(listOf("p2", "p3"), connectedIds(bence))
        assertEquals(ConnectionState.Joined, connections.last())

        bence.session.act(CounterGame.add())
        radio.advance(SHORT)
        assertEquals(2, count(csaba))
    }

    @Test
    fun `an app that leaves from inside the state emission of a takeover leaves no host behind`() {
        val (anna, bence, csaba) = lobbyOfThree()
        anna.session.startGame(CounterGame.id)
        radio.advance(HEARTBEAT)
        val benceSession = bence.session
        // The app reacts to becoming the host by leaving at once — inside the emission, which is
        // inside the session's switch of roles, before the new host role has even started.
        benceSession.onEachState { if (it.role == SessionRole.HOST) bence.kit.leave() }

        anna.kit.leave()
        radio.advance(20 * SECOND)

        assertNull(bence.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), benceSession.state.value.connection)
        assertNull(benceSession.state.value.advertising)
        assertEquals(AdvertisingState.Idle, bence.host.state.value)
        assertNull(bence.host.gameCode)
        assertEquals(0, bence.host.startCount)
        // The game is not lost: Csaba, second in line, takes it over.
        assertEquals(SessionRole.HOST, csaba.state.role)
        assertEquals(listOf("p3"), connectedIds(csaba))
        assertTrue(csaba.host.links.isEmpty())
    }

    @Test
    fun `an app that leaves from inside the state emission of a foreground rejoin never starts the client role`() {
        val (anna, bence, _) = lobbyOfThree()
        val annaSession = anna.session
        annaSession.onEachState { if (it.role == SessionRole.CLIENT) anna.kit.leave() }

        anna.kit.onBackground()
        radio.advance(5 * SECOND)
        assertEquals(SessionRole.HOST, bence.state.role)
        anna.kit.onForeground()
        radio.advance(60 * SECOND)

        assertNull(anna.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), annaSession.state.value.connection)
        assertEquals("the client role never searched for the game", 0, anna.clients.sumOf { it.connectCalls })
        assertEquals(listOf("p2", "p3"), connectedIds(bence))
        assertEquals("only Csaba is linked to the new host", 1, bence.host.links.size)
    }

    @Test
    fun `an app that leaves from inside the emission that ends a session stops everything`() {
        val anna = phone("Anna")
        val bence = phone("Bence")
        bence.host.canHost = false
        anna.kit.host()
        radio.advance(SHORT)
        val benceSession = bence.joinTheOnlyGame()
        benceSession.onEachState { if (it.connection is ConnectionState.Ended) bence.kit.leave() }

        // Nobody can take over: the game ends for Bence, and his app leaves in that very emission.
        anna.kit.leave()
        radio.advance(10 * SECOND)

        assertNull(bence.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), benceSession.state.value.connection)
        assertEquals(1, bence.clients.single().connectCalls)
        assertEquals(0, bence.host.startCount)
    }

    @Test
    fun `an app that leaves from inside the emission that starts a reconnect stops reconnecting`() {
        val (anna, bence, csaba) = lobbyOfThree()
        radio.advance(HEARTBEAT)
        val csabaSession = csaba.session
        var searchesWhenLeaving = -1
        csabaSession.onEachState {
            if (it.connection is ConnectionState.Reconnecting) {
                searchesWhenLeaving = csaba.clients.single().connectCalls
                csaba.kit.leave()
            }
        }

        anna.die()
        radio.advance(60 * SECOND)

        assertNull(csaba.kit.session.value)
        assertEquals(ConnectionState.Ended(EndReason.Left), csabaSession.state.value.connection)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(listOf("p2"), connectedIds(bence))
        assertTrue(bence.host.links.isEmpty())
        // The search that had begun was stopped by the leave, and none followed it.
        assertEquals("no search after leave", searchesWhenLeaving, csaba.clients.single().connectCalls)
        assertEquals(1, csaba.clients.size)
    }

    private companion object {
        const val SHORT = 50L
        const val SECOND = 1_000L
        const val HEARTBEAT = 3_500L
    }
}
