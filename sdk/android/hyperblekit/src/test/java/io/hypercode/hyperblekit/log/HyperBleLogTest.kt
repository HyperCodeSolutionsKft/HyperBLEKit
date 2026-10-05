package io.hypercode.hyperblekit.log

import io.hypercode.hyperblekit.GameConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HyperBleLogTest {

    @Before
    fun setUp() {
        HyperBleLog.clear()
    }

    @After
    fun tearDown() {
        HyperBleLog.sink = null
        HyperBleLog.capacity = GameConstants.DEBUG_LOG_CAPACITY
        HyperBleLog.clear()
    }

    @Test
    fun `the sink gets every line and the buffer keeps the newest`() {
        val seen = mutableListOf<LogEntry>()
        HyperBleLog.sink = { seen += it }
        HyperBleLog.capacity = 2

        HyperBleLog.event("one")
        HyperBleLog.event("two", LogLevel.WARN)
        HyperBleLog.log("BleScanner", "three", LogLevel.ERROR)

        assertEquals(listOf("-- one", "-- two", "three"), seen.map { it.message })
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), seen.map { it.level })
        assertEquals(listOf("-- two", "three"), HyperBleLog.entries.value.map { it.message })
        assertTrue(HyperBleLog.entries.value.last().line.endsWith("  BleScanner: three"))
    }

    @Test
    fun `a sink that throws does not break the caller and the line is still kept`() {
        HyperBleLog.sink = { error("the app's logger failed") }

        HyperBleLog.event("still logged")
        HyperBleLog.stats("RT rx fps=15")

        assertEquals(listOf("-- still logged", "RT rx fps=15"), HyperBleLog.entries.value.map { it.message })
    }

    @Test
    fun `a sink that throws an Error does not break the caller either`() {
        var calls = 0
        HyperBleLog.sink = {
            calls += 1
            if (calls == 1) throw NotImplementedError("the app's sink is not written yet") else throw AssertionError("a test sink asserted")
        }

        HyperBleLog.event("one")
        HyperBleLog.frame(LogDirection.SENT, "TEST_JSON", bytes = 480, chunks = 2, extra = "to=p2")

        assertEquals(2, calls)
        assertEquals(listOf("-- one", "TX TEST_JSON bytes=480 chunks=2 to=p2"), HyperBleLog.entries.value.map { it.message })
    }

    @Test
    fun `an error the process cannot continue after is not swallowed`() {
        HyperBleLog.sink = { throw OutOfMemoryError("thrown by the test") }
        assertThrows(OutOfMemoryError::class.java) { HyperBleLog.event("one") }

        HyperBleLog.sink = { throw NoClassDefFoundError("thrown by the test") }
        assertThrows(NoClassDefFoundError::class.java) { HyperBleLog.event("two") }
    }

    @Test
    fun `clear empties the buffer`() {
        HyperBleLog.event("one")
        HyperBleLog.clear()

        assertEquals(emptyList<LogEntry>(), HyperBleLog.entries.value)
    }
}
