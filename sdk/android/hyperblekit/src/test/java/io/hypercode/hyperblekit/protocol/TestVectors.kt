package io.hypercode.hyperblekit.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads the language-neutral vectors from `/protocol/test-vectors`, shared with the iOS library.
 * The working directory of a Gradle unit test run is not fixed, so walk up from it until the
 * `protocol/test-vectors` directory is found.
 */
object TestVectors {

    fun load(fileName: String): JSONObject {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(8) {
            val candidate = dir?.resolve("protocol/test-vectors/$fileName")
            if (candidate != null && candidate.isFile) {
                return JSONObject(candidate.readText(Charsets.UTF_8))
            }
            dir = dir?.parentFile
        }
        error("could not find protocol/test-vectors/$fileName above ${System.getProperty("user.dir")}")
    }
}

/** A JSON array of integers 0-255 as the raw bytes it represents. */
fun JSONArray.toByteArray(): ByteArray = ByteArray(length()) { getInt(it).toByte() }

/**
 * Deep structural comparison of two parsed JSON values. Object key order does not matter; array
 * order does. Avoids [JSONObject.similar], which is not on every `org.json` on the test classpath.
 */
fun jsonStructurallyEqual(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject ->
        a.length() == b.length() &&
            a.keys().asSequence().all { b.has(it) && jsonStructurallyEqual(a.get(it), b.get(it)) }
    a is JSONArray && b is JSONArray ->
        a.length() == b.length() &&
            (0 until a.length()).all { jsonStructurallyEqual(a.get(it), b.get(it)) }
    else -> a == b || a?.toString() == b?.toString()
}
