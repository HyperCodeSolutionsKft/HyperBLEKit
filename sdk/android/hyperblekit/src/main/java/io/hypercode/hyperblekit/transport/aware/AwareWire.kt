package io.hypercode.hyperblekit.transport.aware

import java.io.ByteArrayOutputStream

/**
 * What travels for Wi-Fi Aware (wire specification, "Wi-Fi Aware data path"). Pure Kotlin, no
 * Android types: the same bytes are checked against `protocol/test-vectors/wifi-aware.json` on
 * both platforms.
 */
internal object AwareWire {
    /** Version of everything in this file; a receiver ignores what carries another one. */
    const val VERSION = 1

    /** The first byte of a transport-control message. A JSON message never starts with it. */
    const val CONTROL_MARKER = 0x00

    /** Length of a host's instance ID and of a link token, in bytes. */
    const val ID_LENGTH = 8

    /** Length of a data-path passphrase, in characters (Wi-Fi Aware allows 8 to 63). */
    const val PASSPHRASE_LENGTH = 32

    /** The first byte of the discovery message a client sends to a host over Wi-Fi Aware: 'H'. */
    const val DISCOVERY_HELLO_MARKER = 0x48

    /** The GameInfo field an Aware-capable host adds: `"aware":1` (the value is [VERSION]). */
    const val GAME_INFO_FIELD = "aware"

    /** The GameInfo field of a host that also takes clients by the paired (iPhone) scheme. */
    const val GAME_INFO_PAIRED_FIELD = "awarePaired"

    /**
     * [info] (compact JSON, as the runtime encodes GameInfo) with `"aware":1` added as its last
     * field, and `"awarePaired":1` after it if [paired]. Clients without Wi-Fi Aware ignore them,
     * as they ignore every unknown field.
     */
    fun withAwareFlag(info: ByteArray, paired: Boolean = false): ByteArray {
        val text = String(info, Charsets.UTF_8)
        if (!text.endsWith("}")) return info
        val separator = if (text.dropLast(1).trimEnd().endsWith("{")) "" else ","
        val pairedField = if (paired) ",\"$GAME_INFO_PAIRED_FIELD\":$VERSION" else ""
        return (text.dropLast(1) + "$separator\"$GAME_INFO_FIELD\":$VERSION$pairedField}").toByteArray(Charsets.UTF_8)
    }

    /** Whether a host's GameInfo says it can take clients over Wi-Fi Aware (this [VERSION]). */
    fun hasAwareFlag(info: ByteArray): Boolean =
        try {
            org.json.JSONObject(String(info, Charsets.UTF_8)).optInt(GAME_INFO_FIELD, 0) == VERSION
        } catch (_: org.json.JSONException) {
            false
        }

    /**
     * How a client on [platform] (`"android"` / `"ios"`) reaches this host over Wi-Fi Aware, or
     * null if it should not ask: a host on the same platform by that platform's own scheme; a
     * host on the other platform only by the paired scheme, only if the client allows
     * [crossPlatform] and the host announces `"awarePaired":1`.
     */
    fun schemeFor(info: ByteArray, platform: String, crossPlatform: Boolean): AwareScheme? {
        val json = try {
            org.json.JSONObject(String(info, Charsets.UTF_8))
        } catch (_: org.json.JSONException) {
            return null
        }
        if (json.optInt(GAME_INFO_FIELD, 0) != VERSION) return null
        val hostPlatform = json.optString("hostPlatform")
        return when {
            hostPlatform == platform -> AwareScheme.native(platform)
            crossPlatform && json.optInt(GAME_INFO_PAIRED_FIELD, 0) == VERSION -> AwareScheme.PAIRED
            else -> null
        }
    }

    /** The `hostPlatform` value of this build in GameInfo. */
    const val THIS_PLATFORM = "android"

    /** Whether a message received over BLE is a transport-control message, not a protocol one. */
    fun isControl(message: ByteArray): Boolean = message.isNotEmpty() && message[0].toInt() == CONTROL_MARKER

    /** A host's publish `serviceSpecificInfo`: `[version][instanceId]`. */
    fun serviceInfo(instanceId: ByteArray): ByteArray = byteArrayOf(VERSION.toByte()) + instanceId

    /** The instance ID in a publish `serviceSpecificInfo`, or null if it is not ours. */
    fun parseServiceInfo(bytes: ByteArray?): ByteArray? =
        if (bytes != null && bytes.size == 1 + ID_LENGTH && bytes[0].toInt() == VERSION) bytes.copyOfRange(1, bytes.size) else null

    /** The discovery message from a client: `['H'][version][token]`. */
    fun discoveryHello(token: ByteArray): ByteArray = byteArrayOf(DISCOVERY_HELLO_MARKER.toByte(), VERSION.toByte()) + token

    /** The token in a discovery message, or null if it is not one. */
    fun parseDiscoveryHello(bytes: ByteArray?): ByteArray? =
        if (bytes != null && bytes.size == 2 + ID_LENGTH && bytes[0].toInt() == DISCOVERY_HELLO_MARKER && bytes[1].toInt() == VERSION) {
            bytes.copyOfRange(2, bytes.size)
        } else {
            null
        }
}

/** How a client finds its host's data path (wire specification, "Wi-Fi Aware data path"). */
internal enum class AwareScheme {
    /** Android ↔ Android: instance ID, NAN discovery message, passphrase-secured data path. */
    NAN,

    /** With an iPhone: paired devices only, the host picked by the echo of the token. */
    PAIRED,
    ;

    companion object {
        /** The scheme two phones of [platform] use with each other. */
        fun native(platform: String): AwareScheme = if (platform == "ios") PAIRED else NAN
    }
}

/** Which path the messages of one direction of a link travel on. */
internal enum class DataPath(val code: Int) {
    BLE(0),
    WIFI_AWARE(1),
    ;

    companion object {
        fun fromCode(code: Int): DataPath? = entries.firstOrNull { it.code == code }
    }
}

/** Why a host declines a client's [AwareControl.Hello]. */
internal enum class DeclineReason(val code: Int) {
    /** The host cannot use Wi-Fi Aware right now (off, not published yet, or not allowed). */
    UNAVAILABLE(1),

    /** Every data path the host's chip supports is taken. */
    NO_FREE_DATA_PATH(2),
    ;

    companion object {
        fun fromCode(code: Int): DeclineReason = entries.firstOrNull { it.code == code } ?: UNAVAILABLE
    }
}

/** What a client hands the host so the data path can be set up: see [AwareControl.Offer]. */
internal class AwareOffer(
    /** Tells this host's publication apart from other hosts' nearby. */
    val instanceId: ByteArray,
    /** Ties the Wi-Fi Aware connection to the BLE link the offer went over. */
    val token: ByteArray,
    /** The data path's PSK passphrase. */
    val passphrase: String,
)

/**
 * Transport-control messages: sent as ordinary (chunked) messages over BLE, told apart from
 * protocol messages by their first byte [AwareWire.CONTROL_MARKER]. Layout: `[0x00][type][body]`.
 *
 * Only a peer that announced Wi-Fi Aware support ever receives one, so older builds never see
 * them.
 */
internal sealed interface AwareControl {
    /** Client → host: "I can use Wi-Fi Aware". `[0x00][0x01][version]` */
    data class Hello(val version: Int = AwareWire.VERSION) : AwareControl

    /**
     * Host → client: how to reach the host over Wi-Fi Aware.
     * `[0x00][0x02][version][instanceId: 8][token: 8][passLen: 1][passphrase: UTF-8]`
     */
    class Offer(val offer: AwareOffer) : AwareControl

    /** Host → client: no data path for this link. `[0x00][0x03][reason]` */
    data class Decline(val reason: DeclineReason) : AwareControl

    /** Either way: "the messages I send after this one travel on [path]". `[0x00][0x04][path]` */
    data class PathSwitch(val path: DataPath) : AwareControl

    /** Client → host: setting the data path up failed, release it. `[0x00][0x05]` */
    data object Failed : AwareControl

    companion object {
        const val HELLO = 0x01
        const val OFFER = 0x02
        const val DECLINE = 0x03
        const val PATH_SWITCH = 0x04
        const val FAILED = 0x05

        fun encode(control: AwareControl): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(AwareWire.CONTROL_MARKER)
            when (control) {
                is Hello -> {
                    out.write(HELLO)
                    out.write(control.version)
                }
                is Offer -> {
                    val offer = control.offer
                    require(offer.instanceId.size == AwareWire.ID_LENGTH && offer.token.size == AwareWire.ID_LENGTH)
                    val passphrase = offer.passphrase.toByteArray(Charsets.UTF_8)
                    require(passphrase.size <= MAX_BYTE)
                    out.write(OFFER)
                    out.write(AwareWire.VERSION)
                    out.write(offer.instanceId)
                    out.write(offer.token)
                    out.write(passphrase.size)
                    out.write(passphrase)
                }
                is Decline -> {
                    out.write(DECLINE)
                    out.write(control.reason.code)
                }
                is PathSwitch -> {
                    out.write(PATH_SWITCH)
                    out.write(control.path.code)
                }
                Failed -> out.write(FAILED)
            }
            return out.toByteArray()
        }

        /** The control message in [bytes], or null if it is malformed, of an unknown type or version. */
        fun decode(bytes: ByteArray): AwareControl? {
            if (bytes.size < 2 || bytes[0].toInt() != AwareWire.CONTROL_MARKER) return null
            val body = bytes.copyOfRange(2, bytes.size)
            return when (bytes[1].toInt() and MAX_BYTE) {
                HELLO -> body.singleOrNull()?.let { Hello(it.toInt() and MAX_BYTE) }
                OFFER -> decodeOffer(body)
                DECLINE -> body.singleOrNull()?.let { Decline(DeclineReason.fromCode(it.toInt() and MAX_BYTE)) }
                PATH_SWITCH -> body.singleOrNull()?.let { code -> DataPath.fromCode(code.toInt() and MAX_BYTE)?.let(::PathSwitch) }
                FAILED -> if (body.isEmpty()) Failed else null
                else -> null
            }
        }

        private fun decodeOffer(body: ByteArray): Offer? {
            val idsEnd = 1 + 2 * AwareWire.ID_LENGTH
            if (body.size < idsEnd + 1 || body[0].toInt() != AwareWire.VERSION) return null
            val passLength = body[idsEnd].toInt() and MAX_BYTE
            if (body.size != idsEnd + 1 + passLength) return null
            return Offer(
                AwareOffer(
                    instanceId = body.copyOfRange(1, 1 + AwareWire.ID_LENGTH),
                    token = body.copyOfRange(1 + AwareWire.ID_LENGTH, idsEnd),
                    passphrase = String(body, idsEnd + 1, passLength, Charsets.UTF_8),
                )
            )
        }

        private const val MAX_BYTE = 0xFF
    }
}

/**
 * Records on the Wi-Fi Aware TCP stream: `[length: 4 bytes, big-endian][kind: 1][body]`, where
 * `length` counts the kind byte and the body. No chunking: TCP keeps order and loses nothing
 * while the connection lives.
 */
internal object AwareStream {
    /**
     * Client → host, the first record: `body` = the link token. The host answers with the same
     * record once it has tied the connection to its link.
     */
    const val HELLO = 0x01

    /** A whole protocol message. */
    const val MESSAGE = 0x02

    /** A real-time frame's payload (no `frameSeq`: TCP neither reorders nor duplicates). */
    const val FRAME = 0x03

    /** Nothing to say; keeps the connection's silence timeout from firing. Empty body. */
    const val KEEPALIVE = 0x04

    const val LENGTH_SIZE = 4

    /** Longest record (kind + body) a receiver accepts; a longer one closes the connection. */
    const val MAX_RECORD_LENGTH = 256 * 1024

    /** A side that has sent nothing for this long sends a [KEEPALIVE]. */
    const val KEEPALIVE_INTERVAL_MILLIS = 2_000L

    /** A connection that received nothing for this long is dead. */
    const val SILENCE_TIMEOUT_MILLIS = 6_000L

    fun encode(kind: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val length = 1 + body.size
        require(length <= MAX_RECORD_LENGTH) { "record of $length bytes is too long" }
        val record = ByteArray(LENGTH_SIZE + length)
        record[0] = (length ushr 24).toByte()
        record[1] = (length ushr 16).toByte()
        record[2] = (length ushr 8).toByte()
        record[3] = length.toByte()
        record[4] = kind.toByte()
        body.copyInto(record, LENGTH_SIZE + 1)
        return record
    }
}

/** One record read from the stream. */
internal class AwareRecord(val kind: Int, val body: ByteArray)

/** The stream carried something that is not a record: the connection must be closed. */
internal class AwareStreamException(message: String) : Exception(message)

/**
 * Cuts the incoming byte stream into records. Feed it whatever a read returned; it keeps a
 * partial record for the next call. Not thread-safe: one reader thread owns it.
 */
internal class AwareStreamDecoder {
    private var buffer = ByteArray(0)

    /** The records completed by [bytes], oldest first. */
    fun feed(bytes: ByteArray, count: Int = bytes.size): List<AwareRecord> {
        buffer += bytes.copyOf(count)
        val records = mutableListOf<AwareRecord>()
        var offset = 0
        while (buffer.size - offset >= AwareStream.LENGTH_SIZE) {
            val length = ((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
            if (length < 1 || length > AwareStream.MAX_RECORD_LENGTH) {
                throw AwareStreamException("bad record length $length")
            }
            if (buffer.size - offset - AwareStream.LENGTH_SIZE < length) break
            val start = offset + AwareStream.LENGTH_SIZE
            records += AwareRecord(buffer[start].toInt() and 0xFF, buffer.copyOfRange(start + 1, start + length))
            offset = start + length
        }
        buffer = buffer.copyOfRange(offset, buffer.size)
        return records
    }
}
