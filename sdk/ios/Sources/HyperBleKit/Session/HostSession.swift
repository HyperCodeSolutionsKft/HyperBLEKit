import Foundation

/// Round-trip times between a host and its clients, measured from the pings the host sends
/// every 3 seconds; see `SessionState.pingStats`.
public struct PingStats: Equatable {
    /// How many round trips were measured, over all clients, since this device became the host.
    public private(set) var count = 0
    /// The sum of all measured round-trip times, in milliseconds.
    public private(set) var totalMs = 0
    /// The longest measured round-trip time, in milliseconds.
    public private(set) var worstMs = 0

    /// Creates a value, for previews and for an app's own tests.
    ///
    /// - Parameters:
    ///   - count: the number of round trips.
    ///   - totalMs: the sum of their times, in milliseconds.
    ///   - worstMs: the longest of them, in milliseconds.
    public init(count: Int = 0, totalMs: Int = 0, worstMs: Int = 0) {
        self.count = count
        self.totalMs = totalMs
        self.worstMs = worstMs
    }

    /// The average round-trip time in milliseconds; nil before the first measurement.
    public var averageMs: Int? { count == 0 ? nil : totalMs / count }

    mutating func record(_ ms: Int) {
        count += 1
        totalMs += ms
        worstMs = max(worstMs, ms)
    }
}

/// The host's side of the engine: seats, sequence numbers, `stateVersion`, heartbeat, the game
/// module that is running, and the snapshots that let another player take over as host.
/// Pure: it is told what happened and answers with the messages to send. See the wire specification
/// "Players and Seats", "`seq` rules", "`stateVersion` rules", "Game modules" and
/// "Reliability Rules".
struct HostSession {
    struct Recipient: Equatable {
        let link: LinkID
        /// Player ID for the debug log, "?" for a link without a seat.
        let label: String
    }

    /// One message and everyone who gets it. A broadcast has several recipients and one `seq`.
    struct Outgoing: Equatable {
        let message: Message
        let recipients: [Recipient]
    }

    struct Output: Equatable {
        var outgoing: [Outgoing] = []
        var notes: [String] = []
        /// Set when the handled message was a `PONG`.
        var roundTripMs: Int?
        /// Set when the host's own request was refused: a move the game rejected, or a game
        /// that could not be started. Nothing was sent.
        var rejection: GameRejection?
    }

    /// What one call of `advanceRealtime` did.
    struct RealtimeStep: Equatable {
        var output = Output()
        /// The world frame for every linked client, when one was due; the newest if several were.
        var clientFrame: Data?
        /// The world after the last tick, for the host's own screen; nil when no tick ran.
        var localFrame: Data?
        var ticks = 0
    }

    /// The game being played, or the finished one that is still on the table.
    private struct RunningGame {
        let module: GameModule
        var instance: GameInstance
    }

    /// The clock of a running real-time game. See the wire specification "Real-time games".
    private struct RealtimeClock {
        /// When the next tick is due; nil until the first one, and while paused.
        var nextTickAt: Date?
        /// No ticks and no frames: a player of the game is offline, or the resume delay runs.
        var isPaused = false
        /// Set once everyone is back: the world moves again at this time.
        var resumeAt: Date?
        /// When a seat last came back (rejoined, or spoke again after going silent): the resume
        /// delay counts from the event that brought the last player of the game back.
        var lastPlayerBackAt: Date?
        /// Ticks since the game started here; every `framePeriodTicks`th one sends a frame.
        var ticksRun = 0
        /// The host's newest own input since the last tick.
        var hostPending: Data?
        /// The host's inputs waiting out `hostInputDelayTicks`, oldest first.
        var hostDelayed = [Data?](repeating: nil, count: ProtocolConstants.hostInputDelayTicks)
    }

    /// Another player's seat.
    private struct Seat {
        var player: Player
        /// nil only for a seat taken over from a snapshot that did not name its device.
        let deviceId: String?
        /// nil while the player has no link to the host.
        var link: LinkID?
        var lastTrafficAt: Date
        /// Client `seq` values already handled on the current connection.
        var seenSeqs: Set<Int>
        /// Set while the seat is disconnected and was one of the snapshot holders when it
        /// dropped: that player may have taken the game over without us noticing.
        var lostAsSuccessorAt: Date?
        /// Set while the seat is disconnected: it is removed at this time unless it comes back.
        var removeAt: Date?
    }

    let gameId: String
    /// +1 every time another player takes over as host.
    let hostEpoch: Int
    /// 1 = just the host in the lobby.
    private(set) var stateVersion: Int
    private(set) var phase: String
    private(set) var pingStats = PingStats()
    /// Set on a takeover that could not continue the snapshot's game.
    let takeoverNote: String?
    /// Seats in this game, the host's included (`HyperBleKitConfig.maxPlayers`).
    let maxPlayers: Int
    /// nil in the lobby.
    private var game: RunningGame?
    /// This device's own player. The first host is `p1`; after a takeover it can be any seat.
    private let host: Player
    private let hostDeviceId: String
    /// Everyone else, ordered by player ID.
    private var seats: [Seat] = []
    /// One counter for everything the host sends.
    private var nextSeq = 1
    /// The max chunk length of every link that is up, header included: a real-time game needs
    /// its largest world frame to fit one packet on every player's link.
    private var linkChunkLengths: [LinkID: Int] = [:]
    private var realtimeClock = RealtimeClock()

    /// One on-demand ping-all in progress (see `pingAll(at:)` / `finishPingAll()`). `expected`
    /// are the clients pinged and `answers` fills with their round trips as PONGs arrive.
    /// `sendTs` is the ping's `pingTs`, so only the PONGs of this round are counted.
    private struct PingAllRound {
        let sendTs: Int64
        let expected: [String]
        var answers: [String: Int] = [:]
    }

    /// The ping-all round in progress (`pingAll(at:)`); nil when none is running.
    private var pingAllRound: PingAllRound?

    /// A new game: this device is `p1`, alone in the lobby.
    init(gameId: String, hostName: String, deviceId: String, maxPlayers: Int = ProtocolConstants.maxPlayers) {
        self.gameId = gameId
        self.maxPlayers = maxPlayers
        hostEpoch = ProtocolConstants.firstHostEpoch
        stateVersion = 1
        phase = GamePhase.lobby
        takeoverNote = nil
        hostDeviceId = deviceId
        host = Player(id: ProtocolConstants.firstHostPlayerId, name: Self.truncated(hostName),
                      platform: ProtocolConstants.platform, connected: true, handSize: nil, canHost: true)
    }

    /// Takes over a game from the snapshot its previous host sent: same game and seats, one epoch
    /// and one state version later. Everyone else starts disconnected and gets the seat back by
    /// rejoining with the same `deviceId`. The game continues from the snapshot's `gameState`;
    /// without a module that can read it, the takeover falls back to the lobby.
    /// Returns nil if `playerId` has no seat in the snapshot.
    /// - Parameter findModule: the game registry's lookup by `game`.
    init?(takingOver snapshot: HostSnapshot, as playerId: String, deviceId: String, at now: Date,
          findModule: (String) -> GameModule?, maxPlayers: Int = ProtocolConstants.maxPlayers) {
        guard var own = snapshot.state.players.first(where: { $0.id == playerId }) else { return nil }
        self.maxPlayers = maxPlayers
        own.connected = true
        own.canHost = true
        own.platform = ProtocolConstants.platform
        gameId = snapshot.state.gameId
        hostEpoch = snapshot.state.hostEpoch + 1
        stateVersion = snapshot.state.stateVersion + 1
        if snapshot.state.phase == GamePhase.lobby {
            phase = GamePhase.lobby
            takeoverNote = nil
        } else if let id = snapshot.game, let module = findModule(id), let gameState = snapshot.gameState,
                  let instance = module.restore(state: gameState) {
            phase = snapshot.state.phase
            game = RunningGame(module: module, instance: instance)
            takeoverNote = nil
        } else {
            phase = GamePhase.lobby
            takeoverNote = "cannot continue game \(snapshot.game ?? "?") from the snapshot, back to the lobby"
        }
        hostDeviceId = deviceId
        host = own
        seats = snapshot.state.players
            .filter { $0.id != playerId }
            .map { player in
                var player = player
                player.connected = false
                // The full reconnect window: they are only now looking for the new host.
                return Seat(player: player, deviceId: snapshot.seats[player.id]?.deviceId, link: nil,
                            lastTrafficAt: now, seenSeqs: [],
                            removeAt: now.addingTimeInterval(ProtocolConstants.reconnectWindow))
            }
        sortSeats()
    }

    var hostId: String { host.id }

    /// `game` of the module that is running, nil in the lobby.
    var gameModuleId: String? { game?.module.id }

    /// The host's own view of the game, nil in the lobby.
    var hostView: JSONValue? { game?.instance.view(for: hostId) }

    /// The real-time numbers of the game on the table; nil in the lobby and for a turn-based game.
    var realtimeSettings: RealtimeSpec? { (game?.module as? RealtimeGameModule)?.realtime }

    /// A real-time game is being played: the host advances it with `advanceRealtime`.
    var isRealtimeRunning: Bool { phase == GamePhase.playing && game?.instance is RealtimeGameInstance }

    /// The real-time game is held: a player of it is offline, or the resume delay runs.
    var isRealtimePaused: Bool { isRealtimeRunning && realtimeClock.isPaused }

    /// Everyone who would play if a game started now: connected, in ascending seat order.
    var connectedPlayerIds: [String] { players.filter(\.connected).map(\.id) }

    var players: [Player] {
        ([host] + seats.map(\.player)).sorted { Player.seatNumber(of: $0.id) < Player.seatNumber(of: $1.id) }
    }

    /// Who would take over, first in line first: connected players that can host and that the
    /// host can actually reach.
    var successors: [String] {
        Succession.order(players: seats.filter { $0.link != nil }.map(\.player), hostId: hostId)
    }

    /// True when there are players but no link to any of them.
    var isAlone: Bool { hasSeatedClients && linkedClients.isEmpty }

    /// A snapshot holder that dropped at least `interval` ago and has neither come back nor left.
    func successorGone(for interval: TimeInterval, at now: Date) -> String? {
        seats.first { seat in
            seat.lostAsSuccessorAt.map { now.timeIntervalSince($0) >= interval } ?? false
        }?.player.id
    }

    var hasSeatedClients: Bool { !seats.isEmpty }

    /// Every disconnected seat and the whole seconds left until it is removed, by player ID.
    func removalCountdowns(at now: Date) -> [String: Int] {
        var countdowns: [String: Int] = [:]
        for seat in seats {
            guard let removeAt = seat.removeAt else { continue }
            countdowns[seat.player.id] = max(0, Int(removeAt.timeIntervalSince(now).rounded(.up)))
        }
        return countdowns
    }

    /// Every joined client that has a link.
    var linkedClients: [Recipient] { recipients(of: seats) }

    var gameInfo: GameInfo {
        GameInfo(v: ProtocolConstants.version, gameId: gameId, hostName: host.name,
                 players: players.count, maxPlayers: maxPlayers,
                 lobbyOpen: phase == GamePhase.lobby, hostPlatform: host.platform,
                 hostId: hostId, hostEpoch: hostEpoch)
    }

    /// Why `module` cannot start because of a weak link: a real-time game needs every
    /// connected player's link to carry its largest world frame in one packet. nil when it can.
    func linkProblem(for module: GameModule) -> String? {
        guard let realtime = module as? RealtimeGameModule else { return nil }
        let weak = seats.first { seat in
            guard seat.player.connected else { return false }
            let chunkLength = seat.link.flatMap { linkChunkLengths[$0] } ?? 0
            return RealtimeFrame.maxPayloadLength(maxChunkLength: chunkLength) < realtime.realtime.maxWorldFrameBytes
        }
        return weak.map { "\(module.title) needs a better connection to \($0.player.id)" }
    }

    // MARK: - Input

    /// A client subscribed. Its max chunk length (header included) is kept while the link is up.
    mutating func linkUp(_ link: LinkID, maxChunkLength: Int) {
        linkChunkLengths[link] = maxChunkLength
    }

    /// `random` is what a game action may use, like the one `startGame` gets.
    mutating func receive<R: RandomNumberGenerator>(_ message: Message, from link: LinkID, at now: Date,
                                                    using random: inout R) -> Output {
        tracked(at: now) { session, output in
            session.handle(message, from: link, now: now, using: &random, output: &output)
        }
    }

    /// A debug test frame arrived. It counts as traffic from its sender and changes nothing else.
    /// Returns the sender's player ID, "?" for a link without a seat.
    mutating func testFrameReceived(from link: LinkID, at now: Date) -> (sender: String, output: Output) {
        guard let index = seats.firstIndex(where: { $0.link == link }) else {
            return (ProtocolConstants.unassignedPlayerId, Output())
        }
        let output = tracked(at: now) { session, output in
            session.noteTraffic(at: index, now: now, output: &output)
        }
        return (seats[index].player.id, output)
    }

    /// A real-time frame from a client. It counts as traffic from its sender, like a message. An
    /// input of the running real-time game from one of its players goes to the game, where the
    /// newest one counts at the next tick. Returns whether it was taken as such an input.
    mutating func frameReceived(_ payload: Data, from link: LinkID, at now: Date) -> (inputTaken: Bool, output: Output) {
        guard let index = seats.firstIndex(where: { $0.link == link }) else { return (false, Output()) }
        let output = tracked(at: now) { session, output in
            session.noteTraffic(at: index, now: now, output: &output)
        }
        let playerId = seats[index].player.id
        guard isRealtimeRunning, let running = game, let instance = running.instance as? RealtimeGameInstance,
              payload.first == realtimeSettings?.inputKind, instance.playerIds.contains(playerId) else {
            return (false, output)
        }
        game = RunningGame(module: running.module, instance: instance.input(playerId: playerId, payload: payload))
        return (true, output)
    }

    /// The host's own input for the running real-time game. It goes through the same module as a
    /// client's, `hostInputDelayTicks` ticks late, so the host has no edge.
    mutating func hostRealtimeInput(_ payload: Data) {
        guard isRealtimeRunning, let instance = game?.instance as? RealtimeGameInstance,
              instance.playerIds.contains(hostId), payload.first == realtimeSettings?.inputKind else { return }
        realtimeClock.hostPending = payload
    }

    /// Call often (several times a tick) while `isRealtimeRunning`: runs the ticks that are due.
    /// Late ticks run one after another; after a stall of more than `realtimeStallLimit` they are
    /// dropped. While a player of the game is offline nothing runs; it resumes
    /// `realtimeResumeDelay` after the last of them is back. A tick that changes the coarse state
    /// is one state change, with `GAME_STATE` to everyone and a snapshot for the successors.
    mutating func advanceRealtime(at now: Date) -> RealtimeStep {
        var frames: (client: Data?, local: Data?, ticks: Int) = (nil, nil, 0)
        let output = tracked(at: now) { session, output in
            frames = session.runDueTicks(now: now, output: &output)
        }
        return RealtimeStep(output: output, clientFrame: frames.client, localFrame: frames.local, ticks: frames.ticks)
    }

    /// The link is gone (the client unsubscribed or Bluetooth went away). The seat is kept.
    mutating func linkDown(_ link: LinkID, at now: Date) -> Output {
        linkChunkLengths[link] = nil
        return tracked(at: now) { session, output in
            guard let index = session.seats.firstIndex(where: { $0.link == link }) else { return }
            let wasHolder = session.snapshotHolders.contains(session.seats[index].player.id)
            session.seats[index].link = nil
            if session.seats[index].player.connected {
                session.markDisconnected(at: index, wasSnapshotHolder: wasHolder, now: now, output: &output)
            }
        }
    }

    /// Call every `ProtocolConstants.pingInterval`: times out silent clients, removes seats that
    /// stayed disconnected too long, then pings everyone.
    /// - Parameter judging: false when this device itself was stalled since the last call. The
    ///   silence was then ours, so nobody is timed out or removed and all clocks start over.
    mutating func heartbeat(at now: Date, judging: Bool = true) -> Output {
        tracked(at: now) { session, output in
            if !judging {
                let earliestRemoval = now.addingTimeInterval(ProtocolConstants.offlineRemovalAfter)
                for index in session.seats.indices {
                    if session.seats[index].link != nil { session.seats[index].lastTrafficAt = now }
                    if session.seats[index].lostAsSuccessorAt != nil { session.seats[index].lostAsSuccessorAt = now }
                    if let removeAt = session.seats[index].removeAt {
                        session.seats[index].removeAt = max(removeAt, earliestRemoval)
                    }
                }
            }
            for index in session.seats.indices where judging && session.seats[index].player.connected
                && now.timeIntervalSince(session.seats[index].lastTrafficAt) > ProtocolConstants.silenceTimeout {
                output.notes.append("\(session.seats[index].player.id) silent for "
                                    + "\(Int(ProtocolConstants.silenceTimeout)) s")
                let wasHolder = session.snapshotHolders.contains(session.seats[index].player.id)
                session.markDisconnected(at: index, wasSnapshotHolder: wasHolder, now: now, output: &output)
            }
            let expired = session.seats.filter { seat in judging && seat.removeAt.map { now >= $0 } == true }
            for seat in expired {
                output.notes.append("\(seat.player.id) did not come back in time, removing the seat")
                session.removeSeat(seat.player.id, now: now, output: &output)
            }
            let everyone = session.linkedClients
            if !everyone.isEmpty {
                let heartbeat = Heartbeat(pingTs: now.wireMillis, stateVersion: session.stateVersion)
                let ping = session.makeMessage(.ping(heartbeat), now: now)
                output.outgoing.append(Outgoing(message: ping, recipients: everyone))
            }
        }
    }

    /// Pings every linked client at once and starts collecting their round trips, for an
    /// on-demand liveness and latency check (`SessionDiagnostics.pingAll()`). The answers arrive
    /// as PONGs like any ping; read them with `finishPingAll()` after a short wait. A new call
    /// replaces any round still open.
    mutating func pingAll(at now: Date) -> Output {
        var output = Output()
        let everyone = linkedClients
        guard !everyone.isEmpty else {
            pingAllRound = nil
            output.notes.append("ping_all: no linked client to ping")
            return output
        }
        let expected = seats.filter { $0.link != nil }.map(\.player.id)
        pingAllRound = PingAllRound(sendTs: now.wireMillis, expected: expected)
        let ping = makeMessage(.ping(Heartbeat(pingTs: now.wireMillis, stateVersion: stateVersion)), now: now)
        output.outgoing.append(Outgoing(message: ping, recipients: everyone))
        output.notes.append("ping_all: pinged \(expected.count) client(s)")
        return output
    }

    /// Ends the ping-all started by `pingAll(at:)` and returns one reply per pinged client, in
    /// seat order; a client that did not answer has a nil time. Empty when no round was open.
    mutating func finishPingAll() -> [PingReply] {
        guard let round = pingAllRound else { return [] }
        pingAllRound = nil
        return round.expected
            .sorted { Player.seatNumber(of: $0) < Player.seatNumber(of: $1) }
            .map { PingReply(playerId: $0, rttMs: round.answers[$0]) }
    }

    /// Starts a game for everyone who is connected. Allowed in the lobby and after a finished
    /// game. `random` is the game's only source of randomness.
    mutating func startGame<R: RandomNumberGenerator>(_ module: GameModule, at now: Date,
                                                      using random: inout R) -> Output {
        tracked(at: now) { session, output in
            let playerIds = session.connectedPlayerIds
            guard session.phase != GamePhase.playing else {
                session.refuse(GameRejection(code: GameRejection.cannotStart, reason: "a game is already running"),
                               output: &output)
                return
            }
            guard module.supports(playerCount: playerIds.count) else {
                session.refuse(GameRejection(code: GameRejection.cannotStart, reason: "\(module.title) cannot be "
                                             + "played by \(playerIds.count) connected player(s)"), output: &output)
                return
            }
            if let problem = session.linkProblem(for: module) {
                session.refuse(GameRejection(code: GameRejection.cannotStart, reason: problem), output: &output)
                return
            }
            session.realtimeClock = RealtimeClock()
            session.gameChanged(to: RunningGame(module: module,
                                                instance: module.start(playerIds: playerIds, using: &random)),
                                now: now, output: &output)
        }
    }

    /// Ends the game, running or finished, and goes back to the lobby.
    mutating func endGame(at now: Date) -> Output {
        tracked(at: now) { session, output in
            guard session.phase != GamePhase.lobby else {
                output.notes.append("no game to end")
                return
            }
            session.gameChanged(to: nil, now: now, output: &output)
        }
    }

    /// The host removes a disconnected player at once, instead of waiting for the seat to time
    /// out. Refused for a connected player and for the host itself.
    mutating func removePlayer(_ playerId: String, at now: Date) -> Output {
        tracked(at: now) { session, output in
            let problem: String?
            if playerId == session.hostId {
                problem = "the host cannot be removed"
            } else if let seat = session.seats.first(where: { $0.player.id == playerId }) {
                problem = seat.player.connected ? "\(playerId) is connected" : nil
            } else {
                problem = "\(playerId) has no seat"
            }
            if let problem {
                session.refuse(GameRejection(code: GameRejection.cannotRemove, reason: problem), output: &output)
                return
            }
            output.notes.append("host removes \(playerId)")
            session.removeSeat(playerId, now: now, output: &output)
        }
    }

    /// The host's own move. It goes through the same game module as a client's, without BLE.
    mutating func hostAction<R: RandomNumberGenerator>(_ action: JSONValue, at now: Date,
                                                       using random: inout R) -> Output {
        tracked(at: now) { session, output in
            if let rejection = session.play(action, as: session.hostId, now: now, using: &random, output: &output) {
                session.refuse(rejection, output: &output)
            }
        }
    }

    /// The host is leaving on purpose. With a successor: a last snapshot for it, then
    /// `HOST_LEAVING` for everyone. Without one the game is over.
    mutating func leaving(at now: Date) -> Output {
        var output = Output()
        let everyone = linkedClients
        guard !everyone.isEmpty else { return output }
        if let next = successors.first, let seat = seats.first(where: { $0.player.id == next }), seat.link != nil {
            output.outgoing.append(Outgoing(message: makeMessage(.hostSnapshot(snapshot), now: now),
                                            recipients: recipients(of: [seat])))
            output.outgoing.append(Outgoing(message: makeMessage(.hostLeaving(HostLeaving(nextHostId: next)), now: now),
                                            recipients: everyone))
        } else {
            let gameOver = GameOver(reason: GameOver.hostLeft, winnerId: nil, stateVersion: nil)
            output.outgoing.append(Outgoing(message: makeMessage(.gameOver(gameOver), now: now),
                                            recipients: everyone))
        }
        return output
    }

    // MARK: - Rules

    /// Runs one input and, if it changed the state version or who the first successors are,
    /// adds a fresh snapshot for those successors.
    private mutating func tracked(at now: Date, _ body: (inout HostSession, inout Output) -> Void) -> Output {
        let versionBefore = stateVersion
        let holdersBefore = snapshotHolders
        var output = Output()
        body(&self, &output)
        if stateVersion != versionBefore || snapshotHolders != holdersBefore {
            let current = snapshot
            for seat in seats where snapshotHolders.contains(seat.player.id) && seat.link != nil {
                output.outgoing.append(Outgoing(message: makeMessage(.hostSnapshot(current), now: now),
                                                recipients: recipients(of: [seat])))
            }
        }
        return output
    }

    private var snapshotHolders: [String] { Array(successors.prefix(ProtocolConstants.snapshotHolders)) }

    private mutating func handle<R: RandomNumberGenerator>(_ message: Message, from link: LinkID, now: Date,
                                                           using random: inout R, output: inout Output) {
        if case .joinRequest(let request) = message.body {
            join(request, envelope: message, link: link, now: now, output: &output)
            return
        }
        guard let index = seats.firstIndex(where: { $0.link == link }) else {
            output.notes.append("ignored \(message.type): sender has not joined")
            return
        }
        guard message.v == ProtocolConstants.version else {
            output.notes.append("ignored \(message.type): protocol version \(message.v)")
            return
        }

        noteTraffic(at: index, now: now, output: &output)
        guard seats[index].seenSeqs.insert(message.seq).inserted else {
            output.notes.append("ignored duplicate \(message.type) seq=\(message.seq) from \(seats[index].player.id)")
            return
        }

        switch message.body {
        case .leave:
            leave(at: index, now: now, output: &output)
        case .ping(let heartbeat):
            output.outgoing.append(Outgoing(message: makeMessage(.pong(Heartbeat(pingTs: heartbeat.pingTs)), now: now),
                                            recipients: recipients(of: [seats[index]])))
        case .pong(let heartbeat):
            let roundTrip = Int(now.wireMillis - heartbeat.pingTs)
            if roundTrip >= 0 {
                pingStats.record(roundTrip)
                output.roundTripMs = roundTrip
                if var round = pingAllRound,
                   heartbeat.pingTs == round.sendTs, round.expected.contains(seats[index].player.id) {
                    round.answers[seats[index].player.id] = roundTrip
                    pingAllRound = round
                }
            }
        case .ack:
            // The host never resends: a client that missed something asks for a STATE_SYNC.
            // An ACK only shows up in the log.
            break
        case .gameAction(let data):
            gameAction(data, refSeq: message.seq, at: index, now: now, using: &random, output: &output)
        case .syncRequest:
            output.outgoing.append(stateSync(to: seats[index], now: now))
        case .unknown(let type):
            output.notes.append("ignored unknown message type \(type)")
        case .joinRequest, .joinAccept, .joinReject, .stateSync, .playerList, .gameState, .error,
             .hostSnapshot, .hostLeaving, .gameOver:
            output.notes.append("ignored \(message.type): not a client message")
        }
    }

    private mutating func noteTraffic(at index: Int, now: Date, output: inout Output) {
        seats[index].lastTrafficAt = now
        if !seats[index].player.connected {
            // It went silent but the link survived: any traffic brings it back with a fresh state.
            seats[index].player.connected = true
            seats[index].lostAsSuccessorAt = nil
            seats[index].removeAt = nil
            realtimeClock.lastPlayerBackAt = now
            stateVersion += 1
            output.outgoing.append(stateSync(to: seats[index], now: now))
            output.outgoing.append(playerList(PlayerList.reconnected, seats[index].player.id, now: now))
        }
    }

    private mutating func join(_ request: JoinRequest, envelope: Message, link: LinkID, now: Date,
                               output: inout Output) {
        guard envelope.v == ProtocolConstants.version else {
            reject(JoinReject.versionMismatch, link: link, now: now, output: &output)
            return
        }
        let name = Self.truncated(request.name)
        let playerId: String
        let event: String
        if let known = seats.firstIndex(where: { $0.deviceId == request.deviceId }) {
            // A known device always gets its old seat back.
            seats[known].player.name = name
            seats[known].player.platform = request.platform
            seats[known].player.canHost = request.canHost
            seats[known].player.connected = true
            realtimeClock.lastPlayerBackAt = now
            seats[known].lostAsSuccessorAt = nil
            seats[known].removeAt = nil
            seats[known].link = link
            seats[known].lastTrafficAt = now
            seats[known].seenSeqs = [envelope.seq]
            playerId = seats[known].player.id
            event = PlayerList.reconnected
        } else {
            guard phase == GamePhase.lobby else {
                reject(JoinReject.gameStarted, link: link, now: now, output: &output)
                return
            }
            guard let free = lowestFreeSeat() else {
                reject(JoinReject.lobbyFull, link: link, now: now, output: &output)
                return
            }
            let player = Player(id: free, name: name, platform: request.platform, connected: true,
                                handSize: nil, canHost: request.canHost)
            seats.append(Seat(player: player, deviceId: request.deviceId, link: link,
                              lastTrafficAt: now, seenSeqs: [envelope.seq]))
            sortSeats()
            playerId = free
            event = PlayerList.joined
        }
        stateVersion += 1
        guard let seat = seats.first(where: { $0.player.id == playerId }) else { return }
        let accept = JoinAccept(playerId: playerId, stateVersion: stateVersion)
        output.outgoing.append(Outgoing(message: makeMessage(.joinAccept(accept), now: now),
                                        recipients: recipients(of: [seat])))
        output.outgoing.append(stateSync(to: seat, now: now))
        output.outgoing.append(playerList(event, playerId, now: now))
    }

    private mutating func reject(_ code: String, link: LinkID, now: Date, output: inout Output) {
        let message = makeMessage(.joinReject(JoinReject(code: code)), now: now)
        let recipient = Recipient(link: link, label: ProtocolConstants.unassignedPlayerId)
        output.outgoing.append(Outgoing(message: message, recipients: [recipient]))
    }

    private mutating func leave(at index: Int, now: Date, output: inout Output) {
        let playerId = seats[index].player.id
        if phase == GamePhase.lobby {
            seats.remove(at: index)
        } else {
            // During a game the seat is kept for now, like a player who went silent.
            seats[index].player.connected = false
            seats[index].lostAsSuccessorAt = nil
            seats[index].removeAt = now.addingTimeInterval(ProtocolConstants.offlineRemovalAfter)
            seats[index].link = nil
        }
        stateVersion += 1
        output.outgoing.append(playerList(PlayerList.left, playerId, now: now))
    }

    private mutating func markDisconnected(at index: Int, wasSnapshotHolder: Bool, now: Date,
                                           output: inout Output) {
        seats[index].player.connected = false
        if wasSnapshotHolder { seats[index].lostAsSuccessorAt = now }
        seats[index].removeAt = now.addingTimeInterval(ProtocolConstants.offlineRemovalAfter)
        stateVersion += 1
        output.outgoing.append(playerList(PlayerList.disconnected, seats[index].player.id, now: now))
    }

    /// Removes a seat for good: `JOIN_REJECT removed` first if it still has a link, then the
    /// new player list for everyone. A player of the running game is then taken out of it: a
    /// second state change, with the game the module goes on with, or back to the lobby if it
    /// cannot continue.
    private mutating func removeSeat(_ playerId: String, now: Date, output: inout Output) {
        guard let index = seats.firstIndex(where: { $0.player.id == playerId }) else { return }
        if let link = seats[index].link {
            let reject = makeMessage(.joinReject(JoinReject(code: JoinReject.removed)), now: now)
            output.outgoing.append(Outgoing(message: reject, recipients: [Recipient(link: link, label: playerId)]))
        }
        seats.remove(at: index)
        stateVersion += 1
        output.outgoing.append(playerList(PlayerList.removed, playerId, now: now))
        guard phase == GamePhase.playing, let running = game else { return }
        guard let instance = running.instance.removePlayer(playerId) else {
            output.notes.append("\(running.module.title) cannot go on without \(playerId): game ended, back to the lobby")
            gameChanged(to: nil, now: now, output: &output)
            return
        }
        // A spectator: the game does not change.
        guard instance.state() != running.instance.state() else { return }
        gameChanged(to: RunningGame(module: running.module, instance: instance), now: now, output: &output)
    }

    // MARK: - Game

    /// See `advanceRealtime`. Returns the frame for the clients, the newest world, and the ticks run.
    private mutating func runDueTicks(now: Date, output: inout Output) -> (Data?, Data?, Int) {
        guard isRealtimeRunning, let running = game, let settings = realtimeSettings,
              var instance = running.instance as? RealtimeGameInstance else { return (nil, nil, 0) }
        let offline = instance.playerIds.filter { id in !(players.first { $0.id == id }?.connected ?? false) }
        if !offline.isEmpty {
            if !realtimeClock.isPaused { output.notes.append("paused: \(offline.joined(separator: ", ")) offline") }
            realtimeClock.isPaused = true
            realtimeClock.resumeAt = nil
            realtimeClock.nextTickAt = nil
            return (nil, nil, 0)
        }
        if realtimeClock.isPaused {
            let resumeAt: Date
            if let scheduled = realtimeClock.resumeAt {
                resumeAt = scheduled
            } else {
                let backAt = min(realtimeClock.lastPlayerBackAt ?? now, now)
                resumeAt = backAt.addingTimeInterval(ProtocolConstants.realtimeResumeDelay)
                realtimeClock.resumeAt = resumeAt
                output.notes.append("everyone is back: resuming in \(Int(ProtocolConstants.realtimeResumeDelay)) s")
            }
            guard now >= resumeAt else { return (nil, nil, 0) }
            realtimeClock.isPaused = false
            realtimeClock.resumeAt = nil
            output.notes.append("resumed")
        }
        let tickLength = TimeInterval(settings.tickMillis) / 1000
        var next = realtimeClock.nextTickAt ?? now
        let late = now.timeIntervalSince(next)
        // A clock that jumped back is treated like a stall too, or the world would stand still.
        if abs(late) > ProtocolConstants.realtimeStallLimit {
            if late > 0 {
                output.notes.append(String(format: "real-time clock stalled for %.1f s: dropping %d ticks",
                                           late, Int(late / tickLength)))
            }
            next = now
        }
        var clientFrame: Data?
        var ticks = 0
        while next <= now && isRealtimeRunning {
            realtimeClock.hostDelayed.append(realtimeClock.hostPending)
            realtimeClock.hostPending = nil
            if let due = realtimeClock.hostDelayed.removeFirst() {
                instance = instance.input(playerId: hostId, payload: due)
            }
            let (ticked, coarseChanged) = instance.tick()
            instance = ticked
            ticks += 1
            realtimeClock.ticksRun += 1
            next.addTimeInterval(tickLength)
            if coarseChanged {
                gameChanged(to: RunningGame(module: running.module, instance: instance), now: now, output: &output)
            } else {
                game = RunningGame(module: running.module, instance: instance)
            }
            // A tick that changes the coarse state also goes out, so the clients see the deciding
            // tick even when the game finishes on it and ticking stops.
            if realtimeClock.ticksRun % settings.framePeriodTicks == 0 || coarseChanged {
                clientFrame = instance.worldFrame()
            }
        }
        realtimeClock.nextTickAt = next
        return (clientFrame, ticks > 0 ? instance.worldFrame() : nil, ticks)
    }

    /// A client's move: applied, or answered with `ERROR` and nothing changes.
    private mutating func gameAction<R: RandomNumberGenerator>(_ data: GameAction, refSeq: Int, at index: Int,
                                                               now: Date, using random: inout R,
                                                               output: inout Output) {
        let rejection: GameRejection?
        if let running = game, phase == GamePhase.playing, running.module.id != data.game {
            rejection = GameRejection(code: ActionError.wrongGame, reason: "the running game is \(running.module.id)")
        } else {
            rejection = play(data.action, as: seats[index].player.id, now: now, using: &random, output: &output)
        }
        guard let rejection else { return }
        let error = ActionError(refSeq: refSeq, code: rejection.code, reason: rejection.reason)
        output.outgoing.append(Outgoing(message: makeMessage(.error(error), now: now),
                                        recipients: recipients(of: [seats[index]])))
    }

    /// Hands one action to the game module. Accepted: one state change, and everyone gets its
    /// new view. Rejected: nothing changes and the reason is returned.
    private mutating func play<R: RandomNumberGenerator>(_ action: JSONValue, as playerId: String, now: Date,
                                                         using random: inout R,
                                                         output: inout Output) -> GameRejection? {
        guard let running = game, phase == GamePhase.playing else {
            return GameRejection(code: ActionError.noGame, reason: "no game is running")
        }
        switch running.instance.apply(playerId: playerId, action: action, using: &random) {
        case .rejected(let rejection):
            return rejection
        case .accepted(let instance):
            gameChanged(to: RunningGame(module: running.module, instance: instance), now: now, output: &output)
            return nil
        }
    }

    /// One state change of the game: every linked client gets its own `GAME_STATE`, each with
    /// its own `seq` and the same `stateVersion`. nil ends the game and returns to the lobby.
    private mutating func gameChanged(to running: RunningGame?, now: Date, output: inout Output) {
        game = running
        if let running {
            phase = running.instance.finished ? GamePhase.finished : GamePhase.playing
        } else {
            phase = GamePhase.lobby
            realtimeClock = RealtimeClock()
        }
        stateVersion += 1
        for seat in seats where seat.link != nil {
            let update = GameUpdate(stateVersion: stateVersion, phase: phase, game: running?.module.id,
                                    view: running?.instance.view(for: seat.player.id))
            output.outgoing.append(Outgoing(message: makeMessage(.gameState(update), now: now),
                                            recipients: recipients(of: [seat])))
        }
    }

    private func refuse(_ rejection: GameRejection, output: inout Output) {
        output.rejection = rejection
        output.notes.append("refused: \(rejection.text)")
    }

    /// Lowest seat of `p2`…`p10` nobody holds. `p1` is never handed out again, and the host's
    /// own seat is never free.
    private func lowestFreeSeat() -> String? {
        (2...maxPlayers)
            .map { "p\($0)" }
            .first { id in id != hostId && !seats.contains { $0.player.id == id } }
    }

    private mutating func sortSeats() {
        seats.sort { Player.seatNumber(of: $0.player.id) < Player.seatNumber(of: $1.player.id) }
    }

    private static func truncated(_ name: String) -> String {
        String(name.prefix(ProtocolConstants.maxNameLength))
    }

    // MARK: - Messages

    private var state: GameState {
        GameState(gameId: gameId, stateVersion: stateVersion, phase: phase, players: players,
                  hostId: hostId, hostEpoch: hostEpoch)
    }

    /// Carries the game module's full state while a game exists.
    private var snapshot: HostSnapshot {
        var seatDevices = [hostId: HostSnapshot.Seat(deviceId: hostDeviceId)]
        for seat in seats {
            if let deviceId = seat.deviceId { seatDevices[seat.player.id] = HostSnapshot.Seat(deviceId: deviceId) }
        }
        return HostSnapshot(state: state, seats: seatDevices, successors: snapshotHolders,
                            game: game?.module.id, gameState: game?.instance.state())
    }

    private mutating func makeMessage(_ body: Message.Body, now: Date) -> Message {
        defer { nextSeq += 1 }
        return Message(seq: nextSeq, from: hostId, ts: now.wireMillis, body: body)
    }

    /// Broadcast to every client that has a link.
    private mutating func playerList(_ event: String, _ playerId: String, now: Date) -> Outgoing {
        let data = PlayerList(event: event, playerId: playerId, stateVersion: stateVersion, players: players)
        return Outgoing(message: makeMessage(.playerList(data), now: now), recipients: linkedClients)
    }

    /// Always targeted: `view` is the recipient's own, absent in the lobby.
    private mutating func stateSync(to seat: Seat, now: Date) -> Outgoing {
        let sync = StateSync(state: state, game: game?.module.id, view: game?.instance.view(for: seat.player.id))
        return Outgoing(message: makeMessage(.stateSync(sync), now: now), recipients: recipients(of: [seat]))
    }

    private func recipients(of seats: [Seat]) -> [Recipient] {
        seats.compactMap { seat in seat.link.map { Recipient(link: $0, label: seat.player.id) } }
    }
}
