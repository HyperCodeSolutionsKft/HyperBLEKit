import Combine
import Foundation

/// One game on this device, from hosting or joining it until it is left.
///
/// A session has no fixed role: with host migration a client can become the host and a host can
/// step down to a client, and the same `GameSession` object lives through that. An app holds
/// the session, draws what `state` says and calls `act(_:)` for the player's moves; it never
/// needs to know which role the device has, except to offer the host-only controls.
///
/// A session is created by `HyperBleKit.host()` or `HyperBleKit.join(_:)` and is already
/// running when it is returned. It ends with `HyperBleKit.leave()`: after that its `state` is
/// `.ended(reason: .left)` for good and every method does nothing.
///
/// Use it on the main thread only. `state` and `realtime` change on the main thread.
@MainActor
public final class GameSession: ObservableObject {
    enum Start {
        /// Create a new game and host it.
        case host
        /// Join a game the scanner found.
        case join(DiscoveredGame)
    }

    enum Role {
        case host(HostRuntime)
        case client(ClientRuntime)
    }

    /// The 4-character code the game is advertised under, e.g. "7K2F". It stays the same for
    /// the whole game, also across host migrations.
    public let gameCode: String
    /// Everything a screen shows about the game: role, connection, players, the game on the
    /// table and this player's view of it. A new value is published whenever a field changes.
    ///
    /// It changes one main-queue turn after the event that caused it, never inside a call:
    /// right after `act(_:)` or `startGame(_:)` returns, `state` is still the old value. What
    /// changed within one turn arrives as one value. The exception is leaving: when
    /// `HyperBleKit.leave()` returns, `state` is already `.ended(reason: .left)`.
    ///
    /// Because of that, an observer of `state` never runs in the middle of the SDK's own work,
    /// and may call anything from it, also `HyperBleKit.leave()`.
    @Published public private(set) var state: SessionState
    /// The moving world and this player's controls of a real-time game. Set while a real-time
    /// game module is on the table, nil otherwise. Every new game gets a new link object, and
    /// so does a game that continues after a host migration. Published together with `state`,
    /// one main-queue turn after the event that caused the change.
    @Published public private(set) var realtime: RealtimeLink?
    /// The debug tools of this session: the JSON test frame and the speed test.
    public let diagnostics = SessionDiagnostics()

    let log: HyperBleLog
    /// The runtime of the current role; nil once the session was left.
    private(set) var role: Role?

    private let config: HyperBleKitConfig
    private let identity: LocalIdentity
    private let makeHostTransport: () -> GameHostTransport
    private let makeClientTransport: () -> GameClientTransport
    private var hasStarted = false
    private var hasLeft = false
    /// Set when hosting was handed over because the app went to the background: the game to
    /// rejoin as a client once the app is back.
    private var rejoinAfterBackground: HostRuntime.StepDown?
    /// What mirrors the current role's runtime into `state`; replaced with the role.
    private var roleSubscriptions = Set<AnyCancellable>()
    /// What the current role's runtime says right now. The runtimes change it in the middle of
    /// their own work; the app sees it one main-queue turn later, through `publish()`.
    private var latest: Latest
    private var isPublishScheduled = false
    private var isPublishing = false

    /// Everything the app can observe on a session and its diagnostics.
    private struct Latest {
        var state: SessionState
        var realtime: RealtimeFeed?
        var testResult: String?
        var testJsonReceived: String?
        var hostSpeedTest: HostSpeedTestState?
        var clientSpeedTest: ClientSpeedTestState?
        var pingAll: PingAllResult?
    }

    init(start: Start, config: HyperBleKitConfig, identity: LocalIdentity, log: HyperBleLog = .shared,
         makeHostTransport: @escaping () -> GameHostTransport,
         makeClientTransport: @escaping () -> GameClientTransport) {
        self.config = config
        self.identity = identity
        self.log = log
        self.makeHostTransport = makeHostTransport
        self.makeClientTransport = makeClientTransport
        let deviceId = identity.deviceId
        let playerName = identity.playerName
        switch start {
        case .host:
            let session = HostSession(gameId: GameCode.random(), hostName: playerName, deviceId: deviceId,
                                      maxPlayers: config.maxPlayers)
            let host = HostRuntime(transport: makeHostTransport(), session: session, games: config.games, log: log,
                                   makeProbeTransport: makeClientTransport)
            role = .host(host)
            gameCode = host.gameCode
            let first = SessionState(role: .host, connection: .joined, gameCode: host.gameCode,
                                     hostId: host.hostId, hostEpoch: host.hostEpoch)
            state = first
            latest = Latest(state: first)
        case .join(let game):
            let client = ClientRuntime(gameCode: game.gameCode, hostPeripheralId: game.id,
                                       transport: makeClientTransport(),
                                       session: ClientSession(deviceId: deviceId, name: playerName),
                                       games: config.games, maxPlayers: config.maxPlayers, log: log)
            role = .client(client)
            gameCode = client.gameCode
            let first = SessionState(role: .client, connection: .connecting, gameCode: client.gameCode,
                                     hostId: client.hostId, hostEpoch: client.hostEpoch)
            state = first
            latest = Latest(state: first)
        }
        diagnostics.session = self
        observeRole()
        // Nobody observes yet: the session is handed out with its real first state.
        publish()
    }

    // MARK: - Game

    /// Host only: starts the game module with this ID for every connected player. Also starts
    /// the game on the table again after it has finished.
    ///
    /// Does nothing on a client, for an ID that is not in `HyperBleKitConfig.games`, and after
    /// the session was left. A start the host refuses (see `startGameProblem(_:)`) changes
    /// nothing but `state.gameNotice`.
    ///
    /// - Parameter gameId: `GameModule.id` of one of `HyperBleKitConfig.games`.
    public func startGame(_ gameId: String) {
        guard case .host(let host) = role, let module = host.findModule(gameId) else { return }
        host.startGame(module)
    }

    /// Why the game module with this ID cannot be started right now, as one English sentence
    /// for the screen; nil when `startGame(_:)` would start it. Not published: ask again when
    /// `state` changes.
    ///
    /// The reasons, in the order they are checked: the session was left; this device is not
    /// the host; the ID is not in `HyperBleKitConfig.games`; a game is running (a finished one
    /// can be started again); the number of connected players is one the module does not
    /// support; a player's connection is too weak for a real-time game.
    ///
    /// - Parameter gameId: `GameModule.id` of one of `HyperBleKitConfig.games`.
    public func startGameProblem(_ gameId: String) -> String? {
        guard let role else { return "The session has ended" }
        guard case .host(let host) = role else { return "Only the host can start a game" }
        guard let module = host.findModule(gameId) else { return "Unknown game \(gameId)" }
        guard host.phase != GamePhase.playing else { return "A game is already running" }
        return host.startGameProblem(for: module)
    }

    /// Host only: ends the game on the table, running or finished, and returns everyone to the
    /// lobby. Does nothing on a client, in the lobby, and after the session was left.
    public func endGame() {
        guard case .host(let host) = role else { return }
        host.endGame()
    }

    /// Host only: removes a disconnected player's seat at once, instead of waiting until the
    /// time in `state.offlineSecondsLeft` has run out. A removed phone that can still be reached
    /// is told so, and its session ends with `EndReason.rejected(code: JoinRejectCode.removed)`;
    /// one that comes back later is treated as a new player.
    ///
    /// Does nothing on a client and after the session was left. A player that is connected, an
    /// unknown player ID and the host's own ID are refused and reported in `state.gameNotice`.
    ///
    /// - Parameter playerId: the seat to remove, e.g. "p3".
    public func removePlayer(_ playerId: String) {
        guard case .host(let host) = role else { return }
        host.removePlayer(playerId)
    }

    /// Makes this player's move in the running game. The same call for the host and a client:
    /// the host applies its own move at once, a client's move is sent to the host, which
    /// decides. An accepted move shows up as a new `state.view`, a refused one in
    /// `state.gameNotice`.
    ///
    /// Does nothing while no game is running, on a client that is not `joined`, and after the
    /// session was left.
    ///
    /// - Parameter action: the move, as JSON in the format of the game module on the table.
    public func act(_ action: JSONValue) {
        switch role {
        case .host(let host): host.play(action)
        case .client(let client): client.play(action)
        case nil: break
        }
    }

    // MARK: - Diagnostics

    func sendTestJson() {
        switch role {
        case .host(let host): host.sendTestFrame()
        case .client(let client): client.sendTestFrame()
        case nil: break
        }
    }

    func pingAll() {
        guard case .host(let host) = role else { return }
        host.pingAll()
    }

    var canRunSpeedTest: Bool {
        guard case .host(let host) = role else { return false }
        return host.canRunSpeedTest
    }

    func startSpeedTest(frameRate: Int, payloadSize: Int) {
        guard case .host(let host) = role else { return }
        host.startSpeedTest(frameRate: frameRate, payloadSize: payloadSize)
    }

    func stopSpeedTest() {
        guard case .host(let host) = role else { return }
        host.stopSpeedTest()
    }

    func tapSpeedTest() {
        guard case .client(let client) = role else { return }
        client.speedTestTapped()
    }

    // MARK: - Lifecycle

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        startRole()
    }

    /// The user closes the screen: a host hands the game over, a client says goodbye. The
    /// session is dead from here on: nothing a transport still delivers while the last messages
    /// are flushed can start a role again.
    func leave() {
        guard !hasLeft else { return }
        hasLeft = true
        if hasStarted {
            switch role {
            case .host(let host): host.leave()
            case .client(let client): client.leave()
            case nil: break
            }
        }
        dropRole()
        rejoinAfterBackground = nil
        latest.realtime = nil
        latest.hostSpeedTest = nil
        latest.clientSpeedTest = nil
        latest.state.connection = .ended(reason: .left)
        // At once, so the caller finds the last state when `leave()` returns. Called from an
        // observer, the publish that is running delivers it right after that observer.
        if !isPublishing { publish() }
    }

    /// Lets go of the current role's runtime: its callbacks and what mirrors it into `state`.
    /// The transports keep themselves alive until their last messages are out.
    private func dropRole() {
        roleSubscriptions.removeAll()
        switch role {
        case .host(let host): host.onStepDown = nil
        case .client(let client): client.onTakeover = nil
        case nil: break
        }
        role = nil
    }

    /// The app went to the background, where it is suspended and cannot serve anyone. A host
    /// hands the game over; `completion` runs when its last messages have had time to go out.
    func enteredBackground(completion: @escaping () -> Void) {
        guard hasStarted, !hasLeft, case .host(let host) = role else {
            completion()
            return
        }
        // A second call before the app is back finds the host stopped and must not erase the
        // hand-over the first one stored.
        if let handover = host.handOverForBackground(completion: completion) {
            rejoinAfterBackground = handover
        }
    }

    /// The app is in the foreground again.
    func returnedToForeground() {
        guard hasStarted, !hasLeft else { return }
        if let handover = rejoinAfterBackground {
            rejoinAfterBackground = nil
            becomeClient(handover)
        } else if case .client(let client) = role {
            client.returnedToForeground()
        }
    }

    private func startRole() {
        switch role {
        case .host(let host): host.start()
        case .client(let client): client.start()
        case nil: break
        }
    }

    private func observeRole() {
        switch role {
        case .host(let host):
            host.onStepDown = { [weak self] handover in
                MainActor.assumeIsolated { self?.becomeClient(handover) }
            }
            mirror(host)
        case .client(let client):
            client.onTakeover = { [weak self] session in
                MainActor.assumeIsolated { self?.becomeHost(session) }
            }
            mirror(client)
        case nil:
            break
        }
    }

    /// The client role has stopped; the game continues here with the state from its snapshot.
    private func becomeHost(_ session: HostSession) {
        guard !hasLeft else { return }
        dropRole()
        role = .host(HostRuntime(transport: makeHostTransport(), session: session, games: config.games, log: log,
                                 makeProbeTransport: makeClientTransport))
        observeRole()
        startRole()
    }

    /// The host role has stopped; this device rejoins its own game on the newer host.
    private func becomeClient(_ handover: HostRuntime.StepDown) {
        guard !hasLeft else { return }
        dropRole()
        let session = ClientSession(deviceId: identity.deviceId, name: identity.playerName,
                                    playerId: handover.playerId, players: handover.players,
                                    hostEpoch: handover.hostEpoch)
        role = .client(ClientRuntime(gameCode: handover.gameCode, hostPeripheralId: nil,
                                     transport: makeClientTransport(), session: session,
                                     games: config.games, maxPlayers: config.maxPlayers, log: log))
        // Started before it is mirrored: a rejoin starts out as `reconnecting`, and the first
        // client state must say so rather than show the runtime's initial `connecting`. Nothing
        // the start sets off arrives before this call returns.
        startRole()
        observeRole()
    }

    // MARK: - State

    /// Mirrors the host role into `state`: everything it publishes, as it changes.
    private func mirror(_ host: HostRuntime) {
        roleSubscriptions.removeAll()
        latest.state = SessionState(role: .host, connection: .joined, gameCode: gameCode,
                                    playerId: host.hostId, hostId: host.hostId, hostEpoch: host.hostEpoch)
        mirror(host.$players, to: \.players)
        mirror(host.$phase, to: \.phase)
        mirror(host.$gameModuleId, to: \.gameId)
        mirror(host.$gameView, to: \.view)
        mirror(host.$gameNotice, to: \.gameNotice)
        mirror(host.$state.map(Optional.some), to: \.advertising)
        mirror(host.$pingStats.map(Optional.some), to: \.pingStats)
        mirror(host.$successors, to: \.successors)
        mirror(host.$removalCountdowns, to: \.offlineSecondsLeft)
        latest.hostSpeedTest = host.speedTest
        mirrorPingAll(host.$pingAllResult)
        latest.clientSpeedTest = nil
        mirrorDiagnostics(result: host.$testFrameText, received: host.$receivedTestFrame)
        mirrorRealtime(host.$realtimeLink)
    }

    /// Mirrors the client role into `state`: everything it publishes, as it changes.
    private func mirror(_ client: ClientRuntime) {
        roleSubscriptions.removeAll()
        latest.state = SessionState(role: .client, connection: .connecting, gameCode: gameCode,
                                    hostId: client.hostId, hostEpoch: client.hostEpoch)
        mirror(client.$status.map(Self.connection), to: \.connection)
        mirror(client.$playerId, to: \.playerId)
        mirror(client.$players, to: \.players)
        mirror(client.$hostId, to: \.hostId)
        mirror(client.$hostEpoch, to: \.hostEpoch)
        mirror(client.$phase, to: \.phase)
        mirror(client.$gameModuleId, to: \.gameId)
        mirror(client.$gameView, to: \.view)
        mirror(client.$notice, to: \.notice)
        mirror(client.$gameNotice, to: \.gameNotice)
        mirror(client.$holdsSnapshot.combineLatest(client.$takeoverRank).map(Self.migrationInfo), to: \.migrationInfo)
        mirror(client.$lastReconnectText, to: \.lastReconnect)
        latest.hostSpeedTest = nil
        // Ping-all is host-only; a client never has a result of its own.
        latest.pingAll = nil
        let speedTest = client.speedTest
        client.$isSpeedTestOpen
            .sink { [weak self] isOpen in
                MainActor.assumeIsolated {
                    self?.latest.clientSpeedTest = isOpen ? speedTest : nil
                    self?.schedulePublish()
                }
            }
            .store(in: &roleSubscriptions)
        mirrorDiagnostics(result: client.$testFrameText, received: client.$receivedTestFrame)
        mirrorRealtime(client.$realtimeLink)
    }

    /// Keeps one field of the state equal to what the runtime publishes.
    private func mirror<P: Publisher, Value: Equatable>(_ publisher: P, to field: WritableKeyPath<SessionState, Value>)
        where P.Output == Value, P.Failure == Never {
        publisher
            .sink { [weak self] value in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.latest.state[keyPath: field] = value
                    self.schedulePublish()
                }
            }
            .store(in: &roleSubscriptions)
    }

    private func mirrorDiagnostics(result: Published<String?>.Publisher, received: Published<String?>.Publisher) {
        result
            .sink { [weak self] text in
                MainActor.assumeIsolated {
                    self?.latest.testResult = text
                    self?.schedulePublish()
                }
            }
            .store(in: &roleSubscriptions)
        received
            .sink { [weak self] text in
                MainActor.assumeIsolated {
                    self?.latest.testJsonReceived = text
                    self?.schedulePublish()
                }
            }
            .store(in: &roleSubscriptions)
    }

    private func mirrorPingAll(_ result: Published<PingAllResult?>.Publisher) {
        result
            .sink { [weak self] result in
                MainActor.assumeIsolated {
                    self?.latest.pingAll = result
                    self?.schedulePublish()
                }
            }
            .store(in: &roleSubscriptions)
    }

    private func mirrorRealtime(_ link: Published<RealtimeFeed?>.Publisher) {
        link
            .sink { [weak self] link in
                MainActor.assumeIsolated {
                    self?.latest.realtime = link
                    self?.schedulePublish()
                }
            }
            .store(in: &roleSubscriptions)
    }

    /// The runtimes change their values in the middle of their own work. An app's observer must
    /// not run there: one that left the game at such a moment would have the runtime carry on
    /// with what it was doing after it had been told to stop. So what the app sees is published
    /// one main-queue turn later, when the runtime is done, and as one consistent value.
    private func schedulePublish() {
        guard !isPublishScheduled else { return }
        isPublishScheduled = true
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.publish() }
        }
    }

    /// Hands `latest` to the app. An observer may change `latest` again (by leaving): then the
    /// loop publishes that too, after the observer has returned.
    private func publish() {
        isPublishScheduled = false
        guard !isPublishing else { return }
        isPublishing = true
        defer { isPublishing = false }
        while !isPublished {
            if state != latest.state { state = latest.state }
            if realtime !== latest.realtime { realtime = latest.realtime }
            if diagnostics.testResult != latest.testResult { diagnostics.testResult = latest.testResult }
            if diagnostics.testJsonReceived != latest.testJsonReceived {
                diagnostics.testJsonReceived = latest.testJsonReceived
            }
            if diagnostics.hostSpeedTest !== latest.hostSpeedTest { diagnostics.hostSpeedTest = latest.hostSpeedTest }
            if diagnostics.clientSpeedTest !== latest.clientSpeedTest {
                diagnostics.clientSpeedTest = latest.clientSpeedTest
            }
            if diagnostics.pingAllResult != latest.pingAll { diagnostics.pingAllResult = latest.pingAll }
        }
    }

    /// Whether the app has seen everything in `latest`.
    private var isPublished: Bool {
        state == latest.state && realtime === latest.realtime
            && diagnostics.testResult == latest.testResult
            && diagnostics.testJsonReceived == latest.testJsonReceived
            && diagnostics.hostSpeedTest === latest.hostSpeedTest
            && diagnostics.clientSpeedTest === latest.clientSpeedTest
            && diagnostics.pingAllResult == latest.pingAll
    }

    private nonisolated static func connection(_ status: ClientRuntime.Status) -> ConnectionState {
        switch status {
        case .connecting: return .connecting
        case .joining: return .joining
        case .joined: return .joined
        case .rejected(let code): return .ended(reason: .rejected(code: code))
        case .reconnecting(let secondsLeft): return .reconnecting(secondsLeft: secondsLeft)
        case .hostLeft: return .ended(reason: .hostLeft)
        case .disconnected(let reason): return .ended(reason: .disconnected(detail: reason))
        }
    }

    private nonisolated static func migrationInfo(holdsSnapshot: Bool, takeoverRank: Int?) -> String? {
        let snapshot = holdsSnapshot ? "Snapshot: held" : "Snapshot: none"
        let rank = takeoverRank.map { "takeover rank #\($0)" } ?? "cannot take over"
        return "\(snapshot) · \(rank)"
    }
}
