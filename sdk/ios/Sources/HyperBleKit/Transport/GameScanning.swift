import Combine
import Foundation

/// A game in range that this device could join, found by `HyperBleKit.startDiscovery()`. Hand it
/// to `HyperBleKit.join(_:)`.
public struct DiscoveredGame: Identifiable, Equatable {
    /// Identifies the hosting phone on this device only: another phone sees the same host under
    /// a different identifier, and it can change over time. Never send or store it.
    public let id: UUID
    /// The 4-character code the game is advertised under, e.g. "7K2F"; the host shows the same.
    public internal(set) var gameCode: String
    /// The signal strength of the last advertisement received, in dBm: around -40 next to the
    /// host, around -90 at the edge of its range.
    public internal(set) var rssi: Int
    /// When this game was first seen in the current scan.
    public let discoveredAt: Date
    /// When an advertisement of this game was last received.
    public internal(set) var lastSeen: Date

    /// Creates a value, for previews and for an app's own tests. A game made here cannot be
    /// joined unless a host with this identifier is really in range.
    ///
    /// - Parameters:
    ///   - id: the host's identifier on this device.
    ///   - gameCode: the 4-character game code.
    ///   - rssi: the signal strength in dBm.
    ///   - discoveredAt: when the game was first seen.
    ///   - lastSeen: when the game was last seen.
    public init(id: UUID, gameCode: String, rssi: Int, discoveredAt: Date, lastSeen: Date) {
        self.id = id
        self.gameCode = gameCode
        self.rssi = rssi
        self.discoveredAt = discoveredAt
        self.lastSeen = lastSeen
    }
}

/// Whether the scan for nearby games is running; see `HyperBleKit.scanningState`.
public enum ScanningState: Equatable {
    /// Discovery is stopped.
    case idle
    /// The scan is running and `HyperBleKit.discoveredGames` is kept up to date.
    case scanning
    /// Discovery was started but Bluetooth cannot be used; the associated value says why. The
    /// scan starts by itself once Bluetooth is available.
    case unavailable(BluetoothAvailability)
}

/// Client side of discovery: finds nearby games.
///
/// Contract for implementations:
/// - Scan for the `BLELayout`'s `serviceUUID` only, with duplicates allowed so RSSI and `lastSeen` stay fresh.
/// - Read the code with `GameCode.from(advertisementData:layout:)`; skip results without a valid code.
///   Don't remember such a peripheral as "not a game": an Android host sends the code in the
///   scan response, so the first callback may only have the service UUID.
/// - Drop games not seen for `staleAfter` seconds.
/// - `start` may be called before Bluetooth is powered on; start scanning once it is.
/// - Publish on the main thread.
protocol GameScanning: AnyObject {
    var statePublisher: AnyPublisher<ScanningState, Never> { get }
    var gamesPublisher: AnyPublisher<[DiscoveredGame], Never> { get }
    func start()
    func stop()
}

extension DiscoveredGame {
    static let staleAfter: TimeInterval = 5
}
