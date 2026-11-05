import Combine
import XCTest
@testable import HyperBleKit

/// The hybrid transports' own rules, over hand-driven fakes: what goes where, and in what order.
final class HybridTransportTests: XCTestCase {
    private final class ManualScheduler {
        private(set) var now: TimeInterval = 0
        private var tasks: [(due: TimeInterval, block: () -> Void)] = []
        lazy var schedule: MainScheduler = { [unowned self] delay, block in self.tasks.append((self.now + delay, block)) }

        func advance(_ seconds: TimeInterval) {
            now += seconds
            let due = tasks.filter { $0.due <= now + 1e-9 }
            tasks.removeAll { $0.due <= now + 1e-9 }
            due.forEach { $0.block() }
        }
    }

    private final class BLEClient: GameClientTransport {
        let events = PassthroughSubject<ClientTransportEvent, Never>()
        var sent: [Data] = []
        var disconnects = 0
        var eventsPublisher: AnyPublisher<ClientTransportEvent, Never> { events.eraseToAnyPublisher() }
        func connect(to gameId: UUID) {}
        func connect(toGameCode gameCode: String) {}
        func send(_ message: Data) -> Int {
            sent.append(message)
            return 1
        }
        func sendFrame(_ payload: Data) -> FrameSendResult { .queued }
        var staleFrameCount: Int { 0 }
        func disconnect() { disconnects += 1 }
        func rejectCurrentHost() {}
        func forgetRejectedHosts() {}
        var controls: [AwareControl?] { sent.filter(AwareWire.isControl).map(AwareControl.decode) }
    }

    private final class AwareClient: AwareClientPath {
        let events = PassthroughSubject<AwareClientEvent, Never>()
        var connectedTo: AwareOffer?
        var connectedScheme: AwareScheme?
        var sent: [Data] = []
        var flushCompletion: (() -> Void)?
        var eventsPublisher: AnyPublisher<AwareClientEvent, Never> { events.eraseToAnyPublisher() }
        func supports(_ scheme: AwareScheme) -> Bool { true }
        func connect(_ offer: AwareOffer, scheme: AwareScheme) {
            connectedTo = offer
            connectedScheme = scheme
        }
        func send(_ message: Data) -> Bool {
            sent.append(message)
            return true
        }
        func sendFrame(_ payload: Data) -> FrameSendResult { .queued }
        func close() {}
        func closeAfterFlush(then completion: @escaping () -> Void) { flushCompletion = completion }
    }

    private static let chunk = 20
    private let offer = AwareOffer(instanceId: Data(repeating: 1, count: 8), token: Data(repeating: 2, count: 8),
                                   passphrase: "passphrase")
    private var scheduler: ManualScheduler!
    private var ble: BLEClient!
    private var aware: AwareClient!
    private var client: HybridClientTransport!
    private var received: [String] = []
    private var cancellables = Set<AnyCancellable>()

    override func setUp() {
        scheduler = ManualScheduler()
        ble = BLEClient()
        aware = AwareClient()
        client = HybridClientTransport(ble: ble, aware: aware, scheduler: scheduler.schedule)
        received = []
        client.eventsPublisher.sink { [unowned self] event in
            if case .message(let bytes, _) = event { self.received.append(String(decoding: bytes, as: UTF8.self)) }
        }.store(in: &cancellables)
    }

    private func bytes(_ text: String) -> Data { Data(text.utf8) }
    private var awareInfo: Data { AwareWire.withAwareFlag(bytes("{\"v\":1,\"hostPlatform\":\"ios\"}")) }

    private func upgraded() {
        ble.events.send(.gameInfo(awareInfo))
        ble.events.send(.ready(maxChunkLength: Self.chunk))
        ble.events.send(.message(AwareControl.offer(offer).encoded(), chunks: 1))
        aware.events.send(.up)
    }

    func testAClientAsksOnlyWhenTheHostsGameInfoHasTheFlag() {
        ble.events.send(.gameInfo(bytes("{\"v\":1}")))
        ble.events.send(.ready(maxChunkLength: Self.chunk))
        XCTAssertTrue(ble.controls.isEmpty)
        ble.events.send(.gameInfo(AwareWire.withAwareFlag(bytes("{\"v\":1,\"hostPlatform\":\"android\"}"))))
        ble.events.send(.ready(maxChunkLength: Self.chunk))
        XCTAssertTrue(ble.controls.isEmpty, "never across platforms")
        ble.events.send(.gameInfo(awareInfo))
        ble.events.send(.ready(maxChunkLength: Self.chunk))
        XCTAssertEqual(ble.controls, [.hello])
    }

    func testAHostOnTheOtherPlatformIsAskedOnlyWithTheExperimentalOption() {
        let androidHost = AwareWire.withAwareFlag(bytes("{\"v\":1,\"hostPlatform\":\"android\"}"), paired: true)
        ble.events.send(.gameInfo(androidHost))
        ble.events.send(.ready(maxChunkLength: Self.chunk))
        XCTAssertTrue(ble.controls.isEmpty)

        let crossBle = BLEClient(), crossAware = AwareClient()
        let cross = HybridClientTransport(ble: crossBle, aware: crossAware, scheduler: scheduler.schedule, crossPlatform: true)
        crossBle.events.send(.gameInfo(androidHost))
        crossBle.events.send(.ready(maxChunkLength: Self.chunk))
        XCTAssertEqual(crossBle.controls, [.hello])
        crossBle.events.send(.message(AwareControl.offer(offer).encoded(), chunks: 1))
        XCTAssertEqual(crossAware.connectedScheme, .paired)
        withExtendedLifetime(cross) {}
    }

    func testAnUpgradedClientSendsOverWiFiAwareAfterTellingTheHost() {
        upgraded()
        XCTAssertEqual(aware.connectedTo, offer)
        XCTAssertEqual(ble.controls.last, .pathSwitch(.wifiAware))
        XCTAssertEqual(client.send(bytes("move")), 1)
        XCTAssertEqual(aware.sent, [bytes("move")])
    }

    func testMessagesFromWiFiAwareWaitForTheBLEMessagesBeforeTheSwitch() {
        upgraded()
        aware.events.send(.message(bytes("m3")))
        XCTAssertEqual(received, [])
        ble.events.send(.message(bytes("m1"), chunks: 1))
        ble.events.send(.message(bytes("m2"), chunks: 1))
        ble.events.send(.message(AwareControl.pathSwitch(.wifiAware).encoded(), chunks: 1))
        aware.events.send(.message(bytes("m4")))
        XCTAssertEqual(received, ["m1", "m2", "m3", "m4"])
    }

    func testHeldMessagesAreDeliveredAfterTheGateTimeout() {
        upgraded()
        aware.events.send(.message(bytes("m1")))
        scheduler.advance(InboundGate.timeout - 0.01)
        XCTAssertEqual(received, [])
        scheduler.advance(0.02)
        XCTAssertEqual(received, ["m1"])
    }

    func testWhenTheDataPathFailsTheClientGoesBackToBLE() {
        upgraded()
        aware.events.send(.down(reason: "lost"))
        XCTAssertEqual(ble.controls.last, .pathSwitch(.ble))
        client.send(bytes("after"))
        XCTAssertEqual(ble.sent.last, bytes("after"))
    }

    func testADeclinedClientAsksAgainThreeTimesInAll() {
        ble.events.send(.gameInfo(awareInfo))
        ble.events.send(.ready(maxChunkLength: Self.chunk))
        for _ in 0...HybridClientTransport.maxHelloAttempts {
            ble.events.send(.message(AwareControl.decline(.noFreeDataPath).encoded(), chunks: 1))
            scheduler.advance(HybridClientTransport.helloRetryDelay)
        }
        XCTAssertEqual(ble.controls.filter { $0 == .hello }.count, HybridClientTransport.maxHelloAttempts)
    }

    func testLimitsStayThoseOfTheBLELink() {
        upgraded()
        let maxMessage = 255 * (Self.chunk - BLEConstants.chunkHeaderSize)
        XCTAssertEqual(client.send(Data(count: maxMessage)), 1)
        XCTAssertEqual(client.send(Data(count: maxMessage + 1)), 0)
        let maxFrame = RealtimeFrame.maxPayloadLength(maxChunkLength: Self.chunk)
        XCTAssertEqual(client.sendFrame(Data(count: maxFrame)), .queued)
        XCTAssertEqual(client.sendFrame(Data(count: maxFrame + 1)), .failed)
    }

    func testDisconnectFlushesTheDataPathBeforeBLECloses() {
        upgraded()
        client.disconnect()
        XCTAssertEqual(ble.disconnects, 0)
        XCTAssertEqual(client.send(bytes("late")), 0)
        aware.flushCompletion?()
        XCTAssertEqual(ble.disconnects, 1)
    }

    // MARK: - Host

    private final class BLEHost: GameHostTransport {
        let events = PassthroughSubject<HostTransportEvent, Never>()
        var sent: [(LinkID, Data)] = []
        var gameInfo = Data()
        var statePublisher: AnyPublisher<AdvertisingState, Never> { Just(.idle).eraseToAnyPublisher() }
        var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { events.eraseToAnyPublisher() }
        func start(gameCode: String) {}
        func stop() {}
        func stopAfterFlush(then completion: @escaping () -> Void) { completion() }
        func setGameInfo(_ info: Data) { gameInfo = info }
        func send(_ message: Data, to link: LinkID) -> Int {
            sent.append((link, message))
            return 1
        }
        func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult { .queued }
        var staleFrameCount: Int { 0 }
        func controls(_ link: LinkID) -> [AwareControl?] {
            sent.filter { $0.0 == link && AwareWire.isControl($0.1) }.map { AwareControl.decode($0.1) }
        }
    }

    private final class AwareHost: AwareHostPath {
        let events = PassthroughSubject<AwareHostEvent, Never>()
        var isSupported = true
        var isReady = true
        var maxDataPaths = 1
        var acceptsPairedClients = false
        var closed: [LinkID] = []
        var sent: [Data] = []
        var eventsPublisher: AnyPublisher<AwareHostEvent, Never> { events.eraseToAnyPublisher() }
        func start() {}
        func stop() {}
        func stopAfterFlush() {}
        func offer(for link: LinkID) -> AwareOffer? {
            AwareOffer(instanceId: Data(count: 8), token: Data(count: 8), passphrase: "passphrase")
        }
        func send(_ message: Data, to link: LinkID) -> Bool {
            sent.append(message)
            return true
        }
        func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult { .queued }
        func close(_ link: LinkID) { closed.append(link) }
    }

    func testAHostOffersDeclinesAndKeepsControlMessagesToItself() {
        let ble = BLEHost(), aware = AwareHost()
        let host = HybridHostTransport(ble: ble, aware: aware, scheduler: scheduler.schedule)
        var runtime: [HostTransportEvent] = []
        host.eventsPublisher.sink { runtime.append($0) }.store(in: &cancellables)
        host.setGameInfo(bytes("{\"v\":1}"))
        XCTAssertTrue(AwareWire.hasAwareFlag(ble.gameInfo))
        let a = UUID(), b = UUID()
        ble.events.send(.linkUp(a, maxChunkLength: Self.chunk))
        ble.events.send(.linkUp(b, maxChunkLength: Self.chunk))
        let hello = AwareControl.hello.encoded()

        aware.isReady = false
        ble.events.send(.message(a, hello, chunks: 1))
        XCTAssertEqual(ble.controls(a).last, .decline(.unavailable))
        aware.isReady = true
        ble.events.send(.message(a, hello, chunks: 1))
        if case .offer? = ble.controls(a).last {} else { XCTFail("no offer") }
        ble.events.send(.message(b, hello, chunks: 1))
        XCTAssertEqual(ble.controls(b).last, .decline(.noFreeDataPath))
        XCTAssertFalse(runtime.contains { if case .message = $0 { return true } else { return false } })

        aware.events.send(.pathUp(a))
        XCTAssertEqual(ble.controls(a).last, .pathSwitch(.wifiAware))
        host.send(bytes("state"), to: a)
        XCTAssertEqual(aware.sent, [bytes("state")])
        ble.events.send(.linkDown(a))
        XCTAssertEqual(aware.closed, [a])
    }
}
