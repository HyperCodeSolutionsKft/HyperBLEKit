import Combine
import Foundation

/// Pretends to advertise; no client ever connects. Used on the simulator, which has no Bluetooth.
final class FakeHostTransport: GameHostTransport {
    private let state = CurrentValueSubject<AdvertisingState, Never>(.idle)
    private let events = PassthroughSubject<HostTransportEvent, Never>()

    var statePublisher: AnyPublisher<AdvertisingState, Never> { state.eraseToAnyPublisher() }
    var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { events.eraseToAnyPublisher() }

    func start(gameCode: String) {
        state.send(.starting(gameCode: gameCode))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            guard let self, self.state.value == .starting(gameCode: gameCode) else { return }
            self.state.send(.advertising(gameCode: gameCode))
        }
    }

    func stop() {
        state.send(.idle)
    }

    func stopAfterFlush(then completion: @escaping () -> Void) {
        stop()
        completion()
    }

    func setGameInfo(_ info: Data) {}

    @discardableResult
    func send(_ message: Data, to link: LinkID) -> Int { 0 }

    @discardableResult
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult { .failed }

    var staleFrameCount: Int { 0 }
}
