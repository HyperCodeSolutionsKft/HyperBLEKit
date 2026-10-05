import Combine
import XCTest
@testable import HyperBleKit

/// An app may call `HyperBleKit.leave()` from inside its `GameSession.$state` observer. Whatever
/// the moment, nothing may survive the leave: no transport still looking for a host or linked
/// to one, no advertising, no seat held as connected on the host, no session that hosts.
@MainActor
final class LeaveFromObserverTests: XCTestCase {
    private struct Phone {
        let device: AirDevice
        let kit: HyperBleKit
    }

    private var air: FakeAir!
    private var suiteNames: [String] = []
    private var observers = Set<AnyCancellable>()

    override func setUp() async throws {
        air = FakeAir()
    }

    override func tearDown() async throws {
        observers.removeAll()
        suiteNames.forEach { UserDefaults.standard.removePersistentDomain(forName: $0) }
        suiteNames = []
        air.reset()
        air = nil
    }

    // MARK: - Helpers

    private func phone(_ name: String) -> Phone {
        let suiteName = "LeaveFromObserverTests-\(name)-\(UUID().uuidString)"
        suiteNames.append(suiteName)
        let identity = LocalIdentity(defaults: UserDefaults(suiteName: suiteName)!)
        identity.storedPlayerName = name
        let device = air.device(name)
        let kit = HyperBleKit(config: HyperBleKitConfig(games: [CounterModule()]), identity: identity,
                              scanner: device.makeScanner(), makeHostTransport: device.makeHostTransport,
                              makeClientTransport: device.makeClientTransport)
        return Phone(device: device, kit: kit)
    }

    @discardableResult
    private func waitUntil(_ what: String, timeout: TimeInterval = 4, file: StaticString = #filePath,
                           line: UInt = #line, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() >= deadline {
                XCTFail("timed out after \(timeout) s waiting until \(what)", file: file, line: line)
                return false
            }
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        }
        return true
    }

    private func settle(_ seconds: TimeInterval) {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }

    /// The game of `host`, as a scanner on another phone reports it.
    private func discovered(_ session: GameSession, hostedBy host: Phone) -> DiscoveredGame {
        DiscoveredGame(id: host.device.activeHost!.peerId, gameCode: session.gameCode, rssi: -50,
                       discoveredAt: Date(), lastSeen: Date())
    }

    private func join(_ phone: Phone, _ session: GameSession, hostedBy host: Phone,
                      file: StaticString = #filePath, line: UInt = #line) -> GameSession {
        let joined = phone.kit.join(discovered(session, hostedBy: host))
        waitUntil("\(phone.device.name) has joined", file: file, line: line) { joined.state.connection == .joined }
        return joined
    }

    /// Like an app's observer: leaves from inside the `$state` sink, the first time `moment` holds.
    /// Returns a box that says whether it has fired.
    private func leave(_ phone: Phone, from session: GameSession, when moment: @escaping (SessionState) -> Bool) -> Fired {
        let fired = Fired()
        session.$state
            .sink { state in
                guard !fired.value, moment(state) else { return }
                fired.value = true
                phone.kit.leave()
            }
            .store(in: &observers)
        return fired
    }

    private final class Fired {
        var value = false
    }

    /// Long enough for a flush window, the reconnect pause of 1 s and an attempt after it.
    private static let aftermath: TimeInterval = 1.8

    private func assertGone(_ phone: Phone, _ session: GameSession, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(phone.kit.session, file: file, line: line)
        XCTAssertEqual(session.state.connection, .ended(reason: .left), file: file, line: line)
        XCTAssertNil(session.realtime, file: file, line: line)
        XCTAssertFalse(phone.device.hasActiveClient, "a transport is still looking for a host or linked to one",
                       file: file, line: line)
        XCTAssertFalse(phone.device.isAdvertising, "still advertising", file: file, line: line)
        XCTAssertNil(phone.device.activeHost, "still hosting", file: file, line: line)
    }

    private func isReconnecting(_ state: SessionState) -> Bool {
        if case .reconnecting = state.connection { return true }
        return false
    }

    // MARK: - Scenarios

    /// 5. On `joined`, right after joining.
    func testLeavingOnJoined() {
        let a = phone("Anna"), b = phone("Bence")
        let sessionA = a.kit.host()
        waitUntil("A advertises") { a.device.isAdvertising }
        let sessionB = b.kit.join(discovered(sessionA, hostedBy: a))
        let fired = leave(b, from: sessionB) { $0.connection == .joined }
        waitUntil("B's observer left on joined") { fired.value }
        settle(Self.aftermath)
        assertGone(b, sessionB)
        // The host was told: the seat is gone, not kept as a player who dropped out.
        XCTAssertEqual(sessionA.state.players.map(\.id), ["p1"])
        XCTAssertEqual(a.device.activeHost?.links.count, 0)
        a.kit.leave()
    }

    /// 1. On the first `reconnecting` after the host is gone.
    func testLeavingOnTheFirstReconnecting() {
        let a = phone("Anna"), b = phone("Bence"), c = phone("Csilla")
        let sessionA = a.kit.host()
        waitUntil("A advertises") { a.device.isAdvertising }
        let sessionB = join(b, sessionA, hostedBy: a)
        let sessionC = join(c, sessionA, hostedBy: a)
        waitUntil("B holds a snapshot") { sessionB.state.migrationInfo == "Snapshot: held · takeover rank #1" }
        let fired = leave(c, from: sessionC, when: isReconnecting)

        // The host hands over to B; C loses its host and would reconnect to B.
        a.kit.leave()
        waitUntil("C's observer left on reconnecting") { fired.value }
        waitUntil("B hosts") { sessionB.state.role == .host }
        settle(Self.aftermath)
        assertGone(c, sessionC)
        XCTAssertTrue(c.device.hostTransports.isEmpty)
        XCTAssertNotEqual(sessionB.state.players.first { $0.id == "p3" }?.connected, true,
                          "the phone that left is with the new host")
        XCTAssertEqual(b.device.activeHost?.links.count, 0)
        b.kit.leave()
    }

    /// 2. On the role flipping to host during a takeover.
    func testLeavingOnBecomingTheHost() {
        let a = phone("Anna"), b = phone("Bence")
        let sessionA = a.kit.host()
        waitUntil("A advertises") { a.device.isAdvertising }
        let sessionB = join(b, sessionA, hostedBy: a)
        waitUntil("B holds a snapshot") { sessionB.state.migrationInfo == "Snapshot: held · takeover rank #1" }
        let fired = leave(b, from: sessionB) { $0.role == .host }

        a.kit.leave()
        waitUntil("B's observer left on becoming the host") { fired.value }
        settle(Self.aftermath)
        assertGone(b, sessionB)
        XCTAssertEqual(sessionB.state.role, .host)
    }

    /// 3. On the first client state of a foreground rejoin (the same path as a step-down).
    func testLeavingOnTheFirstClientStateOfARejoin() {
        let a = phone("Anna"), b = phone("Bence")
        let sessionA = a.kit.host()
        waitUntil("A advertises") { a.device.isAdvertising }
        let sessionB = join(b, sessionA, hostedBy: a)
        waitUntil("B holds a snapshot") { sessionB.state.migrationInfo == "Snapshot: held · takeover rank #1" }
        a.kit.onBackground()
        waitUntil("B hosts") { sessionB.state.role == .host && b.device.isAdvertising }
        waitUntil("A stopped advertising") { !a.device.isAdvertising && a.device.activeHost == nil }
        let fired = leave(a, from: sessionA) { $0.role == .client }

        a.kit.onForeground()
        waitUntil("A's observer left on becoming a client") { fired.value }
        settle(Self.aftermath)
        assertGone(a, sessionA)
        XCTAssertNotEqual(sessionB.state.players.first { $0.id == "p1" }?.connected, true,
                          "the phone that left is with the new host")
        XCTAssertEqual(b.device.activeHost?.links.count, 0)
        b.kit.leave()
    }

    /// 4. On `ended`: the join was rejected.
    func testLeavingOnEndedRejected() {
        let a = phone("Anna"), b = phone("Bence"), c = phone("Csilla")
        let sessionA = a.kit.host()
        waitUntil("A advertises") { a.device.isAdvertising }
        let sessionB = join(b, sessionA, hostedBy: a)
        sessionA.startGame(CounterModule.moduleId)
        waitUntil("the game runs") { sessionB.state.phase == GamePhase.playing }

        let sessionC = c.kit.join(discovered(sessionA, hostedBy: a))
        var seen: EndReason?
        let fired = leave(c, from: sessionC) { state in
            guard case .ended(let reason) = state.connection, reason != .left else { return false }
            seen = reason
            return true
        }
        waitUntil("C's observer left on ended") { fired.value }
        XCTAssertEqual(seen, .rejected(code: JoinRejectCode.gameStarted))
        settle(Self.aftermath)
        assertGone(c, sessionC)
        XCTAssertEqual(sessionA.state.players.map(\.id), ["p1", "p2"])
        XCTAssertEqual(a.device.activeHost?.links.count, 1)
        a.kit.leave()
        b.kit.leave()
    }

    /// 4. On `ended`: the host said the game is over.
    func testLeavingOnEndedHostLeft() throws {
        let a = phone("Anna"), b = phone("Bence")
        let sessionA = a.kit.host()
        waitUntil("A advertises") { a.device.isAdvertising }
        let sessionB = join(b, sessionA, hostedBy: a)
        let fired = leave(b, from: sessionB) { $0.connection == .ended(reason: .hostLeft) }

        // A host with a successor never sends GAME_OVER, so the message is put on B's link.
        let gameOver = Message(seq: 99, from: "p1", ts: Date().wireMillis,
                               body: .gameOver(GameOver(reason: GameOver.hostLeft, winnerId: nil, stateVersion: nil)))
        try XCTUnwrap(b.device.clientTransports.last).inject(.message(try gameOver.encoded(), chunks: 1))
        waitUntil("B's observer left on ended") { fired.value }
        settle(Self.aftermath)
        assertGone(b, sessionB)
        XCTAssertEqual(a.device.activeHost?.links.count, 0)
        a.kit.leave()
    }

    /// 4. On `ended`: the host could not be reached.
    func testLeavingOnEndedDisconnected() {
        let b = phone("Bence")
        let nowhere = DiscoveredGame(id: UUID(), gameCode: "7K2F", rssi: -50, discoveredAt: Date(), lastSeen: Date())
        let sessionB = b.kit.join(nowhere)
        let fired = leave(b, from: sessionB) { state in
            if case .ended(.disconnected) = state.connection { return true }
            return false
        }
        waitUntil("B's observer left on ended") { fired.value }
        settle(Self.aftermath)
        assertGone(b, sessionB)
    }
}
