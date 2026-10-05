import Combine
import CoreGraphics

/// What the host's speed-test panel shows; see `SessionDiagnostics.hostSpeedTest`. It is an
/// observable object of its own, apart from the session state, because the dot moves 30 times
/// a second and only the panel should redraw for that. Changes on the main thread.
public final class HostSpeedTestState: ObservableObject {
    /// Frames a second of the running test, or of the last one.
    @Published public internal(set) var frameRate: Int
    /// Bytes in a frame of the running test, or of the last one.
    @Published public internal(set) var payloadSize: Int
    /// Whether a speed test is running.
    @Published public internal(set) var isRunning: Bool
    /// Where the test's dot is, in the coordinates of the field (`SpeedTestOptions.fieldWidth`
    /// × `fieldHeight`, origin top left). nil while no test runs.
    @Published public internal(set) var dot: CGPoint?
    /// The counters of the last full second, one line per client, e.g.
    /// `p2 fps=15 replaced=0 inFps=4 inGapMax=260`. Empty before the first second is over.
    @Published public internal(set) var lines: [String]

    /// Creates a state object, for previews and for an app's own tests. A session creates its
    /// own; an object made here never changes.
    public init(frameRate: Int = SpeedTestOptions.defaultFrameRate,
                payloadSize: Int = SpeedTestOptions.defaultPayloadSize,
                isRunning: Bool = false, dot: CGPoint? = nil, lines: [String] = []) {
        self.frameRate = frameRate
        self.payloadSize = payloadSize
        self.isRunning = isRunning
        self.dot = dot
        self.lines = lines
    }
}

/// What a client's speed-test view shows; see `SessionDiagnostics.clientSpeedTest`. It is an
/// observable object of its own, apart from the session state, because it changes with every
/// frame the host sends and only the speed-test view should redraw for that. Changes on the
/// main thread.
public final class ClientSpeedTestState: ObservableObject {
    /// Where the host's dot is, in the coordinates of the field (`SpeedTestOptions.fieldWidth`
    /// × `fieldHeight`, origin top left). nil before the first frame.
    @Published public internal(set) var dot: CGPoint?
    /// How many taps this device has sent since the view opened.
    @Published public internal(set) var taps: Int
    /// The counters of the last full second, e.g.
    /// `fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=45/80 size=64` (gaps and round trips
    /// in milliseconds, size in bytes). Empty before the first second is over.
    @Published public internal(set) var lastSecondText: String
    /// The same counters over the whole time since the view opened. Empty before the first
    /// second is over.
    @Published public internal(set) var totalText: String

    /// Creates a state object, for previews and for an app's own tests. A session creates its
    /// own; an object made here never changes.
    public init(dot: CGPoint? = nil, taps: Int = 0, lastSecondText: String = "", totalText: String = "") {
        self.dot = dot
        self.taps = taps
        self.lastSecondText = lastSecondText
        self.totalText = totalText
    }
}

/// What a speed-test panel can offer, and the field its dot moves in. The numbers are part of
/// the speed test's frame format, so they are the same on iOS and Android.
public enum SpeedTestOptions {
    /// The frame rates a host can pick, in frames a second: 15, 20 and 30.
    public static let frameRates = SpeedTestConstants.frameRates
    /// The frame rate a panel starts with: 15 frames a second.
    public static let defaultFrameRate = SpeedTestConstants.defaultFrameRate
    /// The frame sizes a host can pick, in bytes: 32, 64 and 128.
    public static let payloadSizes = SpeedTestConstants.payloadSizes
    /// The frame size a panel starts with: 64 bytes.
    public static let defaultPayloadSize = SpeedTestConstants.defaultPayloadSize
    /// The width of the field the dot moves in, in field units: 320.
    public static let fieldWidth = SpeedTestConstants.fieldWidth
    /// The height of the field the dot moves in, in field units: 180.
    public static let fieldHeight = SpeedTestConstants.fieldHeight
}
