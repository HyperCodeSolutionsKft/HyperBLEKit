import Foundation

/// The client's view of the game across its connections to a host.
/// Pure: it is told what arrived and answers with the messages to send. The host is
/// authoritative, so state only changes when a host message says so.
struct ClientSession {
    /// Something the host announced that ends this connection.
    enum Event: Equatable {
        /// The host is leaving on purpose; `nextHostId` continues the game.
        case hostLeaving(nextHostId: String)
        case gameOver(reason: String)
    }

    struct Output: Equatable {
        var outgoing: [Message] = []
        var notes: [String] = []
        var event: Event?
        /// Set when the host rejected our game action with `ERROR`.
        var rejection: GameRejection?
    }

    let deviceId: String
    let name: String
    /// Our seat, assigned by the host in `JOIN_ACCEPT`. Kept while reconnecting: seats never change.
    private(set) var playerId: String?
    /// The host has accepted us on the current connection.
    private(set) var isAccepted = false
    /// 0 until the first `STATE_SYNC` on the current connection.
    private(set) var stateVersion = 0
    /// Last applied player list. Kept while reconnecting.
    private(set) var players: [Player]
    private(set) var hostId = ProtocolConstants.firstHostPlayerId
    /// Highest `hostEpoch` applied so far, 0 before the first `STATE_SYNC`. A host with a lower
    /// epoch is a stale one and must not be rejoined.
    private(set) var hostEpoch: Int
    private(set) var phase = GamePhase.lobby
    /// `game` of the module that is running, nil in the lobby.
    private(set) var gameModuleId: String?
    /// Our own view of the game, in the module's format. Only the game's screen reads it.
    private(set) var view: JSONValue?
    /// The newest `HOST_SNAPSHOT`, held only if the host chose us as a successor. Never shown.
    private(set) var snapshot: HostSnapshot?
    /// Set when the host answered with `JOIN_REJECT`.
    private(set) var rejectCode: String?
    private var lastHostTrafficAt = Date.distantPast
    /// Starts at 1 on every (re)connection.
    private var nextSeq = 1

    /// - Parameters: `playerId`, `players` and `hostEpoch` are what a host that steps down
    ///   already knows about the game it is about to rejoin as a client.
    init(deviceId: String, name: String, playerId: String? = nil, players: [Player] = [], hostEpoch: Int = 0) {
        self.deviceId = deviceId
        self.name = String(name.prefix(ProtocolConstants.maxNameLength))
        self.playerId = playerId
        self.players = players
        self.hostEpoch = hostEpoch
    }

    /// Our rank for taking over, 1 = first. nil without a snapshot: only a holder can take over.
    /// The rank is our place in the snapshot's `successors`; a snapshot that does not name us
    /// falls back to our place among the players that can host.
    var takeoverRank: Int? {
        guard let snapshot, let playerId else { return nil }
        if let index = snapshot.successors.firstIndex(of: playerId) { return index + 1 }
        return Succession.fallbackRank(of: playerId, players: players, hostId: hostId)
    }

    /// Call once the transport is ready (subscribed); starts a fresh connection.
    mutating func joinRequest(at now: Date) -> Message {
        nextSeq = 1
        isAccepted = false
        // 0 makes sure the STATE_SYNC after the join is applied, whatever version the host is at.
        stateVersion = 0
        // The game comes back with that STATE_SYNC too.
        phase = GamePhase.lobby
        gameModuleId = nil
        view = nil
        rejectCode = nil
        lastHostTrafficAt = now
        let request = JoinRequest(name: name, platform: ProtocolConstants.platform, deviceId: deviceId, canHost: true)
        return makeMessage(.joinRequest(request), now: now)
    }

    mutating func leave(at now: Date) -> Message {
        makeMessage(.leave, now: now)
    }

    func isHostSilent(at now: Date) -> Bool {
        now.timeIntervalSince(lastHostTrafficAt) > ProtocolConstants.silenceTimeout
    }

    /// A debug test frame arrived: it counts as traffic from the host and changes nothing else.
    mutating func testFrameReceived(at now: Date) {
        lastHostTrafficAt = now
    }

    /// Our move in the running game, for the host to judge. nil when no game is running.
    mutating func gameAction(_ action: JSONValue, at now: Date) -> Message? {
        guard phase == GamePhase.playing, let gameModuleId else { return nil }
        return makeMessage(.gameAction(GameAction(game: gameModuleId, action: action)), now: now)
    }

    /// The state to continue the game with as its new host, nil if we hold no snapshot.
    /// - Parameter findModule: the game registry's lookup by `game`.
    func takeOver(at now: Date, findModule: (String) -> GameModule?,
                  maxPlayers: Int = ProtocolConstants.maxPlayers) -> HostSession? {
        guard let snapshot, let playerId else { return nil }
        return HostSession(takingOver: snapshot, as: playerId, deviceId: deviceId, at: now, findModule: findModule,
                           maxPlayers: maxPlayers)
    }

    mutating func receive(_ message: Message, at now: Date) -> Output {
        var output = Output()
        lastHostTrafficAt = now
        if case .joinReject(let reject) = message.body {
            // Handled whatever its `v`: this is how a version mismatch is reported.
            rejectCode = reject.code
            return output
        }
        guard message.v == ProtocolConstants.version else {
            output.notes.append("ignored \(message.type): protocol version \(message.v)")
            return output
        }

        switch message.body {
        case .joinAccept(let accept):
            // The version is taken from the STATE_SYNC that always follows, together with the state.
            playerId = accept.playerId
            isAccepted = true
        case .stateSync(let sync):
            // A full snapshot replaces whatever we have, unless it is older.
            if sync.state.stateVersion >= stateVersion {
                players = sync.state.players
                stateVersion = sync.state.stateVersion
                hostId = sync.state.hostId
                hostEpoch = max(hostEpoch, sync.state.hostEpoch)
                phase = sync.state.phase
                gameModuleId = sync.game
                view = sync.view
                if let held = snapshot, held.state.hostEpoch < sync.state.hostEpoch {
                    // It describes the game under a previous host.
                    snapshot = nil
                }
            } else {
                output.notes.append("STATE_SYNC sv=\(sync.state.stateVersion) is older than sv=\(stateVersion), not applied")
            }
        case .playerList(let list):
            if isNext(list.stateVersion, of: message, now: now, output: &output) {
                players = list.players
                stateVersion = list.stateVersion
            }
        case .gameState(let update):
            if isNext(update.stateVersion, of: message, now: now, output: &output) {
                phase = update.phase
                gameModuleId = update.game
                view = update.view
                stateVersion = update.stateVersion
            }
        case .error(let error):
            output.rejection = GameRejection(code: error.code, reason: error.reason)
        case .hostSnapshot(let new):
            if isNewer(new) {
                snapshot = new
            } else {
                output.notes.append("HOST_SNAPSHOT epoch=\(new.state.hostEpoch) sv=\(new.state.stateVersion) is not newer, not kept")
            }
        case .hostLeaving(let leaving):
            output.event = .hostLeaving(nextHostId: leaving.nextHostId)
        case .gameOver(let gameOver):
            output.event = .gameOver(reason: gameOver.reason)
        case .ping(let heartbeat):
            output.outgoing.append(makeMessage(.pong(Heartbeat(pingTs: heartbeat.pingTs)), now: now))
            if let hostVersion = heartbeat.stateVersion, isAccepted, hostVersion > stateVersion {
                output.notes.append("PING sv=\(hostVersion) is ahead of sv=\(stateVersion), asking for STATE_SYNC")
                output.outgoing.append(syncRequest(now: now))
            }
        case .pong:
            break
        case .unknown(let type):
            output.notes.append("ignored unknown message type \(type)")
        case .joinRequest, .joinReject, .leave, .ack, .gameAction, .syncRequest:
            output.notes.append("ignored \(message.type): not a host message")
        }
        if message.needsAck {
            output.outgoing.append(makeMessage(.ack(Ack(ackSeq: message.seq)), now: now))
        }
        return output
    }

    /// Whether a state change with this version is the one to apply now. An older or repeated
    /// one is already reflected in our state. One that skips ahead means we missed something:
    /// it is not applied and the host is asked for a `STATE_SYNC`. Before the first `STATE_SYNC`
    /// of the connection everything skips ahead: that `STATE_SYNC` was lost.
    private mutating func isNext(_ version: Int, of message: Message, now: Date, output: inout Output) -> Bool {
        if version <= stateVersion {
            output.notes.append("\(message.type) sv=\(version) already applied")
            return false
        }
        if stateVersion == 0 || version > stateVersion + 1 {
            output.notes.append("\(message.type) sv=\(version) skips ahead of sv=\(stateVersion), asking for STATE_SYNC")
            // The host only answers a player it has seated.
            if isAccepted { output.outgoing.append(syncRequest(now: now)) }
            return false
        }
        return true
    }

    private mutating func syncRequest(now: Date) -> Message {
        makeMessage(.syncRequest(SyncRequest(haveVersion: stateVersion)), now: now)
    }

    /// Newer: a higher `hostEpoch`, or the same epoch and a `stateVersion` at least as high.
    private func isNewer(_ new: HostSnapshot) -> Bool {
        guard let held = snapshot else { return true }
        if new.state.hostEpoch != held.state.hostEpoch { return new.state.hostEpoch > held.state.hostEpoch }
        return new.state.stateVersion >= held.state.stateVersion
    }

    private mutating func makeMessage(_ body: Message.Body, now: Date) -> Message {
        defer { nextSeq += 1 }
        return Message(seq: nextSeq, from: isAccepted ? playerId ?? ProtocolConstants.unassignedPlayerId
                                                      : ProtocolConstants.unassignedPlayerId,
                       ts: now.wireMillis, body: body)
    }
}
