package io.hypercode.hyperblekit.log

import android.util.Log
import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.HyperBleKitConfig
import io.hypercode.hyperblekit.protocol.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How serious a log line is. */
public enum class LogLevel {
    /** Normal operation: a message sent or received, a state change. */
    INFO,

    /** Something went wrong and the kit is dealing with it: a lost link, a retry, a refused request. */
    WARN,

    /** Something failed for good: a game that ended, a takeover that could not happen. */
    ERROR,
}

/** Which way a logged message travelled. */
internal enum class LogDirection(val label: String) {
    SENT("TX"),
    RECEIVED("RX"),
}

/**
 * One line of the log. An app may construct entries itself, for previews and tests.
 *
 * @property timestampMillis when it was logged, as wall-clock milliseconds since the Unix epoch.
 * @property level how serious the line is.
 * @property tag the part of the Bluetooth layer the line comes from, e.g. "BleScanner". Empty for
 *   most lines: messages and session events have no tag.
 * @property message the text of the line without the time.
 */
public data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    /** The time of day as "HH:mm:ss.SSS" in the phone's time zone — the same on Android and iOS, so logs from different phones can be compared. */
    public val formattedTime: String
        get() = synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(timestampMillis)) }

    /** The whole line as a debug screen shows it: the time, then the tag if there is one, then the message. */
    public val line: String
        get() = if (tag.isEmpty()) "$formattedTime $message" else "$formattedTime  $tag: $message"

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Everything the kit does, as text: every message sent and received, every connection event,
 * every failure. Bluetooth cannot be tested without real phones, so this log is the main tool for
 * finding out what happened on each of them.
 *
 * There is one log per process, shared by everything the kit does. An app reads it in two ways:
 * [entries] is a bounded buffer of the newest lines, for a debug screen, and [sink] is a callback
 * that gets every line, for the app's own logging. Every line also goes to Logcat under the tag
 * "HyperBleKit".
 *
 * The lines have the same format on Android and iOS, so logs from different phones can be
 * compared. After the time (`HH:mm:ss.SSS`) a line is one of these:
 * - a message sent or received: `DIR TYPE seq=N sv=N bytes=N chunks=N [extra]`, where `DIR` is
 *   `TX` or `RX` and `sv=` is left out for messages without a state version;
 * - a JSON test frame: `DIR TEST_JSON bytes=N chunks=N …`, without `seq=`;
 * - once-a-second statistics of the real-time lane: `RT tx …` or `RT rx …`;
 * - a session or connection event: `-- ` followed by text;
 * - a line of the Bluetooth layer, which is the only kind with a [LogEntry.tag]:
 *   `tag: text`, e.g. `BleScanner: Scan started, …`.
 */
public object HyperBleLog {
    private const val TAG = "HyperBleKit"
    private const val EVENT_PREFIX = "-- "

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /**
     * The newest lines, oldest first. It holds at most [HyperBleKitConfig.logCapacity] lines (500
     * unless a kit was created with another value); older lines are dropped. May change from any
     * thread; collect it on the thread you need.
     */
    public val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /**
     * A callback that gets every line as it is logged, including lines [entries] has dropped
     * since. Null, the default, means none; set it once at app start.
     *
     * It is called on the thread that logged the line — mostly the main thread, sometimes a
     * Bluetooth callback thread — and before the kit goes on, so it must be quick and thread-safe.
     * Whatever it throws is caught and written to Logcat and never reaches the kit — except errors
     * the process cannot continue after (`VirtualMachineError` such as out of memory,
     * `LinkageError`), which are passed on.
     */
    @Volatile
    public var sink: ((LogEntry) -> Unit)? = null

    /** How many lines [entries] keeps; set from `HyperBleKitConfig.logCapacity`. */
    @Volatile
    internal var capacity: Int = GameConstants.DEBUG_LOG_CAPACITY

    internal fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        when (level) {
            LogLevel.INFO -> Log.i(TAG, "$tag: $message")
            LogLevel.WARN -> Log.w(TAG, "$tag: $message")
            LogLevel.ERROR -> Log.e(TAG, "$tag: $message")
        }
        append(LogEntry(System.currentTimeMillis(), level, tag, message))
    }

    /** One line per sent or received message. `sv=` is left out when the message has none. */
    internal fun message(direction: LogDirection, message: Message, bytes: Int, chunks: Int, extra: String? = null) {
        val parts = mutableListOf(direction.label, message.type, "seq=${message.seq}")
        message.stateVersion?.let { parts += "sv=$it" }
        parts += "bytes=$bytes"
        parts += "chunks=$chunks"
        message.logDetail?.let { parts += it }
        extra?.let { parts += it }
        val text = parts.joinToString(" ")
        Log.i(TAG, text)
        append(LogEntry(System.currentTimeMillis(), LogLevel.INFO, tag = "", message = text))
    }

    /**
     * One line for a frame that has no envelope (the debug JSON test), in the same format as
     * [message] but without `seq=` and `sv=`.
     */
    internal fun frame(direction: LogDirection, type: String, bytes: Int, chunks: Int, extra: String) {
        val text = "${direction.label} $type bytes=$bytes chunks=$chunks $extra"
        Log.i(TAG, text)
        append(LogEntry(System.currentTimeMillis(), LogLevel.INFO, tag = "", message = text))
    }

    /**
     * A once-a-second line of the real-time lane, as is, e.g. `RT rx fps=15 stale=0 ...`: frames
     * are never logged one by one, only counted.
     */
    internal fun stats(text: String) {
        Log.i(TAG, text)
        append(LogEntry(System.currentTimeMillis(), LogLevel.INFO, tag = "", message = text))
    }

    /** Anything that is not a message: connection events, drops, failures. */
    internal fun event(text: String, level: LogLevel = LogLevel.INFO) {
        val line = EVENT_PREFIX + text
        when (level) {
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR -> Log.e(TAG, line)
        }
        append(LogEntry(System.currentTimeMillis(), level, tag = "", message = line))
    }

    /** Empties [entries]. Lines already given to [sink] are not affected. May be called from any thread. */
    public fun clear() {
        _entries.value = emptyList()
    }

    private fun append(entry: LogEntry) {
        try {
            sink?.invoke(entry)
        } catch (e: Throwable) {
            // The app's sink failed; a log line must never break a runtime or a Bluetooth callback.
            // What the process cannot go on after (out of memory, a broken class) is not swallowed.
            if (e is VirtualMachineError || e is LinkageError || e is InterruptedException) throw e
            Log.e(TAG, "HyperBleLog.sink threw", e)
        }
        _entries.update { current ->
            val next = current + entry
            val capacity = capacity
            if (next.size > capacity) next.subList(next.size - capacity, next.size) else next
        }
    }
}
