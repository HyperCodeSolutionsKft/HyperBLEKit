import Foundation

/// The one place that decides which transport implementation the SDK uses.
/// The simulator has no Bluetooth, so it always gets the fakes.
enum TransportFactory {
    static func makeHostTransport(layout: BLELayout) -> GameHostTransport {
        #if targetEnvironment(simulator)
        return FakeHostTransport()
        #else
        return BLEHostTransport(layout: layout)
        #endif
    }

    static func makeScanner(layout: BLELayout) -> GameScanning {
        #if targetEnvironment(simulator)
        return FakeGameScanner()
        #else
        return BLEScanner(layout: layout)
        #endif
    }

    static func makeClientTransport(layout: BLELayout) -> GameClientTransport {
        #if targetEnvironment(simulator)
        return FakeClientTransport()
        #else
        return BLEClientTransport(layout: layout)
        #endif
    }
}
