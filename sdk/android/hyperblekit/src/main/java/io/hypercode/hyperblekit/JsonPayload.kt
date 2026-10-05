package io.hypercode.hyperblekit

import org.json.JSONArray
import org.json.JSONObject

/**
 * A JSON object the kit carries without reading it: a game's view of its state
 * ([SessionState.view]), in whatever format that game's module defines.
 *
 * It is immutable and compares by content: two payloads are equal exactly when their JSON is
 * structurally equal (the order of keys does not matter, the order of array elements does). That
 * makes [SessionState] comparable, which `org.json.JSONObject` alone would not.
 *
 * @param json the object to hold. It is copied; changing it afterwards does not change the payload.
 */
public class JsonPayload(json: JSONObject) {
    private val canonical: String = StringBuilder().also { write(json, it) }.toString()

    /** The content as a new `JSONObject` on every call; changing it does not change the payload. Parse once and keep the result if it is read often. */
    public fun toJson(): JSONObject = JSONObject(canonical)

    /** True if [other] is a payload with structurally equal JSON. */
    override fun equals(other: Any?): Boolean = other is JsonPayload && other.canonical == canonical

    /** A hash of the content, consistent with [equals]. */
    override fun hashCode(): Int = canonical.hashCode()

    /** The content as compact JSON text with the keys of every object sorted. */
    override fun toString(): String = canonical

    private companion object {
        fun write(value: Any?, out: StringBuilder) {
            when (value) {
                is JSONObject -> {
                    out.append('{')
                    value.keys().asSequence().sorted().forEachIndexed { index, key ->
                        if (index > 0) out.append(',')
                        out.append(JSONObject.quote(key)).append(':')
                        write(value.get(key), out)
                    }
                    out.append('}')
                }
                is JSONArray -> {
                    out.append('[')
                    for (index in 0 until value.length()) {
                        if (index > 0) out.append(',')
                        write(value.get(index), out)
                    }
                    out.append(']')
                }
                null, JSONObject.NULL -> out.append("null")
                is Boolean -> out.append(value)
                is Number -> out.append(JSONObject.numberToString(value))
                else -> out.append(JSONObject.quote(value.toString()))
            }
        }
    }
}
