import Combine
import Foundation

/// The debug tools of a `GameSession`: a fixed JSON test frame to check that long messages
/// arrive unchanged, and a speed test of the real-time frame lane. They are test equipment for
/// a debug screen; a game does not need them.
///
/// Used and published on the main thread. After the session was left every method does nothing.
@MainActor
public final class SessionDiagnostics: ObservableObject {
    /// One line for the screen about the last test frame sent or received, e.g.
    /// `Test JSON: received 465 B in 3 chunks from p1 — match`. nil before the first.
    @Published public internal(set) var testResult: String?
    /// The last test frame this device received, as indented JSON text. nil before the first;
    /// sending one does not change it.
    @Published public internal(set) var testJsonReceived: String?
    /// What a host's speed-test panel shows. Set while this device is the host, nil while it is
    /// a client and after the session was left. The object itself publishes the moving values.
    @Published public internal(set) var hostSpeedTest: HostSpeedTestState?
    /// What a client's speed-test view shows. Set while the host's speed-test frames arrive,
    /// and nil again 2 seconds after the last one; nil on a host.
    @Published public internal(set) var clientSpeedTest: ClientSpeedTestState?
    /// The last on-demand ping-all on this device (`pingAll()`): one round trip per connected
    /// client. nil until a ping-all has run; always nil while this device is a client (it is
    /// host-only).
    @Published public internal(set) var pingAllResult: PingAllResult?

    weak var session: GameSession?

    init() {}

    /// Sends the test frame once: a host sends it to every joined client, a client sends it to
    /// the host. A client that is not `joined` sends nothing. The outcome appears in `testResult`.
    public func sendTestJson() {
        session?.sendTestJson()
    }

    /// Whether `startSpeedTest(frameRate:payloadSize:)` would start a test now: this device is
    /// the host, the game is in the lobby and at least one client is connected. Not published:
    /// read it when `GameSession.state` changes.
    public var canRunSpeedTest: Bool {
        session?.canRunSpeedTest ?? false
    }

    /// Host only: starts sending speed-test frames to every connected client until
    /// `stopSpeedTest()` is called, a game starts or the last client is gone. Does nothing
    /// while `canRunSpeedTest` is false or a test is already running.
    ///
    /// - Parameters:
    ///   - frameRate: frames a second, one of `SpeedTestOptions.frameRates`.
    ///   - payloadSize: bytes in a frame, one of `SpeedTestOptions.payloadSizes`.
    public func startSpeedTest(frameRate: Int, payloadSize: Int) {
        session?.startSpeedTest(frameRate: frameRate, payloadSize: payloadSize)
    }

    /// Host only: stops the running speed test. Does nothing when none is running.
    public func stopSpeedTest() {
        session?.stopSpeedTest()
    }

    /// Client only: counts a tap on the speed-test field and sends it to the host at once.
    /// Does nothing while `clientSpeedTest` is nil.
    public func tapSpeedTest() {
        session?.tapSpeedTest()
    }

    /// Host only: pings every joined client at once and, after a short wait, reports each one's
    /// round trip in `pingAllResult` (a client that did not answer gets a nil time). On a client
    /// it does nothing. Use it as a liveness and latency check after connecting clients.
    public func pingAll() {
        session?.pingAll()
    }
}
