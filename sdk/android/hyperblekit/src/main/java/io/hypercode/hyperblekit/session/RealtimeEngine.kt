package io.hypercode.hyperblekit.session

import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.LinkId

/**
 * The host's fixed-step schedule for a real-time game (wire specification "Real-time games" → Ticking): tick
 * n is due at start + n × [tickMillis]. Late ticks run one after another; after a stall of more
 * than [stallMillis] the missed ticks are dropped and the schedule starts over from now.
 *
 * Pure Kotlin, monotonic clock passed in; the owner runs the timer.
 */
internal class TickSchedule(
    private val tickMillis: Long,
    private val stallMillis: Long,
    startMillis: Long,
) {
    /** When the next tick is due. */
    var nextDueAtMillis = startMillis + tickMillis
        private set

    /** How many ticks to run at [nowMillis]; the schedule moves past them. */
    fun due(nowMillis: Long): Int {
        if (nowMillis < nextDueAtMillis) return 0
        if (nowMillis - nextDueAtMillis > stallMillis) {
            // A stall: one tick now, the missed ones are dropped.
            nextDueAtMillis = nowMillis + tickMillis
            return 1
        }
        var count = 0
        while (nowMillis >= nextDueAtMillis) {
            count += 1
            nextDueAtMillis += tickMillis
        }
        return count
    }
}

/**
 * The host's real-time counters per client link over one second: world frames sent and replaced,
 * input frames taken and the worst gap between them. Pure Kotlin, monotonic clock passed in.
 */
internal class RealtimeTxStats {
    private class Link {
        var sent = 0
        var replaced = 0
        var inputs = 0
        var inputGapMax = 0L
        var lastInputAtMillis: Long? = null

        /** A refused frame was reported once for this link. */
        var failureReported = false
    }

    private val links = LinkedHashMap<LinkId, Link>()

    /** What the transport did with a world frame for [link]. Returns true for the first refusal on that link. */
    fun frameSent(link: LinkId, result: FrameSendResult): Boolean {
        val counts = links.getOrPut(link) { Link() }
        when (result) {
            FrameSendResult.QUEUED -> counts.sent += 1
            FrameSendResult.REPLACED -> {
                counts.sent += 1
                counts.replaced += 1
            }
            FrameSendResult.FAILED -> {
                if (counts.failureReported) return false
                counts.failureReported = true
                return true
            }
        }
        return false
    }

    /** An input frame from [link] was taken by the game. */
    fun inputReceived(link: LinkId, nowMillis: Long) {
        val counts = links.getOrPut(link) { Link() }
        counts.lastInputAtMillis?.let { counts.inputGapMax = maxOf(counts.inputGapMax, nowMillis - it) }
        counts.lastInputAtMillis = nowMillis
        counts.inputs += 1
    }

    fun linkDown(link: LinkId) {
        links.remove(link)
    }

    /** Ticking starts (again): gaps are measured from here on. */
    fun restart() {
        links.clear()
    }

    /**
     * Closes one second: one line per link, `p2 fps=15 replaced=0 inFps=8 inGapMax=260` (the
     * caller adds `RT tx`). [label] names a link: its player ID if seated, else the link.
     */
    fun secondElapsed(label: (LinkId) -> String): List<String> =
        links.map { (link, counts) ->
            val line = "${label(link)} fps=${counts.sent} replaced=${counts.replaced} " +
                "inFps=${counts.inputs} inGapMax=${counts.inputGapMax}"
            counts.sent = 0
            counts.replaced = 0
            counts.inputs = 0
            counts.inputGapMax = 0
            line
        }
}

/**
 * A client's real-time counters over one second: world frames taken, frames the transport dropped
 * as stale, gaps between frames, and the input round trip — from sending an input to the first
 * world frame that echoes its sequence number. Open while world frames arrive; closed
 * [GameConstants.REALTIME_RX_IDLE_MILLIS] after the last one. Pure Kotlin, monotonic clock passed in.
 */
internal class RealtimeRxStats {
    private var frames = 0
    private var gapSum = 0L
    private var gapCount = 0
    private var gapMax = 0L
    private var over150 = 0
    private var rttSum = 0L
    private var rttCount = 0
    private var rttMax = 0L
    private var size = 0

    private var lastFrameAtMillis: Long? = null
    private var lastEcho: Int? = null
    private var staleAtSecond = 0

    /** When each input sequence number (0…255) was last sent; -1 never. */
    private val sentAtMillis = LongArray(SEQ_MODULUS) { -1L }

    var isOpen = false
        private set

    /** [staleDropped] is the transport's stale counter, to count from. */
    fun open(staleDropped: Int) {
        isOpen = true
        resetSecond()
        lastFrameAtMillis = null
        lastEcho = null
        staleAtSecond = staleDropped
    }

    fun inputSent(seq: Int, nowMillis: Long) {
        sentAtMillis[Math.floorMod(seq, SEQ_MODULUS)] = nowMillis
    }

    /** A world frame of [size] bytes; [echo] is the own input sequence number it echoes, null for none. */
    fun frameReceived(nowMillis: Long, size: Int, echo: Int?) {
        lastFrameAtMillis?.let { last ->
            val gap = nowMillis - last
            gapSum += gap
            gapCount += 1
            gapMax = maxOf(gapMax, gap)
            if (gap > GameConstants.REALTIME_SLOW_GAP_MILLIS) over150 += 1
        }
        lastFrameAtMillis = nowMillis
        frames += 1
        this.size = size
        val previous = lastEcho
        if (echo != null && previous != null && echo != previous) {
            val sentAt = sentAtMillis[Math.floorMod(echo, SEQ_MODULUS)]
            if (sentAt >= 0 && nowMillis >= sentAt) {
                val rtt = nowMillis - sentAt
                rttSum += rtt
                rttCount += 1
                rttMax = maxOf(rttMax, rtt)
            }
        }
        lastEcho = echo
    }

    /** True once no world frame came for [GameConstants.REALTIME_RX_IDLE_MILLIS]; the stats are then closed. */
    fun closeIfIdle(nowMillis: Long): Boolean {
        val last = lastFrameAtMillis ?: return false
        if (!isOpen || nowMillis - last < GameConstants.REALTIME_RX_IDLE_MILLIS) return false
        isOpen = false
        return true
    }

    fun close() {
        isOpen = false
    }

    /**
     * Closes one second: `fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=45/80 size=87` (the caller
     * adds `RT rx`); `rtt=-/-` without a sample. The transport's counter starts over with a new link.
     */
    fun secondElapsed(staleDropped: Int): String {
        val stale = if (staleDropped >= staleAtSecond) staleDropped - staleAtSecond else staleDropped
        staleAtSecond = staleDropped
        val gapAvg = if (gapCount == 0) 0 else gapSum / gapCount
        val rtt = if (rttCount == 0) "-/-" else "${rttSum / rttCount}/$rttMax"
        val line = "fps=$frames stale=$stale gapAvg=$gapAvg gapMax=$gapMax over150=$over150 rtt=$rtt size=$size"
        resetSecond()
        return line
    }

    private fun resetSecond() {
        frames = 0
        gapSum = 0
        gapCount = 0
        gapMax = 0
        over150 = 0
        rttSum = 0
        rttCount = 0
        rttMax = 0
    }

    private companion object {
        const val SEQ_MODULUS = 256
    }
}
