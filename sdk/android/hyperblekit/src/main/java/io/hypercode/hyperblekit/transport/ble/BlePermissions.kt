package io.hypercode.hyperblekit.transport.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions BLE discovery needs, which differ by API level:
 *  - Android 12+ (API 31): the granular BLUETOOTH_SCAN / CONNECT / ADVERTISE permissions.
 *  - Android 8–11: scanning is gated on location, so ACCESS_FINE_LOCATION is requested at runtime
 *    (the plain BLUETOOTH / BLUETOOTH_ADMIN permissions are install-time, declared in the manifest).
 */
internal object BlePermissions {

    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun allGranted(context: Context): Boolean =
        required().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
