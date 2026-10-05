import Foundation

/// Data models as they appear on the wire. See the wire specification "Data models" and "GameInfo".
/// Optional fields are omitted when nil, never sent as `null`.

/// One player in a game, as the host reports it to everyone.
public struct Player: Codable, Equatable, Identifiable {
    /// The player's seat and identifier in this game: "p1" for the phone that created the game;
    /// everyone else gets the lowest free seat of "p2" to "p10". It never changes while the player is in the
    /// game, also across reconnects and host migrations.
    public let id: String
    /// The name the player chose, at most `LocalIdentity.maxNameLength` characters.
    public var name: String
    /// The player's platform: "ios" or "android".
    public var platform: String
    /// Whether the host can reach this player right now. A disconnected player keeps its seat
    /// for a while (see `SessionState.offlineSecondsLeft`).
    public var connected: Bool
    /// A number of cards in the player's hand, for hosts that report one. The SDK's own hosts
    /// never set it: a game module puts such numbers in its views.
    public var handSize: Int?
    /// Whether this player's phone can host, and so can take the game over.
    public var canHost: Bool

    /// Creates a player value, for previews and for an app's own tests.
    ///
    /// - Parameters:
    ///   - id: the seat, e.g. "p2".
    ///   - name: the player's name.
    ///   - platform: "ios" or "android".
    ///   - connected: whether the host can reach the player.
    ///   - handSize: a hand size, when one is reported.
    ///   - canHost: whether the player's phone can host.
    public init(id: String, name: String, platform: String, connected: Bool, handSize: Int? = nil, canHost: Bool) {
        self.id = id
        self.name = name
        self.platform = platform
        self.connected = connected
        self.handSize = handSize
        self.canHost = canHost
    }
}

extension Player {
    /// Decodes a player from the JSON a host sends. A missing `canHost` is read as false and a
    /// missing `handSize` as nil.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        platform = try container.decode(String.self, forKey: .platform)
        connected = try container.decode(Bool.self, forKey: .connected)
        handSize = try container.decodeIfPresent(Int.self, forKey: .handSize)
        canHost = try container.decodeIfPresent(Bool.self, forKey: .canHost) ?? false
    }

    /// 2 for "p2". Seats are compared by number, not as text.
    static func seatNumber(of playerId: String) -> Int {
        Int(playerId.dropFirst()) ?? Int.max
    }
}

/// The values of `SessionState.phase`, as they are sent between phones.
public enum GamePhase {
    /// No game on the table: players join and the host picks a game.
    public static let lobby = "lobby"
    /// A game is being played.
    public static let playing = "playing"
    /// The game is over and its last views are still on the table.
    public static let finished = "finished"
}

/// The codes a host refuses a join request with, as they are sent between phones. They arrive
/// in `EndReason.rejected(code:)`.
public enum JoinRejectCode {
    /// Every seat is taken.
    public static let lobbyFull = "lobby_full"
    /// A game is running or finished and this device has no seat in it: only players who were
    /// in it can come back.
    public static let gameStarted = "game_started"
    /// The host speaks another version of the protocol.
    public static let versionMismatch = "version_mismatch"
    /// The host removed this player from the game.
    public static let removed = "removed"
}

/// Host-owned state snapshot: what the engine knows. Everything about the game itself lives
/// in the game module's own JSON.
struct GameState: Codable, Equatable {
    let gameId: String
    let stateVersion: Int
    let phase: String
    let players: [Player]
    /// Player ID of the current host.
    let hostId: String
    /// Starts at 1, +1 every time another player takes over as host.
    let hostEpoch: Int
}

extension GameState {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        gameId = try container.decode(String.self, forKey: .gameId)
        stateVersion = try container.decode(Int.self, forKey: .stateVersion)
        phase = try container.decode(String.self, forKey: .phase)
        players = try container.decode([Player].self, forKey: .players)
        hostId = try container.decodeIfPresent(String.self, forKey: .hostId) ?? ProtocolConstants.firstHostPlayerId
        hostEpoch = try container.decodeIfPresent(Int.self, forKey: .hostEpoch) ?? ProtocolConstants.firstHostEpoch
    }
}

/// Value of the `GameInfo` characteristic: plain JSON, not chunked.
struct GameInfo: Codable, Equatable {
    let v: Int
    let gameId: String
    let hostName: String
    let players: Int
    let maxPlayers: Int
    let lobbyOpen: Bool
    let hostPlatform: String
    let hostId: String
    let hostEpoch: Int
}

extension GameInfo {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        v = try container.decode(Int.self, forKey: .v)
        gameId = try container.decode(String.self, forKey: .gameId)
        hostName = try container.decode(String.self, forKey: .hostName)
        players = try container.decode(Int.self, forKey: .players)
        maxPlayers = try container.decode(Int.self, forKey: .maxPlayers)
        lobbyOpen = try container.decode(Bool.self, forKey: .lobbyOpen)
        hostPlatform = try container.decode(String.self, forKey: .hostPlatform)
        hostId = try container.decodeIfPresent(String.self, forKey: .hostId) ?? ProtocolConstants.firstHostPlayerId
        hostEpoch = try container.decodeIfPresent(Int.self, forKey: .hostEpoch) ?? ProtocolConstants.firstHostEpoch
    }
}

// MARK: - Message payloads (`data`)

struct JoinRequest: Codable, Equatable {
    let name: String
    let platform: String
    let deviceId: String
    let canHost: Bool
}

extension JoinRequest {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        platform = try container.decode(String.self, forKey: .platform)
        deviceId = try container.decode(String.self, forKey: .deviceId)
        canHost = try container.decodeIfPresent(Bool.self, forKey: .canHost) ?? false
    }
}

struct JoinAccept: Codable, Equatable {
    let playerId: String
    let stateVersion: Int
}

struct JoinReject: Codable, Equatable {
    static let lobbyFull = JoinRejectCode.lobbyFull
    static let gameStarted = JoinRejectCode.gameStarted
    static let versionMismatch = JoinRejectCode.versionMismatch
    /// The host removed this player; sent on a link it still has.
    static let removed = JoinRejectCode.removed

    let code: String
}

struct StateSync: Codable, Equatable {
    let state: GameState
    /// The game module that is running; absent in the lobby.
    var game: String?
    /// The recipient's own view of that game, in the module's format.
    var view: JSONValue?
}

/// Payload of `GAME_STATE`: the game changed. Every player gets its own `view`.
struct GameUpdate: Codable, Equatable {
    let stateVersion: Int
    let phase: String
    /// Absent, like `view`, when no game exists (back in the lobby).
    var game: String?
    var view: JSONValue?
}

/// A player's move. `action` is in the format of the game module `game`.
struct GameAction: Codable, Equatable {
    let game: String
    let action: JSONValue
}

/// Payload of `ERROR`: the `GAME_ACTION` with the client `seq` `refSeq` was rejected.
struct ActionError: Codable, Equatable {
    /// The engine's own codes; all others come from the game module.
    static let noGame = "no_game"
    static let wrongGame = "wrong_game"

    let refSeq: Int
    let code: String
    /// Text for the log.
    var reason: String?
}

struct SyncRequest: Codable, Equatable {
    let haveVersion: Int
}

struct PlayerList: Codable, Equatable {
    static let joined = "joined"
    static let left = "left"
    static let disconnected = "disconnected"
    static let reconnected = "reconnected"
    /// The host removed an offline player's seat.
    static let removed = "removed"

    let event: String
    let playerId: String
    let stateVersion: Int
    let players: [Player]
}

/// Everything a successor needs to continue the game as host. Holds hidden information
/// (the game module's full state), so the receiver keeps it in memory and never shows it.
struct HostSnapshot: Codable, Equatable {
    struct Seat: Codable, Equatable {
        let deviceId: String
    }

    let state: GameState
    /// Every seat, the host's own included, so returning players get their seats back.
    let seats: [String: Seat]
    /// The players this round of snapshots went to, first in line first. A holder's place in
    /// this list is its rank for taking over.
    let successors: [String]
    /// The game module that is running; absent, like `gameState`, in the lobby.
    var game: String?
    /// That module's full authoritative state.
    var gameState: JSONValue?
}

extension HostSnapshot {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = try container.decode(GameState.self, forKey: .state)
        seats = try container.decodeIfPresent([String: Seat].self, forKey: .seats) ?? [:]
        successors = try container.decodeIfPresent([String].self, forKey: .successors) ?? []
        game = try container.decodeIfPresent(String.self, forKey: .game)
        gameState = try container.decodeIfPresent(JSONValue.self, forKey: .gameState)
    }
}

/// The host is leaving on purpose and names who continues.
struct HostLeaving: Codable, Equatable {
    let nextHostId: String
}

struct GameOver: Codable, Equatable {
    static let hostLeft = "host_left"

    let reason: String
    var winnerId: String?
    var stateVersion: Int?
}

/// Payload of both `PING` and `PONG`; the pong echoes the ping's `pingTs`.
struct Heartbeat: Codable, Equatable {
    let pingTs: Int64
    /// The host's current version, only in a `PING` sent by the host.
    var stateVersion: Int?
}

struct Ack: Codable, Equatable {
    let ackSeq: Int
}

extension Date {
    /// Milliseconds since 1970, the unit of `ts` and `pingTs`.
    var wireMillis: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}

/// Compact UTF-8 JSON, the format of every message and of GameInfo.
enum WireJSON {
    static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        // Sorted keys keep the byte count of a message identical across runs.
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(value)
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try JSONDecoder().decode(type, from: data)
    }
}
