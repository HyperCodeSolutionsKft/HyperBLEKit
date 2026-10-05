import Combine
import Foundation

/// What the screen of a real-time game gets on top of `SessionState.view`: the moving world and
/// a way to send this player's controls. It is the same on the host and on a client, so a
/// screen never needs to know which role its device has. Get it from `GameSession.realtime`.
///
/// - On the host, `worldFrames` come straight from the local simulation, one per tick, while
///   the world runs. `sendInput(_:)` feeds the host's own controls to the game, one tick late,
///   so the host has no advantage over the clients.
/// - On a client, `worldFrames` are the frames the host sent: one every
///   `RealtimeSpec.framePeriodTicks` ticks, with some lost on the way. `sendInput(_:)` sends an
///   input frame to the host; only the newest unsent one goes out.
///
/// Nothing arrives while the game is paused (a player is disconnected) or finished. A screen
/// should time frames by the tick number inside them, times `tickMillis`, not by when they
/// arrived, and draw a little behind the newest one (about 100 ms), interpolating between
/// frames. A tick number lower than the previous frame's means a new round or match started:
/// start interpolating afresh.
///
/// The link does not repeat inputs. The screen sends one whenever a control changes and at
/// least every 250 ms, each with a new sequence number in the game's own input format.
///
/// Use it on the main thread only.
public protocol RealtimeLink: AnyObject {
    /// The world frame payloads as they become available, each starting with the game's
    /// `RealtimeSpec.worldKind` byte. Delivered on the main thread. A new subscriber gets the
    /// newest frame at once, if there is one.
    var worldFrames: AnyPublisher<Data, Never> { get }
    /// The newest world frame payload; nil before the first frame of this game has arrived.
    var latestWorldFrame: Data? { get }
    /// The length of one simulation step of the game, in milliseconds
    /// (`RealtimeSpec.tickMillis`), to time frames by their tick number.
    var tickMillis: Int { get }
    /// Sends this player's controls.
    ///
    /// Ignored while the game is not being played, while the host cannot be reached, and for
    /// a payload that does not start with the game's `RealtimeSpec.inputKind` byte.
    ///
    /// - Parameter payload: an input frame payload in the game's own format, kind byte
    ///   included. It must fit one Bluetooth packet: keep it to a few bytes.
    func sendInput(_ payload: Data)
}

/// The `RealtimeLink` both roles hand to the screen: the role's runtime publishes frames into it and
/// decides where inputs go.
final class RealtimeFeed: RealtimeLink {
    let tickMillis: Int
    private let frames = CurrentValueSubject<Data?, Never>(nil)
    private let send: (Data) -> Void

    init(tickMillis: Int, send: @escaping (Data) -> Void) {
        self.tickMillis = tickMillis
        self.send = send
    }

    var worldFrames: AnyPublisher<Data, Never> {
        frames.compactMap { $0 }.eraseToAnyPublisher()
    }

    var latestWorldFrame: Data? { frames.value }

    func sendInput(_ payload: Data) {
        send(payload)
    }

    /// A new world frame for the screen. Main thread only.
    func publish(_ frame: Data) {
        frames.send(frame)
    }
}
