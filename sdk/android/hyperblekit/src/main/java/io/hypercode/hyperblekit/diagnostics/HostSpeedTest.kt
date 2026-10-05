package io.hypercode.hyperblekit.diagnostics

import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.LinkId

/** A dot bouncing around the field, one step per tick. */
internal class BouncingDot {
    var x = SpeedTestConstants.FIELD_WIDTH / 2
        private set
    var y = SpeedTestConstants.FIELD_HEIGHT / 2
        private set
    private var dx = SpeedTestConstants.DOT_STEP_X
    private var dy = SpeedTestConstants.DOT_STEP_Y

    fun step() {
        x += dx
        y += dy
        val maxX = SpeedTestConstants.FIELD_WIDTH - 1
        val maxY = SpeedTestConstants.FIELD_HEIGHT - 1
        // Reflected off the edge, so the dot never leaves the field.
        if (x < 0 || x > maxX) {
            x = if (x < 0) -x else 2 * maxX - x
            dx = -dx
        }
        if (y < 0 || y > maxY) {
            y = if (y < 0) -y else 2 * maxY - y
            dy = -dy
        }
    }
}

/**
 * The host side of the speed test: moves the dot 30 times a second, builds a world frame per
 * linked client at the chosen rate, takes the clients' inputs and counts, per client and second,
 * the frames sent and replaced, the inputs and the worst gap between them.
 *
 * Links are followed while the test is stopped too, so the screen knows whether a client is there
 * and the echo of a client's last input survives a restart. Pure Kotlin, clock passed in; the
 * caller sends the frames and runs the timer.
 */
internal class HostSpeedTest {

    private class Client {
        var sent = 0
        var replaced = 0
        var inputs = 0
        var inputGapMax = 0L
        var lastInputAtMillis: Long? = null
        var taps = 0

        /** The last input taken: what the world frames echo. */
        var echoInputSeq = 0
        var echoClientMillis = 0L
        var inputTakenAtMillis: Long? = null

        /** A refused frame was reported once in this run. */
        var failureReported = false

        fun resetSecond() {
            sent = 0
            replaced = 0
            inputs = 0
            inputGapMax = 0
        }
    }

    private val clients = LinkedHashMap<LinkId, Client>()
    private var dot = BouncingDot()

    var isRunning = false
        private set
    var frameRate = SpeedTestConstants.DEFAULT_FRAME_RATE
        private set
    var payloadSize = SpeedTestConstants.DEFAULT_PAYLOAD_SIZE
        private set

    /** Ticks since the start, wrapping at 2³² on the wire. */
    var tick = 0L
        private set

    val links: List<LinkId> get() = clients.keys.toList()
    val dotX: Int get() = dot.x
    val dotY: Int get() = dot.y

    fun linkUp(link: LinkId) {
        clients[link] = Client()
    }

    fun linkDown(link: LinkId) {
        clients.remove(link)
    }

    fun start(frameRate: Int, payloadSize: Int) {
        this.frameRate = frameRate
        this.payloadSize = payloadSize
        isRunning = true
        tick = 0
        dot = BouncingDot()
        clients.values.forEach {
            it.resetSecond()
            it.failureReported = false
            // Gaps are measured within the run only.
            it.lastInputAtMillis = null
        }
    }

    fun stop() {
        isRunning = false
    }

    /**
     * One tick: moves the dot. Returns true if a world frame goes to every client on this tick,
     * spread evenly so that [frameRate] of every [SpeedTestConstants.TICKS_PER_SECOND] ticks do.
     */
    fun advance(): Boolean {
        tick += 1
        dot.step()
        val perSecond = SpeedTestConstants.TICKS_PER_SECOND
        return (tick * frameRate) / perSecond != ((tick - 1) * frameRate) / perSecond
    }

    /** The world frame for [link] now, padded to [payloadSize]. */
    fun worldFor(link: LinkId, nowMillis: Long): ByteArray {
        val client = clients[link]
        val takenAt = client?.inputTakenAtMillis
        val hold = if (takenAt == null) 0 else minOf(nowMillis - takenAt, SpeedTestPayloads.MAX_HOLD_MILLIS.toLong()).toInt()
        val world = WorldFrame(
            tick = SpeedTestPayloads.u32(tick),
            hostMillis = SpeedTestPayloads.u32(nowMillis),
            x = dot.x,
            y = dot.y,
            echoInputSeq = client?.echoInputSeq ?: 0,
            echoClientMillis = client?.echoClientMillis ?: 0,
            holdMillis = hold,
        )
        return SpeedTestPayloads.encodeWorld(world, payloadSize)
    }

    /** What the transport did with [link]'s frame. Returns true for the run's first refusal on that link. */
    fun frameSent(link: LinkId, result: FrameSendResult): Boolean {
        val client = clients[link] ?: return false
        when (result) {
            FrameSendResult.QUEUED -> client.sent += 1
            FrameSendResult.REPLACED -> {
                client.sent += 1
                client.replaced += 1
            }
            FrameSendResult.FAILED -> {
                if (client.failureReported) return false
                client.failureReported = true
                return true
            }
        }
        return false
    }

    /** A frame from [link]. Returns false if it is not an input. */
    fun inputReceived(link: LinkId, payload: ByteArray, nowMillis: Long): Boolean {
        val input = SpeedTestPayloads.decodeInput(payload) ?: return false
        val client = clients.getOrPut(link) { Client() }
        client.lastInputAtMillis?.let { client.inputGapMax = maxOf(client.inputGapMax, nowMillis - it) }
        client.lastInputAtMillis = nowMillis
        client.inputs += 1
        client.taps = input.taps
        client.echoInputSeq = input.inputSeq
        client.echoClientMillis = input.clientMillis
        client.inputTakenAtMillis = nowMillis
        return true
    }

    /**
     * Closes one second: one line per client, `p2 fps=15 replaced=0 inFps=4 inGapMax=260 taps=3`
     * (the caller adds `RT tx`). [label] names a link: its player ID if seated, else the link.
     */
    fun secondElapsed(label: (LinkId) -> String): List<String> =
        clients.map { (link, client) ->
            val line = "${label(link)} fps=${client.sent} replaced=${client.replaced} " +
                "inFps=${client.inputs} inGapMax=${client.inputGapMax} taps=${client.taps}"
            client.resetSecond()
            line
        }
}
