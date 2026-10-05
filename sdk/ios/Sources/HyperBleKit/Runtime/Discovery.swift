import Combine
import Foundation

/// Looks for nearby games for `HyperBleKit`: the scanner's list in a stable order and when the
/// scan started.
final class Discovery: ObservableObject {
    /// In the order they were found, so rows don't jump around as RSSI changes.
    @Published private(set) var games: [DiscoveredGame] = []
    @Published private(set) var state: ScanningState = .idle
    private(set) var scanStartedAt: Date?

    private let scanner: GameScanning
    private var cancellables = Set<AnyCancellable>()

    init(scanner: GameScanning) {
        self.scanner = scanner
        scanner.statePublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
        scanner.gamesPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.games = $0.sorted { $0.discoveredAt < $1.discoveredAt } }
            .store(in: &cancellables)
    }

    func start(now: Date = Date()) {
        scanStartedAt = now
        scanner.start()
    }

    func stop() { scanner.stop() }

    /// Time from scan start to first sighting, for the "Discovery" measurement.
    func discoveryTime(of game: DiscoveredGame) -> TimeInterval? {
        scanStartedAt.map { max(0, game.discoveredAt.timeIntervalSince($0)) }
    }
}
