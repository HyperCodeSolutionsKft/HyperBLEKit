import Combine
import Foundation

/// The Wi-Fi Aware side of a hybrid link, without the BLE part and without any protocol: it sets
/// up data paths when told to and carries whole messages and frames over them. The hybrid
/// transports decide when to use it.
///
/// Contract for implementations: publish on the main thread, in order; never publish for a link
/// after `close(_:)` for it (or after `close()` on the client side).
enum AwareHostEvent {
    /// The client of this link is connected over Wi-Fi Aware.
    case pathUp(LinkID)
    /// The data path of this link is gone or could not be set up.
    case pathDown(LinkID, reason: String)
    case message(LinkID, Data)
    case frame(LinkID, Data)
    /// For the debug log.
    case log(String)
}

/// Host side: publishes the service and accepts data paths from clients that got an offer.
protocol AwareHostPath: AnyObject {
    var eventsPublisher: AnyPublisher<AwareHostEvent, Never> { get }
    /// The hardware, the OS version and the app's setup allow Wi-Fi Aware at all.
    var isSupported: Bool { get }
    /// Published and able to take clients right now.
    var isReady: Bool { get }
    /// How many data paths can be up at once.
    var maxDataPaths: Int { get }
    /// Also takes clients by the paired scheme; announced in GameInfo as `awarePaired`.
    var acceptsPairedClients: Bool { get }
    func start()
    /// Stops publishing and drops every data path, without events.
    func stop()
    /// Like `stop()`, but what is queued still goes out (for a bounded time).
    func stopAfterFlush()
    /// Prepares a data path for this link; nil if not `isReady`.
    func offer(for link: LinkID) -> AwareOffer?
    /// Queues a message. False if the link has no data path up.
    func send(_ message: Data, to link: LinkID) -> Bool
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult
    /// Drops the data path (or the offer) of this link, without an event.
    func close(_ link: LinkID)
}

enum AwareClientEvent {
    case up
    case down(reason: String)
    case message(Data)
    case frame(Data)
    case log(String)
}

/// Client side: one data path to one host.
protocol AwareClientPath: AnyObject {
    var eventsPublisher: AnyPublisher<AwareClientEvent, Never> { get }
    /// The hardware, the OS version, the build and the config allow this scheme.
    func supports(_ scheme: AwareScheme) -> Bool
    /// Finds the host of `offer` by `scheme` and sets the data path up; ends in `.up` or `.down`.
    func connect(_ offer: AwareOffer, scheme: AwareScheme)
    func send(_ message: Data) -> Bool
    func sendFrame(_ payload: Data) -> FrameSendResult
    /// Drops the data path, without an event.
    func close()
    /// Sends what is queued (for a bounded time), drops the data path without an event, then
    /// calls `completion`.
    func closeAfterFlush(then completion: @escaping () -> Void)
}

/// Runs a block after a delay on the main thread; the hybrid transports' only timer.
typealias MainScheduler = (_ delay: TimeInterval, _ block: @escaping () -> Void) -> Void

enum MainSchedulers {
    static let main: MainScheduler = { delay, block in
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: block)
    }
}

/// The host path of a phone without Wi-Fi Aware: never ready, never offers.
final class UnsupportedAwareHostPath: AwareHostPath {
    var eventsPublisher: AnyPublisher<AwareHostEvent, Never> { Empty().eraseToAnyPublisher() }
    var isSupported: Bool { false }
    var isReady: Bool { false }
    var maxDataPaths: Int { 0 }
    var acceptsPairedClients: Bool { false }
    func start() {}
    func stop() {}
    func stopAfterFlush() {}
    func offer(for link: LinkID) -> AwareOffer? { nil }
    func send(_ message: Data, to link: LinkID) -> Bool { false }
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult { .failed }
    func close(_ link: LinkID) {}
}

/// The client path of a phone without Wi-Fi Aware: never asks for a data path.
final class UnsupportedAwareClientPath: AwareClientPath {
    var eventsPublisher: AnyPublisher<AwareClientEvent, Never> { Empty().eraseToAnyPublisher() }
    func supports(_ scheme: AwareScheme) -> Bool { false }
    func connect(_ offer: AwareOffer, scheme: AwareScheme) {}
    func send(_ message: Data) -> Bool { false }
    func sendFrame(_ payload: Data) -> FrameSendResult { .failed }
    func close() {}
    func closeAfterFlush(then completion: @escaping () -> Void) { completion() }
}
