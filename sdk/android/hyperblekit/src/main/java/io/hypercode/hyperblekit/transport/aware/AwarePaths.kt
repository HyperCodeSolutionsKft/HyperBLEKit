package io.hypercode.hyperblekit.transport.aware

import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.LinkId

/**
 * The Wi-Fi Aware side of a hybrid link, without the BLE part and without any protocol: it sets
 * up data paths when told to and carries whole messages and frames over them. The
 * [io.hypercode.hyperblekit.transport.hybrid] transports decide when to use it.
 *
 * Contract for implementations: deliver events on the main thread, in order; never deliver an
 * event for a link after [AwareHostPath.close] for it (or [AwareClientPath.close]).
 */
internal sealed interface AwareHostEvent {
    /** The client of [link] is connected over Wi-Fi Aware. */
    data class PathUp(val link: LinkId) : AwareHostEvent

    /** The data path of [link] is gone or could not be set up. */
    data class PathDown(val link: LinkId, val reason: String) : AwareHostEvent

    class MessageReceived(val link: LinkId, val bytes: ByteArray) : AwareHostEvent

    class FrameReceived(val link: LinkId, val payload: ByteArray) : AwareHostEvent
}

/** Host side: publishes the service and accepts data paths from clients that got an offer. */
internal interface AwareHostPath {
    /** The hardware, the OS version and the permissions allow Wi-Fi Aware at all. */
    val isSupported: Boolean

    /** Published and able to take clients right now. */
    val isReady: Boolean

    /** How many data paths the chip can hold at once. */
    val maxDataPaths: Int

    /** Also takes clients by the paired (iPhone) scheme; announced in GameInfo as `awarePaired`. */
    val acceptsPairedClients: Boolean

    fun setListener(listener: ((AwareHostEvent) -> Unit)?)

    /** Starts publishing; [isReady] once that worked. */
    fun start()

    /** Stops publishing and drops every data path, without events. */
    fun stop()

    /** Like [stop], but what is queued still goes out (for a bounded time). */
    fun stopAfterFlush()

    /** Prepares a data path for [link]; null if not [isReady]. The offer stays valid for a while. */
    fun offer(link: LinkId): AwareOffer?

    /** Queues a message. False if [link] has no data path up. */
    fun send(link: LinkId, message: ByteArray): Boolean

    /** Puts a frame in the link's frame slot. */
    fun sendFrame(link: LinkId, payload: ByteArray): FrameSendResult

    /** Drops the data path (or the offer) of [link], without an event. */
    fun close(link: LinkId)
}

internal sealed interface AwareClientEvent {
    /** Connected to the host over Wi-Fi Aware. */
    data object Up : AwareClientEvent

    /** The data path is gone or could not be set up. */
    data class Down(val reason: String) : AwareClientEvent

    class MessageReceived(val bytes: ByteArray) : AwareClientEvent

    class FrameReceived(val payload: ByteArray) : AwareClientEvent
}

/** Client side: one data path to one host. */
internal interface AwareClientPath {
    /** The hardware, the OS version, the permissions and the config allow this scheme. */
    fun supports(scheme: AwareScheme): Boolean

    fun setListener(listener: ((AwareClientEvent) -> Unit)?)

    /** Finds the host of [offer] by [scheme] and sets the data path up; ends in [AwareClientEvent.Up] or `Down`. */
    fun connect(offer: AwareOffer, scheme: AwareScheme)

    /** Queues a message. False if no data path is up. */
    fun send(message: ByteArray): Boolean

    fun sendFrame(payload: ByteArray): FrameSendResult

    /** Drops the data path, without an event. */
    fun close()

    /** Sends what is queued (for a bounded time), drops the data path without an event, then calls [then]. */
    fun closeAfterFlush(then: () -> Unit)
}

/** Runs a block after a delay on the main thread; the hybrid transports' only timer. */
internal fun interface MainScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit)
}
