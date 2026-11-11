import Foundation

/// Which radios carry a game's messages once the phones are connected; see
/// `HyperBleKitConfig.dataPaths`. Finding a game, joining it and every fallback always use
/// Bluetooth LE.
public enum DataPaths: Equatable {
    /// Bluetooth LE only, exactly as in HyperBleKit 0.1. The default.
    case bleOnly
    /// Bluetooth LE first; then, between a host and a client that both support Wi-Fi Aware, the
    /// messages move to Wi-Fi Aware, and back to Bluetooth LE if that link breaks. Games behave
    /// the same either way. Phones without Wi-Fi Aware play in the same game over Bluetooth LE.
    ///
    /// On iOS it needs iOS 26 and an iPhone 12 or later, the app built with the iOS 26 SDK, the
    /// `com.apple.developer.wifi-aware` entitlement, the service in Info.plist
    /// (`WiFiAwareServices`), and a one-time pairing of the two iPhones
    /// (`WiFiAwarePairingButton`). Wi-Fi Aware is used between two iPhones or two Android
    /// phones; an iPhone and an Android phone play over Bluetooth LE.
    case preferWiFiAware
}
