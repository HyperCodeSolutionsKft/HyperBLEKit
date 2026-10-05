import Combine
import Foundation

/// Finds two made-up games with jittering RSSI. Used on the simulator and until `BLEScanner` exists.
final class FakeGameScanner: GameScanning {
    private let state = CurrentValueSubject<ScanningState, Never>(.idle)
    private let games = CurrentValueSubject<[DiscoveredGame], Never>([])
    private var timer: Timer?

    var statePublisher: AnyPublisher<ScanningState, Never> { state.eraseToAnyPublisher() }
    var gamesPublisher: AnyPublisher<[DiscoveredGame], Never> { games.eraseToAnyPublisher() }

    func start() {
        guard state.value != .scanning else { return }
        state.send(.scanning)
        let started = Date()
        let fakes = [("7K2F", -55, 0.4), ("Q9MX", -78, 1.2)].map { code, rssi, delay in
            DiscoveredGame(id: UUID(), gameCode: code, rssi: rssi,
                           discoveredAt: started.addingTimeInterval(delay), lastSeen: started)
        }
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            let now = Date()
            self.games.send(fakes
                .filter { $0.discoveredAt <= now }
                .map { game in
                    var game = game
                    game.rssi += Int.random(in: -4...4)
                    game.lastSeen = now
                    return game
                })
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        games.send([])
        state.send(.idle)
    }
}
