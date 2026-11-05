package io.hypercode.hyperblekit.transport.aware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.LinkId
import java.security.SecureRandom

/**
 * What Wi-Fi Aware needs on this phone.
 *
 * The SDK uses it from Android 10 (API 29): the data path needs `WifiAwareNetworkInfo` for the
 * peer's address and port. Before Android 13 it is gated on location like BLE scanning; from 13 on
 * it needs `NEARBY_WIFI_DEVICES`. `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` are install-time
 * permissions the app declares in its manifest.
 */
internal object WifiAwareSupport {
    const val MIN_SDK = Build.VERSION_CODES.Q

    fun permissions(): List<String> =
        when {
            Build.VERSION.SDK_INT < MIN_SDK -> emptyList()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The phone has Wi-Fi Aware, the Android version is recent enough, and the permissions are granted. */
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            (permissions() + INSTALL_TIME_PERMISSIONS).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

    /** [isSupported], and Wi-Fi Aware can be used right now (Wi-Fi on, no hotspot or other conflict). */
    @SuppressLint("MissingPermission") // ACCESS_WIFI_STATE is checked by isSupported first.
    fun isAvailable(context: Context): Boolean =
        isSupported(context) && manager(context)?.isAvailable == true

    fun manager(context: Context): WifiAwareManager? =
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    private val random = SecureRandom()

    fun randomId(): ByteArray = ByteArray(AwareWire.ID_LENGTH).also(random::nextBytes)

    /** A data-path passphrase: letters and digits, [AwareWire.PASSPHRASE_LENGTH] of them. */
    fun randomPassphrase(): String =
        String(CharArray(AwareWire.PASSPHRASE_LENGTH) { PASSPHRASE_ALPHABET[random.nextInt(PASSPHRASE_ALPHABET.length)] })

    val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val INSTALL_TIME_PERMISSIONS = listOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
    )
    private const val PASSPHRASE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** How long a link waits for a data path, from the offer (host) or from `connect` (client). */
    const val SETUP_TIMEOUT_MILLIS = 20_000L

    /** How long a closing data path gets to send what is queued. */
    const val FLUSH_TIMEOUT_MILLIS = 1_000L

    /** How long the network request for a data path may take before it counts as unavailable. */
    const val NETWORK_REQUEST_TIMEOUT_MILLIS = 15_000

    /** A client's discovery message is resent this many times in all if it fails. */
    const val DISCOVERY_MESSAGE_ATTEMPTS = 4
    const val DISCOVERY_MESSAGE_RETRY_MILLIS = 500L

    /** Data paths assumed before Android 13, which cannot tell. */
    const val ASSUMED_DATA_PATHS = 2
}

/** The host path of a phone without Wi-Fi Aware: never ready, never offers. */
internal object UnsupportedAwareHostPath : AwareHostPath {
    override val isSupported = false
    override val isReady = false
    override val maxDataPaths = 0
    override fun setListener(listener: ((AwareHostEvent) -> Unit)?) = Unit
    override fun start() = Unit
    override fun stop() = Unit
    override fun stopAfterFlush() = Unit
    override fun offer(link: LinkId): AwareOffer? = null
    override fun send(link: LinkId, message: ByteArray) = false
    override fun sendFrame(link: LinkId, payload: ByteArray) = FrameSendResult.FAILED
    override fun close(link: LinkId) = Unit
}

/** The client path of a phone without Wi-Fi Aware: never asks for a data path. */
internal object UnsupportedAwareClientPath : AwareClientPath {
    override val isSupported = false
    override fun setListener(listener: ((AwareClientEvent) -> Unit)?) = Unit
    override fun connect(offer: AwareOffer) = Unit
    override fun send(message: ByteArray) = false
    override fun sendFrame(payload: ByteArray) = FrameSendResult.FAILED
    override fun close() = Unit
    override fun closeAfterFlush(then: () -> Unit) = then()
}
