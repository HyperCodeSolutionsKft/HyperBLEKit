import Combine
import Foundation

/// Never reaches a host: every connection attempt fails after a moment.
/// Used on the simulator, which has no Bluetooth.
final class FakeClientTransport: GameClientTransport {
    private let events = PassthroughSubject<ClientTransportEvent, Never>()

    var eventsPublisher: AnyPublisher<ClientTransportEvent, Never> { events.eraseToAnyPublisher() }

    func connect(to gameId: UUID) { fail() }

    func connect(toGameCode gameCode: String) { fail() }

    private func fail() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.events.send(.disconnected(reason: "Connecting needs a real device: the simulator has no Bluetooth."))
        }
    }

    @discardableResult
    func send(_ message: Data) -> Int { 0 }

    @discardableResult
    func sendFrame(_ payload: Data) -> FrameSendResult { .failed }

    var staleFrameCount: Int { 0 }

    func disconnect() {}

    func rejectCurrentHost() {}

    func forgetRejectedHosts() {}
}
