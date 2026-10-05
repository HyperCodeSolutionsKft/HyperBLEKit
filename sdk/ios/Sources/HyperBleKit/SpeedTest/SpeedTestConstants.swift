import Foundation

/// The speed test of the real-time lane: test equipment, kept apart from the session and the
/// games. The values must stay the same on Android and iOS.
enum SpeedTestConstants {
    /// First payload byte of a world frame, host → client.
    static let worldKind: UInt8 = 0x01
    /// First payload byte of an input frame, client → host.
    static let inputKind: UInt8 = 0x02
    /// Bytes of a world payload before its padding.
    static let worldFieldsLength = 20
    static let inputLength = 7

    /// The host moves the dot this many times a second.
    static let ticksPerSecond = 30
    static let tickInterval: TimeInterval = 1 / Double(ticksPerSecond)
    /// World frames a second the host can send.
    static let frameRates = [15, 20, 30]
    static let defaultFrameRate = 15
    /// World payload sizes the host can pick, kind byte included.
    static let payloadSizes = [32, 64, 128]
    static let defaultPayloadSize = 64

    /// The field the dot bounces in.
    static let fieldWidth = 320
    static let fieldHeight = 180
    /// Dot movement per tick.
    static let dotStepX = 3
    static let dotStepY = 2

    /// The client sends an input this often, and at once on a tap.
    static let inputInterval: TimeInterval = 0.25
    /// The client's speed-test view closes this long after the last world frame.
    static let closeAfter: TimeInterval = 2
    /// Stats are logged and shown this often.
    static let statsInterval: TimeInterval = 1
    static let statsIntervalMillis: UInt32 = 1000
    /// A gap between taken world frames longer than this counts in `over150`.
    static let slowGapMillis: UInt32 = 150
}

/// Milliseconds of a monotonic clock, wrapping at 2³², for the `hostMillis` and `clientMillis`
/// fields. Wall time could jump; this cannot.
enum SpeedTestClock {
    static func nowMillis() -> UInt32 {
        UInt32(truncatingIfNeeded: DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }
}
