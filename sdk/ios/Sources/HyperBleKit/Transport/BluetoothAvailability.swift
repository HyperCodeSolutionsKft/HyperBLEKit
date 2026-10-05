import CoreBluetooth

/// Whether Bluetooth can be used on this device right now, reduced to what an app needs to tell
/// the user.
public enum BluetoothAvailability: Equatable {
    /// Not known yet: the system has not reported, or Bluetooth is restarting.
    case unknown
    /// Bluetooth is on and the app may use it.
    case ready
    /// Bluetooth is switched off.
    case poweredOff
    /// The user has not allowed this app to use Bluetooth.
    case unauthorized
    /// This device has no Bluetooth Low Energy; the iOS Simulator never has.
    case unsupported

    init(_ state: CBManagerState) {
        switch state {
        case .poweredOn: self = .ready
        case .poweredOff: self = .poweredOff
        case .unauthorized: self = .unauthorized
        case .unsupported: self = .unsupported
        case .unknown, .resetting: self = .unknown
        @unknown default: self = .unknown
        }
    }

    /// An English sentence to show the user when Bluetooth cannot be used, saying what to do
    /// about it; nil for `ready` and `unknown`.
    public var userMessage: String? {
        switch self {
        case .unknown, .ready: return nil
        case .poweredOff: return "Bluetooth is off. Turn it on in Control Center."
        case .unauthorized: return "Bluetooth access is denied. Allow it in Settings > Privacy > Bluetooth."
        case .unsupported: return "This device doesn't support Bluetooth LE (the simulator never does)."
        }
    }
}
