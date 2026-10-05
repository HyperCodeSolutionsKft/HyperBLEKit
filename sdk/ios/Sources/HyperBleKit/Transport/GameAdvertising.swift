import Combine

/// Whether a host's game can be found by others; see `SessionState.advertising`.
public enum AdvertisingState: Equatable {
    /// Not advertising: hosting has not started yet, or this device has stopped hosting.
    case idle
    /// Hosting has started; waiting for Bluetooth to power on or for advertising to begin.
    /// `gameCode` is the code that will be advertised.
    case starting(gameCode: String)
    /// The game is being advertised under `gameCode` and can be found and joined.
    case advertising(gameCode: String)
    /// Bluetooth cannot be used; the associated value says why. Advertising resumes by itself
    /// once Bluetooth is available.
    case unavailable(BluetoothAvailability)
    /// The system refused to advertise or to serve the game. The associated value is the
    /// system's error text, for a debug screen.
    case failed(String)
}

/// Host side of discovery: makes this device visible as a game.
///
/// Contract for implementations:
/// - Advertise the `BLELayout`'s `serviceUUID` and `GameCode.localName(for:layout:)`.
/// - `start` may be called before Bluetooth is powered on; start advertising once it is.
/// - If Bluetooth turns off while advertising, publish `.unavailable` and resume when it's back.
/// - Publish state on the main thread.
protocol GameAdvertising: AnyObject {
    var statePublisher: AnyPublisher<AdvertisingState, Never> { get }
    func start(gameCode: String)
    func stop()
}
