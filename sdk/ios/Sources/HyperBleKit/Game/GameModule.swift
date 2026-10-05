import Foundation

/// The rules of one game, as the SDK sees them. The SDK runs the session (seats, messages,
/// state sync, host migration) and knows no game; a module supplies one. What crosses between
/// them is plain JSON (`JSONValue`) that the SDK passes on without reading it.
///
/// An app implements this protocol once per game and lists the modules in
/// `HyperBleKitConfig.games`. A module holds no state of its own: the game in progress is a
/// `GameInstance`. Only the host runs the rules; clients get views. For a game whose world
/// moves by itself, implement `RealtimeGameModule` instead.
///
/// The SDK calls a module on the main thread. The same game must have the same `id` and the
/// same JSON formats on iOS and Android, because either can host.
public protocol GameModule {
    /// The identifier of the game, sent between phones, e.g. "chess". Unique among
    /// `HyperBleKitConfig.games` and the same on every platform.
    var id: String { get }
    /// The game's name for the screen. The SDK also uses it in the sentences of
    /// `GameSession.startGameProblem(_:)`.
    var title: String { get }
    /// Whether the game can be played by this many players, the host included. The host asks
    /// before it starts a game, with the number of players that are connected.
    func supports(playerCount: Int) -> Bool
    /// Starts a new game.
    ///
    /// - Parameters:
    ///   - playerIds: the players in the game, in ascending seat order ("p1", "p2", …); their
    ///     number is one `supports(playerCount:)` accepted.
    ///   - random: the only source of randomness the game may use, for shuffling and the like.
    /// - Returns: the game in its first state.
    func start<R: RandomNumberGenerator>(playerIds: [String], using random: inout R) -> GameInstance
    /// Continues a game from the state another host exported with `GameInstance.state()`, when
    /// this device takes the game over.
    ///
    /// - Parameter state: the full state, possibly written by the other platform's module.
    /// - Returns: the game in that state, or nil if the state cannot be read; the session then
    ///   goes back to the lobby.
    func restore(state: JSONValue) -> GameInstance?
}

/// One game in progress, on the host. It is a value: applying an action does not change the
/// instance but gives a new one. The SDK calls it on the main thread.
public protocol GameInstance {
    /// Whether the game is over. The session's phase then becomes `GamePhase.finished`, and the
    /// last views stay on the table until the host starts another game or ends this one.
    var finished: Bool { get }
    /// Judges a player's move.
    ///
    /// - Parameters:
    ///   - playerId: who made the move. It can be anyone in the session, also a player that is
    ///     not in this game.
    ///   - action: the move, in the module's own JSON format. It comes from another phone:
    ///     treat anything unreadable or not allowed as a rejection, never as a crash.
    ///   - random: the only source of randomness the game may use.
    /// - Returns: `.accepted` with the game after the move, or `.rejected` with the reason.
    func apply<R: RandomNumberGenerator>(playerId: String, action: JSONValue, using random: inout R) -> GameOutcome
    /// What one player is allowed to see of the game, in the module's own JSON format. It is
    /// sent to that player after every change and arrives as `SessionState.view`. Leave out
    /// what the player must not know, such as other players' cards.
    ///
    /// - Parameter playerId: the viewer. Someone who is not in the game gets a spectator view.
    func view(for playerId: String) -> JSONValue
    /// The full state of the game, hidden information included, in a JSON format that
    /// `GameModule.restore(state:)` can read on every platform. The host sends it to its
    /// successors so that one of them can continue the game.
    func state() -> JSONValue
    /// The session removed a player: it was offline for too long, or the host removed it.
    ///
    /// - Parameter playerId: the removed player. For someone who is not in the game, return the
    ///   same game unchanged.
    /// - Returns: the game without that player, or nil if it cannot continue; the session then
    ///   goes back to the lobby.
    func removePlayer(_ playerId: String) -> GameInstance?
}

/// What a `GameInstance` answers to a move.
public enum GameOutcome {
    /// The move was allowed; the associated value is the game after it.
    case accepted(GameInstance)
    /// The move was refused and the game is unchanged; the associated value says why.
    case rejected(GameRejection)
}

/// Why a move or a host's request was refused: a short machine-readable code and an optional
/// sentence. The player who made the request sees both in `SessionState.gameNotice`.
public struct GameRejection: Error, Equatable {
    /// The SDK's code for a game the host could not start, e.g. with the wrong number of players.
    public static let cannotStart = "cannot_start"
    /// The SDK's code for a player the host could not remove, e.g. one that is connected.
    public static let cannotRemove = "cannot_remove"

    /// A short identifier of the reason, e.g. "not_your_turn". A game module chooses its own
    /// codes; it is sent to the player's phone as it is.
    public let code: String
    /// An optional sentence that explains the refusal, for the screen and the log.
    public var reason: String?

    /// Creates a rejection.
    ///
    /// - Parameters:
    ///   - code: a short identifier of the reason.
    ///   - reason: an optional sentence that explains it.
    public init(code: String, reason: String? = nil) {
        self.code = code
        self.reason = reason
    }

    /// The rejection as one line for the screen: `code: reason`, or the code alone.
    public var text: String { reason.map { "\(code): \($0)" } ?? code }
}
