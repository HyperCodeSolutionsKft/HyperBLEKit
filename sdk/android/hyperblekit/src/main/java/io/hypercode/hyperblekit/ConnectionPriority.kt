package io.hypercode.hyperblekit

/**
 * How eagerly a client keeps its Bluetooth link to the host. A client requests it right after
 * connecting (Android `BluetoothGatt.requestConnectionPriority`):
 *
 * - [HIGH] — the shortest connection interval: lowest latency and highest throughput, most power.
 * - [BALANCED] — the stack's default interval.
 * - [LOW_POWER] — the longest interval: least power, highest latency.
 *
 * Set it through [HyperBleKit.connectionPriority] before joining; it takes effect on the next
 * connection (and on every reconnect after that). The host does not use it — only the connecting
 * side (the central) can request it, and only on Android. The default is [HIGH].
 */
public enum class ConnectionPriority { HIGH, BALANCED, LOW_POWER }

/** Shares the current [ConnectionPriority] between the kit and the client transports it makes. */
internal class ConnectionPriorityHolder {
    @Volatile
    var value: ConnectionPriority = ConnectionPriority.HIGH
}
