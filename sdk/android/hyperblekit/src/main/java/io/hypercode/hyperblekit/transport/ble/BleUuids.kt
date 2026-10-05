package io.hypercode.hyperblekit.transport.ble

import android.os.ParcelUuid
import io.hypercode.hyperblekit.HyperBleKitConfig
import java.util.UUID

/**
 * BLE identifiers from the wire specification "BLE Layout", for one [HyperBleKitConfig]. The three
 * characteristic UUIDs are derived from the service UUID the same way on iOS, which must stay
 * identical for cross-platform discovery to work.
 */
internal class BleUuids(config: HyperBleKitConfig) {
    val service: UUID = config.serviceUuid

    // GATT characteristics (wire specification "GATT Service").
    val gameInfo: UUID = config.gameInfoUuid
    val clientToHost: UUID = config.clientToHostUuid
    val hostToClient: UUID = config.hostToClientUuid

    val serviceParcel: ParcelUuid = ParcelUuid(service)

    /**
     * Company ID for the manufacturer-specific advertising data that carries the game code on an
     * Android host. The default 0xFFFF is reserved by the Bluetooth SIG for testing.
     */
    val manufacturerId: Int = config.manufacturerId

    companion object {
        /** Client Characteristic Configuration descriptor: a client writes it to enable notifications. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
