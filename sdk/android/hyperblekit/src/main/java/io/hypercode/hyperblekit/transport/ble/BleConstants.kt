package io.hypercode.hyperblekit.transport.ble

/** BLE link limits and timeouts. See the wire specification "MTU and chunking" and "Connection Lifecycle". */
internal object BleConstants {
    /** ATT MTU every link starts with; assumed until `onMtuChanged` says otherwise. */
    const val DEFAULT_MTU = 23

    /** What an Android client asks for right after connecting. */
    const val REQUESTED_MTU = 247

    /** ATT opcode + handle in front of every write / notification: usable bytes are MTU − 3. */
    const val ATT_HEADER_SIZE = 3

    /**
     * Longest attribute value ATT allows. The platform throws for a longer write or notification,
     * and MTU − 3 exceeds it at MTU 517; it also bounds a prepared (long) write.
     */
    const val MAX_ATTRIBUTE_LENGTH = 512

    /** Max bytes of one write / notification on a link with this MTU, chunk header included. */
    fun maxChunkLength(mtu: Int): Int = minOf(mtu - ATT_HEADER_SIZE, MAX_ATTRIBUTE_LENGTH)

    /** Connect, MTU, discovery, GameInfo read and subscribe must finish within this time. */
    const val CONNECT_TIMEOUT_MILLIS = 15_000L

    /** After `disconnect()`, queued writes get this long before the link is closed anyway. */
    const val DISCONNECT_FLUSH_TIMEOUT_MILLIS = 2_000L

    /** After the last queued notification went out, a stopping host waits this long before closing. */
    const val HOST_STOP_LINGER_MILLIS = 300L

    /** How long after a central connects the host restarts advertising. */
    const val ADVERTISING_RESTART_DELAY_MILLIS = 500L

    /** The catch-all `GATT_ERROR` status Android reports for most failed connection attempts. */
    const val GATT_ERROR = 133

    /** A connect attempt that fails with [GATT_ERROR] before connecting is retried this many times. */
    const val GATT_ERROR_RETRIES = 2

    /** Pause between closing a [GATT_ERROR] attempt and the next one, so the stack can free it. */
    const val GATT_ERROR_RETRY_DELAY_MILLIS = 600L
}
