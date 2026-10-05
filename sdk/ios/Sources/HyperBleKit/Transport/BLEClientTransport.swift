import Combine
import CoreBluetooth

/// Client side over BLE: connects to one host, reads GameInfo, subscribes to `HostToClient`
/// and writes chunked messages to `ClientToHost`.
///
/// It has its own `CBCentralManager`. The scanner found the host with a different manager, so
/// the peripheral is looked up again here, by its identifier or by its advertised game code.
final class BLEClientTransport: NSObject, GameClientTransport {
    /// The service, characteristics and advertising format of this app's games.
    private let layout: BLELayout

    init(layout: BLELayout) {
        self.layout = layout
        super.init()
    }

    private enum Target: Equatable {
        /// `DiscoveredGame.id` from the scanner.
        case peripheral(UUID)
        /// Whoever advertises this game code.
        case gameCode(String)
    }

    private let events = PassthroughSubject<ClientTransportEvent, Never>()
    private var manager: CBCentralManager?
    /// The host we should be connected to, nil when idle.
    private var target: Target?
    private var peripheral: CBPeripheral?
    private var clientToHost: CBCharacteristic?
    private var hostToClient: CBCharacteristic?
    private var isReady = false
    private var chunker = Chunker()
    private var reassembler = Reassembler()
    /// Chunks not yet confirmed by the host, oldest first. The first one is in flight when
    /// `isWriting`, unless a frame is.
    private var outgoing: [Data] = []
    private var isWriting = false
    /// The write in flight is a real-time frame, not the first chunk of `outgoing`.
    private var isWritingFrame = false
    /// The newest real-time frame payload waiting behind the write in flight; it goes before
    /// the next chunk.
    private var frameSlot: Data?
    /// `frameSeq` of the next frame written.
    private var nextFrameSeq: UInt8 = 0
    private var incomingFrames = FrameSeqFilter()
    private(set) var staleFrameCount = 0
    private var isClosing = false
    /// Hosts `connect(toGameCode:)` must not pick, see `rejectCurrentHost()`.
    private var rejectedHosts: Set<UUID> = []
    /// Limits the setup phase, then the flush before closing.
    private var timeout: Timer?

    var eventsPublisher: AnyPublisher<ClientTransportEvent, Never> { events.eraseToAnyPublisher() }

    func connect(to gameId: UUID) {
        connect(.peripheral(gameId))
    }

    func connect(toGameCode gameCode: String) {
        connect(.gameCode(gameCode))
    }

    private func connect(_ newTarget: Target) {
        if target != nil { finish(reason: nil) }
        target = newTarget
        chunker = Chunker()
        reassembler = Reassembler()
        resetFrames()
        startTimeout(BLEConstants.connectTimeout) { [weak self] in
            guard let self else { return }
            self.finish(reason: self.peripheral == nil ? "Host not found" : "Connecting timed out")
        }
        if manager == nil {
            // nil queue = main queue, so delegate callbacks and state updates stay on main.
            manager = CBCentralManager(delegate: self, queue: nil)
        } else {
            connectIfReady()
        }
    }

    @discardableResult
    func send(_ message: Data) -> Int {
        guard let peripheral, isReady, !isClosing else {
            events.send(.log("send skipped: not connected"))
            return 0
        }
        do {
            let chunks = try chunker.split(message, maxChunkLength: maxChunkLength(for: peripheral))
            outgoing.append(contentsOf: chunks)
            writeNext()
            return chunks.count
        } catch {
            events.send(.log("send failed: \(error)"))
            return 0
        }
    }

    @discardableResult
    func sendFrame(_ payload: Data) -> FrameSendResult {
        // No log line: frames come many times a second; the caller counts failures.
        guard let peripheral, isReady, !isClosing,
              payload.count <= RealtimeFrame.maxPayloadLength(maxChunkLength: maxChunkLength(for: peripheral))
        else { return .failed }
        let result: FrameSendResult = frameSlot == nil ? .queued : .replaced
        frameSlot = payload
        writeNext()
        return result
    }

    func disconnect() {
        guard target != nil, !isClosing else { return }
        // Only the reliable messages are worth the wait.
        frameSlot = nil
        if outgoing.isEmpty {
            finish(reason: nil)
        } else {
            isClosing = true
            // Strong on purpose: the screen that owns this transport is usually gone by now, and
            // the timer keeps it alive until the queue is flushed. `finish` drops the timer.
            startTimeout(BLEConstants.disconnectFlushTimeout) { self.finish(reason: nil) }
        }
    }

    func rejectCurrentHost() {
        if let peripheral { rejectedHosts.insert(peripheral.identifier) }
        finish(reason: nil)
    }

    func forgetRejectedHosts() {
        rejectedHosts.removeAll()
    }

    /// Largest chunk that fits a single ATT write. The with-response limit alone is often 512
    /// whatever the MTU, and anything above the MTU goes out as a prepared (long) write with
    /// offsets, which the host does not accept. The without-response limit is MTU - 3.
    private func maxChunkLength(for peripheral: CBPeripheral) -> Int {
        min(peripheral.maximumWriteValueLength(for: .withResponse),
            peripheral.maximumWriteValueLength(for: .withoutResponse))
    }

    private func connectIfReady() {
        guard let manager, let target, peripheral == nil, manager.state == .poweredOn else { return }
        if case .peripheral(let id) = target,
           let known = manager.retrievePeripherals(withIdentifiers: [id]).first {
            connect(known)
            return
        }
        // Find it by scanning, then connect from `didDiscover`. Duplicates are allowed because an
        // Android host's code is in its scan response, which can arrive after the main packet.
        if case .gameCode(let code) = target {
            events.send(.log("scanning for game \(code)"))
        } else {
            events.send(.log("host not cached, scanning for it"))
        }
        manager.scanForPeripherals(withServices: [layout.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    private func connect(_ peripheral: CBPeripheral) {
        // Keep a strong reference, or CoreBluetooth drops the connection attempt.
        self.peripheral = peripheral
        peripheral.delegate = self
        events.send(.log("connecting to \(peripheral.identifier.shortName)"))
        events.send(.hostFound)
        manager?.connect(peripheral)
    }

    /// Writes one packet, a waiting frame before the next chunk; the next one goes out from
    /// `didWriteValueFor`.
    private func writeNext() {
        guard !isWriting, let peripheral, let clientToHost else { return }
        if let frame = frameSlot {
            frameSlot = nil
            isWriting = true
            isWritingFrame = true
            peripheral.writeValue(RealtimeFrame.packet(seq: nextFrameSeq, payload: frame), for: clientToHost,
                                  type: .withResponse)
            nextFrameSeq &+= 1
            return
        }
        guard let chunk = outgoing.first else { return }
        isWriting = true
        peripheral.writeValue(chunk, for: clientToHost, type: .withResponse)
    }

    /// Frame counters start over with every connection, in both directions.
    private func resetFrames() {
        frameSlot = nil
        nextFrameSeq = 0
        incomingFrames = FrameSeqFilter()
        isWritingFrame = false
    }

    private func startTimeout(_ interval: TimeInterval, action: @escaping () -> Void) {
        timeout?.invalidate()
        timeout = Timer.scheduledInCommonModes(withTimeInterval: interval, repeats: false) { _ in action() }
    }

    /// Ends the session with the host, whatever state it is in, and reports it once.
    private func finish(reason: String?) {
        guard target != nil else { return }
        target = nil
        timeout?.invalidate()
        timeout = nil
        manager?.stopScan()
        if let peripheral {
            peripheral.delegate = nil
            manager?.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        clientToHost = nil
        hostToClient = nil
        isReady = false
        outgoing.removeAll()
        isWriting = false
        resetFrames()
        isClosing = false
        events.send(.disconnected(reason: reason))
    }
}

extension BLEClientTransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .resetting {
            // The Bluetooth stack restarted: the connection is gone and will not come back by itself.
            finish(reason: "Connection lost: Bluetooth is resetting")
            return
        }
        switch BluetoothAvailability(central.state) {
        case .ready:
            connectIfReady()
        case .unknown:
            break
        case let availability:
            finish(reason: availability.userMessage)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard self.peripheral == nil else { return }
        switch target {
        case .peripheral(let id):
            guard peripheral.identifier == id else { return }
        case .gameCode(let code):
            guard GameCode.from(advertisementData: advertisementData, layout: layout) == code,
                  !rejectedHosts.contains(peripheral.identifier) else { return }
        case nil:
            return
        }
        central.stopScan()
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === self.peripheral else { return }
        events.send(.log("connected, discovering service"))
        peripheral.discoverServices([layout.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }
        finish(reason: "Connect failed: \(error?.localizedDescription ?? "unknown error")")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        // A late callback for a connection we already closed must not end the current one.
        guard peripheral === self.peripheral else { return }
        finish(reason: isClosing ? nil : "Connection lost: \(error?.localizedDescription ?? "closed by host")")
    }
}

extension BLEClientTransport: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == layout.serviceUUID }) else {
            finish(reason: "Game service not found: \(error?.localizedDescription ?? "not offered by host")")
            return
        }
        peripheral.discoverCharacteristics([layout.gameInfoUUID, layout.clientToHostUUID,
                                            layout.hostToClientUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let characteristics = service.characteristics ?? []
        clientToHost = characteristics.first { $0.uuid == layout.clientToHostUUID }
        hostToClient = characteristics.first { $0.uuid == layout.hostToClientUUID }
        guard clientToHost != nil, let hostToClient else {
            finish(reason: "Game characteristics not found: \(error?.localizedDescription ?? "not offered by host")")
            return
        }
        if let gameInfo = characteristics.first(where: { $0.uuid == layout.gameInfoUUID }) {
            // Subscribing continues once the value arrives in `didUpdateValueFor`.
            peripheral.readValue(for: gameInfo)
        } else {
            events.send(.log("host has no GameInfo characteristic"))
            peripheral.setNotifyValue(true, for: hostToClient)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == layout.gameInfoUUID {
            if let value = characteristic.value, error == nil {
                events.send(.gameInfo(value))
            } else {
                events.send(.log("GameInfo read failed: \(error?.localizedDescription ?? "no value")"))
            }
            if let hostToClient, !isReady {
                peripheral.setNotifyValue(true, for: hostToClient)
            }
            return
        }
        guard characteristic.uuid == layout.hostToClientUUID, let chunk = characteristic.value else { return }
        switch reassembler.receive(chunk, log: { events.send(.log($0)) }) {
        case .message(let message, let chunks):
            events.send(.message(message, chunks: chunks))
        case .frame(let seq, let payload):
            if incomingFrames.take(seq) {
                events.send(.frame(payload))
            } else {
                staleFrameCount += 1
            }
        case nil:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == layout.hostToClientUUID else { return }
        guard error == nil, characteristic.isNotifying else {
            finish(reason: "Subscribe failed: \(error?.localizedDescription ?? "notifications are off")")
            return
        }
        timeout?.invalidate()
        timeout = nil
        resetFrames()
        isReady = true
        events.send(.log("max write length: withResponse=\(peripheral.maximumWriteValueLength(for: .withResponse)) "
                         + "withoutResponse=\(peripheral.maximumWriteValueLength(for: .withoutResponse))"))
        events.send(.ready(maxChunkLength: maxChunkLength(for: peripheral)))
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard isWriting else { return }
        isWriting = false
        if isWritingFrame {
            isWritingFrame = false
        } else {
            outgoing.removeFirst()
        }
        if let error {
            // The host's reassembler drops the rest of this message when the next one starts.
            events.send(.log("write failed: \(error.localizedDescription)"))
        }
        if outgoing.isEmpty, isClosing {
            finish(reason: nil)
        } else {
            writeNext()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard invalidatedServices.contains(where: { $0.uuid == layout.serviceUUID }) else { return }
        finish(reason: "Host stopped the game")
    }
}
