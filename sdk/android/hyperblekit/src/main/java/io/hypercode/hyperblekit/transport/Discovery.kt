package io.hypercode.hyperblekit.transport

import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.ScanningState
import io.hypercode.hyperblekit.DiscoveredGame
import io.hypercode.hyperblekit.Platform
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-neutral discovery layer.
 *
 * These interfaces describe *finding games* without referring to BLE, GATT, Wi-Fi, or any other
 * technology. The BLE implementation lives in [io.hypercode.hyperblekit.transport.ble];
 * swapping to Wi-Fi Direct / Multipeer later means providing new implementations of
 * [GameAdvertiser] and [GameScanner] and nothing above this layer changes.
 *
 * Later phases (CONNECT, SHARE) extend this with a message-oriented transport
 * (connect / send / broadcast / onBytes); Phase 1 only needs discovery.
 */

/** What a host wants to make discoverable. */
internal data class AdvertiseConfig(
    val gameCode: String,
    val platform: Platform,
)

/** Host side of discovery: make this device findable by others. */
internal interface GameAdvertiser {
    val state: StateFlow<AdvertisingState>
    fun startAdvertising(config: AdvertiseConfig)
    fun stopAdvertising()
}

/** Client side of discovery: find nearby games. */
internal interface GameScanner {
    val state: StateFlow<ScanningState>
    val discoveredGames: StateFlow<List<DiscoveredGame>>

    /** Millis from [startScan] to the first discovered game; null until one appears. */
    val timeToFirstResultMillis: StateFlow<Long?>

    fun startScan()
    fun stopScan()
}
