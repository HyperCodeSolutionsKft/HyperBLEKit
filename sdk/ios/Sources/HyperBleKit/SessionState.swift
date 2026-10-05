import Foundation

/// The role this device has in a game right now. It changes with a host migration: a client
/// that takes the game over becomes the host, and a host that steps down becomes a client.
public enum SessionRole: Equatable {
    /// This device runs the game: it advertises it, seats the players and decides every move.
    case host
    /// This device is connected to another device that runs the game.
    case client
}

/// Why a session is over for this device. Nothing more happens in a session that has ended;
/// call `HyperBleKit.leave()` to close it.
public enum EndReason: Equatable {
    /// The host refused the join request, or removed this player from the game. `code` is one
    /// of `JoinRejectCode`, or a code this SDK version does not know when the host runs a newer one.
    case rejected(code: String)
    /// The host ended the game, or was lost and neither it nor a new host was found within the
    /// reconnect window of 45 seconds.
    case hostLeft
    /// The first connection to the host failed before this device had joined. `detail` is a
    /// sentence for a debug screen that says why, when the reason is known.
    case disconnected(detail: String?)
    /// This device left: `HyperBleKit.leave()` or `HyperBleKit.close()` was called, or another
    /// session was opened. The session object is dead and never changes again.
    case left
}

/// Where this device stands with the host of the game.
public enum ConnectionState: Equatable {
    /// The Bluetooth link to the host is being set up.
    case connecting
    /// The link is up and the join request has been sent; waiting for the host's answer.
    case joining
    /// In the game. A client can play; a host is in this state from the start until it is left.
    case joined
    /// The host was lost. The SDK is trying to get back in and gives up when the reconnect
    /// window ends. `secondsLeft` is the whole seconds left of that window and counts down
    /// once a second. Moves cannot be made in this state.
    case reconnecting(secondsLeft: Int)
    /// The session is over for this device; `reason` says why.
    case ended(reason: EndReason)
}

/// Everything a screen needs to know about the game this device is in, as one immutable value.
/// `GameSession.state` publishes a new value on the main thread whenever a field changes.
/// Fields that only one role has are nil or empty while the device has the other role.
public struct SessionState: Equatable {
    /// Whether this device is the host or a client right now.
    public internal(set) var role: SessionRole
    /// Where this device stands with the host. A host is `joined` until its session is left.
    public internal(set) var connection: ConnectionState
    /// The 4-character code the game is advertised under, e.g. "7K2F". It never changes.
    public internal(set) var gameCode: String
    /// This device's player ID (its seat, e.g. "p2"). nil on a client until the host has
    /// assigned one; kept while reconnecting.
    public internal(set) var playerId: String?
    /// Everyone in the game, the host included, as the host last reported them.
    public internal(set) var players: [Player]
    /// The player ID of the current host. On a client it is "p1" until the host's state has arrived.
    public internal(set) var hostId: String
    /// How many hosts the game has had: 1 for the first, plus 1 for every takeover. 0 on a
    /// client until the host's state has arrived.
    public internal(set) var hostEpoch: Int
    /// One of the `GamePhase` constants: `lobby`, `playing` or `finished`.
    public internal(set) var phase: String
    /// `GameModule.id` of the game on the table, running or finished; nil in the lobby.
    public internal(set) var gameId: String?
    /// This player's own view of the game, as JSON in the game module's format; nil in the lobby.
    public internal(set) var view: JSONValue?
    /// Client only: a sentence the player should read before the host answers the join request,
    /// e.g. that the lobby is closed or the host uses another protocol version. nil otherwise.
    public internal(set) var notice: String?
    /// Why this player's last move, or the host's last attempt to start a game, was refused:
    /// the game module's code and reason as one line. Cleared when the game moves on.
    public internal(set) var gameNotice: String?
    /// Host only: whether the game is being advertised so that others can find it. nil on a client.
    public internal(set) var advertising: AdvertisingState?
    /// Host only: round-trip times to the clients, in milliseconds. nil on a client.
    public internal(set) var pingStats: PingStats?
    /// Host only: the player IDs that take the game over if this device goes, first in line
    /// first. The first `HyperBleKit.snapshotSuccessors` of them hold a copy of the game.
    /// Empty on a client.
    public internal(set) var successors: [String]
    /// Host only: every player that is disconnected, by player ID, with the whole seconds left
    /// until the host removes its seat. Counts down once a second. Empty on a client.
    public internal(set) var offlineSecondsLeft: [String: Int]
    /// Client only: one line for a debug screen about this device's part in a host migration,
    /// e.g. `Snapshot: held · takeover rank #1`. nil on a host.
    public internal(set) var migrationInfo: String?
    /// Client only: one line for a debug screen about the last reconnect, e.g.
    /// `Last reconnect: 3.2 s (attempt 2)`. nil on a host and before the first reconnect.
    public internal(set) var lastReconnect: String?

    /// Creates a state value, for previews and for an app's own tests. Sessions create their
    /// states themselves; a value made here is not connected to any game.
    public init(role: SessionRole, connection: ConnectionState, gameCode: String, playerId: String? = nil,
                players: [Player] = [], hostId: String, hostEpoch: Int, phase: String = GamePhase.lobby,
                gameId: String? = nil, view: JSONValue? = nil, notice: String? = nil, gameNotice: String? = nil,
                advertising: AdvertisingState? = nil, pingStats: PingStats? = nil, successors: [String] = [],
                offlineSecondsLeft: [String: Int] = [:], migrationInfo: String? = nil, lastReconnect: String? = nil) {
        self.role = role
        self.connection = connection
        self.gameCode = gameCode
        self.playerId = playerId
        self.players = players
        self.hostId = hostId
        self.hostEpoch = hostEpoch
        self.phase = phase
        self.gameId = gameId
        self.view = view
        self.notice = notice
        self.gameNotice = gameNotice
        self.advertising = advertising
        self.pingStats = pingStats
        self.successors = successors
        self.offlineSecondsLeft = offlineSecondsLeft
        self.migrationInfo = migrationInfo
        self.lastReconnect = lastReconnect
    }
}
