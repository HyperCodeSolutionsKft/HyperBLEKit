package io.hypercode.hyperblekit.diagnostics

import org.json.JSONException
import org.json.JSONObject

/**
 * Debug-only "JSON send test": the wire specification's game-state snapshot, sent as a raw message over the
 * live link to check that a multi-chunk payload arrives byte for byte.
 *
 * The frame is **not** part of the protocol: it has no envelope and consumes no `seq`. It
 * is never applied to session state, never ACKed and never counted for dedup.
 */
internal object TestJson {
    /** What the debug log shows as the message type. */
    const val LOG_TYPE = "TEST_JSON"

    /**
     * The snapshot minified in the wire specification's key order. Sent exactly as written here — it must never be
     * re-serialized, because the receiver compares bytes.
     */
    const val PAYLOAD =
        "{\"gameId\":\"7K2F\",\"stateVersion\":12,\"phase\":\"playing\",\"players\":[{\"id\":\"p1\",\"name\":\"Host\",\"platform\":\"android\",\"connected\":true,\"handSize\":2},{\"id\":\"p2\",\"name\":\"Anna\",\"platform\":\"ios\",\"connected\":true,\"handSize\":2},{\"id\":\"p3\",\"name\":\"Bence\",\"platform\":\"android\",\"connected\":true,\"handSize\":4},{\"id\":\"p4\",\"name\":\"Dora\",\"platform\":\"ios\",\"connected\":true,\"handSize\":3}],\"turn\":{\"number\":4,\"playerId\":\"p4\"},\"topCard\":{\"id\":\"c21\",\"color\":\"blue\",\"value\":4},\"deckCount\":22}"

    /** The bytes on the wire. A fresh array on every call. */
    fun bytes(): ByteArray = PAYLOAD.toByteArray(Charsets.UTF_8)

    /** A test frame is a JSON object that has `gameId` and no `type` (so it is not an envelope). */
    fun isTestFrame(message: ByteArray): Boolean = try {
        val json = JSONObject(String(message, Charsets.UTF_8))
        json.has(KEY_GAME_ID) && !json.has(KEY_TYPE)
    } catch (_: JSONException) {
        false
    }

    /** Whether a received test frame is byte for byte what was sent. */
    fun matches(message: ByteArray): Boolean = message.contentEquals(bytes())

    /**
     * The received bytes as indented text for the screen. Works on the raw text, so keys stay in
     * the order they arrived in and nothing is re-serialized. Bytes that are not UTF-8 show up
     * as replacement characters.
     */
    fun pretty(message: ByteArray): String {
        val text = String(message, Charsets.UTF_8)
        val out = StringBuilder(text.length * 2)
        var depth = 0
        var inString = false
        var escaped = false
        fun newLine() {
            out.append('\n')
            repeat(depth) { out.append(INDENT) }
        }
        text.forEachIndexed { index, c ->
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (c) {
                '"' -> {
                    inString = true
                    out.append(c)
                }
                '{', '[' -> {
                    out.append(c)
                    val next = text.getOrNull(index + 1)
                    // An empty object / array stays on one line.
                    if (next != '}' && next != ']') {
                        depth += 1
                        newLine()
                    }
                }
                '}', ']' -> {
                    val previous = text.getOrNull(index - 1)
                    if (previous != '{' && previous != '[') {
                        depth = maxOf(0, depth - 1)
                        newLine()
                    }
                    out.append(c)
                }
                ',' -> {
                    out.append(c)
                    newLine()
                }
                ':' -> out.append(": ")
                ' ', '\n', '\r', '\t' -> Unit
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private const val INDENT = "  "
    private const val KEY_GAME_ID = "gameId"
    private const val KEY_TYPE = "type"
}
