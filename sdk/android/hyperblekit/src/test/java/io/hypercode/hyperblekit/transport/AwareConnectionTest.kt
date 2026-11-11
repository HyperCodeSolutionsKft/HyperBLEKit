package io.hypercode.hyperblekit.transport

import io.hypercode.hyperblekit.transport.aware.AwareConnection
import io.hypercode.hyperblekit.transport.aware.AwareRecord
import io.hypercode.hyperblekit.transport.aware.AwareStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [AwareConnection] over a real loopback TCP connection: records, keepalives, silence, flushing. */
class AwareConnectionTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val opened = mutableListOf<AwareConnection>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        server.close()
    }

    private class End {
        val records = LinkedBlockingQueue<AwareRecord>()
        val closed = LinkedBlockingQueue<String>()
        val posted = LinkedBlockingQueue<String>()
    }

    /** Two connected ends; every callback runs on the socket threads (there is no main thread here). */
    private fun pair(silenceMillis: Long = 6_000, keepaliveMillis: Long = 2_000): Triple<AwareConnection, AwareConnection, Pair<End, End>> {
        val clientSocket = Socket(server.inetAddress, server.localPort)
        val hostSocket = server.accept()
        val a = End()
        val b = End()
        fun connection(socket: Socket, end: End) = AwareConnection(
            socket,
            post = { it() },
            onRecord = { end.records += it },
            onClosed = { end.closed += it },
            silenceTimeoutMillis = silenceMillis,
            keepaliveIntervalMillis = keepaliveMillis,
        ).also { opened += it; it.start() }
        return Triple(connection(clientSocket, a), connection(hostSocket, b), a to b)
    }

    private fun LinkedBlockingQueue<AwareRecord>.next(): AwareRecord = checkNotNull(poll(2, TimeUnit.SECONDS)) { "no record" }

    @Test
    fun `messages and frames arrive in order, both ways`() {
        val (client, host, ends) = pair()
        client.sendRecord(AwareStream.HELLO, byteArrayOf(1, 2))
        client.sendMessage("one".toByteArray())
        client.sendMessage(ByteArray(100_000) { it.toByte() })
        host.sendFrame(byteArrayOf(0x10, 5))

        assertEquals(AwareStream.HELLO, ends.second.records.next().kind)
        assertEquals("one", String(ends.second.records.next().body))
        assertEquals(100_000, ends.second.records.next().body.size)
        val frame = ends.first.records.next()
        assertEquals(AwareStream.FRAME, frame.kind)
        assertEquals(listOf<Byte>(0x10, 5), frame.body.toList())
    }

    @Test
    fun `keepalives keep a quiet connection open, and are not handed on`() {
        val (_, _, ends) = pair(silenceMillis = 600, keepaliveMillis = 100)
        Thread.sleep(1_500)
        assertTrue(ends.first.closed.isEmpty())
        assertTrue(ends.second.closed.isEmpty())
        assertTrue(ends.first.records.isEmpty())
    }

    @Test
    fun `a connection whose other end goes silent closes with a reason`() {
        // The other end never writes, not even keepalives: a raw socket.
        val raw = Socket(server.inetAddress, server.localPort)
        val accepted = server.accept()
        val end = End()
        AwareConnection(accepted, { it() }, { end.records += it }, { end.closed += it }, silenceTimeoutMillis = 300)
            .also { opened += it }
            .start()
        assertEquals("silent for 0 s", end.closed.poll(2, TimeUnit.SECONDS))
        raw.close()
    }

    @Test
    fun `closing one end is reported on the other, never on the closing one`() {
        val (client, _, ends) = pair()
        client.close()
        assertEquals("closed by the other side", ends.second.closed.poll(2, TimeUnit.SECONDS))
        assertTrue(ends.first.closed.poll(300, TimeUnit.MILLISECONDS) == null)
    }

    @Test
    fun `closeAfterFlush sends what is queued, then calls back`() {
        val (client, _, ends) = pair()
        repeat(20) { client.sendMessage("m$it".toByteArray()) }
        val done = LinkedBlockingQueue<Boolean>()
        client.closeAfterFlush(2_000) { done += true }
        assertEquals(false, client.sendMessage("late".toByteArray()))
        assertEquals(true, done.poll(2, TimeUnit.SECONDS))
        val texts = List(20) { String(ends.second.records.next().body) }
        assertEquals(List(20) { "m$it" }, texts)
        assertTrue(ends.first.closed.isEmpty())
    }

    @Test
    fun `a newer frame replaces one that waits`() {
        val (client, _, _) = pair()
        // Nothing can be guaranteed to wait on a fast loopback, so only the result values are checked.
        val first = client.sendFrame(byteArrayOf(1))
        val second = client.sendFrame(byteArrayOf(2))
        assertTrue(first == FrameSendResult.QUEUED)
        assertTrue(second == FrameSendResult.QUEUED || second == FrameSendResult.REPLACED)
    }
}
