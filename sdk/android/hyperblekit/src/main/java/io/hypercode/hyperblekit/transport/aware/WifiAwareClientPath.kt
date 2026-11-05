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

    override val isSupported: Boolean get() = WifiAwareSupport.isSupported(appContext)

    override fun setListener(listener: ((AwareClientEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(offer: AwareOffer) {
        close()
        this.offer = offer
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
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
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
                    if (armed == generation) discovered(peerHandle, serviceSpecificInfo)
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
        val specifier = WifiAwareNetworkSpecifier.Builder(subscription, target)
            .setPskPassphrase(wanted.passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val armed = generation
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val info = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr ?: return
                val port = info.port
                handler.post { if (armed == generation) networkReady(network, InetSocketAddress(address, port)) }
            }

            override fun onUnavailable() {
                handler.post { if (armed == generation) fail("the data path was refused") }
            }

            override fun onLost(network: Network) {
                handler.post { if (armed == generation) fail("the data path was lost") }
            }
        }
        networkCallback = callback
        HyperBleLog.event("Wi-Fi Aware: requesting the data path")
        guarded("requestNetwork") {
            connectivity.requestNetwork(request, callback, handler, WifiAwareSupport.NETWORK_REQUEST_TIMEOUT_MILLIS)
        } ?: run {
            networkCallback = null
            fail("the data path could not be requested")
        }
    }

    private fun networkReady(network: Network, host: InetSocketAddress) {
        if (isConnectingSocket || connection != null) return
        if (host.port == 0) {
            fail("the host gave no port")
            return
        }
        isConnectingSocket = true
        val armed = generation
        val token = offer?.token ?: return
        Thread({
            val socket = try {
                network.socketFactory.createSocket().apply { connect(host, CONNECT_TIMEOUT_MILLIS) }
            } catch (e: IOException) {
                handler.post { if (armed == generation) fail("could not connect: ${e.message}") }
                return@Thread
            }
            handler.post {
                if (armed == generation) socketConnected(socket, token) else runCatching { socket.close() }
            }
        }, "HyperBleKit-aware-connect").apply { isDaemon = true }.start()
    }

    private fun socketConnected(socket: Socket, token: ByteArray) {
        val armed = generation
        val opened = AwareConnection(
            socket,
            post = { handler.post(it) },
            onRecord = { record ->
                if (armed == generation) {
                    when (record.kind) {
                        AwareStream.MESSAGE -> listener?.invoke(AwareClientEvent.MessageReceived(record.body))
                        AwareStream.FRAME -> listener?.invoke(AwareClientEvent.FrameReceived(record.body))
                    }
                }
            },
            onClosed = { reason -> if (armed == generation) fail(reason) },
        )
        connection = opened
        opened.start()
        opened.sendRecord(AwareStream.HELLO, token)
        isUp = true
        isConnectingSocket = false
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
    }
}
