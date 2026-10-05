package io.hypercode.hyperblekit.transport

import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.DiscoveredGame
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-neutral session layer: exchanging **whole messages** as bytes between a host and its
 * clients. Like [GameAdvertiser] / [GameScanner], nothing here refers to BLE or GATT; the BLE
 * implementations live in [io.hypercode.hyperblekit.transport.ble]. A transport knows
 * nothing about JSON or game rules, and chunking is its own business (chunk size is per link).
 */

/**
 * One host↔client link, as seen by the host. Only meaningful on this device and only while the
 * link is up: use it to route messages, never to identify a player (that's `deviceId`).
 */
internal typealias LinkId = String

/**
 * What became of a real-time frame handed to `sendFrame` (wire specification, "Real-time
 * frames"). Frames are never queued: each link has one slot for the newest frame.
 */
internal enum class FrameSendResult {
    /** The frame waits in the empty slot, or went out at once. */
    QUEUED,

    /** The frame took the place of an older one that had not been sent yet. */
    REPLACED,

    /** Not sent: no link, or the payload does not fit one packet on it. */
    FAILED,
}

internal sealed interface HostTransportEvent {
    /** A client subscribed to host messages and can now be sent to. */
    data class LinkUp(val link: LinkId, val maxChunkLength: Int) : HostTransportEvent

    /** The client unsubscribed or its connection went away. */
    data class LinkDown(val link: LinkId) : HostTransportEvent

    /** A whole message arrived, reassembled from [chunks] chunks. */
    class MessageReceived(val link: LinkId, val bytes: ByteArray, val chunks: Int) : HostTransportEvent

    /** A real-time frame arrived and was not stale. Not logged by the transport. */
    class FrameReceived(val link: LinkId, val payload: ByteArray) : HostTransportEvent

    /** The radio came back after an outage and the game is served again; every link was lost. */
    data object Restarted : HostTransportEvent
}

/**
 * Host side of a session: makes the game discoverable and exchanges whole messages with clients.
 *
 * Contract for implementations:
 *  - Be ready to accept clients before becoming discoverable, and stay discoverable until [stop].
 *  - Chunk per link, never reorder chunks, respect the link's backpressure. A frame may go out
 *    between two chunks of a message.
 *  - Deliver events to the listener on the main thread, in order.
 */
internal interface GameHostTransport {
    val state: StateFlow<AdvertisingState>

    /** Whether this device can host at all (what a client reports as `canHost`). */
    val canHost: Boolean

    fun setListener(listener: ((HostTransportEvent) -> Unit)?)

    fun start(gameCode: String)

    /** Stops being discoverable and drops every client. */
    fun stop()

    /**
     * Stops being discoverable at once, delivers what is still queued (for a bounded time), then
     * drops every client like [stop].
     */
    fun stopAfterFlush()

    /** Sets the game summary a client can read before joining (wire specification "GameInfo"). */
    fun setGameInfo(info: ByteArray)

    /** Queues a message for one client. Returns the number of chunks, 0 if it could not be sent. */
    fun send(message: ByteArray, to: LinkId): Int

    /**
     * Puts a real-time frame in the link's frame slot; it goes out before the next chunk. The
     * payload must fit one packet: at most the link's chunk size − 3 bytes. `frameSeq` is added
     * here and starts at 0 when the link comes up.
     */
    fun sendFrame(payload: ByteArray, to: LinkId): FrameSendResult

    /** Frames from [link] dropped as stale or duplicated since its link came up; 0 for no link. */
    fun staleFramesDropped(link: LinkId): Int
}

internal sealed interface ClientTransportEvent {
    /** The host's raw game summary, read before subscribing. */
    class GameInfoRead(val bytes: ByteArray) : ClientTransportEvent

    /** A search found the game and the link to that device is being set up. */
    data class HostFound(val gameId: String) : ClientTransportEvent

    /** Subscribed to host messages: messages can be sent from now on. */
    data class Ready(val maxChunkLength: Int) : ClientTransportEvent

    /** A whole message arrived, reassembled from [chunks] chunks. */
    class MessageReceived(val bytes: ByteArray, val chunks: Int) : ClientTransportEvent

    /** A real-time frame arrived and was not stale. Not logged by the transport. */
    class FrameReceived(val payload: ByteArray) : ClientTransportEvent

    /** The link is gone or could not be set up. [reason] is null after [GameClientTransport.disconnect]. */
    data class Disconnected(val reason: String?) : ClientTransportEvent
}

/**
 * Client side of a session: one connection to one host.
 *
 * Contract for implementations:
 *  - [connect] / [connectByGameCode] set the link up completely (including the subscription to host messages) and
 *    only then reports [ClientTransportEvent.Ready].
 *  - Sends are confirmed by the host, chunked, one chunk (or frame) at a time.
 *  - Deliver events to the listener on the main thread, in order.
 */
internal interface GameClientTransport {
    fun setListener(listener: ((ClientTransportEvent) -> Unit)?)

    /** @param gameId [DiscoveredGame.id] from the scanner. */
    fun connect(gameId: String)

    /**
     * Finds the host again by its advertised game code and connects to it — for a reconnect,
     * where the scanner's old [DiscoveredGame.id] may no longer be valid. Any link that is still
     * up is closed first, without a [ClientTransportEvent.Disconnected] for it.
     *
     * Ends like [connect]: [ClientTransportEvent.Ready], or `Disconnected` with a reason when the
     * attempt fails. It never gives up searching by itself: the caller decides how long to keep
     * trying, calls this again after a failed attempt, and calls [disconnect] to stop for good
     * (until then the transport may keep its search running between attempts).
     */
    fun connectByGameCode(gameCode: String)

    /**
     * The device the current attempt reached is not the host that is wanted (decided from its
     * game summary): close the link without a [ClientTransportEvent.Disconnected] and, if this
     * attempt came from [connectByGameCode], keep searching without ever picking that device again
     * during this search.
     */
    fun skipCurrentHost()

    /** Queues a message for the host. Returns the number of chunks, 0 if it could not be sent. */
    fun send(message: ByteArray): Int

    /**
     * Puts a real-time frame in the frame slot. It is written with response like a chunk, after
     * the write in flight and before the next chunk; a newer frame replaces one that still waits.
     * The payload must fit one packet: at most the link's chunk size − 3 bytes.
     */
    fun sendFrame(payload: ByteArray): FrameSendResult

    /** Frames from the host dropped as stale or duplicated since the link came up. */
    val staleFramesDropped: Int

    /**
     * Evaluates the transport's own timeouts against real elapsed time. Call it after the app may
     * have been frozen (and periodically): a frozen app's timers do not run, so a timeout that
     * expired meanwhile would otherwise fire late.
     */
    fun checkTimeouts()

    /** Sends what is still queued, then closes the connection. Also stops a search in progress. */
    fun disconnect()
}
