import Combine
import XCTest
@testable import HyperBleKit

/// Whole games over the hybrid transports: BLE from `FakeAir`, Wi-Fi Aware from `FakeAwareAir`.
/// The runtime, the sessions and the game are the real code; only the radios are fakes.
@MainActor
final class WifiAwareEndToEndTests: XCTestCase {
    private struct Phone {
        let device: AirDevice
        let kit: HyperBleKit
        /// Every Wi-Fi Aware host path made for this phone; nil for a BLE-only phone.
        let awareHosts: Box<[FakeAwareHostPath]>?
    }

    final class Box<T> {
        var value: T
        init(_ value: T) { self.value = value }
    }

    private var air: FakeAir!
    private var aware: FakeAwareAir!
    private var suiteNames: [String] = []
    private var lines: [String] = []

    override func setUp() async throws {
        air = FakeAir()
        aware = FakeAwareAir()
        lines = []
        HyperBleLog.shared.sink = { [weak self] line in self?.lines.append(line) }
    }

    override func tearDown() async throws {
        HyperBleLog.shared.sink = nil
        suiteNames.forEach { UserDefaults.standard.removePersistentDomain(forName: $0) }
        suiteNames = []
        air.reset()
        aware.reset()
        air = nil
        aware = nil
    }

    private func phone(_ name: String, wifiAware: Bool) -> Phone {
        let suiteName = "WifiAwareEndToEndTests-\(name)-\(UUID().uuidString)"
        suiteNames.append(suiteName)
        let identity = LocalIdentity(defaults: UserDefaults(suiteName: suiteName)!)
        identity.storedPlayerName = name
        let device = air.device(name)
        let config = HyperBleKitConfig(games: [CounterModule()], dataPaths: wifiAware ? .preferWiFiAware : .bleOnly)
        guard wifiAware else {
            let kit = HyperBleKit(config: config, identity: identity, scanner: device.makeScanner(),
                                  makeHostTransport: device.makeHostTransport,
                                  makeClientTransport: device.makeClientTransport)
            return Phone(device: device, kit: kit, awareHosts: nil)
        }
        let aware = self.aware!
        let hosts = Box<[FakeAwareHostPath]>([])
        let kit = HyperBleKit(
            config: config, identity: identity, scanner: device.makeScanner(),
            makeHostTransport: {
                let path = aware.hostPath()
                hosts.value.append(path)
                return HybridHostTransport(ble: device.makeHostTransport(), aware: path, scheduler: aware.scheduler)
            },
            makeClientTransport: {
                HybridClientTransport(ble: device.makeClientTransport(), aware: aware.clientPath(), scheduler: aware.scheduler)
            })
        return Phone(device: device, kit: kit, awareHosts: hosts)
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

    private func logged(_ text: String) -> Bool { lines.contains { $0.contains(text) } }

    private func count(in session: GameSession) -> Int? {
        if case .int(let value)? = session.state.view?["count"] { return value }
        return nil
    }

    private func add(_ amount: Int) -> JSONValue {
        .object(["type": .string("add"), "add": .int(amount)])
    }

    private func startGame(_ host: GameSession, _ others: [GameSession]) {
        host.startGame(CounterModule.moduleId)
        waitUntil("the game is on every table") { ([host] + others).allSatisfy { count(in: $0) == 0 } }
    }

    func testTwoPhonesWithWiFiAwarePlayOverIt() {
        let a = phone("Anna", wifiAware: true), b = phone("Bence", wifiAware: true)
        let sessionA = a.kit.host()
        let sessionB = join(b, code: sessionA.gameCode)
        waitUntil("the link is up over Wi-Fi Aware") { logged("PATH up over Wi-Fi Aware") }
        XCTAssertTrue(logged(": up over Wi-Fi Aware"))

        startGame(sessionA, [sessionB])
        let carried = aware.messagesCarried
        sessionB.act(add(5))
        waitUntil("B's move reached everyone") { [sessionA, sessionB].allSatisfy { count(in: $0) == 5 } }
        sessionA.act(add(2))
        waitUntil("A's move reached everyone") { [sessionA, sessionB].allSatisfy { count(in: $0) == 7 } }
        XCTAssertGreaterThan(aware.messagesCarried, carried + 2)
    }

    func testRealTimeFramesTravelOverWiFiAware() {
        let a = phone("Anna", wifiAware: true), b = phone("Bence", wifiAware: true)
        let sessionA = a.kit.host()
        _ = join(b, code: sessionA.gameCode)
        waitUntil("the link is up over Wi-Fi Aware") { logged("PATH up over Wi-Fi Aware") }
        sessionA.diagnostics.startSpeedTest(frameRate: 15, payloadSize: 64)
        waitUntil("frames went over Wi-Fi Aware") { aware.framesCarried > 5 }
    }

    func testAPhoneWithoutWiFiAwarePlaysInTheSameGameOverBLE() {
        let a = phone("Anna", wifiAware: true), b = phone("Bence", wifiAware: true), c = phone("Csilla", wifiAware: false)
        let sessionA = a.kit.host()
        let sessionB = join(b, code: sessionA.gameCode)
        let sessionC = join(c, code: sessionA.gameCode)
        waitUntil("B is up over Wi-Fi Aware") { logged("PATH up over Wi-Fi Aware") }
        XCTAssertEqual(a.awareHosts?.value.last?.offers, 1)

        startGame(sessionA, [sessionB, sessionC])
        sessionC.act(add(3))
        waitUntil("C's move reached everyone") { [sessionA, sessionB, sessionC].allSatisfy { count(in: $0) == 3 } }
    }

    func testWhenWiFiAwareBreaksTheGameGoesOnOverBLE() {
        let a = phone("Anna", wifiAware: true), b = phone("Bence", wifiAware: true)
        let sessionA = a.kit.host()
        let sessionB = join(b, code: sessionA.gameCode)
        waitUntil("the link is up over Wi-Fi Aware") { logged("PATH up over Wi-Fi Aware") }
        startGame(sessionA, [sessionB])

        aware.isAvailable = false
        aware.breakAll()
        waitUntil("both ends fell back") {
            logged("PATH fallback to BLE (Wi-Fi Aware unavailable)") && logged(": fallback to BLE (Wi-Fi Aware unavailable)")
        }
        let carried = aware.messagesCarried
        sessionB.act(add(4))
        waitUntil("B's move reached everyone over BLE") { [sessionA, sessionB].allSatisfy { count(in: $0) == 4 } }
        XCTAssertEqual(aware.messagesCarried, carried)
        XCTAssertEqual(sessionB.state.connection, .joined)
    }

    func testAClientThatLeavesOverWiFiAwareIsGoneFromTheLobby() {
        let a = phone("Anna", wifiAware: true), b = phone("Bence", wifiAware: true)
        let sessionA = a.kit.host()
        _ = join(b, code: sessionA.gameCode)
        waitUntil("the link is up over Wi-Fi Aware") { logged("PATH up over Wi-Fi Aware") }

        b.kit.leave()
        waitUntil("A's lobby has only A") { sessionA.state.players.map(\.id) == ["p1"] }
    }
}
