package io.hypercode.hyperblekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIdentityTest {
    private val values = HashMap<String, String>()
    private val store = object : IdentityStore {
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun `the device id is made once and kept`() {
        val first = LocalIdentity(store).deviceId

        assertTrue(Regex("[0-9a-f]{32}").matches(first))
        assertEquals(first, LocalIdentity(store).deviceId)
    }

    @Test
    fun `without a typed name the default name is used`() {
        val identity = LocalIdentity(store)

        assertEquals("", identity.storedPlayerName)
        assertEquals("And-" + identity.deviceId.take(4).uppercase(), identity.defaultPlayerName)
        assertEquals(identity.defaultPlayerName, identity.playerName)
        identity.storedPlayerName = "   "
        assertEquals(identity.defaultPlayerName, identity.playerName)
    }

    @Test
    fun `a typed name is stored, trimmed for use and cut to the longest name`() {
        val identity = LocalIdentity(store)

        identity.storedPlayerName = " Anna "
        assertEquals(" Anna ", identity.storedPlayerName)
        assertEquals("Anna", identity.playerName)
        assertEquals("Anna", LocalIdentity(store).playerName)

        assertEquals(12, identity.maxNameLength)
        identity.storedPlayerName = "A very long player name"
        assertEquals("A very long ", identity.storedPlayerName)
        // Cut by characters, not by UTF-16 units: an emoji is never split.
        identity.storedPlayerName = "😀".repeat(20)
        assertEquals("😀".repeat(12), identity.storedPlayerName)
    }
}
