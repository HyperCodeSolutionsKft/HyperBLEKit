import XCTest
@testable import HyperBleKit

final class DiscoveredGamesTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1000)
    private let a = UUID()
    private let b = UUID()

    func testFirstSightingKeepsDiscoveryTime() {
        var games = DiscoveredGames()
        games.record(id: a, gameCode: "7K2F", rssi: -60, at: t0)
        games.record(id: a, gameCode: "7K2F", rssi: -50, at: t0.addingTimeInterval(2))
        let game = games.list.first
        XCTAssertEqual(game?.discoveredAt, t0)
        XCTAssertEqual(game?.lastSeen, t0.addingTimeInterval(2))
        XCTAssertEqual(game?.rssi, -50)
        XCTAssertEqual(games.list.count, 1)
    }

    func testUnknownRSSIKeepsLastValue() {
        var games = DiscoveredGames()
        games.record(id: a, gameCode: "7K2F", rssi: -60, at: t0)
        games.record(id: a, gameCode: "7K2F", rssi: DiscoveredGames.unknownRSSI, at: t0.addingTimeInterval(1))
        XCTAssertEqual(games.list.first?.rssi, -60)
    }

    func testListIsInDiscoveryOrder() {
        var games = DiscoveredGames()
        games.record(id: b, gameCode: "BBBB", rssi: -40, at: t0.addingTimeInterval(1))
        games.record(id: a, gameCode: "AAAA", rssi: -80, at: t0)
        XCTAssertEqual(games.list.map(\.gameCode), ["AAAA", "BBBB"])
    }

    func testRemovesOnlyStaleGames() {
        var games = DiscoveredGames()
        games.record(id: a, gameCode: "AAAA", rssi: -60, at: t0)
        games.record(id: b, gameCode: "BBBB", rssi: -60, at: t0.addingTimeInterval(3))
        let now = t0.addingTimeInterval(DiscoveredGame.staleAfter + 1)
        XCTAssertTrue(games.removeStale(at: now))
        XCTAssertEqual(games.list.map(\.gameCode), ["BBBB"])
        XCTAssertFalse(games.removeStale(at: now))
    }
}
