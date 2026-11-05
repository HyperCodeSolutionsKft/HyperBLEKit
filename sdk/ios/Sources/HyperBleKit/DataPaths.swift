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
    /// On iOS this build does not carry data over Wi-Fi Aware yet: an iPhone plays over
    /// Bluetooth LE with every phone, Android phones with Wi-Fi Aware included.
    case preferWiFiAware
}
