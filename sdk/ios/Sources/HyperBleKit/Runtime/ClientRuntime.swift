import Combine
import Foundation

/// The client role of a game: connects the transport to `ClientSession`, watches for a silent
/// host, gets back in when the host is lost, takes the game over when it is this device's turn
/// to, and writes everything to the debug log.
final class ClientRuntime: ObservableObject {
    enum Status: Equatable {
        case connecting
        /// Subscribed, `JOIN_REQUEST` sent.
        case joining
        case joined
        case rejected(code: String)
        /// The host was lost; trying to get back in until the reconnect window ends.
        case reconnecting(secondsLeft: Int)
        /// The reconnect window ended without getting back in.
        case hostLeft
        case disconnected(reason: String?)
    }

    /// One loss of the host and the attempts to get back in.
    private struct Reconnect {
        let lostAt: Date
        /// Our rank for taking over when the host was lost; it does not change while we look
        /// for a host. nil if we hold no snapshot and so cannot take over.
        let takeoverRank: Int?
        var attempt = 0

        func secondsLeft(at now: Date) -> Int {
            max(0, Int((ProtocolConstants.reconnectWindow - now.timeIntervalSince(lostAt)).rounded(.up)))
        }
    }

    let gameCode: String
    let log: HyperBleLog
    /// The games this app can play (`HyperBleKitConfig.games`).
    let games: [GameModule]
    /// Seats in a game this device hosts after a takeover (`HyperBleKitConfig.maxPlayers`).
    private let maxPlayers: Int
    @Published private(set) var status = Status.connecting
    @Published private(set) var players: [Player] = []
    @Published private(set) var playerId: String?
    /// What GameInfo said that the user should know before the host answers.
    @Published private(set) var notice: String?
    /// Outcome of the last debug test frame sent or received.
    @Published private(set) var testFrameText: String?
    /// The last received debug test frame, pretty-printed. Sent frames are not shown.
    @Published private(set) var receivedTestFrame: String?
    /// How long the last reconnect took, from losing the host to having its state again.
    @Published private(set) var lastReconnectText: String?
    @Published private(set) var hostId: String
    /// 0 until the host's state has arrived.
    @Published private(set) var hostEpoch: Int
    /// `lobby`, `playing` or `finished`.
    @Published private(set) var phase = GamePhase.lobby
    /// `game` of the module that is running, nil in the lobby.
    @Published private(set) var gameModuleId: String?
    /// This player's own view of the game, for the game's screen.
    @Published private(set) var gameView: JSONValue?
    /// Why the host rejected the last own move; cleared when the game moves on.
    @Published private(set) var gameNotice: String?
    /// Whether this device holds a host snapshot (its content is never shown).
    @Published private(set) var holdsSnapshot = false
    /// This device's rank for taking over, 1 = first; nil without a snapshot.
    @Published private(set) var takeoverRank: Int?
    /// The speed-test view is open: the host is sending world frames.
    @Published private(set) var isSpeedTestOpen = false
    /// What the speed-test view shows.
    let speedTest = ClientSpeedTestState()
    /// The moving world and this player's controls, while a real-time game is on the table.
    @Published private(set) var realtimeLink: RealtimeFeed?
    /// Called when this device takes the game over: the client role has stopped and the game
    /// continues with the given host state.
    var onTakeover: ((HostSession) -> Void)?

    /// Where the scanner found the host; nil when rejoining a known game by its code.
    private let hostPeripheralId: UUID?
    private let transport: GameClientTransport
    private var session: ClientSession
    /// Every second: host-silence check, reconnect countdown and the end of the reconnect window.
    private var tickTimer: Timer?
    /// The pause before the next reconnect attempt.
    private var retryTimer: Timer?
    /// Set while the host is lost and we are trying to get back in.
    private var reconnect: Reconnect?
    /// A connection (the first one or a reconnect attempt) is under way or established. Only then
    /// is a disconnect a new loss; otherwise it is the echo of one we already handled.
    private var isAttemptRunning = false
    /// A host was found and is being set up or joined. Scanning alone does not count.
    private var hasLink = false
    /// Subscribed on the current link: host messages count and host silence is watched.
    private var isLinkReady = false
    private var hasStarted = false
    private var hasLeft = false
    /// The speed test's counters while its view is open.
    private var speedTestRun: SpeedTestClient?
    private var lastWorldFrameAt: Date?
    /// Sends an input every 250 ms and closes the view once world frames stop.
    private var speedTestInputTimer: Timer?
    private var speedTestStatsTimer: Timer?
    /// The real-time game's counters while its world frames arrive, logged every second.
    private var realtimeStats: RealtimeRxStats?
    private var realtimeStatsTimer: Timer?
    /// Whether the real-time game was being played at the last update: a new game gets a new link.
    private var wasRealtimePlaying = false
    private var cancellables = Set<AnyCancellable>()

    /// - Parameters:
    ///   - hostPeripheralId: `DiscoveredGame.id` for a first join; nil to rejoin by game code.
    ///   - session: fresh for a first join; seeded with the known game for a host that stepped down.
    init(gameCode: String, hostPeripheralId: UUID?, transport: GameClientTransport,
         session: ClientSession, games: [GameModule] = [], maxPlayers: Int = ProtocolConstants.maxPlayers,
         log: HyperBleLog = .shared) {
        self.gameCode = gameCode
        self.games = games
        self.maxPlayers = maxPlayers
        self.hostPeripheralId = hostPeripheralId
        self.transport = transport
        self.session = session
        self.log = log
        players = session.players
        playerId = session.playerId
        hostId = session.hostId
        hostEpoch = session.hostEpoch
        transport.eventsPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.handle($0) }
            .store(in: &cancellables)
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        let now = Date()
        if let hostPeripheralId {
            log.event("joining \(gameCode) as \(session.name), deviceId \(session.deviceId)", at: now)
            isAttemptRunning = true
            transport.connect(to: hostPeripheralId)
        } else {
            // Getting back into a known game works like a reconnect: by code, within the window.
            log.event("rejoining \(gameCode) as a client, deviceId \(session.deviceId)", at: now)
            let rejoin = Reconnect(lostAt: now, takeoverRank: nil)
            reconnect = rejoin
            status = .reconnecting(secondsLeft: rejoin.secondsLeft(at: now))
            startReconnectAttempt()
        }
        tickTimer = Timer.scheduledInCommonModes(withTimeInterval: ProtocolConstants.silenceCheckInterval,
                                                 repeats: true) { [weak self] _ in
            self?.tick(at: Date())
        }
    }

    /// The app is back in the foreground. Timers did not run while it was suspended, so check
    /// right away whether the host went quiet or the reconnect window ran out meanwhile.
    func returnedToForeground() {
        guard hasStarted, !hasLeft, tickTimer != nil else { return }
        tick(at: Date())
    }

    /// Tells the host we are going (when it can still hear us), then disconnects.
    func leave() {
        guard hasStarted, !hasLeft else { return }
        stopTimers()
        endReconnect()
        if status == .joined {
            send(session.leave(at: Date()))
        }
        hasLeft = true
        // Also stops a reconnect attempt that is still scanning or connecting.
        transport.disconnect()
    }

    /// This player's move, from the game's screen. The host decides; nothing changes locally.
    func play(_ action: JSONValue) {
        guard status == .joined, let message = session.gameAction(action, at: Date()) else { return }
        send(message)
    }

    /// Debug: sends the test frame to the host, raw and without an envelope. Only once joined.
    func sendTestFrame() {
        guard status == .joined else { return }
        let chunks = transport.send(TestFrame.data)
        log.testFrame(.sent, bytes: TestFrame.data.count, chunks: chunks, extra: "to=\(session.hostId)")
        testFrameText = "Test JSON: sent \(TestFrame.data.count) B to \(session.hostId)"
    }

    /// A tap on the speed-test field: counted and sent to the host at once.
    func speedTestTapped() {
        sendSpeedTestInput(tapped: true)
    }

    /// The host asked for this device's log (`LOG_REQUEST`): sends the current debug-log buffer to
    /// the host as `LOG_UPLOAD` batches. The lines are snapshotted first, so the upload's own log
    /// lines are not part of what is sent. An empty log still sends one empty batch, so the host
    /// sees that this client answered.
    private func uploadLogs(at now: Date) {
        guard isLinkReady, status == .joined else {
            log.event("log not uploaded: not connected to the host", at: now)
            return
        }
        let lines = log.entries.map(\.text)
        let size = ProtocolConstants.logUploadBatchLines
        let batches: [[String]] = lines.isEmpty
            ? [[]]
            : stride(from: 0, to: lines.count, by: size).map { Array(lines[$0..<min($0 + size, lines.count)]) }
        log.event("uploading \(lines.count) log line(s) to the host in \(batches.count) batch(es)", at: now)
        for (index, batch) in batches.enumerated() {
            send(session.logUpload(part: index, parts: batches.count, lines: batch, at: now))
        }
    }

    private func handle(_ event: ClientTransportEvent) {
        // Left, or now the host: whatever the old link still reports no longer matters.
        guard !hasLeft else { return }
        let now = Date()
        switch event {
        case .hostFound:
            hasLink = true
        case .gameInfo(let data):
            gameInfoRead(data, at: now)
        case .ready(let maxChunkLength):
            guard isAttemptRunning else { return }
            log.event("subscribed, chunk size \(maxChunkLength)", at: now)
            isLinkReady = true
            // While reconnecting the countdown stays up until the host's state is back.
            if reconnect == nil { status = .joining }
            send(session.joinRequest(at: now))
        case .message(let data, let chunks):
            // Leftovers of a link we already gave up on.
            guard isLinkReady else { return }
            if TestFrame.isTestFrame(data) {
                let sender = session.hostId
                session.testFrameReceived(at: now)
                log.testFrame(.received, bytes: data.count, chunks: chunks,
                              extra: "from=\(sender) \(TestFrame.matchDetail(for: data))", at: now)
                testFrameText = TestFrame.receivedSummary(data, chunks: chunks, from: sender)
                receivedTestFrame = TestFrame.prettyText(data)
                sessionChanged()
                return
            }
            guard let message = try? Message.decode(data) else {
                log.event("RX undecodable message bytes=\(data.count) chunks=\(chunks)", at: now)
                return
            }
            log.message(.received, message, bytes: data.count, chunks: chunks, at: now)
            let output = session.receive(message, at: now)
            output.notes.forEach { log.event($0, at: now) }
            output.outgoing.forEach(send)
            if let rejection = output.rejection { gameNotice = rejection.text }
            sessionChanged()
            if let event = output.event { hostAnnounced(event, at: now) }
            if output.logRequested { uploadLogs(at: now) }
        case .frame(let data):
            // No log line per frame; the speed test and the real-time game log counters once a second.
            guard isLinkReady else { return }
            if realtimeWorldReceived(data) { return }
            speedTestWorldReceived(data, at: now)
        case .disconnected(let reason):
            hasLink = false
            // No reason: we closed the link ourselves (leave, rejection, teardown before a retry).
            guard let reason else {
                log.event("disconnected", at: now)
                return
            }
            guard isAttemptRunning else {
                log.event(reason, at: now)
                return
            }
            if canReconnect {
                hostLost(reason, at: now)
            } else if !isFinished {
                // The first connection never got as far as joining: nothing to get back to.
                log.event(reason, at: now)
                stopTimers()
                status = .disconnected(reason: reason)
            }
        case .log(let text):
            log.event(text, at: now)
        }
    }

    private func gameInfoRead(_ data: Data, at now: Date) {
        log.event("GameInfo \(String(decoding: data, as: UTF8.self))", at: now)
        guard let info = try? WireJSON.decode(GameInfo.self, from: data) else {
            notice = "The host's game info could not be read."
            return
        }
        if info.hostEpoch < session.hostEpoch {
            // A host that has been replaced: joining it would split the game.
            let reason = "stale host \(info.hostId) epoch \(info.hostEpoch), known epoch \(session.hostEpoch)"
            log.event("\(reason): not joining, ignoring that device", at: now)
            transport.rejectCurrentHost()
            if canReconnect { hostLost(reason, at: now) }
            return
        }
        notice = nil
        if info.v != ProtocolConstants.version {
            notice = "Host uses protocol v\(info.v), this app v\(ProtocolConstants.version)."
        } else if !info.lobbyOpen {
            notice = "The lobby is closed: only returning players can join."
        }
        if let notice { log.event(notice, at: now) }
    }

    private func send(_ message: Message) {
        guard let data = try? message.encoded() else {
            log.event("could not encode \(message.type)")
            return
        }
        let chunks = transport.send(data)
        log.message(.sent, message, bytes: data.count, chunks: chunks)
    }

    /// Mirrors the session after a host message.
    private func sessionChanged() {
        players = session.players
        playerId = session.playerId
        hostId = session.hostId
        hostEpoch = session.hostEpoch
        holdsSnapshot = session.snapshot != nil
        takeoverRank = session.takeoverRank
        // Between a rejoin and its STATE_SYNC the session has no game; the last table stays up.
        if session.stateVersion > 0 {
            phase = session.phase
            gameModuleId = session.gameModuleId
            if gameView != session.view {
                gameView = session.view
                gameNotice = nil
            }
            updateRealtime()
        }
        if let code = session.rejectCode {
            // Final, also `removed`: the seat is gone, so there is nothing to reconnect to.
            if code == JoinReject.removed { log.event("removed from the game by the host") }
            status = .rejected(code: code)
            endReconnect()
            stopTimers()
            isLinkReady = false
            transport.disconnect()
        } else if let reconnect {
            // Back in once the STATE_SYNC that follows JOIN_ACCEPT has been applied.
            guard session.isAccepted, session.stateVersion > 0 else { return }
            let seconds = String(format: "%.1f", Date().timeIntervalSince(reconnect.lostAt))
            log.event("reconnected in \(seconds) s (attempt \(reconnect.attempt)) to host "
                      + "\(session.hostId) epoch \(session.hostEpoch)")
            lastReconnectText = "Last reconnect: \(seconds) s (attempt \(reconnect.attempt))"
            endReconnect()
            status = .joined
        } else if session.isAccepted {
            status = .joined
        }
    }

    /// The host said it is going away.
    private func hostAnnounced(_ event: ClientSession.Event, at now: Date) {
        switch event {
        case .hostLeaving(let nextHostId):
            if nextHostId == session.playerId, let hostSession = session.takeOver(at: now, findModule: findModule, maxPlayers: maxPlayers) {
                // Named by the leaving host: no need to wait and see whether it comes back.
                takeOver(hostSession, at: now)
            } else if canReconnect {
                if nextHostId == session.playerId {
                    log.event("error: named as next host but holding no snapshot, cannot take over", at: now)
                }
                hostLost("host is leaving, next host \(nextHostId)", at: now)
            }
        case .gameOver(let reason):
            guard reason == GameOver.hostLeft else {
                log.event("ignored GAME_OVER: reason \(reason)", at: now)
                return
            }
            log.event("game over: \(reason)", at: now)
            endReconnect()
            stopTimers()
            isLinkReady = false
            status = .hostLeft
            transport.disconnect()
        }
    }

    // MARK: - Reconnect

    /// A loss starts (or continues) a reconnect while we are in a game or already trying to get back.
    private var canReconnect: Bool {
        guard !hasLeft else { return false }
        return reconnect != nil || status == .joining || status == .joined
    }

    /// Rejected, given up or failed before joining: nothing more will happen on this screen.
    private var isFinished: Bool {
        switch status {
        case .rejected, .hostLeft, .disconnected: return true
        case .connecting, .joining, .joined, .reconnecting: return false
        }
    }

    /// The link to the host is gone or useless. Starts the reconnect window on the first loss,
    /// then tears the link down and tries again after a short pause.
    private func hostLost(_ reason: String, at now: Date) {
        if let reconnect {
            log.event("reconnect attempt \(reconnect.attempt) failed: \(reason)", at: now)
        } else {
            let lost = Reconnect(lostAt: now, takeoverRank: session.takeoverRank)
            reconnect = lost
            status = .reconnecting(secondsLeft: lost.secondsLeft(at: now))
            log.event("host lost (\(reason)), reconnecting for \(Int(ProtocolConstants.reconnectWindow)) s", at: now)
        }
        isLinkReady = false
        isAttemptRunning = false
        hasLink = false
        transport.disconnect()
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledInCommonModes(withTimeInterval: ProtocolConstants.reconnectPause,
                                                  repeats: false) { [weak self] _ in
            self?.startReconnectAttempt()
        }
    }

    private func startReconnectAttempt() {
        guard !hasLeft, var attempt = reconnect else { return }
        attempt.attempt += 1
        reconnect = attempt
        log.event("reconnect attempt \(attempt.attempt)")
        isAttemptRunning = true
        // By game code: the host's Bluetooth identifier may have changed since we first found it.
        transport.connect(toGameCode: gameCode)
    }

    private func tick(at now: Date) {
        if let reconnect {
            if now.timeIntervalSince(reconnect.lostAt) >= ProtocolConstants.reconnectWindow {
                giveUp(at: now)
                return
            }
            status = .reconnecting(secondsLeft: reconnect.secondsLeft(at: now))
            if isTakeoverDue(reconnect, at: now),
               let hostSession = session.takeOver(at: now, findModule: findModule, maxPlayers: maxPlayers) {
                takeOver(hostSession, at: now)
                return
            }
        }
        if isLinkReady, session.isHostSilent(at: now) {
            hostLost("no traffic for \(Int(ProtocolConstants.silenceTimeout)) s", at: now)
        }
    }

    /// Our turn to host: we hold a snapshot, the delay for our rank has passed
    /// and no host is in reach. An attempt that has found a host is left to finish first.
    private func isTakeoverDue(_ reconnect: Reconnect, at now: Date) -> Bool {
        guard !hasLink, let rank = reconnect.takeoverRank else { return false }
        let delay = ProtocolConstants.takeoverDelay + Double(rank - 1) * ProtocolConstants.takeoverStep
        return now.timeIntervalSince(reconnect.lostAt) >= delay
    }

    /// Stops being a client and hands the game, restored from the snapshot, to the host role.
    private func takeOver(_ hostSession: HostSession, at now: Date) {
        log.event("taking over as host: epoch \(hostSession.hostEpoch), stateVersion \(hostSession.stateVersion), "
                  + "phase \(hostSession.phase)", at: now)
        if let note = hostSession.takeoverNote { log.event(note, at: now) }
        hasLeft = true
        endReconnect()
        stopTimers()
        isLinkReady = false
        transport.disconnect()
        onTakeover?(hostSession)
    }

    private func endReconnect() {
        reconnect = nil
        transport.forgetRejectedHosts()
    }

    private func giveUp(at now: Date) {
        log.event("host left, game over: not back within \(Int(ProtocolConstants.reconnectWindow)) s", at: now)
        endReconnect()
        stopTimers()
        isLinkReady = false
        status = .hostLeft
        // Stops the scan of an attempt that is still looking for the host.
        transport.disconnect()
    }

    private func stopTimers() {
        tickTimer?.invalidate()
        tickTimer = nil
        retryTimer?.invalidate()
        retryTimer = nil
        closeSpeedTest(reason: "the session ended")
        stopRealtimeStats()
    }

    // MARK: - Real-time game

    /// The real-time module of the game on the table, nil for none or a turn-based game.
    private var realtimeModule: RealtimeGameModule? {
        gameModuleId.flatMap(findModule) as? RealtimeGameModule
    }

    /// The module with this `game` ID, nil when this app does not have it.
    private func findModule(_ id: String) -> GameModule? {
        games.first { $0.id == id }
    }

    /// Keeps the screen's link and the counters in step with the game on the table.
    private func updateRealtime() {
        let isPlaying = realtimeModule != nil && phase == GamePhase.playing
        let startsPlaying = isPlaying && !wasRealtimePlaying
        wasRealtimePlaying = isPlaying
        if let module = realtimeModule {
            // A new link for every game (also a rematch), so its frames start afresh.
            if realtimeLink == nil || startsPlaying {
                realtimeLink = RealtimeFeed(tickMillis: module.realtime.tickMillis) { [weak self] payload in
                    self?.sendRealtimeInput(payload)
                }
            }
        } else if realtimeLink != nil {
            realtimeLink = nil
            stopRealtimeStats()
        }
    }

    /// A world frame of the game on the table goes to its screen. Returns false for any other frame.
    private func realtimeWorldReceived(_ payload: Data) -> Bool {
        guard let link = realtimeLink, let module = realtimeModule,
              payload.first == module.realtime.worldKind else { return false }
        if realtimeStats == nil {
            // The counters run while frames arrive, and close a while after the last one.
            realtimeStats = RealtimeRxStats(staleFrameCount: transport.staleFrameCount)
            realtimeStatsTimer = Timer.scheduledInCommonModes(withTimeInterval: SpeedTestConstants.statsInterval,
                                                              repeats: true) { [weak self] _ in
                self?.realtimeSecondEnded()
            }
        }
        let ack = gameView.flatMap { module.echoedInputSeq(inWorld: payload, view: $0) }
        realtimeStats?.frameReceived(size: payload.count, ackedInputSeq: ack, nowMillis: SpeedTestClock.nowMillis())
        link.publish(payload)
        return true
    }

    /// The screen's controls, as an input frame for the host. Only while the game is played and
    /// the host can be reached; the transport sends the newest one, replacing one still waiting.
    private func sendRealtimeInput(_ payload: Data) {
        guard status == .joined, phase == GamePhase.playing, let module = realtimeModule,
              payload.first == module.realtime.inputKind else { return }
        if transport.sendFrame(payload) != .failed {
            realtimeStats?.inputSent(seq: module.inputSeq(inInput: payload), nowMillis: SpeedTestClock.nowMillis())
        }
    }

    private func realtimeSecondEnded() {
        guard var stats = realtimeStats else { return }
        let closeAfter = UInt32(SpeedTestConstants.closeAfter * 1000)
        guard !stats.isIdle(nowMillis: SpeedTestClock.nowMillis(), closeAfterMillis: closeAfter) else {
            stopRealtimeStats()
            return
        }
        log.realtime(stats.endSecond(staleFrameCount: transport.staleFrameCount))
        realtimeStats = stats
    }

    private func stopRealtimeStats() {
        realtimeStatsTimer?.invalidate()
        realtimeStatsTimer = nil
        realtimeStats = nil
    }

    // MARK: - Speed test

    /// No message starts the speed test: the first world frame opens its view.
    private func speedTestWorldReceived(_ payload: Data, at now: Date) {
        guard let world = SpeedTestWorld(payload: payload) else { return }
        if speedTestRun == nil { openSpeedTest() }
        speedTestRun?.worldReceived(world, payloadSize: payload.count, nowMillis: SpeedTestClock.nowMillis())
        lastWorldFrameAt = now
        speedTest.dot = CGPoint(x: Int(world.x), y: Int(world.y))
    }

    private func openSpeedTest() {
        speedTestRun = SpeedTestClient(staleFrameCount: transport.staleFrameCount)
        isSpeedTestOpen = true
        speedTest.taps = 0
        speedTest.lastSecondText = ""
        speedTest.totalText = ""
        log.event("speed test: world frames arriving")
        speedTestInputTimer = Timer.scheduledInCommonModes(withTimeInterval: SpeedTestConstants.inputInterval,
                                                           repeats: true) { [weak self] _ in
            self?.speedTestInputDue(at: Date())
        }
        speedTestStatsTimer = Timer.scheduledInCommonModes(withTimeInterval: SpeedTestConstants.statsInterval,
                                                           repeats: true) { [weak self] _ in
            self?.speedTestSecondEnded()
        }
        sendSpeedTestInput(tapped: false)
    }

    private func speedTestInputDue(at now: Date) {
        if let lastWorldFrameAt, now.timeIntervalSince(lastWorldFrameAt) >= SpeedTestConstants.closeAfter {
            closeSpeedTest(reason: "no world frame for \(Int(SpeedTestConstants.closeAfter)) s")
            return
        }
        sendSpeedTestInput(tapped: false)
    }

    private func sendSpeedTestInput(tapped: Bool) {
        guard var run = speedTestRun else { return }
        let input = run.nextInput(nowMillis: SpeedTestClock.nowMillis(), tapped: tapped)
        speedTestRun = run
        transport.sendFrame(input.encoded())
        speedTest.taps = Int(run.taps)
    }

    private func speedTestSecondEnded() {
        guard var run = speedTestRun else { return }
        log.realtime(run.endSecond(staleFrameCount: transport.staleFrameCount))
        speedTestRun = run
        speedTest.lastSecondText = "\(run.lastSecond.text) size=\(run.payloadSize)"
        speedTest.totalText = run.totalText
    }

    private func closeSpeedTest(reason: String) {
        guard let run = speedTestRun else { return }
        speedTestInputTimer?.invalidate()
        speedTestInputTimer = nil
        speedTestStatsTimer?.invalidate()
        speedTestStatsTimer = nil
        log.event("speed test closed (\(reason)), total \(run.totalText)")
        speedTestRun = nil
        lastWorldFrameAt = nil
        isSpeedTestOpen = false
        speedTest.dot = nil
    }
}
