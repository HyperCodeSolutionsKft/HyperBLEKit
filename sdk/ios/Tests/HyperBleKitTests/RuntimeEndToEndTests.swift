import Combine
import XCTest
@testable import HyperBleKit

/// The runtime end to end: several kits in one process, talking over the `FakeAir`, through the
/// public facade only. The runtimes run on real timers with the protocol's fixed delays, so
/// the scenario that waits for a takeover takes about 6 seconds of real time.
@MainActor
final class RuntimeEndToEndTests: XCTestCase {
    /// One phone: its place on the air and the kit an app on it would hold.
    private struct Phone {
        let device: AirDevice
        let kit: HyperBleKit
    }

    private var air: FakeAir!
    private var suiteNames: [String] = []

    override func setUp() async throws {
        air = FakeAir()
    }

    override func tearDown() async throws {
        suiteNames.forEach { UserDefaults.standard.removePersistentDomain(forName: $0) }
        suiteNames = []
        air.reset()
        air = nil
    }

    // MARK: - Helpers

    private func phone(_ name: String) -> Phone {
        let suiteName = "RuntimeEndToEndTests-\(name)-\(UUID().uuidString)"
        suiteNames.append(suiteName)
        let identity = LocalIdentity(defaults: UserDefaults(suiteName: suiteName)!)
        identity.storedPlayerName = name
        let device = air.device(name)
        let kit = HyperBleKit(config: HyperBleKitConfig(games: [CounterModule()]), identity: identity,
                              scanner: device.makeScanner(), makeHostTransport: device.makeHostTransport,
                              makeClientTransport: device.makeClientTransport)
        return Phone(device: device, kit: kit)
    }

    /// Runs the main run loop until `condition` holds; fails the test after `timeout` seconds.
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

    /// Lets everything that is already on its way arrive.
    private func settle(_ seconds: TimeInterval = 0.3) {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }

    /// Finds the game with this code by scanning, as an app's join screen does, and joins it.
    private func join(_ phone: Phone, code: String, file: StaticString = #filePath, line: UInt = #line) -> GameSession {
        phone.kit.startDiscovery()
        waitUntil("\(phone.device.name) finds \(code)", file: file, line: line) {
            phone.kit.discoveredGames.contains { $0.gameCode == code }
        }
        let game = phone.kit.discoveredGames.first { $0.gameCode == code }!
        phone.kit.stopDiscovery()
        let session = phone.kit.join(game)
        waitUntil("\(phone.device.name) has joined", file: file, line: line) { session.state.connection == .joined }
        return session
    }

    /// A hosts, then B and C join in that order: p1, p2, p3.
    private func threePhonesInTheLobby(file: StaticString = #filePath, line: UInt = #line)
        -> (a: Phone, b: Phone, c: Phone, sessionA: GameSession, sessionB: GameSession, sessionC: GameSession) {
        let a = phone("Anna"), b = phone("Bence"), c = phone("Csilla")
        let sessionA = a.kit.host()
        let sessionB = join(b, code: sessionA.gameCode, file: file, line: line)
        let sessionC = join(c, code: sessionA.gameCode, file: file, line: line)
        waitUntil("everyone sees three connected players", file: file, line: line) {
            [sessionA, sessionB, sessionC].allSatisfy { $0.state.players.filter(\.connected).count == 3 }
        }
        // The successors hold a snapshot before anything is done to the host.
        waitUntil("the successors hold a snapshot", file: file, line: line) {
            sessionB.state.migrationInfo == "Snapshot: held · takeover rank #1"
                && sessionC.state.migrationInfo == "Snapshot: held · takeover rank #2"
        }
        return (a, b, c, sessionA, sessionB, sessionC)
    }

    private func count(in session: GameSession) -> Int? {
        if case .int(let value)? = session.state.view?["count"] { return value }
        return nil
    }

    private func ownMoves(in session: GameSession) -> Int? {
        if case .int(let value)? = session.state.view?["ownMoves"] { return value }
        return nil
    }

    private func add(_ amount: Int) -> JSONValue {
        .object(["type": .string("add"), "add": .int(amount)])
    }

    // MARK: - Joining and playing

    func testThreePhonesJoinPlayAndOneLeaves() {
        let a = phone("Anna")
        var firstRoles: [SessionRole] = []
        let observer = a.kit.$session.compactMap { $0?.state.role }.sink { firstRoles.append($0) }
        let sessionA = a.kit.host()
        observer.cancel()
        // A hosted session never appears as a client, not even in its first value.
        XCTAssertEqual(firstRoles, [.host])
        XCTAssertEqual(sessionA.state.role, .host)
        XCTAssertEqual(sessionA.state.connection, .joined)

        let b = phone("Bence"), c = phone("Csilla")
        let sessionB = join(b, code: sessionA.gameCode)
        let sessionC = join(c, code: sessionA.gameCode)
        waitUntil("everyone sees three connected players") {
            [sessionA, sessionB, sessionC].allSatisfy { $0.state.players.filter(\.connected).count == 3 }
        }
        for session in [sessionA, sessionB, sessionC] {
            XCTAssertEqual(session.state.players.map(\.id), ["p1", "p2", "p3"])
            XCTAssertEqual(session.state.players.map(\.name), ["Anna", "Bence", "Csilla"])
            XCTAssertEqual(session.state.hostId, "p1")
            XCTAssertEqual(session.state.hostEpoch, 1)
            XCTAssertEqual(session.state.gameCode, sessionA.gameCode)
            XCTAssertEqual(session.state.phase, GamePhase.lobby)
        }
        XCTAssertEqual([sessionA, sessionB, sessionC].map(\.state.playerId), ["p1", "p2", "p3"])
        XCTAssertEqual([sessionA, sessionB, sessionC].map(\.state.role), [.host, .client, .client])
        XCTAssertEqual(sessionA.state.successors, ["p2", "p3"])
        XCTAssertNil(sessionB.state.advertising)
        XCTAssertNil(sessionB.state.pingStats)

        // The host starts the test game.
        XCTAssertNil(sessionA.startGameProblem(CounterModule.moduleId))
        XCTAssertEqual(sessionB.startGameProblem(CounterModule.moduleId), "Only the host can start a game")
        XCTAssertEqual(sessionA.startGameProblem("no-such-game"), "Unknown game no-such-game")
        sessionB.startGame(CounterModule.moduleId)
        settle()
        XCTAssertEqual(sessionA.state.phase, GamePhase.lobby, "a client cannot start a game")
        sessionA.startGame(CounterModule.moduleId)
        waitUntil("the game is on every table") {
            [sessionA, sessionB, sessionC].allSatisfy {
                $0.state.phase == GamePhase.playing && $0.state.gameId == CounterModule.moduleId && count(in: $0) == 0
            }
        }
        // While a game runs, another cannot be started, and the problem says so.
        XCTAssertEqual(sessionA.startGameProblem(CounterModule.moduleId), "A game is already running")
        sessionA.startGame(CounterModule.moduleId)
        // State is published one main-queue turn after the call, never inside it.
        XCTAssertNil(sessionA.state.gameNotice)
        waitUntil("the refused start is reported") { sessionA.state.gameNotice != nil }
        XCTAssertEqual(sessionA.state.gameNotice, "cannot_start: a game is already running")
        XCTAssertEqual(sessionB.startGameProblem(CounterModule.moduleId), "Only the host can start a game")

        // A client's move and the host's own move are accepted and reach everyone.
        sessionB.act(add(5))
        waitUntil("B's move reached everyone") { [sessionA, sessionB, sessionC].allSatisfy { count(in: $0) == 5 } }
        sessionA.act(add(2))
        waitUntil("A's move reached everyone") { [sessionA, sessionB, sessionC].allSatisfy { count(in: $0) == 7 } }
        // Every player gets its own view.
        XCTAssertEqual([sessionA, sessionB, sessionC].map { ownMoves(in: $0) }, [1, 1, 0])

        // A refused move changes nothing but the notice of the player who made it.
        sessionC.act(add(-1))
        waitUntil("C's move was refused") { sessionC.state.gameNotice != nil }
        XCTAssertEqual(sessionC.state.gameNotice, "bad_move: add must be a positive number")
        sessionA.act(add(0))
        waitUntil("A's move was refused") { sessionA.state.gameNotice?.hasPrefix("bad_move") == true }
        XCTAssertEqual(sessionA.state.gameNotice, "bad_move: add must be a positive number")
        settle()
        XCTAssertEqual([sessionA, sessionB, sessionC].map { count(in: $0) }, [7, 7, 7])
        XCTAssertNil(sessionB.state.gameNotice)

        // The next accepted move clears the notice, and everyone ends on the same state version.
        sessionC.act(add(1))
        waitUntil("C's second move reached everyone") { [sessionA, sessionB, sessionC].allSatisfy { count(in: $0) == 8 } }
        XCTAssertNil(sessionC.state.gameNotice)
        waitUntil("everyone is on the host's state version") {
            b.device.lastStateVersion == a.device.lastStateVersion && c.device.lastStateVersion == a.device.lastStateVersion
        }
        XCTAssertGreaterThan(a.device.lastStateVersion, 1)

        // A client leaves: its session is over, the others play on without it.
        c.kit.leave()
        XCTAssertNil(c.kit.session)
        XCTAssertEqual(sessionC.state.connection, .ended(reason: .left))
        // During a game the seat is kept for a while, like that of a player who went silent.
        waitUntil("the others see C offline") {
            [sessionA, sessionB].allSatisfy { $0.state.players.first { $0.id == "p3" }?.connected == false }
        }
        XCTAssertNotNil(sessionA.state.offlineSecondsLeft["p3"])
        XCTAssertEqual(sessionB.state.offlineSecondsLeft, [:], "only the host counts down")
        // The host removes the seat at once; a connected player cannot be removed.
        sessionA.removePlayer("p2")
        waitUntil("the refused removal is reported") { sessionA.state.gameNotice != nil }
        XCTAssertEqual(sessionA.state.gameNotice, "cannot_remove: p2 is connected")
        sessionA.removePlayer("p3")
        waitUntil("the others see C gone") {
            [sessionA, sessionB].allSatisfy { $0.state.players.map(\.id) == ["p1", "p2"] }
        }
        XCTAssertEqual(sessionA.state.offlineSecondsLeft, [:])
        XCTAssertEqual(sessionA.state.phase, GamePhase.playing)
        XCTAssertEqual(sessionA.state.successors, ["p2"])

        // The host ends the game: back to the lobby.
        sessionA.endGame()
        waitUntil("everyone is back in the lobby") {
            [sessionA, sessionB].allSatisfy { $0.state.phase == GamePhase.lobby && $0.state.gameId == nil && $0.state.view == nil }
        }
        XCTAssertNil(sessionA.startGameProblem(CounterModule.moduleId), "back in the lobby it can be started again")
        a.kit.leave()
        XCTAssertEqual(sessionA.startGameProblem(CounterModule.moduleId), "The session has ended")
        b.kit.leave()
    }

    func testOnePlayerCannotStartTheGame() {
        let a = phone("Anna")
        let session = a.kit.host()
        XCTAssertEqual(session.startGameProblem(CounterModule.moduleId), "Counter needs 2–4 connected players, 1 connected now")
        a.kit.leave()
    }

    // MARK: - Host migration

    func testALeavingHostHandsTheGameToItsSuccessor() {
        let (a, b, c, sessionA, sessionB, sessionC) = threePhonesInTheLobby()
        sessionA.startGame(CounterModule.moduleId)
        waitUntil("the game is on every table") { [sessionA, sessionB, sessionC].allSatisfy { count(in: $0) == 0 } }
        sessionB.act(add(3))
        waitUntil("B's move reached everyone") { [sessionA, sessionB, sessionC].allSatisfy { count(in: $0) == 3 } }
        waitUntil("B holds the newest snapshot") { b.device.lastStateVersion == a.device.lastStateVersion }

        a.kit.leave()
        XCTAssertEqual(sessionA.state.connection, .ended(reason: .left))

        // The named successor takes over at once, on the same session object.
        waitUntil("B hosts") { sessionB.state.role == .host }
        XCTAssertTrue(b.kit.session === sessionB)
        waitUntil("B advertises the game") { sessionB.state.advertising == .advertising(gameCode: sessionA.gameCode) }
        // Nothing of the client role survives the swap.
        XCTAssertEqual(sessionB.state.connection, .joined)
        XCTAssertEqual(sessionB.state.playerId, "p2")
        XCTAssertEqual(sessionB.state.hostId, "p2")
        XCTAssertEqual(sessionB.state.hostEpoch, 2)
        XCTAssertNotNil(sessionB.state.pingStats)
        XCTAssertNil(sessionB.state.migrationInfo)
        XCTAssertNil(sessionB.state.lastReconnect)
        XCTAssertNil(sessionB.state.notice)
        XCTAssertNotNil(sessionB.diagnostics.hostSpeedTest)
        XCTAssertNil(sessionB.diagnostics.clientSpeedTest)
        // The game continues from the snapshot.
        XCTAssertEqual(sessionB.state.phase, GamePhase.playing)
        XCTAssertEqual(count(in: sessionB), 3)

        // The third phone follows the new host and keeps its seat.
        waitUntil("C is with the new host") {
            sessionC.state.connection == .joined && sessionC.state.hostId == "p2" && sessionC.state.hostEpoch == 2
        }
        XCTAssertEqual(sessionC.state.role, .client)
        XCTAssertEqual(sessionC.state.playerId, "p3")
        XCTAssertNotNil(sessionC.state.lastReconnect)
        XCTAssertEqual(count(in: sessionC), 3)
        sessionC.act(add(1))
        waitUntil("the game goes on under the new host") { count(in: sessionB) == 4 && count(in: sessionC) == 4 }

        // The old host's seat is kept for a while, disconnected, and it no longer advertises.
        XCTAssertEqual(sessionB.state.players.first { $0.id == "p1" }?.connected, false)
        XCTAssertNotNil(sessionB.state.offlineSecondsLeft["p1"])
        waitUntil("A stopped advertising") { !a.device.isAdvertising && a.device.activeHost == nil }
        b.kit.leave()
        c.kit.leave()
    }

    /// The long one: the takeover delay of a host lost without warning is 5 seconds.
    func testALostHostIsReplacedAndStepsDownWhenItComesBack() throws {
        let (a, b, c, sessionA, sessionB, sessionC) = threePhonesInTheLobby()
        let hostTransport = try XCTUnwrap(a.device.activeHost)

        // The host drops off the air without a word.
        let lostAt = Date()
        hostTransport.powerOff()
        waitUntil("the clients are reconnecting") {
            [sessionB, sessionC].allSatisfy {
                if case .reconnecting = $0.state.connection { return true }
                return false
            }
        }
        waitUntil("the host sees its clients gone") { sessionA.state.offlineSecondsLeft.count == 2 }
        XCTAssertEqual(sessionA.state.advertising, .unavailable(.poweredOff))
        XCTAssertEqual(sessionA.state.role, .host)

        // The first successor waits out the takeover delay, then hosts.
        waitUntil("B takes the game over", timeout: 9) { sessionB.state.role == .host }
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(lostAt), 4.5, "the takeover must wait for its delay")
        XCTAssertEqual(sessionB.state.hostEpoch, 2)
        XCTAssertEqual(sessionB.state.hostId, "p2")
        XCTAssertEqual(sessionC.state.role, .client, "the second successor's turn has not come")
        waitUntil("C is with the new host") {
            sessionC.state.connection == .joined && sessionC.state.hostId == "p2" && sessionC.state.hostEpoch == 2
        }

        // The old host comes back, finds a host with a higher epoch and steps down.
        var clientConnections: [ConnectionState] = []
        let observer = sessionA.$state.filter { $0.role == .client }.map(\.connection).removeDuplicates()
            .sink { clientConnections.append($0) }
        hostTransport.powerOn()
        waitUntil("A steps down and rejoins as a client") {
            sessionA.state.role == .client && sessionA.state.connection == .joined
        }
        observer.cancel()
        // The client role starts out reconnecting: no `connecting` or `joining` in between.
        XCTAssertEqual(clientConnections.count, 2)
        if case .reconnecting? = clientConnections.first {} else {
            XCTFail("the first client state was \(String(describing: clientConnections.first))")
        }
        XCTAssertEqual(clientConnections.last, .joined)
        XCTAssertTrue(a.kit.session === sessionA)
        XCTAssertEqual(sessionA.state.playerId, "p1", "it gets its own seat back")
        XCTAssertEqual(sessionA.state.hostId, "p2")
        XCTAssertEqual(sessionA.state.hostEpoch, 2)
        // Nothing of the host role survives the swap.
        XCTAssertNil(sessionA.state.advertising)
        XCTAssertNil(sessionA.state.pingStats)
        XCTAssertEqual(sessionA.state.successors, [])
        XCTAssertEqual(sessionA.state.offlineSecondsLeft, [:])
        XCTAssertNotNil(sessionA.state.migrationInfo)
        XCTAssertNil(sessionA.diagnostics.hostSpeedTest)
        waitUntil("A stopped advertising") { !a.device.isAdvertising && a.device.activeHost == nil }
        waitUntil("everyone sees three connected players again") {
            [sessionA, sessionB, sessionC].allSatisfy { $0.state.players.filter(\.connected).count == 3 }
        }
        XCTAssertEqual(sessionB.state.players.map(\.id), ["p1", "p2", "p3"])
        a.kit.leave()
        b.kit.leave()
        c.kit.leave()
    }

    func testAHostInTheBackgroundHandsOverAndRejoinsInTheForeground() {
        let (a, b, c, sessionA, sessionB, sessionC) = threePhonesInTheLobby()

        a.kit.onBackground()
        // A second call must not erase the hand-over the first one stored.
        a.kit.onBackground()
        waitUntil("B hosts") { sessionB.state.role == .host && sessionB.state.hostEpoch == 2 }
        waitUntil("C is with the new host") { sessionC.state.connection == .joined && sessionC.state.hostId == "p2" }
        waitUntil("A stopped advertising") { !a.device.isAdvertising }
        XCTAssertEqual(sessionB.state.players.first { $0.id == "p1" }?.connected, false)

        var clientConnections: [ConnectionState] = []
        let observer = sessionA.$state.filter { $0.role == .client }.map(\.connection).removeDuplicates()
            .sink { clientConnections.append($0) }
        a.kit.onForeground()
        waitUntil("A is back as a client") { sessionA.state.role == .client && sessionA.state.connection == .joined }
        observer.cancel()
        // The first client state is already the reconnect, with the whole window left.
        XCTAssertEqual(clientConnections, [.reconnecting(secondsLeft: 45), .joined])
        XCTAssertTrue(a.kit.session === sessionA)
        XCTAssertEqual(sessionA.state.playerId, "p1")
        XCTAssertEqual(sessionA.state.hostId, "p2")
        XCTAssertEqual(sessionA.state.hostEpoch, 2)
        XCTAssertNil(sessionA.state.advertising)
        XCTAssertNil(sessionA.state.pingStats)
        waitUntil("everyone sees three connected players again") {
            [sessionA, sessionB, sessionC].allSatisfy { $0.state.players.filter(\.connected).count == 3 }
        }
        // Coming to the foreground again changes nothing.
        a.kit.onForeground()
        settle()
        XCTAssertEqual(sessionA.state.role, .client)
        XCTAssertEqual(sessionA.state.connection, .joined)
        a.kit.leave()
        b.kit.leave()
        c.kit.leave()
    }

    // MARK: - Dead sessions

    func testALeftSessionNeverBecomesAHost() throws {
        let a = phone("Anna"), b = phone("Bence")
        let sessionA = a.kit.host()
        let sessionB = join(b, code: sessionA.gameCode)
        waitUntil("B holds a snapshot") { sessionB.state.migrationInfo == "Snapshot: held · takeover rank #1" }
        let link = try XCTUnwrap(b.device.clientTransports.last)

        b.kit.leave()
        XCTAssertNil(b.kit.session)
        XCTAssertEqual(sessionB.state.connection, .ended(reason: .left))
        // While the LEAVE is still being flushed, the host announces that it is going and names
        // this device. A session that was left must not start hosting.
        let leaving = Message(seq: 99, from: "p1", ts: Date().wireMillis,
                              body: .hostLeaving(HostLeaving(nextHostId: "p2")))
        link.inject(.message(try leaving.encoded(), chunks: 1))
        settle(0.5)
        XCTAssertEqual(sessionB.state.connection, .ended(reason: .left))
        XCTAssertEqual(sessionB.state.role, .client)
        XCTAssertTrue(b.device.hostTransports.isEmpty, "a dead session started hosting")
        XCTAssertFalse(b.device.isAdvertising)

        // Nothing brings it back, and nothing done to it has an effect.
        b.kit.onBackground()
        b.kit.onForeground()
        sessionB.act(add(1))
        sessionB.startGame(CounterModule.moduleId)
        sessionB.diagnostics.sendTestJson()
        settle()
        XCTAssertEqual(sessionB.state.connection, .ended(reason: .left))
        XCTAssertEqual(b.device.clientTransports.count, 1, "a dead session connected again")
        XCTAssertNil(sessionB.realtime)
        XCTAssertNil(sessionB.diagnostics.clientSpeedTest)

        // The same for a host that left: its last state says so and it stops advertising.
        a.kit.leave()
        XCTAssertEqual(sessionA.state.connection, .ended(reason: .left))
        XCTAssertEqual(sessionA.state.role, .host)
        XCTAssertNil(sessionA.diagnostics.hostSpeedTest)
        a.kit.onForeground()
        waitUntil("A stopped advertising") { !a.device.isAdvertising && a.device.activeHost == nil }
        XCTAssertEqual(a.device.hostTransports.count, 1)
        XCTAssertTrue(a.device.clientTransports.isEmpty, "a dead host session rejoined as a client")
    }

    /// The case the flush window makes possible: a client has left but its link is still up
    /// when the host leaves and announces its successor to everyone it can still reach.
    func testAClientThatLeftIgnoresAHostLeavingInsideItsFlushWindow() {
        let (a, b, c, sessionA, sessionB, sessionC) = threePhonesInTheLobby()

        // C, which is not the successor, leaves; the host leaves before C's link has closed.
        c.kit.leave()
        a.kit.leave()
        XCTAssertEqual(sessionC.state.connection, .ended(reason: .left))
        XCTAssertEqual(sessionA.state.connection, .ended(reason: .left))

        waitUntil("B hosts") { sessionB.state.role == .host && sessionB.state.hostEpoch == 2 }
        // Well past C's flush window, the reconnect pause and a reconnect attempt.
        settle(FakeAir.flushWindow + 1.5)
        XCTAssertEqual(sessionC.state.connection, .ended(reason: .left))
        XCTAssertEqual(sessionC.state.role, .client)
        XCTAssertEqual(c.device.clientTransports.count, 1, "the phone that left connected again")
        XCTAssertTrue(c.device.hostTransports.isEmpty)
        XCTAssertNil(c.kit.session)
        // The new host has nobody connected but itself.
        XCTAssertEqual(sessionB.state.players.filter(\.connected).map(\.id), ["p2"])
        XCTAssertNotEqual(sessionB.state.players.first { $0.id == "p3" }?.connected, true)
        XCTAssertEqual(b.device.activeHost?.links.count, 0)
        b.kit.leave()
    }

    func testAHostThatLeavesWhileInTheBackgroundDoesNotRejoin() {
        let (a, b, c, sessionA, sessionB, _) = threePhonesInTheLobby()
        a.kit.onBackground()
        waitUntil("B hosts") { sessionB.state.role == .host }
        a.kit.leave()
        a.kit.onForeground()
        settle()
        XCTAssertEqual(sessionA.state.connection, .ended(reason: .left))
        XCTAssertTrue(a.device.clientTransports.isEmpty, "a dead session rejoined after coming to the foreground")
        b.kit.leave()
        c.kit.leave()
    }

    func testPingAllReportsEveryConnectedClientInSeatOrder() {
        let (a, b, c, sessionA, _, _) = threePhonesInTheLobby()
        sessionA.diagnostics.pingAll()
        // The diagnostics are published one run-loop turn later. While the 2 s round is open it
        // lists both clients with no times yet.
        settle(0.05)
        XCTAssertEqual(sessionA.diagnostics.pingAllResult?.pending, true)
        XCTAssertEqual(sessionA.diagnostics.pingAllResult?.replies.map(\.playerId), ["p2", "p3"])
        waitUntil("the ping-all round finishes") {
            sessionA.diagnostics.pingAllResult?.pending == false
        }
        let replies = sessionA.diagnostics.pingAllResult!.replies
        XCTAssertEqual(replies.map(\.playerId), ["p2", "p3"])
        XCTAssertTrue(replies.allSatisfy { $0.rttMs != nil }, "every connected client should answer the ping-all")
        a.kit.leave()
        b.kit.leave()
        c.kit.leave()
    }

    func testPingAllOnAClientDoesNothing() {
        let (a, b, c, _, sessionB, _) = threePhonesInTheLobby()
        sessionB.diagnostics.pingAll()
        settle()
        XCTAssertNil(sessionB.diagnostics.pingAllResult, "ping-all is host-only; a client must not report a result")
        a.kit.leave()
        b.kit.leave()
        c.kit.leave()
    }

    func testHostCollectsEveryClientLogInOneRequest() {
        let (a, b, c, sessionA, sessionB, sessionC) = threePhonesInTheLobby()
        XCTAssertNil(sessionA.diagnostics.clientLogs, "nothing is collected until it is asked for")

        sessionA.diagnostics.requestClientLogs()
        waitUntil("the host has collected both client logs") {
            sessionA.diagnostics.clientLogs?.pending == false
        }
        let collection = sessionA.diagnostics.clientLogs!
        XCTAssertEqual(collection.expectedClients, 2)
        XCTAssertEqual(collection.logs.map(\.playerId), ["p2", "p3"])
        XCTAssertEqual(collection.logs.map(\.name), ["Bence", "Csilla"])
        XCTAssertTrue(collection.logs.allSatisfy(\.complete), "every announced batch arrived")
        XCTAssertTrue(collection.logs.allSatisfy { !$0.lines.isEmpty }, "a client uploads its own log lines")
        // Collection is host-only: a client never gathers logs itself.
        XCTAssertNil(sessionB.diagnostics.clientLogs)
        XCTAssertNil(sessionC.diagnostics.clientLogs)
        a.kit.leave()
        b.kit.leave()
        c.kit.leave()
    }

    func testCollectingLogsWithNoClientGivesAnEmptyCollection() {
        let a = phone("Anna")
        let sessionA = a.kit.host()
        settle()
        sessionA.diagnostics.requestClientLogs()
        waitUntil("the empty collection is published") { sessionA.diagnostics.clientLogs != nil }
        let collection = sessionA.diagnostics.clientLogs!
        XCTAssertEqual(collection.expectedClients, 0)
        XCTAssertFalse(collection.pending)
        XCTAssertTrue(collection.logs.isEmpty)
        a.kit.leave()
    }

    func testClosingTheKitLeavesAndStopsDiscovery() {
        let a = phone("Anna"), b = phone("Bence")
        let sessionA = a.kit.host()
        b.kit.startDiscovery()
        waitUntil("B finds the game") { !b.kit.discoveredGames.isEmpty }
        XCTAssertEqual(b.kit.scanningState, .scanning)
        XCTAssertNotNil(b.kit.timeToFirstResult)

        b.kit.close()
        settle()
        XCTAssertEqual(b.kit.scanningState, .idle)
        XCTAssertEqual(b.kit.discoveredGames, [])

        a.kit.close()
        XCTAssertNil(a.kit.session)
        XCTAssertEqual(sessionA.state.connection, .ended(reason: .left))
        waitUntil("A stopped advertising") { !a.device.isAdvertising && a.device.activeHost == nil }
        // Closing twice is harmless.
        a.kit.close()
    }
}
