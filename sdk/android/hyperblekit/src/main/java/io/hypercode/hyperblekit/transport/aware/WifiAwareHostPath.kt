package io.hypercode.hyperblekit.transport.aware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.LinkId
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Host side of Wi-Fi Aware on Android (wire specification, "Wi-Fi Aware data path"):
 *
 * 1. [start]: attach to Wi-Fi Aware, open a TCP server socket, publish [serviceName] with
 *    `[version][instanceId]` as service-specific info. [isReady] once publishing.
 * 2. [offer] for a link: a fresh token and passphrase, sent to the client over BLE by the hybrid
 *    transport.
 * 3. The client finds the publication, sends `['H'][version][token]` as a discovery message;
 *    the host requests the data path for that peer with the link's passphrase and the server's
 *    port.
 * 4. The client connects over TCP and sends a `HELLO` record with the token: the link's path is
 *    up ([AwareHostEvent.PathUp]).
 *
 * **Experimental, paired scheme** ([crossPlatform], Android 14 with Wi-Fi Aware pairing): the
 * publication also takes pairing requests while [pairing] waits for one, and for every peer that
 * pairs or verifies an earlier pairing (an iPhone) the host requests a data path secured by the
 * pairing, at once, without a discovery message: an iPhone sends none. Its TCP connection is then
 * tied to a link by the token in its HELLO, as for any client.
 *
 * If Wi-Fi Aware becomes unavailable (Wi-Fi off, hotspot on), every data path breaks and the
 * links fall back to BLE; publishing starts again by itself once it is back.
 *
 * Main thread only; the socket threads post back to it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
internal class WifiAwareHostPath(
    context: Context,
    private val serviceName: String,
    private val crossPlatform: Boolean = false,
    private val pairing: io.hypercode.hyperblekit.WifiAwarePairing? = null,
) : AwareHostPath {

    private class Pending(val token: ByteArray, val passphrase: String) {
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var connection: AwareConnection? = null
    }

    private val appContext = context.applicationContext
    private val handler = WifiAwareSupport.mainHandler
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var listener: ((AwareHostEvent) -> Unit)? = null

    /** From [start] until [stop]. */
    private var isStarted = false

    /** Counts starts and stops, so a callback of an earlier session does nothing. */
    private var generation = 0
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var server: ServerSocket? = null
    private var instanceId = ByteArray(0)
    private val links = LinkedHashMap<LinkId, Pending>()

    /** Data paths requested for paired peers (paired scheme), by peer. */
    private val pairedNetworks = HashMap<PeerHandle, ConnectivityManager.NetworkCallback>()

    /** Sockets accepted that have not sent their HELLO yet. */
    private val unbound = HashSet<AwareConnection>()
    private var isReceiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED || !isStarted) return
            if (WifiAwareSupport.isAvailable(appContext)) {
                if (session == null) {
                    HyperBleLog.event("Wi-Fi Aware is available again: publishing")
                    attach()
                }
            } else {
                HyperBleLog.event("Wi-Fi Aware became unavailable: dropping every data path", LogLevel.WARN)
                closeSession(reportLinks = true)
            }
        }
    }

    override val isSupported: Boolean get() = WifiAwareSupport.isSupported(appContext)

    override var isReady: Boolean = false
        private set

    override val maxDataPaths: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WifiAwareSupport.manager(appContext)?.characteristics?.numberOfSupportedDataPaths
                ?: WifiAwareSupport.ASSUMED_DATA_PATHS
        } else {
            WifiAwareSupport.ASSUMED_DATA_PATHS
        }

    override val acceptsPairedClients: Boolean
        get() = crossPlatform && WifiAwareSupport.isPairingSupported(appContext)

    override fun setListener(listener: ((AwareHostEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun start() {
        stop()
        isStarted = true
        ContextCompat.registerReceiver(
            appContext,
            stateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
        attach()
    }

    override fun stop() {
        isStarted = false
        if (isReceiverRegistered) {
            appContext.unregisterReceiver(stateReceiver)
            isReceiverRegistered = false
        }
        closeSession(reportLinks = false)
    }

    override fun stopAfterFlush() {
        isStarted = false
        isReady = false
        publishSession?.close()
        publishSession = null
        val flushing = links.values.mapNotNull { it.connection }
        links.values.forEach { it.connection = null }
        flushing.forEach { it.closeAfterFlush(WifiAwareSupport.FLUSH_TIMEOUT_MILLIS) {} }
        handler.postDelayed({ if (!isStarted) stop() }, WifiAwareSupport.FLUSH_TIMEOUT_MILLIS)
    }

    override fun offer(link: LinkId): AwareOffer? {
        if (!isReady) return null
        close(link)
        val pending = Pending(WifiAwareSupport.randomId(), WifiAwareSupport.randomPassphrase())
        links[link] = pending
        val armed = generation
        handler.postDelayed({
            if (armed == generation && links[link] === pending && pending.connection == null) {
                drop(link, report = true, reason = "no data path within ${WifiAwareSupport.SETUP_TIMEOUT_MILLIS / 1000} s")
            }
        }, WifiAwareSupport.SETUP_TIMEOUT_MILLIS)
        return AwareOffer(instanceId, pending.token, pending.passphrase)
    }

    override fun send(link: LinkId, message: ByteArray): Boolean =
        links[link]?.connection?.sendMessage(message) ?: false

    override fun sendFrame(link: LinkId, payload: ByteArray): FrameSendResult =
        links[link]?.connection?.sendFrame(payload) ?: FrameSendResult.FAILED

    override fun close(link: LinkId) {
        drop(link, report = false, reason = "")
    }

    private fun emit(event: AwareHostEvent) {
        listener?.invoke(event)
    }

    private fun drop(link: LinkId, report: Boolean, reason: String) {
        val pending = links.remove(link) ?: return
        pending.connection?.close()
        pending.networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        if (report) emit(AwareHostEvent.PathDown(link, reason))
    }

    // --- Session ---

    private fun attach() {
        val manager = WifiAwareSupport.manager(appContext)
        if (manager == null || !WifiAwareSupport.isAvailable(appContext)) {
            HyperBleLog.event("Wi-Fi Aware not available now: hosting over BLE only until it is", LogLevel.WARN)
            return
        }
        val armed = ++generation
        guarded("attach") {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (armed != generation || !isStarted) {
                        session.close()
                        return
                    }
                    attached(session)
                }

                override fun onAttachFailed() {
                    if (armed == generation) HyperBleLog.event("Wi-Fi Aware attach failed", LogLevel.WARN)
                }
            }, handler)
        }
    }

    private fun attached(session: WifiAwareSession) {
        this.session = session
        val socket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            HyperBleLog.event("Wi-Fi Aware: could not open a server socket (${e.message})", LogLevel.ERROR)
            closeSession(reportLinks = false)
            return
        }
        server = socket
        startAcceptLoop(socket, generation)
        instanceId = WifiAwareSupport.randomId()
        val builder = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(AwareWire.serviceInfo(instanceId))
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
        if (acceptsPairedClients && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setPairingConfig(WifiAwareSupport.publisherPairingConfig())
        }
        val config = builder.build()
        val armed = generation
        guarded("publish") {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    if (armed != generation) {
                        session.close()
                        return
                    }
                    publishSession = session
                    isReady = true
                    pairing?.hostIsPublishing = true
                    HyperBleLog.event("Wi-Fi Aware: publishing $serviceName, port ${socket.localPort}")
                }

                override fun onSessionConfigFailed() {
                    if (armed == generation) HyperBleLog.event("Wi-Fi Aware: publishing failed", LogLevel.WARN)
                }

                override fun onSessionTerminated() {
                    if (armed != generation) return
                    isReady = false
                    publishSession = null
                    HyperBleLog.event("Wi-Fi Aware: publishing ended", LogLevel.WARN)
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    if (armed == generation) discoveryMessage(peerHandle, message)
                }

                // --- Paired scheme (experimental); only called on Android 14 and later ---

                override fun onPairingSetupRequestReceived(peerHandle: PeerHandle, requestId: Int) {
                    if (armed != generation || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
                    val publish = publishSession ?: return
                    val waiting = pairing
                    if (waiting != null) {
                        waiting.acceptIncoming(publish, peerHandle, requestId)
                    } else {
                        guarded("rejectPairingRequest") { publish.rejectPairingRequest(requestId, peerHandle) }
                    }
                }

                override fun onPairingSetupSucceeded(peerHandle: PeerHandle, alias: String) {
                    if (armed != generation) return
                    pairing?.pairingSucceeded(alias)
                    requestPairedDataPath(peerHandle, alias)
                }

                override fun onPairingSetupFailed(peerHandle: PeerHandle) {
                    if (armed == generation) pairing?.pairingFailed("the other phone's pairing did not complete")
                }

                override fun onPairingVerificationSucceed(peerHandle: PeerHandle, alias: String) {
                    if (armed == generation) requestPairedDataPath(peerHandle, alias)
                }

                override fun onPairingVerificationFailed(peerHandle: PeerHandle) {
                    if (armed == generation) {
                        HyperBleLog.event("Wi-Fi Aware: a paired phone failed verification; pair them again", LogLevel.WARN)
                    }
                }
            }, handler)
        }
    }

    /** Ends publishing, the server socket and every data path. */
    private fun closeSession(reportLinks: Boolean) {
        generation += 1
        isReady = false
        pairing?.hostIsPublishing = false
        pairedNetworks.values.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        pairedNetworks.clear()
        publishSession?.close()
        publishSession = null
        session?.close()
        session = null
        server?.let { runCatching { it.close() } }
        server = null
        unbound.forEach { it.close() }
        unbound.clear()
        links.keys.toList().forEach { drop(it, report = reportLinks, reason = "Wi-Fi Aware unavailable") }
    }

    // --- Data paths ---

    /** A client that got an offer found us: request the data path for it. */
    private fun discoveryMessage(peer: PeerHandle, message: ByteArray) {
        val token = AwareWire.parseDiscoveryHello(message) ?: return
        val (link, pending) = links.entries.firstOrNull { it.value.token.contentEquals(token) }?.toPair() ?: run {
            HyperBleLog.event("Wi-Fi Aware: discovery message with an unknown token", LogLevel.WARN)
            return
        }
        if (pending.networkCallback != null) return
        val publish = publishSession ?: return
        val port = server?.localPort ?: return
        val specifier = WifiAwareNetworkSpecifier.Builder(publish, peer)
            .setPskPassphrase(pending.passphrase)
            .setPort(port)
            .setTransportProtocol(TCP_PROTOCOL)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() {
                handler.post {
                    if (links[link] === pending) drop(link, report = true, reason = "the data path was refused")
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (links[link] === pending) drop(link, report = true, reason = "the data path was lost")
                }
            }
        }
        pending.networkCallback = callback
        HyperBleLog.event("Wi-Fi Aware: requesting the data path for $link")
        if (guarded("requestNetwork") {
                connectivity.requestNetwork(request, callback, handler, WifiAwareSupport.NETWORK_REQUEST_TIMEOUT_MILLIS)
            } == null
        ) {
            pending.networkCallback = null
            drop(link, report = true, reason = "the data path could not be requested")
        }
    }

    /**
     * Paired scheme: a peer paired with or verified an earlier pairing, so it may be an iPhone
     * that is about to connect. Its data path is requested right away; the TCP connection that
     * follows finds its link by the token, like any other.
     */
    private fun requestPairedDataPath(peer: PeerHandle, alias: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || pairedNetworks.containsKey(peer)) return
        val publish = publishSession ?: return
        val port = server?.localPort ?: return
        val builder = WifiAwareNetworkSpecifier.Builder(publish, peer)
        val security = WifiAwareSupport.pairedSecurity()
        if (security != null) {
            builder.setDataPathSecurityConfig(security).setPort(port).setTransportProtocol(TCP_PROTOCOL)
        }
        val specifier = guarded("paired network specifier") { builder.build() } ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val armed = generation
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { if (armed == generation) HyperBleLog.event("Wi-Fi Aware: data path up with paired $alias") }
            }

            override fun onUnavailable() {
                handler.post {
                    if (armed == generation && pairedNetworks.remove(peer) != null) {
                        HyperBleLog.event("Wi-Fi Aware: no data path with paired $alias", LogLevel.WARN)
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (armed == generation && pairedNetworks.remove(peer) != null) {
                        HyperBleLog.event("Wi-Fi Aware: data path with paired $alias lost")
                    }
                }
            }
        }
        pairedNetworks[peer] = callback
        HyperBleLog.event("Wi-Fi Aware: requesting a data path for paired $alias" + if (security == null) " (open)" else "")
        guarded("requestNetwork") {
            connectivity.requestNetwork(request, callback, handler, WifiAwareSupport.NETWORK_REQUEST_TIMEOUT_MILLIS)
        } ?: pairedNetworks.remove(peer)
    }

    private fun startAcceptLoop(socket: ServerSocket, armed: Int) {
        Thread({
            while (true) {
                val accepted: Socket = try {
                    socket.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                handler.post {
                    if (armed == generation) accepted(accepted) else runCatching { accepted.close() }
                }
            }
        }, "HyperBleKit-aware-accept").apply { isDaemon = true }.start()
    }

    /** A TCP connection came in: it belongs to the link whose token its HELLO carries. */
    private fun accepted(socket: Socket) {
        var link: LinkId? = null
        lateinit var connection: AwareConnection
        connection = AwareConnection(
            socket,
            post = { handler.post(it) },
            onRecord = { record ->
                val bound = link
                when {
                    bound == null && record.kind == AwareStream.HELLO -> link = bind(connection, record.body)
                    bound == null -> Unit
                    record.kind == AwareStream.MESSAGE -> emit(AwareHostEvent.MessageReceived(bound, record.body))
                    record.kind == AwareStream.FRAME -> emit(AwareHostEvent.FrameReceived(bound, record.body))
                }
            },
            onClosed = { reason ->
                unbound.remove(connection)
                val bound = link
                if (bound != null && links[bound]?.connection === connection) {
                    drop(bound, report = true, reason = reason)
                }
            },
        )
        unbound += connection
        connection.start()
    }

    private fun bind(connection: AwareConnection, token: ByteArray): LinkId? {
        unbound.remove(connection)
        val entry = links.entries.firstOrNull { it.value.token.contentEquals(token) }
        if (entry == null || entry.value.connection != null) {
            HyperBleLog.event("Wi-Fi Aware: connection with an unknown token, closing", LogLevel.WARN)
            connection.close()
            return null
        }
        entry.value.connection = connection
        // The echo tells the client it reached the right host (an iPhone client cannot tell otherwise).
        connection.sendRecord(AwareStream.HELLO, token)
        emit(AwareHostEvent.PathUp(entry.key))
        return entry.key
    }

    private inline fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            HyperBleLog.event("Wi-Fi Aware $what: permission missing (${e.message})", LogLevel.ERROR)
            null
        } catch (e: IllegalArgumentException) {
            HyperBleLog.event("Wi-Fi Aware $what: rejected (${e.message})", LogLevel.ERROR)
            null
        } catch (e: IllegalStateException) {
            HyperBleLog.event("Wi-Fi Aware $what: not possible now (${e.message})", LogLevel.ERROR)
            null
        }

    private companion object {
        /** IANA protocol number of TCP, for the network specifier. */
        const val TCP_PROTOCOL = 6
    }
}
