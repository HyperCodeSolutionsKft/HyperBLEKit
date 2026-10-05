import Combine
import Foundation

/// One host↔client link, as seen by the host. Only meaningful on this device and only while
/// the link is up: use it to route messages, never to identify a player (that's `deviceId`).
typealias LinkID = UUID

/// What became of a real-time frame handed to the transport.
enum FrameSendResult: Equatable {
    /// In the frame slot, or already out.
    case queued
    /// Took the place of a frame in the slot that had not gone out yet.
    case replaced
    /// Not sent: no link, or the payload does not fit one packet.
    case failed
}

enum HostTransportEvent {
    /// A client subscribed to notifications and can now be sent to.
    case linkUp(LinkID, maxChunkLength: Int)
    /// The client unsubscribed or Bluetooth went away.
    case linkDown(LinkID)
    /// A whole message arrived, reassembled from `chunks` chunks.
    case message(LinkID, Data, chunks: Int)
    /// A real-time frame arrived and was newer than the last one taken from this link.
    case frame(LinkID, Data)
    /// Connection events and problems, for the debug log.
    case log(String)
}

/// Host side of a session: advertises the game and exchanges whole messages with clients.
///
/// Contract for implementations, on top of `GameAdvertising`:
/// - Serve the GATT service before advertising starts.
/// - Chunk per link, never drop or reorder chunks, respect notification backpressure.
/// - Real-time frames: one slot per link, numbered per link from 0 when it comes up; incoming
///   frames that are stale or duplicated are dropped and counted, never logged one by one.
/// - Know nothing about JSON or game rules.
/// - Publish on the main thread.
protocol GameHostTransport: GameAdvertising {
    var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { get }
    /// Sets the value served by the `GameInfo` characteristic.
    func setGameInfo(_ info: Data)
    /// Queues a message for one client. Returns the number of chunks, 0 if it could not be sent.
    @discardableResult
    func send(_ message: Data, to link: LinkID) -> Int
    /// Puts a real-time frame in this link's frame slot, replacing a frame that has not gone out
    /// yet. It goes before the next reliable chunk. The payload must fit one packet.
    @discardableResult
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult
    /// Frames from any client dropped as stale or duplicated so far.
    var staleFrameCount: Int { get }
    /// Stops advertising at once, so nobody new finds this host, but keeps serving the clients
    /// for a moment so the last messages still get out. Then stops completely and calls `completion`.
    func stopAfterFlush(then completion: @escaping () -> Void)
}

enum ClientTransportEvent {
    /// A host was found and is being connected to and set up (discovery, GameInfo, subscribing).
    /// The attempt ends in `.ready` or `.disconnected`.
    case hostFound
    /// The raw `GameInfo` value, read before subscribing.
    case gameInfo(Data)
    /// Subscribed to host notifications: messages can be sent from now on.
    case ready(maxChunkLength: Int)
    /// A whole message arrived, reassembled from `chunks` chunks.
    case message(Data, chunks: Int)
    /// A real-time frame arrived and was newer than the last one taken.
    case frame(Data)
    /// The link is gone or could not be set up. `reason` is nil after `disconnect()`.
    case disconnected(reason: String?)
    /// Connection events and problems, for the debug log.
    case log(String)
}

/// Client side of a session: one connection to one host.
///
/// Contract for implementations:
/// - `connect` → discover → read GameInfo → subscribe, then publish `.ready`.
/// - Every `connect` ends in `.ready` or `.disconnected`; it never hangs. A new `connect`
///   first tears down whatever the previous one left.
/// - Writes are with response, chunked, one chunk at a time.
/// - Real-time frames: one slot, numbered from 0 on every `.ready`; incoming frames that are
///   stale or duplicated are dropped and counted, never logged one by one.
/// - Know nothing about JSON or game rules.
/// - Publish on the main thread.
protocol GameClientTransport: AnyObject {
    var eventsPublisher: AnyPublisher<ClientTransportEvent, Never> { get }
    /// - Parameter gameId: `DiscoveredGame.id` from the scanner.
    func connect(to gameId: UUID)
    /// Scans for the host advertising this game code and connects to it. Used to get back to a
    /// game: the host's identifier may have changed since it was first found, its code has not.
    func connect(toGameCode gameCode: String)
    /// Queues a message for the host. Returns the number of chunks, 0 if it could not be sent.
    @discardableResult
    func send(_ message: Data) -> Int
    /// Puts a real-time frame in the frame slot, replacing one that waits behind the write in
    /// flight. It goes before the next reliable chunk. The payload must fit one packet.
    @discardableResult
    func sendFrame(_ payload: Data) -> FrameSendResult
    /// Frames from the host dropped as stale or duplicated so far, over all connections.
    var staleFrameCount: Int { get }
    /// Sends what is still queued, then closes the connection.
    func disconnect()
    /// Closes the connection and stops `connect(toGameCode:)` from picking this host again,
    /// so the scan can find another device advertising the same game.
    func rejectCurrentHost()
    func forgetRejectedHosts()
}
