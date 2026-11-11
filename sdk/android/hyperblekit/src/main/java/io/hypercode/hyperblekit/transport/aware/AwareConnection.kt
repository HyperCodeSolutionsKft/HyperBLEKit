package io.hypercode.hyperblekit.transport.aware

import io.hypercode.hyperblekit.transport.FrameSendResult
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection of a Wi-Fi Aware data path, carrying [AwareStream] records. Plain
 * `java.net`, nothing of Wi-Fi Aware itself, so it also runs over loopback in tests.
 *
 * A reader thread and a writer thread own the socket. Like a BLE link, the writer holds a queue
 * of messages and a single slot for the newest frame, and a waiting frame goes before the next
 * message. Nothing sent for [AwareStream.KEEPALIVE_INTERVAL_MILLIS] sends a keepalive; nothing
 * received for [AwareStream.SILENCE_TIMEOUT_MILLIS] closes the connection.
 *
 * [post] must run its block on the main thread: every callback goes through it. Public methods
 * may be called from any thread; in the SDK they are called from the main thread.
 *
 * @param onRecord a received [AwareStream.HELLO], [AwareStream.MESSAGE] or [AwareStream.FRAME].
 * @param onClosed the connection broke, with the reason; called once, never after [close].
 */
internal class AwareConnection(
    private val socket: Socket,
    private val post: (() -> Unit) -> Unit,
    private val onRecord: (AwareRecord) -> Unit,
    private val onClosed: (String) -> Unit,
    private val silenceTimeoutMillis: Long = AwareStream.SILENCE_TIMEOUT_MILLIS,
    private val keepaliveIntervalMillis: Long = AwareStream.KEEPALIVE_INTERVAL_MILLIS,
) {
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var frame: ByteArray? = null
    private var isClosed = false
    private var flushThen: (() -> Unit)? = null

    /** Set by [close]: nothing is reported any more, not even what is already posted. */
    @Volatile
    private var isSilenced = false

    fun start() {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = silenceTimeoutMillis.toInt()
        } catch (e: IOException) {
            fail("socket setup failed: ${e.message}")
            return
        }
        Thread(::readLoop, "HyperBleKit-aware-read").apply { isDaemon = true }.start()
        Thread(::writeLoop, "HyperBleKit-aware-write").apply { isDaemon = true }.start()
    }

    /** Queues a record of any kind; false once the connection is closed or closing. */
    fun sendRecord(kind: Int, body: ByteArray = ByteArray(0)): Boolean {
        val record = AwareStream.encode(kind, body)
        synchronized(lock) {
            if (isClosed || flushThen != null) return false
            queue.addLast(record)
            lock.notifyAll()
        }
        return true
    }

    fun sendMessage(message: ByteArray): Boolean = sendRecord(AwareStream.MESSAGE, message)

    fun sendFrame(payload: ByteArray): FrameSendResult {
        val record = AwareStream.encode(AwareStream.FRAME, payload)
        synchronized(lock) {
            if (isClosed || flushThen != null) return FrameSendResult.FAILED
            val replaced = frame != null
            frame = record
            lock.notifyAll()
            return if (replaced) FrameSendResult.REPLACED else FrameSendResult.QUEUED
        }
    }

    /** Closes at once; no callback follows. */
    fun close() {
        isSilenced = true
        synchronized(lock) {
            isClosed = true
            queue.clear()
            frame = null
            flushThen = null
            lock.notifyAll()
        }
        closeSocket()
    }

    /**
     * Sends what is queued (frames are dropped), then closes and posts [then]; after [timeoutMillis]
     * it closes anyway. No [onClosed] follows.
     */
    fun closeAfterFlush(timeoutMillis: Long, then: () -> Unit) {
        isSilenced = true
        val already = synchronized(lock) {
            if (isClosed) {
                true
            } else {
                frame = null
                flushThen = then
                lock.notifyAll()
                false
            }
        }
        if (already) {
            post(then)
            return
        }
        Thread({
            try {
                Thread.sleep(timeoutMillis)
            } catch (_: InterruptedException) {
                return@Thread
            }
            finishFlush()
        }, "HyperBleKit-aware-flush").apply { isDaemon = true }.start()
    }

    private fun finishFlush() {
        val then = synchronized(lock) {
            val pending = flushThen ?: return
            flushThen = null
            isClosed = true
            queue.clear()
            lock.notifyAll()
            pending
        }
        closeSocket()
        post(then)
    }

    private fun readLoop() {
        val decoder = AwareStreamDecoder()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = socket.getInputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    fail("closed by the other side")
                    return
                }
                for (record in decoder.feed(buffer, count)) {
                    if (record.kind == AwareStream.KEEPALIVE) continue
                    post { if (!isSilenced) onRecord(record) }
                }
            }
        } catch (_: SocketTimeoutException) {
            fail("silent for ${silenceTimeoutMillis / MILLIS_PER_SECOND} s")
        } catch (e: AwareStreamException) {
            fail(e.message ?: "bad record")
        } catch (e: IOException) {
            fail("read failed: ${e.message}")
        }
    }

    private fun writeLoop() {
        val keepalive = AwareStream.encode(AwareStream.KEEPALIVE)
        try {
            val output = socket.getOutputStream()
            while (true) {
                val next: ByteArray? = synchronized(lock) {
                    if (isClosed) return
                    if (frame == null && queue.isEmpty() && flushThen == null) {
                        lock.wait(keepaliveIntervalMillis)
                        if (isClosed) return
                    }
                    val waitingFrame = frame
                    when {
                        waitingFrame != null -> waitingFrame.also { frame = null }
                        queue.isNotEmpty() -> queue.removeFirst()
                        // Flushing, and everything is out.
                        flushThen != null -> null
                        // Nothing to send for a whole interval.
                        else -> keepalive
                    }
                }
                if (next == null) {
                    finishFlush()
                    return
                }
                output.write(next)
                output.flush()
            }
        } catch (_: InterruptedException) {
            fail("writer interrupted")
        } catch (e: IOException) {
            fail("write failed: ${e.message}")
        }
    }

    private fun fail(reason: String) {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            queue.clear()
            frame = null
            lock.notifyAll()
        }
        closeSocket()
        // A flush in progress ends here too: what is left is lost, the caller still goes on.
        val then = synchronized(lock) { flushThen.also { flushThen = null } }
        if (then != null) post(then)
        post { if (!isSilenced) onClosed(reason) }
    }

    private fun closeSocket() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Closing anyway.
        }
    }

    private companion object {
        const val READ_BUFFER_SIZE = 16 * 1024
        const val MILLIS_PER_SECOND = 1_000L
    }
}
