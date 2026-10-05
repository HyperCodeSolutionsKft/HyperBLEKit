package io.hypercode.hyperblekit.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.hypercode.hyperblekit.GameCode
import io.hypercode.hyperblekit.GameConstants
import io.hypercode.hyperblekit.Platform
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.DiscoveredGame
import io.hypercode.hyperblekit.transport.DiscoveryRegistry
import io.hypercode.hyperblekit.transport.GameScanner
import io.hypercode.hyperblekit.ScanningState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE implementation of [GameScanner]: scans filtered by the service UUID, parses the "CG-XXXX"
 * name into a game code, and maintains a live, auto-pruned list of discovered games.
 *
 * Scan callbacks and the prune tick are marshalled onto the main thread, so the [DiscoveryRegistry]
 * is only ever touched from one thread. Monotonic [SystemClock.elapsedRealtime] is used for timing
 * and staleness (never wall-clock, which can jump).
 */
@SuppressLint("MissingPermission")
internal class BleGameScanner(
    context: Context,
    private val uuids: BleUuids,
    private val localNamePrefix: String,
) : GameScanner {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private val registry = DiscoveryRegistry(GameConstants.DISCOVERY_STALE_TIMEOUT_MILLIS)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ScanningState>(ScanningState.Idle)
    override val state: StateFlow<ScanningState> = _state.asStateFlow()

    private val _discoveredGames = MutableStateFlow<List<DiscoveredGame>>(emptyList())
    override val discoveredGames: StateFlow<List<DiscoveredGame>> = _discoveredGames.asStateFlow()

    private val _timeToFirstResultMillis = MutableStateFlow<Long?>(null)
    override val timeToFirstResultMillis: StateFlow<Long?> = _timeToFirstResultMillis.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var scanStartMillis: Long = 0L

    private val pruneTick = object : Runnable {
        override fun run() {
            _discoveredGames.value = registry.prune(SystemClock.elapsedRealtime())
            handler.postDelayed(this, GameConstants.DISCOVERY_PRUNE_INTERVAL_MILLIS)
        }
    }

    override fun startScan() {
        stopScan()

        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = ScanningState.Failed("Bluetooth is off — turn it on to find games.")
            HyperBleLog.log(TAG, "Cannot scan: Bluetooth off", LogLevel.ERROR)
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _state.value = ScanningState.Failed("No BLE scanner available on this device.")
            HyperBleLog.log(TAG, "Cannot scan: bluetoothLeScanner is null", LogLevel.ERROR)
            return
        }
        // Asked first: on Android 8–11 a scan without the location permission does not fail, it
        // just never finds anything.
        if (!BlePermissions.allGranted(appContext)) {
            permissionMissing(null)
            return
        }

        registry.clear()
        BleDeviceCache.clear()
        _discoveredGames.value = emptyList()
        _timeToFirstResultMillis.value = null
        scanStartMillis = SystemClock.elapsedRealtime()

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(uuids.serviceParcel).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleResult)
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = ScanningState.Failed("Scan failed (error $errorCode).")
                HyperBleLog.log(TAG, "Scan failed (error $errorCode)", LogLevel.ERROR)
            }
        }

        scanner = leScanner
        callback = cb
        _state.value = ScanningState.Scanning
        HyperBleLog.log(TAG, "Scan started, filtering on service ${uuids.service}")
        try {
            leScanner.startScan(filters, settings, cb)
        } catch (e: SecurityException) {
            // Revoked between the check above and the call.
            scanner = null
            callback = null
            permissionMissing(e)
            return
        }
        handler.postDelayed(pruneTick, GameConstants.DISCOVERY_PRUNE_INTERVAL_MILLIS)
    }

    /** A scan without the Bluetooth permissions: reported as a failed scan, never thrown at the caller. */
    private fun permissionMissing(cause: SecurityException?) {
        _state.value = ScanningState.Failed("Bluetooth permission missing — grant it to find games.")
        HyperBleLog.log(TAG, "Cannot scan: Bluetooth permission missing" + cause?.message?.let { " ($it)" }.orEmpty(), LogLevel.ERROR)
    }

    private fun handleResult(result: ScanResult) {
        // The service UUID matched; the game code arrives via the scan response and may not be
        // present on the very first callback — ignore until it parses. Accept either source:
        // the "CG-XXXX" local name (iOS hosts) or the manufacturer data (Android hosts).
        val record = result.scanRecord
        val gameCode = GameCode.fromLocalName(record?.deviceName, localNamePrefix)
            ?: GameCode.fromManufacturerData(record?.getManufacturerSpecificData(uuids.manufacturerId))
            ?: return

        // Kept for the connect: only the scanned device object carries the address type.
        BleDeviceCache.put(result.device)

        val now = SystemClock.elapsedRealtime()
        _discoveredGames.value = registry.observe(
            id = result.device.address,
            gameCode = gameCode,
            platform = Platform.UNKNOWN, // learned from GameInfo in Phase 2
            rssi = result.rssi,
            nowMillis = now,
        )

        if (_timeToFirstResultMillis.value == null) {
            val elapsed = now - scanStartMillis
            _timeToFirstResultMillis.value = elapsed
            HyperBleLog.log(TAG, "First result in ${elapsed}ms: CG-$gameCode rssi=${result.rssi}dBm")
        }
    }

    override fun stopScan() {
        val scanner = scanner
        val callback = callback
        if (scanner != null && callback != null) {
            runCatching { scanner.stopScan(callback) }
            HyperBleLog.log(TAG, "Scan stopped")
        }
        this.scanner = null
        this.callback = null
        handler.removeCallbacks(pruneTick)
        if (_state.value is ScanningState.Scanning) _state.value = ScanningState.Idle
    }

    private companion object {
        const val TAG = "BleScanner"
    }
}
