package io.hypercode.hyperblekit.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import io.hypercode.hyperblekit.GameCode
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.AdvertiseConfig
import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.transport.GameAdvertiser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE implementation of [GameAdvertiser].
 *
 * Per the wire specification's BLE layout, an Android host advertises the service UUID in the main packet and
 * carries the game code as manufacturer-specific data (company ID 0xFFFF, the 4 ASCII bytes of the
 * code) in the scan response. It deliberately does NOT use `setIncludeDeviceName(true)`: that would
 * broadcast — and require renaming — the user's system Bluetooth name. (iOS hosts instead advertise
 * the code in the "CG-XXXX" local name; our scanner accepts either source.)
 *
 * Permissions (BLUETOOTH_ADVERTISE on 12+) are verified by the app before any call here, hence the
 * class-level [SuppressLint].
 */
@SuppressLint("MissingPermission")
internal class BleGameAdvertiser(context: Context, private val uuids: BleUuids) : GameAdvertiser {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private val _state = MutableStateFlow<AdvertisingState>(AdvertisingState.Idle)
    override val state: StateFlow<AdvertisingState> = _state.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    override fun startAdvertising(config: AdvertiseConfig) {
        stopAdvertising()

        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off — turn it on to host.")
            return
        }
        // Not every chipset can act as a BLE peripheral; without this, hosting is impossible.
        if (!adapter.isMultipleAdvertisementSupported) {
            _state.value = AdvertisingState.Unsupported(
                "This device can't advertise over BLE, so it can't host a game."
            )
            HyperBleLog.log(TAG, "Advertising unsupported (isMultipleAdvertisementSupported=false)", LogLevel.WARN)
            return
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            fail("No BLE advertiser available on this device.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // clients will connect in Phase 2
            .build()

        // Main packet: flags + the 128-bit service UUID (~21 of 31 bytes). The game code rides in
        // the scan response as manufacturer data so both fit without touching the device name.
        val codeBytes = config.gameCode.toByteArray(Charsets.US_ASCII)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(uuids.serviceParcel)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(uuids.manufacturerId, codeBytes)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                _state.value = AdvertisingState.Advertising(config.gameCode)
                HyperBleLog.log(TAG, "Advertising code ${config.gameCode} (service ${uuids.service}, mfr 0x${uuids.manufacturerId.toString(16)})")
            }

            override fun onStartFailure(errorCode: Int) {
                fail("Advertising failed to start (error $errorCode).")
            }
        }

        _state.value = AdvertisingState.Starting
        advertiser = leAdvertiser
        callback = cb
        try {
            leAdvertiser.startAdvertising(settings, advertiseData, scanResponse, cb)
        } catch (e: SecurityException) {
            // Hosting without the Bluetooth permissions: reported as failed advertising, never thrown.
            advertiser = null
            callback = null
            _state.value = AdvertisingState.Failed("Bluetooth permission missing — grant it to host.")
            HyperBleLog.log(TAG, "Cannot advertise: Bluetooth permission missing (${e.message})", LogLevel.ERROR)
        }
    }

    override fun stopAdvertising() {
        val advertiser = advertiser
        val callback = callback
        if (advertiser != null && callback != null) {
            runCatching { advertiser.stopAdvertising(callback) }
            HyperBleLog.log(TAG, "Stopped advertising")
        }
        this.advertiser = null
        this.callback = null
        // Keep a terminal Unsupported/Failed state visible; otherwise return to Idle.
        if (_state.value is AdvertisingState.Advertising || _state.value is AdvertisingState.Starting) {
            _state.value = AdvertisingState.Idle
        }
    }

    private fun fail(reason: String) {
        _state.value = AdvertisingState.Failed(reason)
        HyperBleLog.log(TAG, reason, LogLevel.ERROR)
    }

    private companion object {
        const val TAG = "BleAdvertiser"
    }
}
