package io.hypercode.hyperblekit.transport.ble

import android.bluetooth.BluetoothDevice

/**
 * The [BluetoothDevice] objects of the current scan list, by address.
 *
 * A device taken from a scan result knows its address type (public or random); one rebuilt from
 * the bare address with `getRemoteDevice` does not, and connecting to a random address as if it
 * were public fails with status 133 — which is what an iPhone host advertises with.
 *
 * Filled by [BleGameScanner], read by [BleClientTransport]. Cleared when a new scan starts, not
 * when a scan stops, so the connect that follows `stopScan` still finds its device.
 * Main thread only, like both of its users.
 */
internal object BleDeviceCache {
    private val devices = HashMap<String, BluetoothDevice>()

    fun put(device: BluetoothDevice) {
        devices[device.address] = device
    }

    fun get(address: String): BluetoothDevice? = devices[address]

    fun clear() = devices.clear()
}
