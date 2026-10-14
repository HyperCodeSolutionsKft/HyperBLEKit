import Combine
import Foundation

/// The host role of a game: connects the transport to `HostSession`, drives the heartbeat,
/// looks for a newer host when it is left alone and writes everything to the debug log.
final class HostRuntime: ObservableObject {
    /// What a host that steps down knows about the game it rejoins as a client.
    struct StepDown {
        let gameCode: String
        let playerId: String
        let players: [Player]
        let hostEpoch: Int
    }

    let gameCode: String
    let log: HyperBleLog
    /// The games this app can play (`HyperBleKitConfig.games`).
    let games: [GameModule]
    @Published private(set) var state: AdvertisingState = .idle
    @Published private(set) var players: [Player] = []
    @Published private(set) var successors: [String] = []
    @Published private(set) var pingStats = PingStats()
    /// Every disconnected player and the seconds left until its seat is removed, by player ID.
    /// Counts down every second.
    @Published private(set) var removalCountdowns: [String: Int] = [:]
    /// `lobby`, `playing` or `finished`.
    @Published private(set) var phase: String
    /// `game` of the module that is running, nil in the lobby.
    @Published private(set) var gameModuleId: String?
    /// The host's own view of the game, for the game's screen.
    @Published private(set) var gameView: JSONValue?
    /// Why the last own move or game start was refused; cleared when the game moves on.
    @Published private(set) var gameNotice: String?
    /// Outcome of the last debug test frame sent or received.
    @Published private(set) var testFrameText: String?
    /// The last received debug test frame, pretty-printed. Sent frames are not shown.
    @Published private(set) var receivedTestFrame: String?
    /// The last on-demand ping-all (`pingAll()`); nil until one has run.
    @Published private(set) var pingAllResult: PingAllResult?
    /// Called when another device hosts this game with a better claim: this device has stopped
    /// hosting and should continue as a client.
    var onStepDown: ((StepDown) -> Void)?
    /// What the speed test shows.
    let speedTest = HostSpeedTestState()
    /// The moving world and the host's own controls, while a real-time game is on the table.
    @Published private(set) var realtimeLink: RealtimeFeed?

    private let transport: GameHostTransport
    private let makeProbeTransport: () -> GameClientTransport
    private var session: HostSession
    /// Every second: the removal countdowns, and the heartbeat whenever it is due.
    private var heartbeatTimer: Timer?
    /// A client transport that is looking for another host of this game, nil when not probing.
    private var probe: GameClientTransport?
    private var probeEvents: AnyCancellable?
    /// Since when there are seated players but no link to any of them.
    private var aloneSince: Date?
    private var lastProbeEndedAt: Date?
    /// When the heartbeat last ran; a long gap means this device itself was stalled.
    private var lastHeartbeatAt: Date?
    /// Left or stepped down: nothing is sent or handled any more.
    private var isStopped = false
    /// The running speed test, nil when none runs.
    private var speedTestRun: SpeedTestHost?
    /// Host ticks of the speed test, 30 a second.
    private var speedTestTimer: Timer?
    /// Runs the real-time game's due ticks, twice a tick, while one is being played.
    private var realtimeTimer: Timer?
    /// Collects the answers of a ping-all and reports them when it fires, nil when none runs.
    private var pingAllTimer: Timer?
    /// The counters while the world runs; nil while it is paused.
    private var realtimeStats: RealtimeTxStats?
    /// Whether a real-time game was being played at the last update: a new game gets a new link.
    private var wasRealtimeRunning = false
    private var cancellables = Set<AnyCancellable>()

    /// - Parameter session: a new game, or one taken over from its previous host.
    init(transport: GameHostTransport, session: HostSession, games: [GameModule] = [], log: HyperBleLog = .shared,
         makeProbeTransport: @escaping () -> GameClientTransport) {
        self.transport = transport
        self.session = session
        self.games = games
        self.log = log
        self.makeProbeTransport = makeProbeTransport
        gameCode = session.gameId
        players = session.players
        successors = session.successors
        phase = session.phase
        gameModuleId = session.gameModuleId
        gameView = session.hostView
        transport.statePublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.advertisingChanged($0) }
            .store(in: &cancellables)
        transport.eventsPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.handle($0) }
            .store(in: &cancellables)
    }

    var hostId: String { session.hostId }
    var hostEpoch: Int { session.hostEpoch }

    func start() {
        guard heartbeatTimer == nil, !isStopped else { return }
        publishGameInfo()
        transport.start(gameCode: gameCode)
        let now = Date()
        // The first heartbeat comes one ping interval after the start.
        lastHeartbeatAt = now
        publishCountdowns(at: now)
        heartbeatTimer = Timer.scheduledInCommonModes(withTimeInterval: ProtocolConstants.silenceCheckInterval,
                                                      repeats: true) { [weak self] _ in
            self?.tick(at: Date())
        }
        // A game taken over from a snapshot may be a real-time one: its loop starts at once.
        updateRealtime()
    }

    /// The app is going to the background, where it cannot keep hosting. Hands the game to the
    /// first successor, if there is one, and returns what is needed to rejoin it later.
    /// `completion` runs once the last messages have had time to go out.
    func handOverForBackground(completion: @escaping () -> Void) -> StepDown? {
        guard !isStopped, let next = session.successors.first else {
            if !isStopped { log.event("app in background: no successor with a live link, still hosting") }
            completion()
            return nil
        }
        log.event("app in background: handing over to \(next)")
        let handover = StepDown(gameCode: gameCode, playerId: hostId, players: session.players, hostEpoch: hostEpoch)
        apply(session.leaving(at: Date()))
        shutDown()
        transport.stopAfterFlush(then: completion)
        return handover
    }

    /// The user stops hosting: hands the game to the first successor, or ends it if there is none.
    func leave() {
        guard !isStopped else { return }
        apply(session.leaving(at: Date()))
        shutDown()
        transport.stopAfterFlush(then: {})
    }

    // MARK: - Game

    /// Why this game cannot be started right now, nil when it can.
    func startGameProblem(for module: GameModule) -> String? {
        guard !module.supports(playerCount: players.filter(\.connected).count) else {
            return session.linkProblem(for: module)
        }
        let counts = (1...session.maxPlayers).filter { module.supports(playerCount: $0) }
        guard let least = counts.first, let most = counts.last else { return "\(module.title) cannot be played here" }
        let range = least == most ? "\(least)" : "\(least)–\(most)"
        return "\(module.title) needs \(range) connected players"
    }

    /// Starts this game for everyone who is connected.
    func startGame(_ module: GameModule) {
        guard !isStopped else { return }
        var random = SystemRandomNumberGenerator()
        apply(session.startGame(module, at: Date(), using: &random))
    }

    /// The module with this `game` ID, nil when this app does not have it.
    func findModule(_ id: String) -> GameModule? {
        games.first { $0.id == id }
    }

    /// Ends the game, running or finished, and returns everyone to the lobby.
    func endGame() {
        guard !isStopped else { return }
        apply(session.endGame(at: Date()))
    }

    /// Removes a disconnected player at once, from the player list or the game table.
    func removePlayer(_ playerId: String) {
        guard !isStopped else { return }
        log.event("host: remove \(playerId)")
        apply(session.removePlayer(playerId, at: Date()))
    }

    /// The host's own move, from the game's screen.
    func play(_ action: JSONValue) {
        guard !isStopped else { return }
        var random = SystemRandomNumberGenerator()
        apply(session.hostAction(action, at: Date(), using: &random))
    }

    /// Debug: sends the test frame to every joined client, raw and without an envelope.
    func sendTestFrame() {
        let recipients = session.linkedClients
        for recipient in recipients {
            let chunks = transport.send(TestFrame.data, to: recipient.link)
            log.testFrame(.sent, bytes: TestFrame.data.count, chunks: chunks, extra: "to=\(recipient.label)")
        }
        testFrameText = recipients.isEmpty
            ? "Test JSON: no joined client to send to"
            : "Test JSON: sent \(TestFrame.data.count) B to \(recipients.map(\.label).joined(separator: ", "))"
        if recipients.isEmpty { log.event("test JSON not sent: no joined client") }
    }

    /// Debug: pings every joined client at once and publishes each one's round trip as it
    /// answers, finishing after `ProtocolConstants.pingAllTimeout` (clients that stayed silent
    /// get a nil time). A new call replaces one still running.
    func pingAll() {
        guard !isStopped else { return }
        pingAllTimer?.invalidate()
        pingAllTimer = nil
        apply(session.pingAll(at: Date()))
        let pending = session.linkedClients.map { PingReply(playerId: $0.label, rttMs: nil) }
        guard !pending.isEmpty else {
            pingAllResult = PingAllResult(replies: [], pending: false)
            return
        }
        pingAllResult = PingAllResult(replies: pending, pending: true)
        pingAllTimer = Timer.scheduledInCommonModes(withTimeInterval: ProtocolConstants.pingAllTimeout,
                                                    repeats: false) { [weak self] _ in
            guard let self else { return }
            self.pingAllTimer = nil
            let replies = self.session.finishPingAll()
            self.pingAllResult = PingAllResult(replies: replies, pending: false)
            self.log.event("ping_all: \(replies.filter { $0.rttMs != nil }.count)/\(replies.count) answered")
        }
    }

    // MARK: - Speed test

    /// The speed test can run: in the lobby, with at least one client linked.
    var canRunSpeedTest: Bool {
        !isStopped && phase == GamePhase.lobby && !session.linkedClients.isEmpty
    }

    /// Starts sending world frames to every linked client, with the rate and size picked.
    func startSpeedTest(frameRate: Int, payloadSize: Int) {
        guard speedTestRun == nil, canRunSpeedTest else { return }
        let run = SpeedTestHost(frameRate: frameRate, payloadSize: payloadSize,
                                nowMillis: SpeedTestClock.nowMillis())
        speedTestRun = run
        speedTest.frameRate = run.frameRate
        speedTest.payloadSize = run.payloadSize
        speedTest.isRunning = true
        speedTest.lines = []
        log.event("speed test started: \(run.frameRate) frames/s, \(run.payloadSize) B")
        speedTestTimer = Timer.scheduledInCommonModes(withTimeInterval: SpeedTestConstants.tickInterval,
                                                      repeats: true) { [weak self] _ in
            self?.speedTestTick()
        }
    }

    func stopSpeedTest(reason: String = "stopped") {
        guard speedTestRun != nil else { return }
        speedTestTimer?.invalidate()
        speedTestTimer = nil
        speedTestRun = nil
        speedTest.isRunning = false
        speedTest.dot = nil
        log.event("speed test \(reason)")
    }

    /// One host tick: moves the dot, sends the world frames when due, logs the stats once a second.
    private func speedTestTick() {
        guard var run = speedTestRun else { return }
        let clients = session.linkedClients
        guard !clients.isEmpty else {
            stopSpeedTest(reason: "stopped: no client linked")
            return
        }
        let now = SpeedTestClock.nowMillis()
        if run.advance() {
            for client in clients {
                let result = transport.sendFrame(run.worldPayload(for: client.link, nowMillis: now), to: client.link)
                run.frameSent(to: client.link, result: result)
            }
        }
        if let lines = run.reportIfDue(nowMillis: now, clients: clients.map { ($0.link, $0.label) }) {
            lines.forEach { log.realtime("RT tx \($0)") }
            speedTest.lines = lines
        }
        speedTestRun = run
        speedTest.dot = CGPoint(x: run.dot.x, y: run.dot.y)
    }

    /// The advertising state as it is written to the log.
    private var statusText: String {
        switch state {
        case .idle: return "Not advertising"
        case .starting: return "Starting…"
        case .advertising: return "Advertising — waiting for players"
        case .unavailable(let availability): return availability.userMessage ?? "Bluetooth not ready"
        case .failed(let reason): return "Failed: \(reason)"
        }
    }

    private func advertisingChanged(_ newState: AdvertisingState) {
        guard newState != state, !isStopped else { return }
        let wasUnavailable: Bool
        if case .unavailable = state { wasUnavailable = true } else { wasUnavailable = false }
        state = newState
        log.event("advertising: \(statusText)")
        if case .unavailable = newState { return }
        // While Bluetooth was away, a successor may have taken the game over.
        if wasUnavailable, session.hasSeatedClients { startProbe(reason: "Bluetooth is back") }
    }

    private func handle(_ event: HostTransportEvent) {
        guard !isStopped else { return }
        let now = Date()
        switch event {
        case .linkUp(let link, let maxChunkLength):
            log.event("client \(link.shortName) subscribed, chunk size \(maxChunkLength)", at: now)
            session.linkUp(link, maxChunkLength: maxChunkLength)
        case .linkDown(let link):
            log.event("client \(link.shortName) link down", at: now)
            apply(session.linkDown(link, at: now))
        case .message(let link, let data, let chunks):
            if TestFrame.isTestFrame(data) {
                let (sender, output) = session.testFrameReceived(from: link, at: now)
                log.testFrame(.received, bytes: data.count, chunks: chunks,
                              extra: "from=\(sender) \(TestFrame.matchDetail(for: data))", at: now)
                testFrameText = TestFrame.receivedSummary(data, chunks: chunks, from: sender)
                receivedTestFrame = TestFrame.prettyText(data)
                apply(output)
                return
            }
            guard let message = try? Message.decode(data) else {
                log.event("RX undecodable message from \(link.shortName) bytes=\(data.count) chunks=\(chunks)", at: now)
                return
            }
            var random = SystemRandomNumberGenerator()
            let output = session.receive(message, from: link, at: now, using: &random)
            log.message(.received, message, bytes: data.count, chunks: chunks,
                        extra: output.roundTripMs.map { "rtt=\($0)ms" }, at: now)
            apply(output)
        case .frame(let link, let data):
            // No log line per frame; the speed test and the real-time game log counters once a second.
            // A frame counts as traffic from its sender, like a message.
            let (inputTaken, output) = session.frameReceived(data, from: link, at: now)
            if inputTaken { realtimeStats?.inputReceived(from: link, nowMillis: SpeedTestClock.nowMillis()) }
            speedTestRun?.inputReceived(data, from: link, nowMillis: SpeedTestClock.nowMillis())
            if !output.outgoing.isEmpty || !output.notes.isEmpty { apply(output) }
        case .log(let text):
            log.event(text, at: now)
        }
    }

    /// Sends what the session decided, one log line per recipient.
    private func apply(_ output: HostSession.Output) {
        output.notes.forEach { log.event($0) }
        for outgoing in output.outgoing {
            guard let data = try? outgoing.message.encoded() else {
                log.event("could not encode \(outgoing.message.type)")
                continue
            }
            for recipient in outgoing.recipients {
                let chunks = transport.send(data, to: recipient.link)
                log.message(.sent, outgoing.message, bytes: data.count, chunks: chunks, extra: "to=\(recipient.label)")
            }
        }
        // GameInfo carries the player count and, through the phase, whether the lobby is open.
        if players != session.players || phase != session.phase {
            players = session.players
            phase = session.phase
            publishGameInfo()
        }
        if phase != GamePhase.lobby { stopSpeedTest(reason: "stopped: a game started") }
        successors = session.successors
        pingStats = session.pingStats
        publishCountdowns(at: Date())
        gameModuleId = session.gameModuleId
        let view = session.hostView
        if let rejection = output.rejection {
            gameNotice = rejection.text
        } else if view != gameView {
            gameNotice = nil
        }
        gameView = view
        updateRealtime()
    }

    private func publishGameInfo() {
        guard let data = try? WireJSON.encode(session.gameInfo) else { return }
        transport.setGameInfo(data)
        log.event("GameInfo \(String(decoding: data, as: UTF8.self))")
    }

    /// Runs every second. Timers fire a little early or late: half a tick of slack keeps the
    /// heartbeat on every `pingInterval`, not one tick after it.
    private func tick(at now: Date) {
        let sinceHeartbeat = lastHeartbeatAt.map { now.timeIntervalSince($0) } ?? .infinity
        if sinceHeartbeat >= ProtocolConstants.pingInterval - ProtocolConstants.silenceCheckInterval / 2 {
            heartbeat(at: now)
        } else {
            publishCountdowns(at: now)
        }
    }

    /// Only when a number changed: the screen redraws for every publish.
    private func publishCountdowns(at now: Date) {
        let countdowns = session.removalCountdowns(at: now)
        if countdowns != removalCountdowns { removalCountdowns = countdowns }
    }

    private func heartbeat(at now: Date) {
        // A gap this long means our own timer did not run (the app was suspended): the clients
        // were not silent, we were deaf. Judging them now would mark everyone disconnected.
        let gap = lastHeartbeatAt.map { now.timeIntervalSince($0) } ?? 0
        let wasStalled = gap > 2 * ProtocolConstants.pingInterval
        lastHeartbeatAt = now
        if wasStalled {
            log.event(String(format: "host was stalled for %.0f s, not judging clients", gap), at: now)
            aloneSince = nil
            lastProbeEndedAt = now
        }
        apply(session.heartbeat(at: now, judging: !wasStalled))
        if !wasStalled { probeIfReplaceable(at: now) }
    }

    private func shutDown() {
        stopSpeedTest(reason: "stopped: no longer hosting")
        stopRealtimeLoop()
        isStopped = true
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        pingAllTimer?.invalidate()
        pingAllTimer = nil
        endProbe()
    }

    // MARK: - Real-time game

    /// Keeps the screen's link and the tick loop in step with the game on the table.
    private func updateRealtime() {
        let startsRunning = session.isRealtimeRunning && !wasRealtimeRunning
        wasRealtimeRunning = session.isRealtimeRunning
        if let settings = session.realtimeSettings {
            // A new link for every game (also "Play again"), so its frames start afresh.
            if realtimeLink == nil || startsRunning {
                realtimeLink = RealtimeFeed(tickMillis: settings.tickMillis) { [weak self] payload in
                    guard let self, !self.isStopped else { return }
                    self.session.hostRealtimeInput(payload)
                }
            }
        } else if realtimeLink != nil {
            realtimeLink = nil
        }
        if session.isRealtimeRunning, let settings = session.realtimeSettings {
            guard realtimeTimer == nil else { return }
            log.event("real-time loop started: tick \(settings.tickMillis) ms, a frame every "
                      + "\(settings.framePeriodTicks) ticks")
            // Twice a tick: the session runs whatever ticks are due, late ones back to back.
            let interval = TimeInterval(settings.tickMillis) / 2000
            realtimeTimer = Timer.scheduledInCommonModes(withTimeInterval: interval, repeats: true) { [weak self] _ in
                self?.realtimeTick()
            }
        } else {
            stopRealtimeLoop()
        }
    }

    private func stopRealtimeLoop() {
        guard realtimeTimer != nil else { return }
        realtimeTimer?.invalidate()
        realtimeTimer = nil
        realtimeStats = nil
        log.event("real-time loop stopped")
    }

    /// Runs the due ticks, sends the world frame to every linked client when one is due, shows
    /// the newest world on the own screen and logs the counters once a second.
    private func realtimeTick() {
        guard !isStopped else { return }
        let step = session.advanceRealtime(at: Date())
        if let frame = step.clientFrame {
            for client in session.linkedClients {
                realtimeStats?.frameSent(to: client.link, result: transport.sendFrame(frame, to: client.link))
            }
        }
        if let frame = step.localFrame { realtimeLink?.publish(frame) }
        if !step.output.outgoing.isEmpty || !step.output.notes.isEmpty { apply(step.output) }
        // Counted and logged only while the world runs, not while it is paused.
        guard session.isRealtimeRunning, !session.isRealtimePaused else {
            realtimeStats = nil
            return
        }
        if realtimeStats == nil { realtimeStats = RealtimeTxStats(nowMillis: SpeedTestClock.nowMillis()) }
        let clients = session.linkedClients.map { (link: $0.link, label: $0.label) }
        if let lines = realtimeStats?.reportIfDue(nowMillis: SpeedTestClock.nowMillis(), clients: clients) {
            lines.forEach { log.realtime("RT tx \($0)") }
        }
    }

    // MARK: - Stepping down

    /// Another player may have taken the game over without this host noticing: when it can
    /// reach none of its players, or when a player that held a snapshot dropped and stays away.
    /// While that lasts, it looks for a newer host at every probe interval; it keeps serving
    /// the players it still has in the meantime.
    private func probeIfReplaceable(at now: Date) {
        let interval = ProtocolConstants.hostAloneProbeAfter
        var reason: String?
        if session.isAlone {
            let since = aloneSince ?? now
            aloneSince = since
            if now.timeIntervalSince(since) >= interval { reason = "alone" }
        } else {
            aloneSince = nil
        }
        if reason == nil, let gone = session.successorGone(for: interval, at: now) {
            reason = "successor \(gone) gone"
        }
        guard let reason else { return }
        if let lastProbeEndedAt, now.timeIntervalSince(lastProbeEndedAt) < interval { return }
        startProbe(reason: reason)
    }

    /// Looks for another device advertising this game and reads its GameInfo.
    private func startProbe(reason: String) {
        guard probe == nil, !isStopped else { return }
        log.event("probe: looking for another host of \(gameCode) (\(reason))")
        let probe = makeProbeTransport()
        self.probe = probe
        probeEvents = probe.eventsPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.handleProbe($0) }
        probe.connect(toGameCode: gameCode)
    }

    private func handleProbe(_ event: ClientTransportEvent) {
        switch event {
        case .gameInfo(let data):
            guard let other = try? WireJSON.decode(GameInfo.self, from: data) else {
                log.event("probe: the other host's GameInfo could not be read, keeping the host role")
                endProbe()
                return
            }
            let found = "found host \(other.hostId) epoch \(other.hostEpoch), ours \(hostId) epoch \(hostEpoch)"
            if Succession.outranks(otherHostId: other.hostId, otherEpoch: other.hostEpoch,
                                   ownHostId: hostId, ownEpoch: hostEpoch) {
                log.event("probe: \(found): stepping down")
                stepDown(to: other)
            } else {
                log.event("probe: \(found): keeping the host role")
                endProbe()
            }
        case .ready:
            log.event("probe: the other host has no GameInfo, keeping the host role")
            endProbe()
        case .disconnected(let reason):
            log.event("probe: no other host (\(reason ?? "disconnected")), keeping the host role")
            endProbe()
        case .log(let text):
            log.event("probe: \(text)")
        case .hostFound, .message, .frame:
            break
        }
    }

    private func endProbe() {
        probeEvents = nil
        probe?.disconnect()
        probe = nil
        // The next probe is due a full interval from now.
        lastProbeEndedAt = Date()
    }

    private func stepDown(to other: GameInfo) {
        let handover = StepDown(gameCode: gameCode, playerId: hostId, players: session.players,
                                hostEpoch: other.hostEpoch)
        shutDown()
        transport.stop()
        onStepDown?(handover)
    }
}
