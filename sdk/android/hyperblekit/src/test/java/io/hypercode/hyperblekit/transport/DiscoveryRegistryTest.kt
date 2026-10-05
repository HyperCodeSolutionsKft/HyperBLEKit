package io.hypercode.hyperblekit.transport

import io.hypercode.hyperblekit.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRegistryTest {

    private val staleMs = 10_000L

    private fun registry() = DiscoveryRegistry(staleMs)

    @Test
    fun `observe adds a new game`() {
        val snapshot = registry().observe("aa", "7K2F", Platform.UNKNOWN, rssi = -40, nowMillis = 0)
        assertEquals(1, snapshot.size)
        assertEquals("7K2F", snapshot[0].gameCode)
        assertEquals(0L, snapshot[0].firstSeenMillis)
        assertEquals(0L, snapshot[0].lastSeenMillis)
    }

    @Test
    fun `re-observe refreshes lastSeen but keeps firstSeen`() {
        val reg = registry()
        reg.observe("aa", "7K2F", Platform.UNKNOWN, rssi = -40, nowMillis = 0)
        val snapshot = reg.observe("aa", "7K2F", Platform.UNKNOWN, rssi = -50, nowMillis = 3_000)
        assertEquals(1, snapshot.size)
        assertEquals(0L, snapshot[0].firstSeenMillis)
        assertEquals(3_000L, snapshot[0].lastSeenMillis)
        assertEquals(-50, snapshot[0].rssi)
    }

    @Test
    fun `prune drops games past the stale timeout`() {
        val reg = registry()
        reg.observe("aa", "7K2F", Platform.UNKNOWN, rssi = -40, nowMillis = 0)
        assertTrue(reg.prune(nowMillis = staleMs).isNotEmpty())       // exactly at timeout: kept
        assertTrue(reg.prune(nowMillis = staleMs + 1).isEmpty())      // just past: dropped
    }

    @Test
    fun `snapshot is sorted by strongest signal first`() {
        val reg = registry()
        reg.observe("weak", "AAAA", Platform.UNKNOWN, rssi = -80, nowMillis = 0)
        reg.observe("strong", "BBBB", Platform.UNKNOWN, rssi = -30, nowMillis = 0)
        val snapshot = reg.observe("mid", "CCCC", Platform.UNKNOWN, rssi = -55, nowMillis = 0)
        assertEquals(listOf("BBBB", "CCCC", "AAAA"), snapshot.map { it.gameCode })
    }

    @Test
    fun `known platform is not overwritten by a later UNKNOWN`() {
        val reg = registry()
        reg.observe("aa", "7K2F", Platform.IOS, rssi = -40, nowMillis = 0)
        val snapshot = reg.observe("aa", "7K2F", Platform.UNKNOWN, rssi = -40, nowMillis = 1_000)
        assertEquals(Platform.IOS, snapshot[0].platform)
    }
}
