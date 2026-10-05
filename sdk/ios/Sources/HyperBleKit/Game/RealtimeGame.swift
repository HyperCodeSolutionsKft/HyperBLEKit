import Foundation

/// The fixed numbers of a real-time game. They belong to the module, not to a game in progress:
/// the host checks every player's connection against them before it starts the game, and a
/// client, which runs no rules, needs them to tell the game's frames from others.
public struct RealtimeSpec: Equatable {
    /// The length of one simulation step, in milliseconds, e.g. 33 for 30 steps a second.
    public let tickMillis: Int
    /// The host sends a world frame to the clients every this many ticks: 2 at 30 ticks a
    /// second gives 15 frames a second.
    public let framePeriodTicks: Int
    /// The first byte of every world frame payload (host to client). It tells the game's
    /// frames from others; it must not be 0x01 or 0x02, which the speed test uses.
    public let worldKind: UInt8
    /// The first byte of every input frame payload (client to host). It must differ from
    /// `worldKind` and must not be 0x01 or 0x02.
    public let inputKind: UInt8
    /// The size in bytes of the largest world frame payload the game builds, kind byte
    /// included. A frame must fit one Bluetooth packet, so the host starts the game only when
    /// every connected player's link can carry this many bytes at once.
    public let maxWorldFrameBytes: Int

    /// Creates the specification of a real-time game.
    ///
    /// - Parameters:
    ///   - tickMillis: the length of one simulation step, in milliseconds.
    ///   - framePeriodTicks: a world frame is sent every this many ticks; at least 1.
    ///   - worldKind: the first byte of a world frame payload.
    ///   - inputKind: the first byte of an input frame payload.
    ///   - maxWorldFrameBytes: the largest world frame payload in bytes, kind byte included.
    public init(tickMillis: Int, framePeriodTicks: Int, worldKind: UInt8, inputKind: UInt8, maxWorldFrameBytes: Int) {
        self.tickMillis = tickMillis
        self.framePeriodTicks = framePeriodTicks
        self.worldKind = worldKind
        self.inputKind = inputKind
        self.maxWorldFrameBytes = maxWorldFrameBytes
    }
}

/// A game module whose world moves by itself, tick by tick, such as an action game. Everything
/// of `GameModule` holds for it unchanged. The game has two layers: the coarse state (scores,
/// round, winner), which `GameInstance.state()` and `view(for:)` return and which is synced
/// reliably, and the moving world, which the host sends as small frames that may be lost.
/// A screen gets the frames through `GameSession.realtime`.
public protocol RealtimeGameModule: GameModule {
    /// The game's tick length, frame rate, frame kinds and largest frame.
    var realtime: RealtimeSpec { get }
    /// Reads the input sequence number out of an input frame payload. The SDK uses it only to
    /// measure the round-trip time for the debug log.
    ///
    /// - Parameter payload: an input frame payload of this game, kind byte included.
    /// - Returns: the sequence number, or nil when the payload has none.
    func inputSeq(inInput payload: Data) -> UInt8?
    /// Reads, out of a world frame, the newest input sequence number of one player that the
    /// host had used when it built the frame. The SDK uses it only to measure the round-trip
    /// time for the debug log.
    ///
    /// - Parameters:
    ///   - frame: a world frame payload of this game, kind byte included.
    ///   - view: that player's current view (`SessionState.view`), which tells which player of
    ///     the frame it is.
    /// - Returns: the echoed sequence number, or nil when the frame does not say.
    func echoedInputSeq(inWorld frame: Data, view: JSONValue) -> UInt8?
}

/// One real-time game in progress, on the host. It is a value, like every `GameInstance`: an
/// input or a tick gives a new instance. `GameModule.restore(state:)` gets only the coarse
/// state, so after a host migration the world starts afresh (for example a new round with the
/// scores kept). The SDK calls it on the main thread.
public protocol RealtimeGameInstance: GameInstance {
    /// The players of the game, in seat order. Only their inputs are passed on, and the host
    /// pauses the world while one of them is disconnected.
    var playerIds: [String] { get }
    /// Takes a player's controls. Of several inputs before a tick, only the newest counts at
    /// that tick.
    ///
    /// - Parameters:
    ///   - playerId: one of `playerIds`.
    ///   - payload: the input frame payload, kind byte included, as the player's screen passed
    ///     it to `RealtimeLink.sendInput(_:)`. It comes from another phone: a payload the game
    ///     cannot read must count as no input, never crash.
    /// - Returns: the game with that input waiting for the next tick.
    func input(playerId: String, payload: Data) -> RealtimeGameInstance
    /// Advances the world by one tick of `RealtimeSpec.tickMillis`.
    ///
    /// - Returns: the game after the tick, and `coarseChanged`: true when `state()` differs
    ///   from before the tick (a point scored, the game finished). The host then syncs the new
    ///   state and views reliably to everyone.
    func tick() -> (instance: RealtimeGameInstance, coarseChanged: Bool)
    /// The moving world as one frame payload, the same for every player: the first byte is
    /// `RealtimeSpec.worldKind`, and the size is at most `RealtimeSpec.maxWorldFrameBytes`.
    /// Include a tick number, so that a screen can time frames by it.
    func worldFrame() -> Data
}
