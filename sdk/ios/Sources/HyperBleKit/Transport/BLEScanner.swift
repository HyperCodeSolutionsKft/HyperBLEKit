import Combine
import CoreBluetooth

/// Scans for nearby games by service UUID and reads the game code from each advertisement.
final class BLEScanner: NSObject, GameScanning {
    /// The service, characteristics and advertising format of this app's games.
    private let layout: BLELayout

    init(layout: BLELayout) {
        self.layout = layout
        super.init()
    }

    private let state = CurrentValueSubject<ScanningState, Never>(.idle)
    private let gamesSubject = CurrentValueSubject<[DiscoveredGame], Never>([])
    private var manager: CBCentralManager?
    private var wantsToScan = false
    private var games = DiscoveredGames()
    private var staleTimer: Timer?

    var statePublisher: AnyPublisher<ScanningState, Never> { state.eraseToAnyPublisher() }
    var gamesPublisher: AnyPublisher<[DiscoveredGame], Never> { gamesSubject.eraseToAnyPublisher() }

    func start() {
        wantsToScan = true
        if manager == nil {
            // Creating the manager shows the Bluetooth permission prompt on first use.
            // nil queue = main queue, so delegate callbacks and state updates stay on main.
            manager = CBCentralManager(delegate: self, queue: nil)
        } else {
            startIfReady()
        }
    }

    func stop() {
        wantsToScan = false
        manager?.stopScan()
        stopStaleTimer()
        clearGames()
        state.send(.idle)
    }

    private func startIfReady() {
        guard let manager, wantsToScan, manager.state == .poweredOn else { return }
        // Duplicates keep RSSI and lastSeen fresh, and let us pick up an Android host's
        // code from its scan response, which can arrive after the main packet.
        manager.scanForPeripherals(withServices: [layout.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        state.send(.scanning)
        startStaleTimer()
    }

    private func startStaleTimer() {
        guard staleTimer == nil else { return }
        staleTimer = Timer.scheduledInCommonModes(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            if self.games.removeStale(at: Date()) {
                self.gamesSubject.send(self.games.list)
            }
        }
    }

    private func stopStaleTimer() {
        staleTimer?.invalidate()
        staleTimer = nil
    }

    private func clearGames() {
        games.removeAll()
        gamesSubject.send([])
    }
}

extension BLEScanner: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard wantsToScan else { return }
        switch BluetoothAvailability(central.state) {
        case .ready:
            startIfReady()
        case .unknown:
            break
        case let availability:
            // Scanning stops on its own; we restart it when Bluetooth is back.
            stopStaleTimer()
            clearGames()
            state.send(.unavailable(availability))
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // No code yet is normal for an Android host's main packet; the scan response follows.
        guard wantsToScan, let code = GameCode.from(advertisementData: advertisementData, layout: layout) else { return }
        games.record(id: peripheral.identifier, gameCode: code, rssi: RSSI.intValue, at: Date())
        gamesSubject.send(games.list)
    }
}
