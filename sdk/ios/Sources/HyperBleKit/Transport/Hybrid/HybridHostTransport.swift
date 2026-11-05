import Combine
import Foundation

/// Host side over BLE with Wi-Fi Aware as an upgrade per link (wire specification, "Wi-Fi Aware
/// data path"). To the runtime it is one more `GameHostTransport`: the same links, the same
/// events, the same limits.
///
/// Every link comes up over BLE exactly as without Wi-Fi Aware, and lives exactly as long as its
/// BLE connection. A client that can use Wi-Fi Aware says so with `hello`; the host answers with
/// an `offer` (or `decline`), and once the Wi-Fi Aware path reports the data path up, the host's
/// messages and frames to that client travel over it, and back over BLE if it fails.
///
/// The frame payload limit and the largest message stay those of the link's BLE chunk size, so a
/// game behaves the same when a link falls back.
///
/// Main thread only.
final class HybridHostTransport: GameHostTransport {
    private final class Link {
        let id: LinkID
        let maxChunkLength: Int
        let gate: InboundGate
        /// An offer was sent and the data path is not up yet.
        var offered = false
        /// The data path is up: messages and frames to this client go over Wi-Fi Aware.
        var awareUp = false

        init(id: LinkID, maxChunkLength: Int, gate: InboundGate) {
            self.id = id
            self.maxChunkLength = maxChunkLength
            self.gate = gate
        }
    }

    private let ble: GameHostTransport
    private let aware: AwareHostPath
    private let scheduler: MainScheduler
    private let events = PassthroughSubject<HostTransportEvent, Never>()
    private var links: [LinkID: Link] = [:]
    private var cancellables = Set<AnyCancellable>()

    init(ble: GameHostTransport, aware: AwareHostPath, scheduler: @escaping MainScheduler = MainSchedulers.main) {
        self.ble = ble
        self.aware = aware
        self.scheduler = scheduler
        ble.eventsPublisher.sink { [weak self] in self?.bleEvent($0) }.store(in: &cancellables)
        aware.eventsPublisher.sink { [weak self] in self?.awareEvent($0) }.store(in: &cancellables)
    }

    var statePublisher: AnyPublisher<AdvertisingState, Never> { ble.statePublisher }
    var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { events.eraseToAnyPublisher() }
    var staleFrameCount: Int { ble.staleFrameCount }

    func start(gameCode: String) {
        links.values.forEach(forget)
        if aware.isSupported {
            aware.start()
        } else {
            log("Wi-Fi Aware not supported here: hosting over BLE only")
        }
        ble.start(gameCode: gameCode)
    }

    func stop() {
        links.values.forEach(forget)
        aware.stop()
        ble.stop()
    }

    func stopAfterFlush(then completion: @escaping () -> Void) {
        aware.stopAfterFlush()
        ble.stopAfterFlush(then: completion)
    }

    /// A host that can take clients over Wi-Fi Aware says so in its GameInfo.
    func setGameInfo(_ info: Data) {
        ble.setGameInfo(aware.isSupported ? AwareWire.withAwareFlag(info) : info)
    }

    @discardableResult
    func send(_ message: Data, to link: LinkID) -> Int {
        guard let state = links[link], state.awareUp else { return ble.send(message, to: link) }
        let maxLength = Int(UInt8.max) * (state.maxChunkLength - BLEConstants.chunkHeaderSize)
        guard message.count <= maxLength else {
            // The same limit as on BLE, so a message that works here still works after a fallback.
            log("send failed: message of \(message.count) bytes exceeds \(maxLength)")
            return 0
        }
        if aware.send(message, to: link) { return 1 }
        return ble.send(message, to: link)
    }

    @discardableResult
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult {
        guard let state = links[link], state.awareUp else { return ble.sendFrame(payload, to: link) }
        guard payload.count <= RealtimeFrame.maxPayloadLength(maxChunkLength: state.maxChunkLength) else { return .failed }
        return aware.sendFrame(payload, to: link)
    }

    private func log(_ text: String) {
        events.send(.log(text))
    }

    private func sendControl(_ control: AwareControl, to link: LinkID) {
        ble.send(control.encoded(), to: link)
    }

    /// Drops the link's data path or offer; the link itself stays as BLE has it.
    private func forget(_ link: Link) {
        if link.offered || link.awareUp { aware.close(link.id) }
        link.offered = false
        link.awareUp = false
        link.gate.reset()
    }

    // MARK: - BLE

    private func bleEvent(_ event: HostTransportEvent) {
        switch event {
        case .linkUp(let id, let maxChunkLength):
            if let old = links[id] { forget(old) }
            links[id] = Link(id: id, maxChunkLength: maxChunkLength, gate: InboundGate(
                scheduler: scheduler,
                deliver: { [weak self] in self?.events.send(.message(id, $0, chunks: 1)) },
                log: { [weak self] in self?.log($0) }))
            events.send(event)
        case .linkDown(let id):
            if let link = links[id] { forget(link) }
            links[id] = nil
            events.send(event)
        case .message(let id, let bytes, _):
            if AwareWire.isControl(bytes) { control(bytes, from: id) } else { events.send(event) }
        case .frame, .log:
            events.send(event)
        }
    }

    private func control(_ bytes: Data, from id: LinkID) {
        guard let link = links[id] else { return }
        switch AwareControl.decode(bytes) {
        case .hello:
            hello(link, id: id)
        case .pathSwitch(.wifiAware):
            link.gate.open()
        case .pathSwitch(.ble):
            log("PATH \(id): client is back on BLE")
        case .failed:
            log("PATH \(id): client could not set up Wi-Fi Aware, staying on BLE")
            if link.offered { aware.close(id) }
            link.offered = false
        case .offer, .decline, nil:
            log("PATH \(id): ignored transport-control message bytes=\(bytes.count)")
        }
    }

    private func hello(_ link: Link, id: LinkID) {
        guard !link.offered, !link.awareUp else { return }
        var reason: DeclineReason?
        if !aware.isReady {
            reason = .unavailable
        } else if links.values.filter({ $0.offered || $0.awareUp }).count >= aware.maxDataPaths {
            reason = .noFreeDataPath
        }
        guard reason == nil, let offer = aware.offer(for: id) else {
            let why = reason ?? .unavailable
            log("PATH \(id): Wi-Fi Aware declined (\(why.logName)), staying on BLE")
            sendControl(.decline(why), to: id)
            return
        }
        link.offered = true
        log("PATH \(id): offering Wi-Fi Aware")
        sendControl(.offer(offer), to: id)
    }

    // MARK: - Wi-Fi Aware

    private func awareEvent(_ event: AwareHostEvent) {
        switch event {
        case .pathUp(let id):
            guard let link = links[id] else {
                aware.close(id)
                return
            }
            link.offered = false
            link.awareUp = true
            // After every message already queued on BLE, so the client can keep the order.
            sendControl(.pathSwitch(.wifiAware), to: id)
            log("PATH \(id): up over Wi-Fi Aware")
        case .pathDown(let id, let reason):
            guard let link = links[id] else { return }
            let wasUp = link.awareUp
            link.offered = false
            link.awareUp = false
            link.gate.reset()
            if wasUp {
                sendControl(.pathSwitch(.ble), to: id)
                log("PATH \(id): fallback to BLE (\(reason))")
            } else {
                log("PATH \(id): Wi-Fi Aware not set up (\(reason)), staying on BLE")
            }
        case .message(let id, let bytes):
            links[id]?.gate.fromAware(bytes)
        case .frame(let id, let payload):
            if links[id] != nil { events.send(.frame(id, payload)) }
        case .log(let text):
            log(text)
        }
    }
}

extension DeclineReason {
    /// How the log names it, the same on both platforms.
    var logName: String {
        switch self {
        case .unavailable: return "unavailable"
        case .noFreeDataPath: return "no_free_data_path"
        }
    }
}
