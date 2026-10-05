import Combine
import CoreBluetooth
import XCTest
@testable import HyperBleKit

private final class StubScanner: GameScanning {
    let state = CurrentValueSubject<ScanningState, Never>(.idle)
    let games = CurrentValueSubject<[DiscoveredGame], Never>([])
    private(set) var startCount = 0

    var statePublisher: AnyPublisher<ScanningState, Never> { state.eraseToAnyPublisher() }
    var gamesPublisher: AnyPublisher<[DiscoveredGame], Never> { games.eraseToAnyPublisher() }
    func start() { startCount += 1 }
    func stop() {}
}

private final class StubAdvertiser: GameHostTransport {
    let state = CurrentValueSubject<AdvertisingState, Never>(.idle)
    private(set) var startedCode: String?

    var statePublisher: AnyPublisher<AdvertisingState, Never> { state.eraseToAnyPublisher() }
    var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { Empty().eraseToAnyPublisher() }
    func start(gameCode: String) { startedCode = gameCode }
    func stop() {}
    func stopAfterFlush(then completion: @escaping () -> Void) {}
    func setGameInfo(_ info: Data) {}
    func send(_ message: Data, to link: LinkID) -> Int { 0 }
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult { .failed }
    var staleFrameCount: Int { 0 }
}

final class DiscoveryViewModelTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1000)

    private func game(_ code: String, foundAfter seconds: TimeInterval) -> DiscoveredGame {
        let at = t0.addingTimeInterval(seconds)
        return DiscoveredGame(id: UUID(), gameCode: code, rssi: -60, discoveredAt: at, lastSeen: at)
    }

    /// Lets `receive(on: DispatchQueue.main)` deliver.
    private func drainMainQueue() {
        let done = expectation(description: "main queue")
        DispatchQueue.main.async { done.fulfill() }
        wait(for: [done], timeout: 1)
    }

    func testJoinListKeepsDiscoveryOrder() {
        let scanner = StubScanner()
        let viewModel = Discovery(scanner: scanner)
        scanner.games.send([game("BBBB", foundAfter: 2), game("AAAA", foundAfter: 1)])
        drainMainQueue()
        XCTAssertEqual(viewModel.games.map(\.gameCode), ["AAAA", "BBBB"])
    }

    func testDiscoveryTimeIsMeasuredFromScanStart() {
        let scanner = StubScanner()
        let viewModel = Discovery(scanner: scanner)
        XCTAssertNil(viewModel.discoveryTime(of: game("AAAA", foundAfter: 1)))
        viewModel.start(now: t0)
        XCTAssertEqual(scanner.startCount, 1)
        XCTAssertEqual(viewModel.discoveryTime(of: game("AAAA", foundAfter: 1.5)), 1.5)
    }

    func testJoinShowsBluetoothProblem() {
        let scanner = StubScanner()
        let viewModel = Discovery(scanner: scanner)
        scanner.state.send(.unavailable(.poweredOff))
        drainMainQueue()
        XCTAssertEqual(viewModel.state, .unavailable(.poweredOff))
        XCTAssertNotNil(BluetoothAvailability.poweredOff.userMessage)
    }

    func testHostAdvertisesItsGameCode() {
        let advertiser = StubAdvertiser()
        let viewModel = HostRuntime(transport: advertiser,
                                    session: HostSession(gameId: "7K2F", hostName: "Host", deviceId: "d1"),
                                    makeProbeTransport: FakeClientTransport.init)
        viewModel.start()
        XCTAssertEqual(advertiser.startedCode, "7K2F")
        advertiser.state.send(.advertising(gameCode: "7K2F"))
        drainMainQueue()
        XCTAssertEqual(viewModel.state, .advertising(gameCode: "7K2F"))
    }

    func testBluetoothAvailabilityMapping() {
        XCTAssertEqual(BluetoothAvailability(.poweredOn), .ready)
        XCTAssertEqual(BluetoothAvailability(.poweredOff), .poweredOff)
        XCTAssertEqual(BluetoothAvailability(.unauthorized), .unauthorized)
        XCTAssertEqual(BluetoothAvailability(.unsupported), .unsupported)
        XCTAssertEqual(BluetoothAvailability(.resetting), .unknown)
        XCTAssertNil(BluetoothAvailability.ready.userMessage)
    }
}
