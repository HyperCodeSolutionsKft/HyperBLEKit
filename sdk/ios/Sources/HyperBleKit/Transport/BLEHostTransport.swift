import Combine
import CoreBluetooth

/// Host side over BLE: one `CBPeripheralManager` serves the GATT service and advertises
/// the game (service UUID + "CG-XXXX" local name) for the whole session.
final class BLEHostTransport: NSObject, GameHostTransport {
    /// The service, characteristics and advertising format of this app's games.
    private let layout: BLELayout

    init(layout: BLELayout) {
        self.layout = layout
        super.init()
    }

    /// A central that subscribed to `HostToClient`.
    private struct Link {
        let central: CBCentral
        var chunker = Chunker()
        var reassembler = Reassembler()
        /// Chunks not yet accepted by `updateValue`, oldest first.
        var outgoing: [Data] = []
        /// Since when `updateValue` has been refusing the first queued chunk, nil while it flows.
        var blockedSince: Date?
        /// The newest real-time frame payload not yet accepted by `updateValue`; it goes before
        /// the next chunk.
        var frameSlot: Data?
        /// `frameSeq` of the next frame that goes out.
        var nextFrameSeq: UInt8 = 0
        var incomingFrames = FrameSeqFilter()
    }

    private let state = CurrentValueSubject<AdvertisingState, Never>(.idle)
    private let events = PassthroughSubject<HostTransportEvent, Never>()
    private var manager: CBPeripheralManager?
    /// The code we should be advertising, nil after `stop`.
    private var gameCode: String?
    private var gameInfo = Data()
    private var hostToClient: CBMutableCharacteristic?
    private var links: [LinkID: Link] = [:]
    /// The GameInfo value each central started reading, so a long read that spans several
    /// requests is served from one consistent value even if the game info changes meanwhile.
    private var gameInfoReads: [UUID: Data] = [:]
    private(set) var staleFrameCount = 0

    var statePublisher: AnyPublisher<AdvertisingState, Never> { state.eraseToAnyPublisher() }
    var eventsPublisher: AnyPublisher<HostTransportEvent, Never> { events.eraseToAnyPublisher() }

    func start(gameCode: String) {
        self.gameCode = gameCode
        state.send(.starting(gameCode: gameCode))
        if let manager {
            manager.stopAdvertising()
            publishServiceIfReady()
        } else {
            // Creating the manager shows the Bluetooth permission prompt on first use.
            // nil queue = main queue, so delegate callbacks and state updates stay on main.
            manager = CBPeripheralManager(delegate: self, queue: nil)
        }
    }

    func stop() {
        gameCode = nil
        manager?.stopAdvertising()
        if manager?.state == .poweredOn {
            // Connected clients see the service disappear; a peripheral can't disconnect them.
            manager?.removeAllServices()
        }
        hostToClient = nil
        dropAllLinks()
        state.send(.idle)
    }

    func stopAfterFlush(then completion: @escaping () -> Void) {
        guard gameCode != nil else {
            completion()
            return
        }
        manager?.stopAdvertising()
        events.send(.log("advertising stopped, closing in \(Int(BLEConstants.disconnectFlushTimeout)) s"))
        // Strong on purpose: the screen that owns this transport is usually gone by now, and the
        // timer keeps it alive until the last notifications are out.
        _ = Timer.scheduledInCommonModes(withTimeInterval: BLEConstants.disconnectFlushTimeout,
                                         repeats: false) { _ in
            self.stop()
            completion()
        }
    }

    func setGameInfo(_ info: Data) {
        gameInfo = info
    }

    @discardableResult
    func send(_ message: Data, to link: LinkID) -> Int {
        guard var entry = links[link] else {
            events.send(.log("send skipped: \(link.shortName) is not subscribed"))
            return 0
        }
        do {
            // Chunk size is per recipient; the central may have a different MTU than the others.
            let chunks = try entry.chunker.split(message, maxChunkLength: entry.central.maximumUpdateValueLength)
            entry.outgoing.append(contentsOf: chunks)
            links[link] = entry
            flushOutgoing()
            return chunks.count
        } catch {
            events.send(.log("send failed to \(link.shortName): \(error)"))
            return 0
        }
    }

    @discardableResult
    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult {
        // No log line: frames come many times a second; the caller counts failures.
        guard let entry = links[link],
              payload.count <= RealtimeFrame.maxPayloadLength(maxChunkLength: entry.central.maximumUpdateValueLength)
        else { return .failed }
        links[link]?.frameSlot = payload
        flushOutgoing()
        return entry.frameSlot == nil ? .queued : .replaced
    }

    /// Adds the GATT service; advertising starts once the service is in place.
    private func publishServiceIfReady() {
        guard let manager, gameCode != nil, manager.state == .poweredOn else { return }
        // Replacing the service invalidates every subscription made on the old one.
        dropAllLinks()
        // value nil = dynamic: reads and writes come to the delegate.
        let gameInfo = CBMutableCharacteristic(type: layout.gameInfoUUID, properties: .read,
                                               value: nil, permissions: .readable)
        let clientToHost = CBMutableCharacteristic(type: layout.clientToHostUUID, properties: .write,
                                                   value: nil, permissions: .writeable)
        let hostToClient = CBMutableCharacteristic(type: layout.hostToClientUUID, properties: .notify,
                                                   value: nil, permissions: .readable)
        let service = CBMutableService(type: layout.serviceUUID, primary: true)
        service.characteristics = [gameInfo, clientToHost, hostToClient]
        self.hostToClient = hostToClient
        manager.removeAllServices()
        manager.add(service)
    }

    /// Sends queued chunks, per link in order, until the system refuses one. A waiting frame
    /// goes before the next chunk. A refused packet stays where it is and is retried from
    /// `peripheralManagerIsReady(toUpdateSubscribers:)`, so no chunk is dropped or reordered.
    private func flushOutgoing(at now: Date = Date()) {
        guard let manager, let hostToClient else { return }
        for id in Array(links.keys) {
            while let link = links[id] {
                let packet: Data
                if let frame = link.frameSlot {
                    packet = RealtimeFrame.packet(seq: link.nextFrameSeq, payload: frame)
                } else if let chunk = link.outgoing.first {
                    packet = chunk
                } else {
                    break
                }
                if manager.updateValue(packet, for: hostToClient, onSubscribedCentrals: [link.central]) {
                    if link.frameSlot != nil {
                        links[id]?.frameSlot = nil
                        links[id]?.nextFrameSeq &+= 1
                    } else {
                        links[id]?.outgoing.removeFirst()
                    }
                    links[id]?.blockedSince = nil
                    continue
                }
                let blockedSince = link.blockedSince ?? now
                links[id]?.blockedSince = blockedSince
                if now.timeIntervalSince(blockedSince) > BLEConstants.blockedLinkTimeout {
                    // A central that went away without unsubscribing must not hold up the others.
                    events.send(.log("\(id.shortName) took no notification for "
                                     + "\(Int(BLEConstants.blockedLinkTimeout)) s, dropping the link"))
                    dropLink(id)
                }
                // This link waits; the others may still have room.
                break
            }
        }
    }

    private func dropLink(_ id: LinkID) {
        guard links.removeValue(forKey: id) != nil else { return }
        gameInfoReads[id] = nil
        events.send(.linkDown(id))
    }

    private func dropAllLinks() {
        gameInfoReads.removeAll()
        Array(links.keys).forEach(dropLink)
    }
}

extension BLEHostTransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard let gameCode else { return }
        if peripheral.state == .resetting {
            // The Bluetooth stack restarted: every link is gone. The service is published again
            // once the state is back to powered on.
            dropAllLinks()
        }
        switch BluetoothAvailability(peripheral.state) {
        case .ready:
            state.send(.starting(gameCode: gameCode))
            publishServiceIfReady()
        case .unknown:
            state.send(.starting(gameCode: gameCode))
        case let availability:
            // Advertising and all links stop on their own; we set up again when Bluetooth is back.
            dropAllLinks()
            state.send(.unavailable(availability))
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard let gameCode else { return }
        if let error {
            state.send(.failed(error.localizedDescription))
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [layout.serviceUUID],
            CBAdvertisementDataLocalNameKey: GameCode.localName(for: gameCode, layout: layout),
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard let gameCode else { return }
        if let error {
            state.send(.failed(error.localizedDescription))
        } else {
            state.send(.advertising(gameCode: gameCode))
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == layout.gameInfoUUID else {
            peripheral.respond(to: request, withResult: .readNotPermitted)
            return
        }
        // A long value is read in several requests; each one continues at `offset` of the
        // value the read started with.
        let reader = request.central.identifier
        if request.offset == 0 {
            gameInfoReads[reader] = gameInfo
            events.send(.log("GameInfo read by \(reader.shortName) bytes=\(gameInfo.count)"))
        }
        let value = gameInfoReads[reader] ?? gameInfo
        guard request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // One response covers the whole batch and goes to the first request.
        guard let first = requests.first else { return }
        guard requests.allSatisfy({ $0.characteristic.uuid == layout.clientToHostUUID && $0.offset == 0 }) else {
            peripheral.respond(to: first, withResult: .requestNotSupported)
            return
        }
        peripheral.respond(to: first, withResult: .success)

        for request in requests {
            guard let chunk = request.value else { continue }
            let id = request.central.identifier
            guard var link = links[id] else {
                // The usual cause: a client that sends JOIN_REQUEST before subscribing to HostToClient.
                events.send(.log("ignored write from \(id.shortName) bytes=\(chunk.count): "
                                 + "not subscribed to HostToClient yet"))
                continue
            }
            let packet = link.reassembler.receive(chunk) { events.send(.log("\(id.shortName): \($0)")) }
            links[id] = link
            switch packet {
            case .message(let message, let chunks):
                events.send(.message(id, message, chunks: chunks))
            case .frame(let seq, let payload):
                if links[id]?.incomingFrames.take(seq) == true {
                    events.send(.frame(id, payload))
                } else {
                    staleFrameCount += 1
                }
            case nil:
                break
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == layout.hostToClientUUID else { return }
        // A new subscription is a new connection: chunk and frame counters start over in both directions.
        links[central.identifier] = Link(central: central)
        events.send(.linkUp(central.identifier, maxChunkLength: central.maximumUpdateValueLength))
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == layout.hostToClientUUID else { return }
        dropLink(central.identifier)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushOutgoing()
    }
}

extension UUID {
    /// First block of the UUID: enough to tell links apart in the debug log.
    var shortName: String { String(uuidString.prefix(8)) }
}
