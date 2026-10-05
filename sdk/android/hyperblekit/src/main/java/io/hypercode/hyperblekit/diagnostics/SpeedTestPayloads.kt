package io.hypercode.hyperblekit.diagnostics

/**
 * `0x01` world (host → client): the dot and the echo of the client's last input, padded to the
 * size chosen on the host. 32-bit fields are unsigned and kept in a [Long].
 */
internal data class WorldFrame(
    /** Host tick counter, 30 a second, wrapping at 2³². */
    val tick: Long,
    /** Host monotonic clock in ms, wrapping at 2³². */
    val hostMillis: Long,
    val x: Int,
    val y: Int,
    /** `inputSeq` of the last input taken from this client, 0 before any. */
    val echoInputSeq: Int,
    /** `clientMillis` of that input. */
    val echoClientMillis: Long,
    /** Ms between taking that input and building this frame, capped at 65535. */
    val holdMillis: Int,
)

/** `0x02` input (client → host). */
internal data class InputFrame(
    /** +1 per input sent, wrapping at 256. */
    val inputSeq: Int,
    /** Client monotonic clock in ms, wrapping at 2³². */
    val clientMillis: Long,
    /** Taps on the client's field so far, wrapping at 256. */
    val taps: Int,
)

/**
 * Encodes and decodes the speed-test payloads: big-endian, unsigned, the first byte is the kind.
 * The layout must stay the same on Android and iOS. Pure Kotlin.
 */
internal object SpeedTestPayloads {
    const val KIND_WORLD = 0x01
    const val KIND_INPUT = 0x02

    /** Bytes of a world payload before its padding. */
    const val WORLD_FIELDS_SIZE = 20
    const val INPUT_SIZE = 7

    const val MAX_HOLD_MILLIS = 0xFFFF
    private const val U8 = 0xFF
    private const val U32 = 0xFFFF_FFFFL

    /** A monotonic clock reading as it goes on the wire: its low 32 bits. */
    fun u32(millis: Long): Long = millis and U32

    /** The payload's kind, or null for an empty payload. */
    fun kind(payload: ByteArray): Int? = payload.firstOrNull()?.let { it.toInt() and U8 }

    /** The world frame padded with zeros to [size] bytes (at least [WORLD_FIELDS_SIZE]). */
    fun encodeWorld(world: WorldFrame, size: Int): ByteArray {
        val bytes = ByteArray(maxOf(size, WORLD_FIELDS_SIZE))
        bytes[0] = KIND_WORLD.toByte()
        putU32(bytes, 1, world.tick)
        putU32(bytes, 5, world.hostMillis)
        putU16(bytes, 9, world.x)
        putU16(bytes, 11, world.y)
        bytes[13] = world.echoInputSeq.toByte()
        putU32(bytes, 14, world.echoClientMillis)
        putU16(bytes, 18, world.holdMillis.coerceIn(0, MAX_HOLD_MILLIS))
        return bytes
    }

    /** Null unless [payload] is a world frame. Padding is not checked. */
    fun decodeWorld(payload: ByteArray): WorldFrame? {
        if (payload.size < WORLD_FIELDS_SIZE || kind(payload) != KIND_WORLD) return null
        return WorldFrame(
            tick = u32At(payload, 1),
            hostMillis = u32At(payload, 5),
            x = u16At(payload, 9),
            y = u16At(payload, 11),
            echoInputSeq = payload[13].toInt() and U8,
            echoClientMillis = u32At(payload, 14),
            holdMillis = u16At(payload, 18),
        )
    }

    fun encodeInput(input: InputFrame): ByteArray {
        val bytes = ByteArray(INPUT_SIZE)
        bytes[0] = KIND_INPUT.toByte()
        bytes[1] = input.inputSeq.toByte()
        putU32(bytes, 2, input.clientMillis)
        bytes[6] = input.taps.toByte()
        return bytes
    }

    /** Null unless [payload] is an input frame. */
    fun decodeInput(payload: ByteArray): InputFrame? {
        if (payload.size < INPUT_SIZE || kind(payload) != KIND_INPUT) return null
        return InputFrame(
            inputSeq = payload[1].toInt() and U8,
            clientMillis = u32At(payload, 2),
            taps = payload[6].toInt() and U8,
        )
    }

    /** Input round trip on the client: `now − echoClientMillis − holdMillis`, mod 2³². */
    fun roundTripMillis(nowMillis: Long, echoClientMillis: Long, holdMillis: Int): Long =
        (nowMillis - echoClientMillis - holdMillis) and U32

    private fun putU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value shr 8).toByte()
        bytes[at + 1] = value.toByte()
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = (value shr (8 * (3 - i))).toByte()
    }

    private fun u16At(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and U8) shl 8) or (bytes[at + 1].toInt() and U8)

    private fun u32At(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and U8.toLong())
        return value
    }
}
