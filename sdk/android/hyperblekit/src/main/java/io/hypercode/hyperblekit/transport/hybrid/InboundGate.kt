package io.hypercode.hyperblekit.transport.hybrid

import io.hypercode.hyperblekit.transport.aware.MainScheduler

/**
 * Keeps the messages of one direction of a link in order while the sender moves from BLE to
 * Wi-Fi Aware (wire specification, "Switching paths").
 *
 * The sender sends `PATH_SWITCH(wifiAware)` over BLE after its last BLE message, then continues
 * on Wi-Fi Aware. Wi-Fi Aware is much faster, so its first messages can overtake the last BLE
 * ones: they are held here until the switch arrives over BLE, or for [timeoutMillis] at most if
 * it never does (a dropped BLE chunk). BLE messages are always delivered at once.
 *
 * Main thread only.
 */
internal class InboundGate(
    private val scheduler: MainScheduler,
    private val deliver: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var isOpen = false
    private val held = ArrayDeque<ByteArray>()

    /** Counts resets, so a timeout armed for an earlier path does nothing. */
    private var generation = 0

    /** A message that arrived over Wi-Fi Aware. */
    fun fromAware(message: ByteArray) {
        if (isOpen) {
            deliver(message)
            return
        }
        held.addLast(message)
        if (held.size > MAX_HELD) {
            log("PATH gate: $MAX_HELD messages held without PATH_SWITCH, opening")
            open()
            return
        }
        if (held.size == 1) {
            val armed = generation
            scheduler.schedule(timeoutMillis) {
                if (armed == generation && !isOpen && held.isNotEmpty()) {
                    log("PATH gate: no PATH_SWITCH after $timeoutMillis ms, opening")
                    open()
                }
            }
        }
    }

    /** `PATH_SWITCH(wifiAware)` arrived over BLE: every BLE message before it has been delivered. */
    fun open() {
        isOpen = true
        while (held.isNotEmpty()) deliver(held.removeFirst())
    }

    /**
     * The Wi-Fi Aware path is gone or not set up yet. What it delivered is handed on (it arrived,
     * it is better late than lost) and the gate is closed for the next path.
     */
    fun reset() {
        generation += 1
        while (held.isNotEmpty()) deliver(held.removeFirst())
        isOpen = false
    }

    internal companion object {
        const val TIMEOUT_MILLIS = 3_000L
        const val MAX_HELD = 256
    }
}
