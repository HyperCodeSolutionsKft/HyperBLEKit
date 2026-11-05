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

    /// The Wi-Fi Aware data path: the `WiFiAware` framework on iOS 26 and later (built with the
    /// iOS 26 SDK), on an iPhone that supports it with the service in Info.plist. Anywhere else
    /// an iPhone never asks for or offers one, and the hybrid transports behave exactly like the
    /// BLE ones.
    static func makeAwareHostPath(config: HyperBleKitConfig) -> AwareHostPath {
        #if canImport(WiFiAware) && os(iOS)
        if #available(iOS 26.0, *), WiFiAwareSupport.isSupported(serviceName: config.wifiAwareServiceName) {
            return WiFiAwareHostPath(serviceName: config.wifiAwareServiceName)
        }
        #endif
        return UnsupportedAwareHostPath()
    }

    static func makeAwareClientPath(config: HyperBleKitConfig) -> AwareClientPath {
        #if canImport(WiFiAware) && os(iOS)
        if #available(iOS 26.0, *), WiFiAwareSupport.isSupported(serviceName: config.wifiAwareServiceName) {
            return WiFiAwareClientPath(serviceName: config.wifiAwareServiceName)
        }
        #endif
        return UnsupportedAwareClientPath()
    }

    /// Whether this iPhone can carry data over Wi-Fi Aware with this config.
    static func isWiFiAwareSupported(config: HyperBleKitConfig) -> Bool {
        guard config.dataPaths == .preferWiFiAware else { return false }
        #if canImport(WiFiAware) && os(iOS) && !targetEnvironment(simulator)
        if #available(iOS 26.0, *) {
            return WiFiAwareSupport.isSupported(serviceName: config.wifiAwareServiceName)
        }
        #endif
        return false
    }
}
