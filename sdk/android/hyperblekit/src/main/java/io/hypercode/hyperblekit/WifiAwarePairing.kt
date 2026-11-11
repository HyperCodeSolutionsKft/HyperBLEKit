package io.hypercode.hyperblekit

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.AwarePairingConfig
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.ServiceDiscoveryInfo
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import androidx.annotation.RequiresApi
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.aware.WifiAwareSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Where a [WifiAwarePairing] is; see [WifiAwarePairing.state]. */
public sealed interface WifiAwarePairingState {
    /** No pairing in progress. */
    public data object Idle : WifiAwarePairingState

    /**
     * [WifiAwarePairing.waitForPairing]: show [pin] to the user, who types it on the iPhone when its
     * pairing sheet asks for it.
     */
    public data class ShowPin(
        /** The six digits to show. */
        val pin: String,
    ) : WifiAwarePairingState

    /** [WifiAwarePairing.pairWithNearbyPhone]: looking for a nearby phone that waits for pairing. */
    public data object Searching : WifiAwarePairingState

    /** Found one: ask the user for the code the other phone shows, then call [WifiAwarePairing.enterPin]. */
    public data object EnterPin : WifiAwarePairingState

    /** The code is being checked. */
    public data object Pairing : WifiAwarePairingState

    /** Paired; the system keeps the pairing, so this is done once per pair of phones. */
    public data class Paired(
        /** The name this phone gave the other one. */
        val alias: String,
    ) : WifiAwarePairingState

    /** The pairing did not work. */
    public data class Failed(
        /** Why, in English, for a debug screen. */
        val reason: String,
    ) : WifiAwarePairingState
}

/**
 * **Experimental.** Pairs this phone with an iPhone for Wi-Fi Aware, as
 * [HyperBleKitConfig.experimentalCrossPlatformWifiAware] needs: an iPhone uses Wi-Fi Aware only with
 * devices it has paired with (Wi-Fi Aware 4.0 pairing, with a six-digit code).
 *
 * Two ways round, matching the two sides of the iPhone's `WiFiAwarePairingButton`:
 * - [waitForPairing] here, `.pairWithNearbyPhone` on the iPhone: the iPhone lists this phone, and
 *   its user types the code this phone shows ([WifiAwarePairingState.ShowPin]).
 * - [pairWithNearbyPhone] here, `.waitForPairing` on the iPhone: this phone finds the iPhone, the
 *   iPhone shows a code, and the user enters it here ([enterPin]).
 *
 * Nobody has reported Wi-Fi Aware pairing between Android and iOS working end to end yet; whether
 * it works depends on the phone's Wi-Fi chip and firmware. The log says how far it got.
 *
 * Main thread only.
 */
@SuppressLint("MissingPermission")
public class WifiAwarePairing internal constructor(
    context: Context,
    private val serviceName: String,
) {
    private val appContext = context.applicationContext
    private val handler = WifiAwareSupport.mainHandler
    private val _state = MutableStateFlow<WifiAwarePairingState>(WifiAwarePairingState.Idle)

    /** Where the pairing is. Changes on the main thread. */
    public val state: StateFlow<WifiAwarePairingState> = _state.asStateFlow()

    /** Counts attempts, so callbacks of an abandoned one do nothing. */
    private var generation = 0
    private var session: WifiAwareSession? = null
    private var discovery: DiscoverySession? = null
    private var peer: PeerHandle? = null

    /** True while a hosted game publishes the service itself: then [waitForPairing] needs no session of its own. */
    internal var hostIsPublishing: Boolean = false

    /**
     * Whether this phone can pair for Wi-Fi Aware: Android 14 or later, a Wi-Fi chip with Wi-Fi
     * Aware pairing, and the permissions granted. Asked from the system each time it is read.
     */
    public val isSupported: Boolean get() = WifiAwareSupport.isPairingSupported(appContext)

    /**
     * Lets a nearby iPhone pair with this phone for the next two minutes, and returns the code to
     * show (also in [state] as [WifiAwarePairingState.ShowPin]); null if [isSupported] is false.
     */
    public fun waitForPairing(): String? {
        stop()
        if (!isSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val pin = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(PIN_RANGE))
        _state.value = WifiAwarePairingState.ShowPin(pin)
        armTimeout()
        if (!hostIsPublishing) attachThen { publishForPairing(it) }
        HyperBleLog.event("Wi-Fi Aware pairing: waiting for a phone (code shown)")
        return pin
    }

    /** Looks for a nearby phone that waits for pairing; [state] says when to ask for its code. */
    public fun pairWithNearbyPhone() {
        stop()
        if (!isSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            _state.value = WifiAwarePairingState.Failed("Wi-Fi Aware pairing is not supported on this phone")
            return
        }
        _state.value = WifiAwarePairingState.Searching
        armTimeout()
        attachThen { subscribeForPairing(it) }
    }

    /** The code the other phone shows, once [state] is [WifiAwarePairingState.EnterPin]. */
    public fun enterPin(pin: String) {
        val target = peer
        val subscription = discovery
        if (_state.value != WifiAwarePairingState.EnterPin || target == null || subscription == null ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            return
        }
        _state.value = WifiAwarePairingState.Pairing
        guarded("initiatePairingRequest") {
            subscription.initiatePairingRequest(target, newAlias(), Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_PASN_128, pin)
        } ?: fail("the pairing request was refused")
    }

    /** Ends a pairing in progress; [state] goes back to [WifiAwarePairingState.Idle]. Pairings already made stay. */
    public fun stop() {
        generation += 1
        discovery?.close()
        discovery = null
        session?.close()
        session = null
        peer = null
        _state.value = WifiAwarePairingState.Idle
    }

    /** Forgets every phone this app paired with for Wi-Fi Aware. */
    public fun forgetPairedDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        guarded("resetPairedDevices") { WifiAwareSupport.manager(appContext)?.resetPairedDevices() }
        HyperBleLog.event("Wi-Fi Aware pairing: forgot every paired device")
    }

    // --- For the host path, which publishes the service while hosting ---

    /** The code for a pairing request that arrived now, or null if this phone is not waiting for one. */
    internal fun pinForIncomingRequest(): String? = (_state.value as? WifiAwarePairingState.ShowPin)?.pin

    internal fun pairingSucceeded(alias: String) {
        HyperBleLog.event("Wi-Fi Aware pairing: paired with $alias")
        generation += 1
        discovery?.close()
        discovery = null
        session?.close()
        session = null
        _state.value = WifiAwarePairingState.Paired(alias)
    }

    internal fun pairingFailed(reason: String) {
        if (_state.value is WifiAwarePairingState.Paired) return
        fail(reason)
    }

    internal fun newAlias(): String = "hbk-" + String.format(java.util.Locale.ROOT, "%06x", random.nextInt(ALIAS_RANGE))

    // --- Sessions ---

    private fun fail(reason: String) {
        HyperBleLog.event("Wi-Fi Aware pairing failed: $reason", LogLevel.WARN)
        stop()
        _state.value = WifiAwarePairingState.Failed(reason)
    }

    private fun armTimeout() {
        val armed = generation
        handler.postDelayed({
            if (armed == generation && _state.value !is WifiAwarePairingState.Paired) fail("no pairing within 2 minutes")
        }, TIMEOUT_MILLIS)
    }

    private fun attachThen(block: (WifiAwareSession) -> Unit) {
        val manager = WifiAwareSupport.manager(appContext)
        if (manager == null || !WifiAwareSupport.isAvailable(appContext)) {
            fail("Wi-Fi Aware is not available")
            return
        }
        val armed = generation
        guarded("attach") {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (armed != generation) {
                        session.close()
                        return
                    }
                    this@WifiAwarePairing.session = session
                    block(session)
                }

                override fun onAttachFailed() {
                    if (armed == generation) fail("Wi-Fi Aware attach failed")
                }
            }, handler)
        } ?: fail("Wi-Fi Aware attach refused")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun publishForPairing(session: WifiAwareSession) {
        val armed = generation
        val config = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setPairingConfig(WifiAwareSupport.publisherPairingConfig())
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()
        guarded("publish") {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    if (armed == generation) discovery = session else session.close()
                }

                override fun onSessionConfigFailed() {
                    if (armed == generation) fail("publishing for pairing failed")
                }

                override fun onPairingSetupRequestReceived(peerHandle: PeerHandle, requestId: Int) {
                    if (armed != generation) return
                    acceptIncoming(discovery ?: return, peerHandle, requestId)
                }

                override fun onPairingSetupSucceeded(peerHandle: PeerHandle, alias: String) {
                    if (armed == generation) pairingSucceeded(alias)
                }

                override fun onPairingSetupFailed(peerHandle: PeerHandle) {
                    if (armed == generation) fail("the other phone's pairing did not complete")
                }
            }, handler)
        } ?: fail("publish refused")
    }

    /** A pairing request reached a publication (this one's or the host's): accept it with the code shown. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    internal fun acceptIncoming(session: DiscoverySession, peerHandle: PeerHandle, requestId: Int) {
        val pin = pinForIncomingRequest()
        if (pin == null) {
            HyperBleLog.event("Wi-Fi Aware pairing: request while not waiting for one, rejected")
            guarded("rejectPairingRequest") { session.rejectPairingRequest(requestId, peerHandle) }
            return
        }
        HyperBleLog.event("Wi-Fi Aware pairing: request received, accepting with the code shown")
        _state.value = WifiAwarePairingState.Pairing
        guarded("acceptPairingRequest") {
            session.acceptPairingRequest(requestId, peerHandle, newAlias(), Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_PASN_128, pin)
        } ?: fail("accepting the pairing was refused")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun subscribeForPairing(session: WifiAwareSession) {
        val armed = generation
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setPairingConfig(WifiAwareSupport.subscriberPairingConfig())
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        guarded("subscribe") {
            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    if (armed == generation) discovery = session else session.close()
                }

                override fun onSessionConfigFailed() {
                    if (armed == generation) fail("subscribing for pairing failed")
                }

                override fun onServiceDiscovered(info: ServiceDiscoveryInfo) {
                    if (armed == generation) discovered(info)
                }

                override fun onBootstrappingSucceeded(peerHandle: PeerHandle, method: Int) {
                    if (armed != generation || peerHandle != peer) return
                    HyperBleLog.event("Wi-Fi Aware pairing: found a phone, enter the code it shows")
                    _state.value = WifiAwarePairingState.EnterPin
                }

                override fun onBootstrappingFailed(peerHandle: PeerHandle) {
                    if (armed == generation && peerHandle == peer) fail("the other phone did not take the code request")
                }

                override fun onPairingSetupSucceeded(peerHandle: PeerHandle, alias: String) {
                    if (armed == generation) pairingSucceeded(alias)
                }

                override fun onPairingSetupFailed(peerHandle: PeerHandle) {
                    if (armed == generation) fail("wrong code, or the other phone refused")
                }
            }, handler)
        } ?: fail("subscribe refused")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun discovered(info: ServiceDiscoveryInfo) {
        if (peer != null || _state.value != WifiAwarePairingState.Searching) return
        if (info.pairedAlias != null) {
            HyperBleLog.event("Wi-Fi Aware pairing: ${info.pairedAlias} is already paired, skipping it")
            return
        }
        val offered = info.pairingConfig
        if (offered == null || !offered.isPairingSetupEnabled ||
            offered.bootstrappingMethods and AwarePairingConfig.PAIRING_BOOTSTRAPPING_PIN_CODE_DISPLAY == 0
        ) {
            HyperBleLog.event("Wi-Fi Aware pairing: a phone that does not offer pairing by code, skipping it")
            return
        }
        val subscription = discovery ?: return
        peer = info.peerHandle
        guarded("initiateBootstrappingRequest") {
            subscription.initiateBootstrappingRequest(info.peerHandle, AwarePairingConfig.PAIRING_BOOTSTRAPPING_PIN_CODE_KEYPAD)
        } ?: fail("the code request could not be sent")
    }

    private inline fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            HyperBleLog.event("Wi-Fi Aware pairing $what: permission missing (${e.message})", LogLevel.ERROR)
            null
        } catch (e: IllegalArgumentException) {
            HyperBleLog.event("Wi-Fi Aware pairing $what: rejected (${e.message})", LogLevel.ERROR)
            null
        } catch (e: IllegalStateException) {
            HyperBleLog.event("Wi-Fi Aware pairing $what: not possible now (${e.message})", LogLevel.ERROR)
            null
        }

    private companion object {
        const val TIMEOUT_MILLIS = 120_000L
        const val PIN_RANGE = 1_000_000
        const val ALIAS_RANGE = 0x1000000
        val random = SecureRandom()
    }
}
