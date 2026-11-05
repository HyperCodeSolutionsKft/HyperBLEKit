import Combine
import Foundation

/// Client side over BLE with Wi-Fi Aware as an upgrade (wire specification, "Wi-Fi Aware data
/// path"); see `HybridHostTransport` for the idea. The connection is set up and searched for over
/// BLE exactly as without Wi-Fi Aware. Once it is ready, and if the host's GameInfo says it takes
/// Wi-Fi Aware, this side asks for a data path; once it is up, messages and frames to the host go
/// over it, and back over BLE if it fails.
///
/// The connection lives exactly as long as its BLE link: `.disconnected` comes from BLE only.
///
/// Main thread only.
final class HybridClientTransport: GameClientTransport {
    /// A declined client asks this many times in all per connection.
    static let maxHelloAttempts = 3
    static let helloRetryDelay: TimeInterval = 10

    private enum AwareState { case none, asked, connecting, up }

    private let ble: GameClientTransport
    private let aware: AwareClientPath
    private let scheduler: MainScheduler
    private let events = PassthroughSubject<ClientTransportEvent, Never>()
    private var cancellables = Set<AnyCancellable>()
    private var hostTakesAware = false
    private var awareState = AwareState.none
    private var maxChunkLength = 0
    private var helloAttempts = 0
    /// Counts BLE connections, so a retry armed for an earlier one does nothing.
    private var connection = 0
    /// `disconnect()` waits for the data path to flush: nothing can be sent meanwhile.
    private var isClosing = false
    private lazy var gate = InboundGate(
        scheduler: scheduler,
        deliver: { [weak self] in self?.events.send(.message($0, chunks: 1)) },
        log: { [weak self] in self?.log($0) })

    init(ble: GameClientTransport, aware: AwareClientPath, scheduler: @escaping MainScheduler = MainSchedulers.main) {
        self.ble = ble
        self.aware = aware
        self.scheduler = scheduler
        ble.eventsPublisher.sink { [weak self] in self?.bleEvent($0) }.store(in: &cancellables)
        aware.eventsPublisher.sink { [weak self] in self?.awareEvent($0) }.store(in: &cancellables)
    }

    var eventsPublisher: AnyPublisher<ClientTransportEvent, Never> { events.eraseToAnyPublisher() }
    var staleFrameCount: Int { ble.staleFrameCount }

    func connect(to gameId: UUID) {
        resetAware()
        ble.connect(to: gameId)
    }

    func connect(toGameCode gameCode: String) {
        resetAware()
        ble.connect(toGameCode: gameCode)
    }

    func rejectCurrentHost() {
        resetAware()
        ble.rejectCurrentHost()
    }

    func forgetRejectedHosts() {
        ble.forgetRejectedHosts()
    }

    @discardableResult
    func send(_ message: Data) -> Int {
        guard !isClosing else { return 0 }
        guard awareState == .up else { return ble.send(message) }
        let maxLength = Int(UInt8.max) * (maxChunkLength - BLEConstants.chunkHeaderSize)
        guard message.count <= maxLength else {
            log("send failed: message of \(message.count) bytes exceeds \(maxLength)")
            return 0
        }
        if aware.send(message) { return 1 }
        return ble.send(message)
    }

    @discardableResult
    func sendFrame(_ payload: Data) -> FrameSendResult {
        guard !isClosing else { return .failed }
        guard awareState == .up else { return ble.sendFrame(payload) }
        guard payload.count <= RealtimeFrame.maxPayloadLength(maxChunkLength: maxChunkLength) else { return .failed }
        return aware.sendFrame(payload)
    }

    func disconnect() {
        if awareState == .up && !isClosing {
            // The last messages (a LEAVE) may be on the data path: they go out before BLE
            // closes, or the host could see the BLE link drop before they arrive.
            isClosing = true
            let armed = connection
            aware.closeAfterFlush { [weak self] in
                guard let self, armed == self.connection else { return }
                self.resetAware()
                self.ble.disconnect()
            }
            return
        }
        guard !isClosing else { return }
        resetAware()
        ble.disconnect()
    }

    /// Forgets the data path of the current connection; a new connection starts on BLE only.
    private func resetAware() {
        connection += 1
        isClosing = false
        if awareState == .connecting || awareState == .up { aware.close() }
        awareState = .none
        hostTakesAware = false
        helloAttempts = 0
        gate.reset()
    }

    private func log(_ text: String) {
        events.send(.log(text))
    }

    private func sendControl(_ control: AwareControl) {
        ble.send(control.encoded())
    }

    // MARK: - BLE

    private func bleEvent(_ event: ClientTransportEvent) {
        switch event {
        case .gameInfo(let bytes):
            hostTakesAware = AwareWire.hasAwareFlag(bytes)
            events.send(event)
        case .ready(let maxChunkLength):
            self.maxChunkLength = maxChunkLength
            events.send(event)
            if hostTakesAware && aware.isSupported && awareState == .none { sayHello() }
        case .message(let bytes, _):
            if AwareWire.isControl(bytes) { control(bytes) } else { events.send(event) }
        case .disconnected:
            // The host may have dropped the link while the data path was flushing.
            resetAware()
            events.send(event)
        case .hostFound, .frame, .log:
            events.send(event)
        }
    }

    private func sayHello() {
        helloAttempts += 1
        awareState = .asked
        sendControl(.hello)
    }

    private func control(_ bytes: Data) {
        switch AwareControl.decode(bytes) {
        case .offer(let offer):
            guard awareState == .asked else { return }
            awareState = .connecting
            log("PATH host offers Wi-Fi Aware, connecting")
            aware.connect(offer)
        case .decline(let reason):
            guard awareState == .asked else { return }
            awareState = .none
            guard helloAttempts < Self.maxHelloAttempts else {
                log("PATH host declined Wi-Fi Aware (\(reason.logName)), staying on BLE")
                return
            }
            log("PATH host declined Wi-Fi Aware (\(reason.logName)), asking again in \(Int(Self.helloRetryDelay)) s")
            let armed = connection
            scheduler(Self.helloRetryDelay) { [weak self] in
                guard let self, armed == self.connection, self.awareState == .none, !self.isClosing else { return }
                self.sayHello()
            }
        case .pathSwitch(.wifiAware):
            gate.open()
        case .pathSwitch(.ble):
            log("PATH host is back on BLE")
        case .hello, .failed, nil:
            log("PATH ignored transport-control message bytes=\(bytes.count)")
        }
    }

    // MARK: - Wi-Fi Aware

    private func awareEvent(_ event: AwareClientEvent) {
        switch event {
        case .up:
            guard awareState == .connecting else {
                aware.close()
                return
            }
            awareState = .up
            // After every message already queued on BLE, so the host can keep the order.
            sendControl(.pathSwitch(.wifiAware))
            log("PATH up over Wi-Fi Aware")
        case .down(let reason):
            let previous = awareState
            guard previous == .connecting || previous == .up else { return }
            awareState = .none
            gate.reset()
            if previous == .up {
                sendControl(.pathSwitch(.ble))
                log("PATH fallback to BLE (\(reason))")
            } else {
                sendControl(.failed)
                log("PATH Wi-Fi Aware not set up (\(reason)), staying on BLE")
            }
        case .message(let bytes):
            if awareState == .up { gate.fromAware(bytes) }
        case .frame(let payload):
            if awareState == .up { events.send(.frame(payload)) }
        case .log(let text):
            log(text)
        }
    }
}
