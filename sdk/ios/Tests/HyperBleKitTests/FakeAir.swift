import Combine
import Foundation
@testable import HyperBleKit

/// An in-process stand-in for the air between phones, so several `HyperBleKit`s in one test
/// process find, join and lose each other the way phones do over BLE. Every delivery hops
/// through the main queue, like a real transport's callbacks: nothing arrives inside the call
/// that sent it, and order is kept.
///
/// Main thread only.
final class FakeAir {
    /// The chunk size every fake link reports: large enough for a small real-time frame.
    static let maxChunkLength = 182
    /// How long a client link stays up after `disconnect()`, in seconds (2 s at most on BLE).
    static let flushWindow: TimeInterval = 0.25

    fileprivate var hosts: [AirHostTransport] = []
    fileprivate var seekers: [AirClientTransport] = []
    fileprivate var scanners: [AirScanner] = []
    private var devices: [String: AirDevice] = [:]

    /// The phone with this name; the same object for the same name.
    func device(_ name: String) -> AirDevice {
        if let device = devices[name] { return device }
        let device = AirDevice(name: name, air: self)
        devices[name] = device
        return device
    }

    /// Forgets every phone, so nothing a finished test left behind keeps the others alive.
    /// Blocks that are still on their way keep what they need until they have run.
    func reset() {
        devices.values.forEach { $0.forgetTransports() }
        devices.removeAll()
        hosts.removeAll()
        seekers.removeAll()
        scanners.removeAll()
    }

    /// Every host that can be found right now, except those on `device` itself: a phone never
    /// sees its own advertisement.
    fileprivate func visibleHosts(for device: AirDevice) -> [AirHostTransport] {
        hosts.filter { $0.isVisible && $0.device !== device }
    }

    /// A host started or stopped being visible: scanners and waiting clients look again.
    fileprivate func visibilityChanged() {
        scanners.forEach { $0.publish() }
        for seeker in seekers { seeker.seek() }
    }
}

/// One phone on the `FakeAir`: it makes the transports a kit on that phone uses.
final class AirDevice {
    let name: String
    fileprivate let air: FakeAir
    /// Every host transport made for this phone, oldest first.
    private(set) var hostTransports: [AirHostTransport] = []
    /// Every client transport made for this phone, oldest first (probes included).
    private(set) var clientTransports: [AirClientTransport] = []

    fileprivate init(name: String, air: FakeAir) {
        self.name = name
        self.air = air
    }

    fileprivate func forgetTransports() {
        hostTransports.removeAll()
        clientTransports.removeAll()
    }

    func makeHostTransport() -> GameHostTransport {
        let transport = AirHostTransport(device: self)
        hostTransports.append(transport)
        return transport
    }

    func makeClientTransport() -> GameClientTransport {
        let transport = AirClientTransport(device: self)
        clientTransports.append(transport)
        return transport
    }

    func makeScanner() -> GameScanning {
        AirScanner(device: self)
    }

    /// Whether a client transport of this phone is looking for a host or linked to one.
    var hasActiveClient: Bool { clientTransports.contains { $0.isActive } }

    /// Whether this phone is advertising a game right now.
    var isAdvertising: Bool { hostTransports.contains { $0.isVisible } }

    /// The transport of the game this phone hosts right now, nil when it hosts none.
    var activeHost: AirHostTransport? { hostTransports.last { $0.gameCode != nil } }

    /// The highest `stateVersion` in the messages this phone has sent as a host or received as
    /// a client.
    var lastStateVersion: Int {
        max(hostTransports.map(\.lastStateVersion).max() ?? 0, clientTransports.map(\.lastStateVersion).max() ?? 0)
    }
}

final class AirHostTransport: GameHostTransport {
    fileprivate let device: AirDevice
    /// The identifier a scanner reports for this host (`DiscoveredGame.id`).
    let peerId = UUID()
    private let state = CurrentValueSubject<AdvertisingState, Never>(.idle)
    private let events = PassthroughSubject<HostTransportEvent, Never>()
    /// The code this host should be advertising, nil before `start` and after `stop`.
    private(set) var gameCode: String?
    private(set) var gameInfo = Data()
    private var isPoweredOn = true
    private var isAdvertising = false
    private(set) var links: [LinkID: AirClientTransport] = [:]
    private(set) var lastStateVersion = 0

    fileprivate init(device: AirDevice) {
        self.device = device
    }

    fileprivate var isVisible: Bool { gameCode != nil && isAdvertising && isPoweredOn }

    var statePublisher: AnyPublisher<AdvertisingState, Never> { state.eraseToAnyPublisher() }
    var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { events.eraseToAnyPublisher() }

    func start(gameCode: String) {
        self.gameCode = gameCode
        isAdvertising = true
        device.air.hosts.append(self)
        state.send(isPoweredOn ? .advertising(gameCode: gameCode) : .unavailable(.poweredOff))
        device.air.visibilityChanged()
    }

    func stop() {
        guard gameCode != nil else { return }
        gameCode = nil
        isAdvertising = false
        dropLinks(tellingHost: false)
        device.air.hosts.removeAll { $0 === self }
        state.send(.idle)
        device.air.visibilityChanged()
    }

    func stopAfterFlush(then completion: @escaping () -> Void) {
        guard gameCode != nil else {
            completion()
            return
        }
        isAdvertising = false
        device.air.visibilityChanged()
        // What was sent before is already on its way; the links close behind it.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            self.stop()
            completion()
        }
    }

    func setGameInfo(_ info: Data) {
        gameInfo = info
    }

    @discardableResult
    func send(_ message: Data, to link: LinkID) -> Int {
        guard let client = links[link] else { return 0 }
        if let version = (try? Message.decode(message))?.stateVersion {
            lastStateVersion = max(lastStateVersion, version)
        }
        client.deliver(.message(message, chunks: 1), from: self)
        return 1
    }

    @discardableResult
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult {
        guard let client = links[link] else { return .failed }
        client.deliver(.frame(payload), from: self)
        return .queued
    }

    var staleFrameCount: Int { 0 }

    // MARK: - What the air does to this host

    fileprivate func attach(_ client: AirClientTransport) -> LinkID {
        let link = LinkID()
        links[link] = client
        emit(.linkUp(link, maxChunkLength: FakeAir.maxChunkLength))
        return link
    }

    fileprivate func detach(_ link: LinkID) {
        guard links.removeValue(forKey: link) != nil else { return }
        emit(.linkDown(link))
    }

    fileprivate func receive(_ event: HostTransportEvent) {
        emit(event)
    }

    private func emit(_ event: HostTransportEvent) {
        DispatchQueue.main.async { self.events.send(event) }
    }

    private func dropLinks(tellingHost: Bool) {
        let dropped = links
        links.removeAll()
        for (link, client) in dropped {
            if tellingHost { emit(.linkDown(link)) }
            client.linkLost(to: self, reason: "the host is gone")
        }
    }

    // MARK: - Test controls

    /// Bluetooth goes away on this phone: every link drops on both sides and nobody finds it.
    func powerOff() {
        guard isPoweredOn else { return }
        isPoweredOn = false
        dropLinks(tellingHost: true)
        state.send(.unavailable(.poweredOff))
        device.air.visibilityChanged()
    }

    /// Bluetooth is back: the game is advertised again.
    func powerOn() {
        guard !isPoweredOn else { return }
        isPoweredOn = true
        if let gameCode, isAdvertising { state.send(.advertising(gameCode: gameCode)) }
        device.air.visibilityChanged()
    }
}

final class AirClientTransport: GameClientTransport {
    private enum Target {
        case peer(UUID)
        case gameCode(String)
    }

    fileprivate let device: AirDevice
    private let events = PassthroughSubject<ClientTransportEvent, Never>()
    private var target: Target?
    private var host: AirHostTransport?
    private var link: LinkID?
    private var rejected: Set<UUID> = []
    /// Counts up with every link and every teardown, so a close that was scheduled for an
    /// older link does nothing.
    private var generation = 0
    private(set) var lastStateVersion = 0

    fileprivate init(device: AirDevice) {
        self.device = device
    }

    var eventsPublisher: AnyPublisher<ClientTransportEvent, Never> { events.eraseToAnyPublisher() }

    /// Looking for a host, linked to one, or still flushing: not at rest.
    var isActive: Bool { target != nil }

    func connect(to gameId: UUID) {
        tearDown()
        target = .peer(gameId)
        guard let found = device.air.visibleHosts(for: device).first(where: { $0.peerId == gameId }) else {
            target = nil
            emit(.disconnected(reason: "The host is out of range."))
            return
        }
        attach(to: found)
    }

    func connect(toGameCode gameCode: String) {
        tearDown()
        target = .gameCode(gameCode)
        device.air.seekers.append(self)
        seek()
    }

    /// Looks for a host advertising the wanted game code; keeps waiting when there is none.
    fileprivate func seek() {
        guard host == nil, case .gameCode(let code) = target else { return }
        let found = device.air.visibleHosts(for: device).first { $0.gameCode == code && !rejected.contains($0.peerId) }
        guard let found else { return }
        device.air.seekers.removeAll { $0 === self }
        attach(to: found)
    }

    private func attach(to found: AirHostTransport) {
        host = found
        emit(.hostFound)
        emit(.gameInfo(found.gameInfo))
        link = found.attach(self)
        emit(.ready(maxChunkLength: FakeAir.maxChunkLength))
    }

    @discardableResult
    func send(_ message: Data) -> Int {
        guard let host, let link else { return 0 }
        host.receive(.message(link, message, chunks: 1))
        return 1
    }

    @discardableResult
    func sendFrame(_ payload: Data) -> FrameSendResult {
        guard let host, let link else { return .failed }
        host.receive(.frame(link, payload))
        return .queued
    }

    var staleFrameCount: Int { 0 }

    /// Like the real transport after a last message: the link stays up for a moment so what
    /// was queued gets out, and whatever the host sends in that window still arrives.
    func disconnect() {
        guard target != nil else { return }
        guard host != nil else {
            tearDown()
            emit(.disconnected(reason: nil))
            return
        }
        let closing = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + FakeAir.flushWindow) {
            guard self.generation == closing else { return }
            self.tearDown()
            self.emit(.disconnected(reason: nil))
        }
    }

    func rejectCurrentHost() {
        if let host { rejected.insert(host.peerId) }
        disconnect()
    }

    func forgetRejectedHosts() {
        rejected.removeAll()
    }

    private func tearDown() {
        generation += 1
        device.air.seekers.removeAll { $0 === self }
        if let host, let link { host.detach(link) }
        host = nil
        link = nil
        target = nil
    }

    // MARK: - What the air does to this client

    fileprivate func deliver(_ event: ClientTransportEvent, from sender: AirHostTransport) {
        if case .message(let data, _) = event, let version = (try? Message.decode(data))?.stateVersion {
            lastStateVersion = max(lastStateVersion, version)
        }
        emit(event)
    }

    fileprivate func linkLost(to lost: AirHostTransport, reason: String) {
        guard host === lost else { return }
        generation += 1
        host = nil
        link = nil
        target = nil
        emit(.disconnected(reason: reason))
    }

    private func emit(_ event: ClientTransportEvent) {
        DispatchQueue.main.async { self.events.send(event) }
    }

    // MARK: - Test controls

    /// Delivers an event as if the host's side of a link that is being closed still sent it.
    func inject(_ event: ClientTransportEvent) {
        emit(event)
    }
}

final class AirScanner: GameScanning {
    private let device: AirDevice
    private let state = CurrentValueSubject<ScanningState, Never>(.idle)
    private let games = CurrentValueSubject<[DiscoveredGame], Never>([])
    private var firstSeen: [UUID: Date] = [:]
    private var isScanning = false

    fileprivate init(device: AirDevice) {
        self.device = device
    }

    var statePublisher: AnyPublisher<ScanningState, Never> { state.eraseToAnyPublisher() }
    var gamesPublisher: AnyPublisher<[DiscoveredGame], Never> { games.eraseToAnyPublisher() }

    func start() {
        guard !isScanning else { return }
        isScanning = true
        device.air.scanners.append(self)
        state.send(.scanning)
        publish()
    }

    func stop() {
        isScanning = false
        device.air.scanners.removeAll { $0 === self }
        firstSeen.removeAll()
        games.send([])
        state.send(.idle)
    }

    fileprivate func publish() {
        guard isScanning else { return }
        let now = Date()
        games.send(device.air.visibleHosts(for: device).compactMap { host in
            guard let code = host.gameCode else { return nil }
            let first = firstSeen[host.peerId] ?? now
            firstSeen[host.peerId] = first
            return DiscoveredGame(id: host.peerId, gameCode: code, rssi: -50, discoveredAt: first, lastSeen: now)
        })
    }
}

/// A game for the runtime tests, not a real one: every player adds to a shared counter, and the
/// game ends at `target`. A player's view also carries what only that player may see (its own
/// number of moves), so views differ per player.
struct CounterModule: GameModule {
    static let moduleId = "counter"
    static let target = 100

    let id = CounterModule.moduleId
    let title = "Counter"

    func supports(playerCount: Int) -> Bool { (2...4).contains(playerCount) }

    func start<R: RandomNumberGenerator>(playerIds: [String], using random: inout R) -> GameInstance {
        CounterGame(count: 0, moves: Dictionary(uniqueKeysWithValues: playerIds.map { ($0, 0) }))
    }

    func restore(state: JSONValue) -> GameInstance? {
        try? state.decoded(as: CounterGame.self)
    }
}

struct CounterGame: GameInstance, Codable, Equatable {
    var count: Int
    /// Moves made, by player ID.
    var moves: [String: Int]

    var finished: Bool { count >= CounterModule.target }

    func apply<R: RandomNumberGenerator>(playerId: String, action: JSONValue, using random: inout R) -> GameOutcome {
        guard moves[playerId] != nil else {
            return .rejected(GameRejection(code: "not_in_game", reason: "\(playerId) does not play"))
        }
        guard case .int(let amount)? = action["add"], amount > 0 else {
            return .rejected(GameRejection(code: "bad_move", reason: "add must be a positive number"))
        }
        var next = self
        next.count += amount
        next.moves[playerId, default: 0] += 1
        return .accepted(next)
    }

    func view(for playerId: String) -> JSONValue {
        .object(["count": .int(count), "ownMoves": .int(moves[playerId] ?? 0)])
    }

    func state() -> JSONValue {
        (try? JSONValue(encoding: self)) ?? .null
    }

    func removePlayer(_ playerId: String) -> GameInstance? {
        var next = self
        next.moves[playerId] = nil
        return next.moves.count >= 2 ? next : nil
    }
}
