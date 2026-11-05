import Foundation

/// The one place that decides which transport implementation the SDK uses.
/// The simulator has no Bluetooth, so it always gets the fakes.
enum TransportFactory {
    static func makeHostTransport(config: HyperBleKitConfig) -> GameHostTransport {
        #if targetEnvironment(simulator)
        return FakeHostTransport()
        #else
        let ble = BLEHostTransport(layout: config.layout)
        guard config.dataPaths == .preferWiFiAware else { return ble }
        return HybridHostTransport(ble: ble, aware: makeAwareHostPath(config: config))
        #endif
    }

    static func makeScanner(layout: BLELayout) -> GameScanning {
        #if targetEnvironment(simulator)
        return FakeGameScanner()
        #else
        return BLEScanner(layout: layout)
        #endif
    }

    static func makeClientTransport(config: HyperBleKitConfig) -> GameClientTransport {
        #if targetEnvironment(simulator)
        return FakeClientTransport()
        #else
        let ble = BLEClientTransport(layout: config.layout)
        guard config.dataPaths == .preferWiFiAware else { return ble }
        return HybridClientTransport(ble: ble, aware: makeAwareClientPath(config: config))
        #endif
    }

    /// The Wi-Fi Aware data path of this platform. iOS gets its own in a later phase (the
    /// `WiFiAware` framework, iOS 26); until then an iPhone never asks for or offers one, and
    /// the hybrid transports behave exactly like the BLE ones.
    static func makeAwareHostPath(config: HyperBleKitConfig) -> AwareHostPath {
        UnsupportedAwareHostPath()
    }

    static func makeAwareClientPath(config: HyperBleKitConfig) -> AwareClientPath {
        UnsupportedAwareClientPath()
    }
}
