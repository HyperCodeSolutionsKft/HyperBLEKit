package io.hypercode.hyperblekit

import kotlin.random.Random

/**
 * Generation and parsing of the short game code and the advertised local name.
 *
 * Pure and dependency-free so it can be unit-tested without a device.
 */
internal object GameCode {
    // Crockford-ish alphabet: no 0/O/1/I so codes are unambiguous when read aloud.
    private const val CHARSET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** Generates a fresh code such as "7K2F". Pass a seeded [random] in tests. */
    fun generate(length: Int = GameConstants.GAME_CODE_LENGTH, random: Random = Random): String =
        buildString { repeat(length) { append(CHARSET[random.nextInt(CHARSET.length)]) } }

    /** The advertised local name for a code, e.g. "CG-7K2F" (used by iOS hosts). */
    fun localName(gameCode: String, prefix: String = GameConstants.LOCAL_NAME_PREFIX): String = prefix + gameCode

    /** True if [code] has the exact 4-char shape and charset of a valid game code. */
    fun isValid(code: String): Boolean =
        code.length == GameConstants.GAME_CODE_LENGTH && code.all { it in CHARSET }

    /**
     * Extracts the game code from an advertised local name (the form an iOS host uses), or null if
     * [localName] is absent or does not match the "CG-XXXX" shape.
     */
    fun fromLocalName(localName: String?, prefix: String = GameConstants.LOCAL_NAME_PREFIX): String? {
        if (localName == null) return null
        if (!localName.startsWith(prefix)) return null
        val code = localName.removePrefix(prefix)
        return code.takeIf { isValid(it) }
    }

    /**
     * Extracts the game code from the raw manufacturer-data bytes an Android host advertises
     * (the 4 ASCII bytes of the code), or null if they don't decode to a valid code.
     */
    fun fromManufacturerData(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != GameConstants.GAME_CODE_LENGTH) return null
        val code = bytes.toString(Charsets.US_ASCII)
        return code.takeIf { isValid(it) }
    }
}
