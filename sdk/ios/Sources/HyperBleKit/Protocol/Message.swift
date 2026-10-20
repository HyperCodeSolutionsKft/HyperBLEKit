import Foundation

/// One protocol message: the envelope `{v,type,seq,from,ts,data}` with a typed payload.
/// See the wire specification "Envelope".
struct Message: Equatable {
    var v = ProtocolConstants.version
    var seq: Int
    var from: String
    /// Sender time in ms. For latency measurement only.
    var ts: Int64
    var body: Body

    enum Body: Equatable {
        case joinRequest(JoinRequest)
        case joinAccept(JoinAccept)
        case joinReject(JoinReject)
        case stateSync(StateSync)
        case playerList(PlayerList)
        case gameState(GameUpdate)
        case gameAction(GameAction)
        case error(ActionError)
        case syncRequest(SyncRequest)
        case leave
        case ping(Heartbeat)
        case pong(Heartbeat)
        case ack(Ack)
        case hostSnapshot(HostSnapshot)
        case hostLeaving(HostLeaving)
        case gameOver(GameOver)
        /// Host → all joined clients: asks each to upload its debug log (diagnostics). No payload.
        case logRequest
        /// Client → host: one batch of this device's debug log (diagnostics).
        case logUpload(LogUpload)
        /// A type this build doesn't know. Logged and ignored.
        case unknown(type: String)
    }

    var type: String {
        switch body {
        case .joinRequest: return "JOIN_REQUEST"
        case .joinAccept: return "JOIN_ACCEPT"
        case .joinReject: return "JOIN_REJECT"
        case .stateSync: return "STATE_SYNC"
        case .playerList: return "PLAYER_LIST"
        case .gameState: return "GAME_STATE"
        case .gameAction: return "GAME_ACTION"
        case .error: return "ERROR"
        case .syncRequest: return "SYNC_REQUEST"
        case .leave: return "LEAVE"
        case .ping: return "PING"
        case .pong: return "PONG"
        case .ack: return "ACK"
        case .hostSnapshot: return "HOST_SNAPSHOT"
        case .hostLeaving: return "HOST_LEAVING"
        case .gameOver: return "GAME_OVER"
        case .logRequest: return "LOG_REQUEST"
        case .logUpload: return "LOG_UPLOAD"
        case .unknown(let type): return type
        }
    }

    /// Only state-changing messages carry one. The one in a host `PING` is for comparison only.
    var stateVersion: Int? {
        switch body {
        case .joinAccept(let data): return data.stateVersion
        case .stateSync(let data): return data.state.stateVersion
        case .playerList(let data): return data.stateVersion
        case .gameState(let data): return data.stateVersion
        case .hostSnapshot(let data): return data.state.stateVersion
        case .gameOver(let data): return data.stateVersion
        default: return nil
        }
    }

    /// Whether the receiving client must confirm this message with `ACK`.
    var needsAck: Bool {
        switch body {
        case .joinAccept, .gameState, .stateSync, .hostSnapshot, .gameOver: return true
        default: return false
        }
    }

    /// Payload details worth a place in the debug log line.
    var logDetail: String? {
        switch body {
        case .joinRequest(let data): return "name=\(data.name)"
        case .joinAccept(let data): return "player=\(data.playerId)"
        case .joinReject(let data): return "code=\(data.code)"
        case .playerList(let data): return "event=\(data.event) player=\(data.playerId)"
        case .gameState(let data): return "phase=\(data.phase)" + (data.game.map { " game=\($0)" } ?? "")
        case .gameAction(let data): return "game=\(data.game) \(Self.summary(of: data.action))"
        case .error(let data): return "code=\(data.code) refSeq=\(data.refSeq)"
        case .syncRequest(let data): return "have=\(data.haveVersion)"
        case .ack(let data): return "ackSeq=\(data.ackSeq)"
        case .stateSync(let data): return "host=\(data.state.hostId) epoch=\(data.state.hostEpoch)"
        case .hostSnapshot(let data): return "epoch=\(data.state.hostEpoch) seats=\(data.seats.count) "
            + "successors=\(data.successors.joined(separator: ","))"
        case .hostLeaving(let data): return "next=\(data.nextHostId)"
        case .gameOver(let data): return "reason=\(data.reason)"
        case .logUpload(let data): return "part=\(data.part + 1)/\(data.parts) lines=\(data.lines.count)"
        default: return nil
        }
    }

    /// A game action for the log, whatever the game: its `type`, then its other text members
    /// in key order. `{"type":"play","card":"MA"}` reads "play MA".
    private static func summary(of action: JSONValue) -> String {
        guard case .object(let members) = action else { return "?" }
        let type = members["type"]?.stringValue ?? "?"
        let rest = members.keys.sorted().filter { $0 != "type" }.compactMap { members[$0]?.stringValue }
        return ([type] + rest).joined(separator: " ")
    }

    func encoded() throws -> Data { try WireJSON.encode(self) }

    static func decode(_ data: Data) throws -> Message { try WireJSON.decode(Message.self, from: data) }
}

extension Message: Codable {
    private enum CodingKeys: String, CodingKey {
        case v, type, seq, from, ts, data
    }

    /// `data` of messages without a payload: `{}`.
    private struct NoData: Codable {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        v = try container.decode(Int.self, forKey: .v)
        seq = try container.decode(Int.self, forKey: .seq)
        from = try container.decode(String.self, forKey: .from)
        ts = try container.decode(Int64.self, forKey: .ts)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "JOIN_REQUEST": body = .joinRequest(try container.decode(JoinRequest.self, forKey: .data))
        case "JOIN_ACCEPT": body = .joinAccept(try container.decode(JoinAccept.self, forKey: .data))
        case "JOIN_REJECT": body = .joinReject(try container.decode(JoinReject.self, forKey: .data))
        case "STATE_SYNC": body = .stateSync(try container.decode(StateSync.self, forKey: .data))
        case "PLAYER_LIST": body = .playerList(try container.decode(PlayerList.self, forKey: .data))
        case "GAME_STATE": body = .gameState(try container.decode(GameUpdate.self, forKey: .data))
        case "GAME_ACTION": body = .gameAction(try container.decode(GameAction.self, forKey: .data))
        case "ERROR": body = .error(try container.decode(ActionError.self, forKey: .data))
        case "SYNC_REQUEST": body = .syncRequest(try container.decode(SyncRequest.self, forKey: .data))
        case "LEAVE": body = .leave
        case "PING": body = .ping(try container.decode(Heartbeat.self, forKey: .data))
        case "PONG": body = .pong(try container.decode(Heartbeat.self, forKey: .data))
        case "ACK": body = .ack(try container.decode(Ack.self, forKey: .data))
        case "HOST_SNAPSHOT": body = .hostSnapshot(try container.decode(HostSnapshot.self, forKey: .data))
        case "HOST_LEAVING": body = .hostLeaving(try container.decode(HostLeaving.self, forKey: .data))
        case "GAME_OVER": body = .gameOver(try container.decode(GameOver.self, forKey: .data))
        case "LOG_REQUEST": body = .logRequest
        case "LOG_UPLOAD": body = .logUpload(try container.decode(LogUpload.self, forKey: .data))
        default: body = .unknown(type: type)
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(v, forKey: .v)
        try container.encode(type, forKey: .type)
        try container.encode(seq, forKey: .seq)
        try container.encode(from, forKey: .from)
        try container.encode(ts, forKey: .ts)
        switch body {
        case .joinRequest(let data): try container.encode(data, forKey: .data)
        case .joinAccept(let data): try container.encode(data, forKey: .data)
        case .joinReject(let data): try container.encode(data, forKey: .data)
        case .stateSync(let data): try container.encode(data, forKey: .data)
        case .playerList(let data): try container.encode(data, forKey: .data)
        case .gameState(let data): try container.encode(data, forKey: .data)
        case .gameAction(let data): try container.encode(data, forKey: .data)
        case .error(let data): try container.encode(data, forKey: .data)
        case .syncRequest(let data): try container.encode(data, forKey: .data)
        case .ping(let data), .pong(let data): try container.encode(data, forKey: .data)
        case .ack(let data): try container.encode(data, forKey: .data)
        case .hostSnapshot(let data): try container.encode(data, forKey: .data)
        case .hostLeaving(let data): try container.encode(data, forKey: .data)
        case .gameOver(let data): try container.encode(data, forKey: .data)
        case .logUpload(let data): try container.encode(data, forKey: .data)
        case .leave, .logRequest, .unknown: try container.encode(NoData(), forKey: .data)
        }
    }
}
