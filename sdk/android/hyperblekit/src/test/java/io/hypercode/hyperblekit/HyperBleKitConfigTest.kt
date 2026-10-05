package io.hypercode.hyperblekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class HyperBleKitConfigTest {

    @Test
    fun `the default service UUID gives the wire specification's characteristic UUIDs`() {
        val config = HyperBleKitConfig(games = emptyList())

        assertEquals(UUID.fromString("6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E"), config.serviceUuid)
        assertEquals(UUID.fromString("6E4A0002-B5A3-F393-E0A9-E50E24DCCA9E"), config.gameInfoUuid)
        assertEquals(UUID.fromString("6E4A0003-B5A3-F393-E0A9-E50E24DCCA9E"), config.clientToHostUuid)
        assertEquals(UUID.fromString("6E4A0004-B5A3-F393-E0A9-E50E24DCCA9E"), config.hostToClientUuid)
    }

    @Test
    fun `the defaults are the values of the wire spec`() {
        val config = HyperBleKitConfig(games = emptyList())

        assertEquals("CG-", config.localNamePrefix)
        assertEquals(0xFFFF, config.manufacturerId)
        assertEquals(10, config.maxPlayers)
        assertEquals(500, config.logCapacity)
    }

    @Test
    fun `characteristic UUIDs follow a custom service UUID and only its first field changes`() {
        val config = HyperBleKitConfig(
            games = emptyList(),
            serviceUuid = UUID.fromString("12345678-9ABC-DEF0-1234-56789ABCDEF0"),
        )

        assertEquals(UUID.fromString("12345679-9ABC-DEF0-1234-56789ABCDEF0"), config.gameInfoUuid)
        assertEquals(UUID.fromString("1234567A-9ABC-DEF0-1234-56789ABCDEF0"), config.clientToHostUuid)
        assertEquals(UUID.fromString("1234567B-9ABC-DEF0-1234-56789ABCDEF0"), config.hostToClientUuid)
    }

    @Test
    fun `the first field wraps around instead of carrying into the next one`() {
        val config = HyperBleKitConfig(
            games = emptyList(),
            serviceUuid = UUID.fromString("FFFFFFFE-0000-1000-8000-00805F9B34FB"),
        )

        assertEquals(UUID.fromString("FFFFFFFF-0000-1000-8000-00805F9B34FB"), config.gameInfoUuid)
        assertEquals(UUID.fromString("00000000-0000-1000-8000-00805F9B34FB"), config.clientToHostUuid)
        assertEquals(UUID.fromString("00000001-0000-1000-8000-00805F9B34FB"), config.hostToClientUuid)
    }

    @Test
    fun `values outside their range are refused when the config is created`() {
        fun refused(create: () -> HyperBleKitConfig) {
            assertThrows(IllegalArgumentException::class.java) { create() }
        }
        refused { HyperBleKitConfig(games = emptyList(), maxPlayers = 1) }
        refused { HyperBleKitConfig(games = emptyList(), maxPlayers = 11) }
        refused { HyperBleKitConfig(games = emptyList(), logCapacity = 0) }
        refused { HyperBleKitConfig(games = emptyList(), localNamePrefix = "") }
        refused { HyperBleKitConfig(games = emptyList(), localNamePrefix = "GAME-") }
        refused { HyperBleKitConfig(games = emptyList(), localNamePrefix = "Já-") }
        refused { HyperBleKitConfig(games = emptyList(), manufacturerId = -1) }
        refused { HyperBleKitConfig(games = emptyList(), manufacturerId = 0x10000) }
    }

    @Test
    fun `the limits themselves are accepted`() {
        val small = HyperBleKitConfig(games = emptyList(), maxPlayers = 2, logCapacity = 1, localNamePrefix = "X", manufacturerId = 0)
        val large = HyperBleKitConfig(games = emptyList(), maxPlayers = 10, localNamePrefix = "ABC-", manufacturerId = 0xFFFF)

        assertEquals(2, small.maxPlayers)
        assertEquals("ABC-", large.localNamePrefix)
    }

    @Test
    fun `the list of games is copied and looked up by id`() {
        val games = mutableListOf<io.hypercode.hyperblekit.game.GameModule>(io.hypercode.hyperblekit.fake.CounterGame)
        val config = HyperBleKitConfig(games)
        games.clear()

        assertEquals(listOf("counter"), config.games.map { it.id })
        assertEquals(io.hypercode.hyperblekit.fake.CounterGame, config.findGame("counter"))
        assertEquals(null, config.findGame("chess"))
    }
}
