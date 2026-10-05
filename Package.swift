// swift-tools-version: 5.9
import PackageDescription

// HyperBleKit: local multiplayer for nearby phones over Bluetooth LE. The Swift library lives in
// sdk/ios; the manifest is at the repository root so the package can be added by its Git URL.
let package = Package(
    name: "HyperBleKit",
    platforms: [
        .iOS(.v15),
        // CoreBluetooth exists on macOS: lets `swift build` and `swift test` run on the host.
        .macOS(.v12),
    ],
    products: [
        .library(name: "HyperBleKit", targets: ["HyperBleKit"]),
    ],
    targets: [
        .target(name: "HyperBleKit", path: "sdk/ios/Sources/HyperBleKit"),
        .testTarget(name: "HyperBleKitTests", dependencies: ["HyperBleKit"], path: "sdk/ios/Tests/HyperBleKitTests"),
    ],
    // Swift 5 mode: the code is confined to the main thread by convention, not by annotations.
    swiftLanguageVersions: [.v5]
)
