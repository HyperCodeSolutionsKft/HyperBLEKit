package io.hypercode.hyperblekit.fake

import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.LinkId
import io.hypercode.hyperblekit.transport.aware.AwareClientEvent
import io.hypercode.hyperblekit.transport.aware.AwareClientPath
import io.hypercode.hyperblekit.transport.aware.AwareHostEvent
import io.hypercode.hyperblekit.transport.aware.AwareHostPath
import io.hypercode.hyperblekit.transport.aware.AwareOffer
import io.hypercode.hyperblekit.transport.aware.AwareScheme
import io.hypercode.hyperblekit.transport.aware.MainScheduler

/**
 * Wi-Fi Aware between the phones of one [FakeRadio]: host paths publish under a random instance
 * ID, client paths find them by the offer's, and both ends exchange messages and frames through
 * the radio's queue, in order. A test can break every data path with [breakAll], or keep new
 * ones from coming up with [isAvailable].
 */
internal class FakeAwareAir(val radio: FakeRadio) {
    private val hosts = mutableListOf<FakeAwareHostPath>()
    private var instances = 0L

    internal fun newInstanceId(): ByteArray = java.nio.ByteBuffer.allocate(8).putLong(++instances).array()

    /** False: no host is ready and no client gets a data path. */
    var isAvailable = true

    /** How long a data path takes to come up, in ms of virtual time. */
    var setupMillis = 100L

    /** Messages that went over Wi-Fi Aware, both directions. */
    var messagesCarried = 0

    /** Frames that went over Wi-Fi Aware, both directions. */
    var framesCarried = 0

    val scheduler = MainScheduler { delay, block -> radio.post(delay, block) }

    fun hostPath(maxDataPaths: Int = 4) = FakeAwareHostPath(this, maxDataPaths).also { hosts += it }

    fun clientPath() = FakeAwareClientPath(this)

    internal fun findHost(instanceId: ByteArray) = hosts.firstOrNull { it.isReady && it.instanceId.contentEquals(instanceId) }

    /** Every data path breaks at once, as when Wi-Fi is switched off; both ends hear it. */
    fun breakAll(reason: String = "Wi-Fi Aware unavailable") {
        hosts.forEach { it.breakAll(reason) }
    }
}

internal class FakeAwareHostPath(private val air: FakeAwareAir, override val maxDataPaths: Int) : AwareHostPath {
    private var listener: ((AwareHostEvent) -> Unit)? = null
    private var isStarted = false
    var instanceId = ByteArray(8)
        private set

    private class Path(val token: ByteArray) {
        var client: FakeAwareClientPath? = null
    }

    private val paths = LinkedHashMap<LinkId, Path>()

    /** How many offers were made; for asserting that a host did or did not offer. */
    var offers = 0
        private set

    override var isSupported = true
    override var acceptsPairedClients = false
    override val isReady: Boolean get() = isStarted && air.isAvailable

    override fun setListener(listener: ((AwareHostEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun start() {
        isStarted = true
        instanceId = air.newInstanceId()
    }

    override fun stop() {
        isStarted = false
        paths.values.forEach { it.client?.hostClosed(silent = false, reason = "host stopped") }
        paths.clear()
    }

    override fun stopAfterFlush() {
        // What is queued is already on the radio's queue.
        air.radio.post { stop() }
    }

    override fun offer(link: LinkId): AwareOffer? {
        if (!isReady) return null
        offers += 1
        val token = ByteArray(8) { (link.hashCode() + it + offers).toByte() }
        paths[link] = Path(token)
        return AwareOffer(instanceId, token, "passphrase-$link")
    }

    /** A client with a valid token: the data path is up on both ends. */
    internal fun accept(client: FakeAwareClientPath, token: ByteArray): LinkId? {
        val entry = paths.entries.firstOrNull { it.value.token.contentEquals(token) && it.value.client == null } ?: return null
        entry.value.client = client
        listener?.invoke(AwareHostEvent.PathUp(entry.key))
        return entry.key
    }

    override fun send(link: LinkId, message: ByteArray): Boolean {
        val client = paths[link]?.client ?: return false
        air.messagesCarried += 1
        air.radio.post { client.deliver(AwareClientEvent.MessageReceived(message)) }
        return true
    }

    override fun sendFrame(link: LinkId, payload: ByteArray): FrameSendResult {
        val client = paths[link]?.client ?: return FrameSendResult.FAILED
        air.framesCarried += 1
        air.radio.post { client.deliver(AwareClientEvent.FrameReceived(payload)) }
        return FrameSendResult.QUEUED
    }

    override fun close(link: LinkId) {
        paths.remove(link)?.client?.hostClosed(silent = false, reason = "closed by the host")
    }

    internal fun deliver(client: FakeAwareClientPath, event: AwareHostEvent) {
        if (paths.values.any { it.client === client }) listener?.invoke(event)
    }

    internal fun linkOf(client: FakeAwareClientPath): LinkId? = paths.entries.firstOrNull { it.value.client === client }?.key

    /** The client end closed: the host hears the path go down. */
    internal fun clientClosed(client: FakeAwareClientPath, reason: String) {
        val link = linkOf(client) ?: return
        paths.remove(link)
        air.radio.post { listener?.invoke(AwareHostEvent.PathDown(link, reason)) }
    }

    fun breakAll(reason: String) {
        val broken = paths.toList()
        paths.clear()
        for ((link, path) in broken) {
            path.client?.hostClosed(silent = false, reason = reason)
            air.radio.post { listener?.invoke(AwareHostEvent.PathDown(link, reason)) }
        }
    }
}

internal class FakeAwareClientPath(private val air: FakeAwareAir) : AwareClientPath {
    private var listener: ((AwareClientEvent) -> Unit)? = null
    private var host: FakeAwareHostPath? = null
    private var attempt = 0

    /** The schemes this phone can use; both by default. */
    var supportedSchemes = setOf(AwareScheme.NAN, AwareScheme.PAIRED)

    /** The scheme of the last [connect]. */
    var lastScheme: AwareScheme? = null
        private set

    override fun supports(scheme: AwareScheme) = scheme in supportedSchemes

    override fun setListener(listener: ((AwareClientEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(offer: AwareOffer, scheme: AwareScheme) {
        lastScheme = scheme
        close()
        val current = ++attempt
        air.radio.post(air.setupMillis) {
            if (current != attempt) return@post
            val target = if (air.isAvailable) air.findHost(offer.instanceId) else null
            if (target == null || target.accept(this, offer.token) == null) {
                listener?.invoke(AwareClientEvent.Down("no data path"))
                return@post
            }
            host = target
            listener?.invoke(AwareClientEvent.Up)
        }
    }

    override fun send(message: ByteArray): Boolean {
        val target = host ?: return false
        air.messagesCarried += 1
        air.radio.post { if (host === target) target.deliver(this, AwareHostEvent.MessageReceived(target.linkOf(this) ?: return@post, message)) }
        return true
    }

    override fun sendFrame(payload: ByteArray): FrameSendResult {
        val target = host ?: return FrameSendResult.FAILED
        air.framesCarried += 1
        air.radio.post { if (host === target) target.deliver(this, AwareHostEvent.FrameReceived(target.linkOf(this) ?: return@post, payload)) }
        return FrameSendResult.QUEUED
    }

    override fun close() {
        attempt += 1
        val target = host ?: return
        host = null
        target.clientClosed(this, "closed by the client")
    }

    override fun closeAfterFlush(then: () -> Unit) {
        // What was sent is on the radio's queue ahead of this.
        air.radio.post {
            close()
            then()
        }
    }

    internal fun deliver(event: AwareClientEvent) {
        if (host != null) listener?.invoke(event)
    }

    /** The host end went away; [silent] means the host closed it on purpose and expects no event. */
    internal fun hostClosed(silent: Boolean, reason: String) {
        if (host == null) return
        host = null
        attempt += 1
        if (!silent) air.radio.post { listener?.invoke(AwareClientEvent.Down(reason)) }
    }
}
