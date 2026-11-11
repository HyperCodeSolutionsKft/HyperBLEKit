package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.fake.CounterGame
import io.hypercode.hyperblekit.fake.FakeAwareAir
import io.hypercode.hyperblekit.fake.FakeDevice
import io.hypercode.hyperblekit.fake.FakeRadio
import io.hypercode.hyperblekit.log.HyperBleLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole games over the hybrid transports: BLE from [FakeRadio], Wi-Fi Aware from [FakeAwareAir].
 * The runtime, the sessions and the game are the real code; only the radios are fakes.
 */
class WifiAwareEndToEndTest {
    private val radio = FakeRadio()
    private val air = FakeAwareAir(radio)
    private val config = HyperBleKitConfig(games = listOf(CounterGame), dataPaths = DataPaths.PREFER_WIFI_AWARE)
    private val lines = mutableListOf<String>()

    init {
        HyperBleLog.sink = { lines += it.message }
    }

    @After
    fun tearDown() {
        radio.shutDown()
        HyperBleLog.sink = null
    }

    private fun logged(text: String) = lines.any { text in it }

    private fun awarePhone(name: String) = FakeDevice(radio, name, config, air)

    private fun blePhone(name: String) = FakeDevice(radio, name, HyperBleKitConfig(games = listOf(CounterGame)))

    private val FakeDevice.session: GameSession get() = checkNotNull(kit.session.value) { "no session" }
    private val FakeDevice.state: SessionState get() = session.state.value

    private fun FakeDevice.joinTheOnlyGame() {
        kit.startDiscovery()
        radio.advance(SHORT)
        kit.join(kit.discoveredGames.value.single())
        radio.advance(SECOND)
    }

    private fun count(device: FakeDevice): Int = checkNotNull(device.state.view).toJson().getInt("count")

    /** Plays the counter to its end, every player in turn, and checks every phone saw every step. */
    private fun playToTheEnd(vararg players: FakeDevice) {
        var turn = 0
        while (count(players[0]) < CounterGame.TARGET) {
            val before = count(players[0])
            players[turn % players.size].session.act(CounterGame.add())
            radio.advance(SHORT)
            for (player in players) assertEquals(player.address, before + 1, count(player))
            turn += 1
        }
    }

    @Test
    fun `two phones with Wi-Fi Aware move their messages to it and play a game over it`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()

        assertTrue(logged("PATH up over Wi-Fi Aware"))
        assertTrue(logged("PATH L1: up over Wi-Fi Aware"))
        assertEquals(ConnectionState.Joined, bence.state.connection)

        val carriedBefore = air.messagesCarried
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        playToTheEnd(anna, bence)
        assertTrue("game messages went over Wi-Fi Aware", air.messagesCarried > carriedBefore + CounterGame.TARGET)
    }

    @Test
    fun `real-time frames travel over Wi-Fi Aware too`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()

        anna.session.diagnostics.startSpeedTest(frameRate = 15, payloadSize = 64)
        radio.advance(2 * SECOND)
        assertTrue(air.framesCarried > 10)
        assertNotNull(bence.session.diagnostics.clientSpeedTest.value)
    }

    @Test
    fun `a phone without Wi-Fi Aware plays in the same game over BLE`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        val csaba = blePhone("Csaba")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        csaba.joinTheOnlyGame()

        assertEquals(1, anna.awareHost?.offers)
        assertEquals(listOf("p1", "p2", "p3"), csaba.state.players.map { it.id })
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        playToTheEnd(anna, bence, csaba)
    }

    @Test
    fun `a host without Wi-Fi Aware takes Wi-Fi Aware clients over BLE`() {
        val anna = blePhone("Anna")
        val bence = awarePhone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()

        assertTrue(lines.none { "PATH" in it })
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        playToTheEnd(anna, bence)
        assertEquals(0, air.messagesCarried)
    }

    @Test
    fun `when Wi-Fi Aware breaks in the middle of a game the game goes on over BLE`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        anna.session.act(CounterGame.add())
        radio.advance(SHORT)
        assertEquals(1, count(bence))

        air.isAvailable = false
        air.breakAll()
        radio.advance(SHORT)
        assertTrue(logged("PATH fallback to BLE (Wi-Fi Aware unavailable)"))

        val carried = air.messagesCarried
        bence.session.act(CounterGame.add())
        radio.advance(SHORT)
        assertEquals(2, count(anna))
        assertEquals(2, count(bence))
        assertEquals(carried, air.messagesCarried)
        assertEquals(ConnectionState.Joined, bence.state.connection)
        assertTrue(anna.state.players.all { it.connected })
    }

    @Test
    fun `a client the host has no data path for stays on BLE and plays`() {
        val anna = FakeDevice(radio, "Anna", config, air)
        val bence = awarePhone("Bence")
        val csaba = awarePhone("Csaba")
        anna.kit.host()
        radio.advance(SHORT)
        // The fake host path allows 4 by default; take all but one away by asking with others.
        repeat(3) { awarePhone("Extra$it").joinTheOnlyGameOf(anna) }
        bence.joinTheOnlyGameOf(anna)
        csaba.joinTheOnlyGameOf(anna)

        assertTrue(logged("Wi-Fi Aware declined (no_free_data_path)"))
        assertEquals(ConnectionState.Joined, csaba.state.connection)
    }

    private fun FakeDevice.joinTheOnlyGameOf(host: FakeDevice) {
        kit.startDiscovery()
        radio.advance(SHORT)
        kit.join(kit.discoveredGames.value.single { it.gameCode == host.session.gameCode })
        radio.advance(SECOND)
    }

    @Test
    fun `a client declined because Wi-Fi Aware was not ready asks again and gets it`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        air.isAvailable = false
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        assertTrue(logged("PATH host declined Wi-Fi Aware (unavailable), asking again"))

        air.isAvailable = true
        radio.advance(11 * SECOND)
        assertTrue(logged("PATH up over Wi-Fi Aware"))
        assertEquals(ConnectionState.Joined, bence.state.connection)
    }

    @Test
    fun `a client that leaves over Wi-Fi Aware is gone from the host's lobby`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        assertEquals(2, anna.state.players.size)

        bence.kit.leave()
        radio.advance(SECOND)
        assertEquals(listOf("p1"), anna.state.players.map { it.id })
    }

    @Test
    fun `when the host leaves the successor takes over and upgrades the others again`() {
        val anna = awarePhone("Anna")
        val bence = awarePhone("Bence")
        val csaba = awarePhone("Csaba")
        anna.kit.host()
        radio.advance(SHORT)
        bence.joinTheOnlyGame()
        csaba.joinTheOnlyGame()
        anna.session.startGame(CounterGame.id)
        radio.advance(SHORT)
        anna.session.act(CounterGame.add())
        radio.advance(SECOND)

        anna.kit.leave()
        radio.advance(5 * SECOND)
        assertEquals(SessionRole.HOST, bence.state.role)
        assertEquals(ConnectionState.Joined, csaba.state.connection)
        assertEquals(1, bence.awareHost?.offers)

        val carried = air.messagesCarried
        bence.session.act(CounterGame.add())
        radio.advance(SHORT)
        assertEquals(2, count(csaba))
        assertTrue(air.messagesCarried > carried)
    }

    private companion object {
        const val SHORT = 50L
        const val SECOND = 1_000L
    }
}
