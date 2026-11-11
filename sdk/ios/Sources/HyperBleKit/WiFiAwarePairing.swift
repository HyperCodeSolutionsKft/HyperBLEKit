// The pairing an iPhone needs before it can use Wi-Fi Aware with another one (iOS 26).
// Compiled only with the iOS 26 SDK; see `WiFiAwarePaths.swift`.

#if canImport(WiFiAware) && canImport(DeviceDiscoveryUI) && os(iOS)
import DeviceDiscoveryUI
import SwiftUI
import WiFiAware

/// A button that pairs this iPhone with another one for Wi-Fi Aware, through the system's
/// pairing sheet. Two iPhones only use Wi-Fi Aware with each other once they are paired; until
/// then (and with every phone they are not paired with) they play over Bluetooth LE. A pairing is
/// kept by the system, so it is done once per pair of devices, not per game.
///
/// Pairing takes both phones: one shows the button with `.waitForPairing`, the other the button
/// with `.pairWithNearbyPhone`, and the user picks the first phone in the second one's list and
/// confirms the code. Which phone hosts a game later does not matter.
///
/// The button is shown only with `DataPaths.preferWiFiAware` and the service in Info.plist; it
/// shows `unavailableTitle` otherwise, and on phones without Wi-Fi Aware.
@available(iOS 26.0, *)
public struct WiFiAwarePairingButton: View {
    /// Which side of the pairing this phone is.
    public enum Role {
        /// Lets a nearby iPhone find this one and pair with it.
        case waitForPairing
        /// Lists nearby iPhones that wait for pairing, and pairs with the one the user picks.
        case pairWithNearbyPhone
    }

    private let serviceName: String
    private let isEnabled: Bool
    private let role: Role
    private let title: String
    private let unavailableTitle: String

    /// - Parameters:
    ///   - config: the kit's configuration; its `wifiAwareServiceName` is the service paired for.
    ///   - role: which side of the pairing this phone is.
    ///   - title: the button's text.
    ///   - unavailableTitle: what is shown where Wi-Fi Aware cannot be used.
    public init(config: HyperBleKitConfig, role: Role, title: String = "Pair for Wi-Fi Aware",
                unavailableTitle: String = "Wi-Fi Aware unavailable") {
        serviceName = config.wifiAwareServiceName
        isEnabled = config.dataPaths == .preferWiFiAware
        self.role = role
        self.title = title
        self.unavailableTitle = unavailableTitle
    }

    public var body: some View {
        if isEnabled,
           let publishable = WAPublishableService.allServices[serviceName],
           let subscribable = WASubscribableService.allServices[serviceName] {
            switch role {
            case .waitForPairing:
                DevicePairingView(.wifiAware(.connecting(to: publishable, from: .userSpecifiedDevices))) {
                    Text(title)
                } fallback: {
                    Text(unavailableTitle)
                }
            case .pairWithNearbyPhone:
                DevicePicker(.wifiAware(.connecting(to: .userSpecifiedDevices, from: subscribable))) { endpoint in
                    HyperBleLog.shared.event("Wi-Fi Aware: paired with \(endpoint)")
                } label: {
                    Text(title)
                } fallback: {
                    Text(unavailableTitle)
                }
            }
        } else {
            Text(unavailableTitle)
        }
    }
}
#endif
