package io.hypercode.hyperblekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameCodeTest {

    @Test
    fun `generate produces the requested length`() {
        repeat(50) {
            assertEquals(GameConstants.GAME_CODE_LENGTH, GameCode.generate().length)
        }
    }

    @Test
    fun `generate uses only the unambiguous charset`() {
        val forbidden = setOf('0', '1', 'I', 'O')
        val code = GameCode.generate(length = 1000, random = Random(42))
        assertTrue(code.none { it in forbidden })
        assertTrue(code.all { it.isDigit() || it.isUpperCase() })
    }

    @Test
    fun `generate is deterministic for a seeded random`() {
        assertEquals(
            GameCode.generate(random = Random(7)),
            GameCode.generate(random = Random(7)),
        )
    }

    @Test
    fun `localName prefixes the code`() {
        assertEquals("CG-7K2F", GameCode.localName("7K2F"))
    }

    @Test
    fun `fromLocalName round-trips a valid name`() {
        assertEquals("7K2F", GameCode.fromLocalName("CG-7K2F"))
    }

    @Test
    fun `fromLocalName rejects malformed names`() {
        assertNull(GameCode.fromLocalName(null))
        assertNull(GameCode.fromLocalName(""))
        assertNull(GameCode.fromLocalName("7K2F"))        // missing prefix
        assertNull(GameCode.fromLocalName("CG-7K2"))      // too short
        assertNull(GameCode.fromLocalName("CG-7K2FA"))    // too long
        assertNull(GameCode.fromLocalName("CG-7K2!"))     // illegal char
        assertNull(GameCode.fromLocalName("CG-7K2O"))     // ambiguous char not in charset
    }

    @Test
    fun `fromManufacturerData decodes the 4 ASCII bytes`() {
        assertEquals("7K2F", GameCode.fromManufacturerData("7K2F".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `fromManufacturerData rejects malformed bytes`() {
        assertNull(GameCode.fromManufacturerData(null))
        assertNull(GameCode.fromManufacturerData(ByteArray(0)))
        assertNull(GameCode.fromManufacturerData("7K2".toByteArray()))     // too short
        assertNull(GameCode.fromManufacturerData("7K2FA".toByteArray()))   // too long
        assertNull(GameCode.fromManufacturerData("7K2O".toByteArray()))    // ambiguous char
    }
}
