package io.hypercode.hyperblekit.transport.aware

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.ServiceDiscoveryInfo
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import androidx.annotation.RequiresApi
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.FrameSendResult
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client side of Wi-Fi Aware on Android; the steps are those of [WifiAwareHostPath], seen from
 * the other end:
 *
 * attach → subscribe to the service → the publication whose service-specific info carries the
 * offer's instance ID is the host → send it `['H'][version][token]` → request the data path with
 * the offer's passphrase → once the network is there, connect to the host's address and port from
 * [WifiAwareNetworkInfo] → send the `HELLO` record → [AwareClientEvent.Up].
 *
 * **Experimental, paired scheme** ([AwareScheme.PAIRED], for an iPhone host): subscribe with
 * pairing enabled; every publisher this phone has paired with verifies the pairing by itself; for
 * each verified one in turn, request a data path secured by the pairing, connect, send the
 * `HELLO`, and keep the first host that echoes the token. Pair first with
 * [io.hypercode.hyperblekit.WifiAwarePairing].
 *
 * The whole setup must finish within [WifiAwareSupport.SETUP_TIMEOUT_MILLIS], or it ends in
 * [AwareClientEvent.Down].
 *
 * Main thread only; the socket threads post back to it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
internal class WifiAwareClientPath(
    context: Context,
    private val serviceName: String,
    private val crossPlatform: Boolean = false,
) : AwareClientPath {

    private val appContext = context.applicationContext
    private val handler = WifiAwareSupport.mainHandler
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var listener: ((AwareClientEvent) -> Unit)? = null

    /** Counts attempts, so a callback of an abandoned one does nothing. */
    private var generation = 0
    private var offer: AwareOffer? = null
    private var session: WifiAwareSession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peer: PeerHandle? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isConnectingSocket = false
    private var connection: AwareConnection? = null
    private var isUp = false
    private var scheme = AwareScheme.NAN

    /** Counts data-path tries within an attempt (the paired scheme tries one paired peer after another). */
    private var pathGeneration = 0

    /** Paired scheme: verified peers not tried yet, in the order they verified. */
    private val pairedPeers = ArrayDeque<Pair<PeerHandle, String>>()
    private val triedPeers = HashSet<PeerHandle>()
    private var unpairedLogged = false

    override fun supports(scheme: AwareScheme): Boolean = when (scheme) {
        AwareScheme.NAN -> WifiAwareSupport.isSupported(appContext)
        AwareScheme.PAIRED -> crossPlatform && WifiAwareSupport.isPairingSupported(appContext)
    }

    override fun setListener(listener: ((AwareClientEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(offer: AwareOffer, scheme: AwareScheme) {
        close()
        this.offer = offer
        this.scheme = scheme
        val armed = generation
        handler.postDelayed({
            if (armed == generation && !isUp) fail("no data path within ${WifiAwareSupport.SETUP_TIMEOUT_MILLIS / 1000} s")
        }, WifiAwareSupport.SETUP_TIMEOUT_MILLIS)

        val manager = WifiAwareSupport.manager(appContext)
        if (manager == null || !WifiAwareSupport.isAvailable(appContext)) {
            handler.post { if (armed == generation) fail("Wi-Fi Aware is not available") }
            return
        }
        guarded("attach") {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (armed != generation) {
                        session.close()
                        return
                    }
                    attached(session)
                }

                override fun onAttachFailed() {
                    if (armed == generation) fail("Wi-Fi Aware attach failed")
                }
            }, handler)
        } ?: handler.post { if (armed == generation) fail("Wi-Fi Aware attach refused") }
    }

    override fun send(message: ByteArray): Boolean = if (isUp) connection?.sendMessage(message) ?: false else false

    override fun sendFrame(payload: ByteArray): FrameSendResult =
        if (isUp) connection?.sendFrame(payload) ?: FrameSendResult.FAILED else FrameSendResult.FAILED

    override fun close() {
        generation += 1
        connection?.close()
        connection = null
        teardownSession()
    }

    override fun closeAfterFlush(then: () -> Unit) {
        val flushing = connection
        generation += 1
        connection = null
        if (flushing == null) {
            teardownSession()
            then()
            return
        }
        flushing.closeAfterFlush(WifiAwareSupport.FLUSH_TIMEOUT_MILLIS) {
            teardownSession()
            then()
        }
    }

    private fun teardownSession() {
        isUp = false
        isConnectingSocket = false
        offer = null
        peer = null
        pathGeneration += 1
        pairedPeers.clear()
        triedPeers.clear()
        unpairedLogged = false
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        subscribeSession?.close()
        subscribeSession = null
        session?.close()
        session = null
    }

    private fun fail(reason: String) {
        close()
        listener?.invoke(AwareClientEvent.Down(reason))
    }

    // --- Setup steps (main thread) ---

    private fun attached(session: WifiAwareSession) {
        this.session = session
        val armed = generation
        val builder = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
        if (scheme == AwareScheme.PAIRED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setPairingConfig(WifiAwareSupport.subscriberPairingConfig())
        }
        val config = builder.build()
        guarded("subscribe") {
            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    if (armed != generation) {
                        session.close()
                        return
                    }
                    subscribeSession = session
                }

                override fun onSessionConfigFailed() {
                    if (armed == generation) fail("subscribing failed")
                }

                override fun onSessionTerminated() {
                    if (armed == generation && !isUp) fail("subscription ended")
                }

                override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                    if (armed == generation && scheme == AwareScheme.NAN) discovered(peerHandle, serviceSpecificInfo)
                }

                // --- Paired scheme (experimental); only called on Android 13 / 14 and later ---

                override fun onServiceDiscovered(info: ServiceDiscoveryInfo) {
                    if (armed != generation || scheme != AwareScheme.PAIRED) return
                    // A paired publisher verifies the pairing by itself; until then nothing to do.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && info.pairedAlias == null && !unpairedLogged) {
                        unpairedLogged = true
                        HyperBleLog.event("Wi-Fi Aware: found a phone that is not paired with this one; pair them first")
                    }
                }

                override fun onPairingVerificationSucceed(peerHandle: PeerHandle, alias: String) {
                    if (armed != generation || scheme != AwareScheme.PAIRED || peerHandle in triedPeers) return
                    HyperBleLog.event("Wi-Fi Aware: paired $alias is near, trying it")
                    pairedPeers.addLast(peerHandle to alias)
                    if (peer == null) tryNextPaired()
                }

                override fun onPairingVerificationFailed(peerHandle: PeerHandle) {
                    if (armed == generation) HyperBleLog.event("Wi-Fi Aware: a paired phone failed verification; pair them again", LogLevel.WARN)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    if (armed == generation) discoveryMessageSent()
                }

                override fun onMessageSendFailed(messageId: Int) {
                    if (armed != generation) return
                    if (messageId >= WifiAwareSupport.DISCOVERY_MESSAGE_ATTEMPTS) {
                        fail("the host did not take the discovery message")
                    } else {
                        handler.postDelayed({ if (armed == generation) sendDiscoveryMessage(messageId + 1) },
                            WifiAwareSupport.DISCOVERY_MESSAGE_RETRY_MILLIS)
                    }
                }
            }, handler)
        } ?: fail("subscribe refused")
    }

    private fun discovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?) {
        val wanted = offer ?: return
        if (peer != null) return
        val instanceId = AwareWire.parseServiceInfo(serviceSpecificInfo) ?: return
        // Other hosts nearby publish the same service; only ours carries the offer's instance ID.
        if (!instanceId.contentEquals(wanted.instanceId)) return
        peer = peerHandle
        HyperBleLog.event("Wi-Fi Aware: found the host, sending the discovery message")
        sendDiscoveryMessage(1)
    }

    /** [attempt] doubles as the message ID, so a failure tells which attempt it was. */
    private fun sendDiscoveryMessage(attempt: Int) {
        val subscription = subscribeSession ?: return
        val target = peer ?: return
        val token = offer?.token ?: return
        guarded("sendMessage") { subscription.sendMessage(target, attempt, AwareWire.discoveryHello(token)) }
            ?: fail("the discovery message could not be sent")
    }

    private fun discoveryMessageSent() {
        if (networkCallback != null) return
        val subscription = subscribeSession ?: return
        val target = peer ?: return
        val wanted = offer ?: return
        requestDataPath(WifiAwareNetworkSpecifier.Builder(subscription, target).setPskPassphrase(wanted.passphrase))
    }

    /** Paired scheme: the next verified peer that has not been tried yet. */
    private fun tryNextPaired() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val subscription = subscribeSession ?: return
        val (next, alias) = pairedPeers.removeFirstOrNull() ?: return
        triedPeers += next
        peer = next
        val builder = WifiAwareNetworkSpecifier.Builder(subscription, next)
        WifiAwareSupport.pairedSecurity()?.let(builder::setDataPathSecurityConfig)
        HyperBleLog.event("Wi-Fi Aware: requesting the data path with paired $alias")
        requestDataPath(builder)
    }

    private fun requestDataPath(builder: WifiAwareNetworkSpecifier.Builder) {
        val specifier = guarded("network specifier") { builder.build() } ?: return pathFailed("the data path could not be described")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val armed = generation
        val path = ++pathGeneration
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr ?: return
                val port = info.port
                handler.post { if (isCurrent(armed, path)) networkReady(network, InetSocketAddress(address, port)) }
            }

            override fun onUnavailable() {
                handler.post { if (isCurrent(armed, path)) pathFailed("the data path was refused") }
            }

            override fun onLost(network: Network) {
                handler.post { if (isCurrent(armed, path)) pathFailed("the data path was lost") }
            }
        }
        networkCallback = callback
        HyperBleLog.event("Wi-Fi Aware: requesting the data path")
        guarded("requestNetwork") {
            connectivity.requestNetwork(request, callback, handler, WifiAwareSupport.NETWORK_REQUEST_TIMEOUT_MILLIS)
        } ?: run {
            networkCallback = null
            pathFailed("the data path could not be requested")
        }
    }

    private fun isCurrent(armed: Int, path: Int) = armed == generation && path == pathGeneration

    /**
     * One data path did not work. Before the link is up, the paired scheme moves on to the next
     * paired peer (it may simply have been another host); anything else ends the attempt.
     */
    private fun pathFailed(reason: String) {
        if (scheme != AwareScheme.PAIRED || isUp) {
            fail(reason)
            return
        }
        HyperBleLog.event("Wi-Fi Aware: that paired phone is not our host ($reason), trying the next one")
        pathGeneration += 1
        connection?.close()
        connection = null
        isConnectingSocket = false
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        peer = null
        tryNextPaired()
    }

    private fun networkReady(network: Network, host: InetSocketAddress) {
        if (isConnectingSocket || connection != null) return
        if (host.port == 0) {
            pathFailed("the host gave no port")
            return
        }
        isConnectingSocket = true
        val armed = generation
        val path = pathGeneration
        val token = offer?.token ?: return
        Thread({
            val socket = try {
                network.socketFactory.createSocket().apply { connect(host, CONNECT_TIMEOUT_MILLIS) }
            } catch (e: IOException) {
                handler.post { if (isCurrent(armed, path)) pathFailed("could not connect: ${e.message}") }
                return@Thread
            }
            handler.post {
                if (isCurrent(armed, path)) socketConnected(socket, token) else runCatching { socket.close() }
            }
        }, "HyperBleKit-aware-connect").apply { isDaemon = true }.start()
    }

    private fun socketConnected(socket: Socket, token: ByteArray) {
        val armed = generation
        val path = pathGeneration
        val opened = AwareConnection(
            socket,
            post = { handler.post(it) },
            onRecord = { record ->
                if (isCurrent(armed, path)) {
                    when (record.kind) {
                        AwareStream.MESSAGE -> if (isUp) listener?.invoke(AwareClientEvent.MessageReceived(record.body))
                        AwareStream.FRAME -> if (isUp) listener?.invoke(AwareClientEvent.FrameReceived(record.body))
                        AwareStream.HELLO -> if (!isUp && record.body.contentEquals(token)) up(socket)
                    }
                }
            },
            onClosed = { reason -> if (isCurrent(armed, path)) pathFailed(reason) },
        )
        connection = opened
        isConnectingSocket = false
        opened.start()
        opened.sendRecord(AwareStream.HELLO, token)
        if (scheme == AwareScheme.NAN) {
            // The host was picked by its instance ID: it is ours.
            up(socket)
            return
        }
        // Paired scheme: only the host that knows the token echoes it.
        handler.postDelayed({
            if (isCurrent(armed, path) && !isUp) pathFailed("no answer to the HELLO")
        }, HELLO_TIMEOUT_MILLIS)
    }

    private fun up(socket: Socket) {
        isUp = true
        HyperBleLog.event("Wi-Fi Aware: connected to ${socket.inetAddress}")
        listener?.invoke(AwareClientEvent.Up)
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
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val HELLO_TIMEOUT_MILLIS = 3_000L
    }
}
