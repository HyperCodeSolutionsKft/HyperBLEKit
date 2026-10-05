package io.hypercode.hyperblekit

/**
 * How long messages take between a host and its clients ([SessionState.pingStats]). The host
 * sends every client a ping every 3 seconds and measures the time until the answer; this is the
 * sum over all clients since hosting started. An app may construct values itself, for previews
 * and tests.
 *
 * @property count how many answers were measured.
 * @property totalMs the sum of all measured round trips, in milliseconds.
 * @property worstMs the longest round trip, in milliseconds; 0 while [count] is 0.
 */
public data class PingStats(val count: Int = 0, val totalMs: Long = 0, val worstMs: Long = 0) {
    /** The average round trip in milliseconds, rounded down; null while nothing was measured. */
    public val averageMs: Long? get() = if (count == 0) null else totalMs / count

    internal fun record(ms: Long): PingStats = PingStats(count + 1, totalMs + ms, maxOf(worstMs, ms))
}
