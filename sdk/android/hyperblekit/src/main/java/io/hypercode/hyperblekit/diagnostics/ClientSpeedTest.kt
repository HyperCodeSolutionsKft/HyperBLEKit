package io.hypercode.hyperblekit.diagnostics

/**
 * The client side of the speed test. No message starts it: it opens with the first world frame
 * and is over [SpeedTestConstants.CLIENT_CLOSE_AFTER_MILLIS] after the last one. Meanwhile it
 * builds the inputs and measures, per second and since it opened: world frames taken, frames the
 * transport dropped as stale, gaps between frames, and the input round trip.
 *
 * Input numbering and taps go on across openings. Pure Kotlin, clock passed in (monotonic ms).
 */
internal class ClientSpeedTest {

    /** Counters over one period: the last second, or everything since the test opened. */
    private class Period {
        var frames = 0
        var gapSum = 0L
        var gapCount = 0
        var gapMax = 0L
        var over150 = 0
        var rttSum = 0L
        var rttCount = 0
        var rttMax = 0L

        fun gap(gap: Long) {
            gapSum += gap
            gapCount += 1
            gapMax = maxOf(gapMax, gap)
            if (gap > SpeedTestConstants.SLOW_GAP_MILLIS) over150 += 1
        }

        fun roundTrip(rtt: Long) {
            rttSum += rtt
            rttCount += 1
            rttMax = maxOf(rttMax, rtt)
        }

        /** `fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=45/80 size=64`; `rtt=-/-` without a sample. */
        fun line(fps: Long, stale: Int, size: Int): String {
            val gapAvg = if (gapCount == 0) 0 else gapSum / gapCount
            val rtt = if (rttCount == 0) "-/-" else "${rttSum / rttCount}/$rttMax"
            return "fps=$fps stale=$stale gapAvg=$gapAvg gapMax=$gapMax over150=$over150 rtt=$rtt size=$size"
        }
    }

    private var second = Period()
    private var total = Period()

    /** Monotonic ms of the last world frame taken; null while the test is not open. */
    private var lastFrameAtMillis: Long? = null
    private var openedAtMillis = 0L

    /** `echoInputSeq` of the last world frame; null until the first one after opening. */
    private var lastEcho: Int? = null

    /** The transport's stale counter when the test opened and at the last second. */
    private var staleAtOpen = 0
    private var staleAtSecond = 0

    private var inputSeq = 0
    var taps = 0
        private set

    var isOpen = false
        private set
    var dotX = 0
        private set
    var dotY = 0
        private set

    /** Payload size of the last world frame. */
    var size = 0
        private set

    /**
     * A frame from the host, already past the transport's stale rule. Returns false if it is not
     * a world frame. Opens the test if it was not open.
     *
     * @param staleDropped the transport's count of stale frames, to measure from when opening.
     */
    fun frameReceived(payload: ByteArray, nowMillis: Long, staleDropped: Int): Boolean {
        val world = SpeedTestPayloads.decodeWorld(payload) ?: return false
        if (!isOpen) open(nowMillis, staleDropped)
        lastFrameAtMillis?.let {
            val gap = nowMillis - it
            second.gap(gap)
            total.gap(gap)
        }
        lastFrameAtMillis = nowMillis
        second.frames += 1
        total.frames += 1
        size = payload.size
        dotX = world.x
        dotY = world.y

        // A changed echo means the host took a newer input; echo 0 with clientMillis 0 is "none yet".
        val echoChanged = lastEcho != null && world.echoInputSeq != lastEcho
        val hasInput = world.echoInputSeq != 0 || world.echoClientMillis != 0L
        if (echoChanged && hasInput) {
            val rtt = SpeedTestPayloads.roundTripMillis(nowMillis, world.echoClientMillis, world.holdMillis)
            second.roundTrip(rtt)
            total.roundTrip(rtt)
        }
        lastEcho = world.echoInputSeq
        return true
    }

    private fun open(nowMillis: Long, staleDropped: Int) {
        isOpen = true
        openedAtMillis = nowMillis
        second = Period()
        total = Period()
        lastFrameAtMillis = null
        lastEcho = null
        staleAtOpen = staleDropped
        staleAtSecond = staleDropped
    }

    /** True once no world frame came for [SpeedTestConstants.CLIENT_CLOSE_AFTER_MILLIS]; the test is then closed. */
    fun closeIfIdle(nowMillis: Long): Boolean {
        val last = lastFrameAtMillis ?: return false
        if (!isOpen || nowMillis - last < SpeedTestConstants.CLIENT_CLOSE_AFTER_MILLIS) return false
        close()
        return true
    }

    /** Closes the test; the next world frame opens it again with fresh stats. */
    fun close() {
        isOpen = false
    }

    /** A tap on the field: counted, and goes out with the next input. */
    fun tap() {
        taps += 1
    }

    /** The next input to send, numbered from 1 and wrapping. */
    fun nextInput(nowMillis: Long): ByteArray {
        inputSeq = (inputSeq + 1) % SEQ_MODULUS
        return SpeedTestPayloads.encodeInput(InputFrame(inputSeq, SpeedTestPayloads.u32(nowMillis), taps % SEQ_MODULUS))
    }

    /**
     * Closes one second and returns its line (the caller adds `RT rx`). [staleDropped] is the
     * transport's count; it starts over with a new link, so a drop in it counts from 0.
     */
    fun secondElapsed(staleDropped: Int): String {
        val stale = if (staleDropped >= staleAtSecond) staleDropped - staleAtSecond else staleDropped
        staleAtSecond = staleDropped
        val line = second.line(second.frames.toLong(), stale, size)
        second = Period()
        return line
    }

    /** Since the test opened, in the same format; `fps` is the average over that time. */
    fun sinceStart(nowMillis: Long, staleDropped: Int): String {
        val elapsed = maxOf(nowMillis - openedAtMillis, 1L)
        val fps = total.frames * MILLIS_PER_SECOND / elapsed
        val stale = if (staleDropped >= staleAtOpen) staleDropped - staleAtOpen else staleDropped
        return total.line(fps, stale, size) + " secs=${elapsed / MILLIS_PER_SECOND}"
    }

    private companion object {
        const val SEQ_MODULUS = 256
        const val MILLIS_PER_SECOND = 1_000L
    }
}
