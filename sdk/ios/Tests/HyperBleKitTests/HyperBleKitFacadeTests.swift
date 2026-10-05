import Combine
import XCTest
@testable import HyperBleKit

/// The public facade over fake transports: what `HyperBleKit` and `GameSession` publish.
@MainActor
final class HyperBleKitFacadeTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() async throws {
        suiteName = "HyperBleKitFacadeTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeKit(maxPlayers: Int = 10) -> HyperBleKit {
        HyperBleKit(config: HyperBleKitConfig(games: [], maxPlayers: maxPlayers),
                    identity: LocalIdentity(defaults: defaults), scanner: FakeGameScanner(),
                    makeHostTransport: FakeHostTransport.init, makeClientTransport: FakeClientTransport.init)
    }

    /// Lets `receive(on: DispatchQueue.main)` and the fakes' short delays deliver.
    private func wait(_ seconds: TimeInterval) {
        let done = expectation(description: "wait")
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { done.fulfill() }
        wait(for: [done], timeout: seconds + 2)
    }

    func testIdentityStoresTheTypedNameCutToTheMaximum() {
        let identity = LocalIdentity(defaults: defaults)
        XCTAssertEqual(identity.storedPlayerName, "")
        XCTAssertEqual(identity.playerName, identity.defaultPlayerName)
        XCTAssertTrue(identity.defaultPlayerName.hasPrefix("iOS-"))
        identity.storedPlayerName = "A very long player name"
        XCTAssertEqual(identity.storedPlayerName.count, LocalIdentity.maxNameLength)
        // The effective name is trimmed.
        XCTAssertEqual(identity.playerName, "A very long")
        XCTAssertEqual(identity.deviceId, LocalIdentity(defaults: defaults).deviceId)
    }

    func testHostingOpensASessionAsHostAndLeavingClosesIt() {
        let kit = makeKit()
        XCTAssertNil(kit.session)
        let session = kit.host()
        XCTAssertTrue(kit.session === session)
        XCTAssertTrue(GameCode.isValid(session.gameCode))
        XCTAssertEqual(session.state.role, .host)
        XCTAssertEqual(session.state.connection, .joined)
        XCTAssertEqual(session.state.gameCode, session.gameCode)
        XCTAssertEqual(session.state.playerId, "p1")
        XCTAssertEqual(session.state.hostId, "p1")
        XCTAssertEqual(session.state.hostEpoch, 1)
        XCTAssertEqual(session.state.phase, GamePhase.lobby)
        XCTAssertEqual(session.state.players.map(\.id), ["p1"])
        XCTAssertEqual(session.state.players.first?.name, kit.identity.playerName)
        XCTAssertEqual(session.state.pingStats, PingStats())
        XCTAssertNotNil(session.diagnostics.hostSpeedTest)
        XCTAssertNil(session.diagnostics.clientSpeedTest)
        XCTAssertFalse(session.diagnostics.canRunSpeedTest)
        wait(0.6)
        XCTAssertEqual(session.state.advertising, .advertising(gameCode: session.gameCode))
        kit.leave()
        XCTAssertNil(kit.session)
    }

    func testJoiningWithoutBluetoothEndsDisconnected() {
        let kit = makeKit()
        let game = DiscoveredGame(id: UUID(), gameCode: "7K2F", rssi: -60, discoveredAt: Date(), lastSeen: Date())
        let session = kit.join(game)
        XCTAssertEqual(session.gameCode, "7K2F")
        XCTAssertEqual(session.state.role, .client)
        XCTAssertEqual(session.state.connection, .connecting)
        XCTAssertNil(session.state.playerId)
        XCTAssertNil(session.state.advertising)
        XCTAssertNil(session.state.pingStats)
        XCTAssertEqual(session.state.migrationInfo, "Snapshot: none · cannot take over")
        wait(0.6)
        guard case .ended(reason: .disconnected(let detail)) = session.state.connection else {
            return XCTFail("expected an ended session, got \(session.state.connection)")
        }
        XCTAssertNotNil(detail)
        kit.leave()
        XCTAssertNil(kit.session)
    }

    func testOpeningASecondSessionLeavesTheFirst() {
        let kit = makeKit()
        let first = kit.host()
        let second = kit.host()
        XCTAssertFalse(first === second)
        XCTAssertTrue(kit.session === second)
    }

    func testDiscoveryPublishesGamesAndTheTimeToTheFirstOne() {
        let kit = makeKit()
        XCTAssertEqual(kit.scanningState, .idle)
        kit.startDiscovery()
        wait(0.2)
        XCTAssertEqual(kit.scanningState, .scanning)
        XCTAssertNil(kit.timeToFirstResult)
        wait(1.0)
        XCTAssertEqual(kit.discoveredGames.first?.gameCode, "7K2F")
        XCTAssertNotNil(kit.timeToFirstResult)
        if let first = kit.discoveredGames.first {
            XCTAssertNotNil(kit.discoveryTime(of: first))
        }
        kit.stopDiscovery()
        wait(0.1)
        XCTAssertEqual(kit.scanningState, .idle)
        XCTAssertEqual(kit.discoveredGames, [])
    }

    func testTheLogKeepsTheConfiguredNumberOfLinesAndFeedsTheSink() {
        let log = HyperBleLog()
        log.capacity = 3
        var seen: [String] = []
        log.sink = { seen.append($0) }
        (1...5).forEach { log.event("line \($0)") }
        XCTAssertEqual(log.entries.count, 3)
        XCTAssertEqual(seen.count, 5)
        XCTAssertTrue(log.entries.last?.text.hasSuffix("-- line 5") ?? false)
        XCTAssertEqual(log.text.split(separator: "\n").count, 3)
        log.clear()
        XCTAssertEqual(log.entries, [])
    }
}
