import Combine
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The entry point of the SDK: local multiplayer for nearby phones over Bluetooth Low Energy,
/// without internet, between iPhones and Android phones.
///
/// An app creates one `HyperBleKit` with its `HyperBleKitConfig` and keeps it for as long as it
/// offers multiplayer. It looks for nearby games with `startDiscovery()` and gets a
/// `GameSession` from `host()` or `join(_:)`. The SDK does discovery, connection, reliable
/// state sync, the real-time frame lane, reconnecting and host migration; the app brings its
/// game modules and its screens.
///
/// Use it on the main thread only. Everything it publishes changes on the main thread.
///
/// The app's Info.plist needs `NSBluetoothAlwaysUsageDescription`; iOS asks the user for
/// Bluetooth access the first time the app scans or hosts. On the iOS Simulator, which has no
/// Bluetooth, discovery finds two made-up games, hosting never gets a client and joining fails.
@MainActor
public final class HyperBleKit: ObservableObject {
    /// How many of a host's `SessionState.successors` hold a copy of the game and can take it
    /// over: the first 2.
    public static let snapshotSuccessors = ProtocolConstants.snapshotHolders

    /// The configuration this kit was created with.
    public let config: HyperBleKitConfig
    /// Who this device is in a game: its stable device ID and the player's name.
    public let identity: LocalIdentity
    /// Whether this device can host a game, i.e. advertise and serve other phones. Always true
    /// on iOS; some Android phones cannot.
    public let canHost = true

    /// Whether the scan for nearby games is running, or why it cannot run.
    @Published public private(set) var scanningState: ScanningState = .idle
    /// The games in range, in the order they were first found, so a list does not jump around
    /// as signal strengths change. A game that has not been seen for 5 seconds is dropped.
    /// Empty while discovery is stopped.
    @Published public private(set) var discoveredGames: [DiscoveredGame] = []
    /// Milliseconds from the last `startDiscovery()` to the first game found; nil until a game
    /// was found. A measurement for a debug screen.
    @Published public private(set) var timeToFirstResult: Int?
    /// The game this device is in; nil while it is in none. Set by `host()` and `join(_:)`,
    /// cleared by `leave()`.
    @Published public private(set) var session: GameSession?

    private let makeHostTransport: () -> GameHostTransport
    private let makeClientTransport: () -> GameClientTransport
    private let discovery: Discovery
    private var cancellables = Set<AnyCancellable>()
    private var isClosed = false

    /// Creates the kit. Nothing touches Bluetooth, and the user is not asked for Bluetooth
    /// access, until discovery or a session starts.
    ///
    /// - Parameter config: the games this app can play and its Bluetooth identifiers.
    public convenience init(config: HyperBleKitConfig) {
        let layout = config.layout
        self.init(config: config, identity: LocalIdentity(),
                  scanner: TransportFactory.makeScanner(layout: layout),
                  makeHostTransport: { TransportFactory.makeHostTransport(layout: layout) },
                  makeClientTransport: { TransportFactory.makeClientTransport(layout: layout) })
    }

    init(config: HyperBleKitConfig, identity: LocalIdentity, scanner: GameScanning,
         makeHostTransport: @escaping () -> GameHostTransport,
         makeClientTransport: @escaping () -> GameClientTransport) {
        self.config = config
        self.identity = identity
        self.makeHostTransport = makeHostTransport
        self.makeClientTransport = makeClientTransport
        discovery = Discovery(scanner: scanner)
        HyperBleLog.shared.capacity = config.logCapacity
        discovery.$state
            .sink { [weak self] state in MainActor.assumeIsolated { self?.scanningState = state } }
            .store(in: &cancellables)
        discovery.$games
            .sink { [weak self] games in MainActor.assumeIsolated { self?.gamesChanged(games) } }
            .store(in: &cancellables)
    }

    // MARK: - Discovery

    /// Starts looking for nearby games of this app (same `HyperBleKitConfig.serviceUUID`) and
    /// keeps `discoveredGames` up to date until `stopDiscovery()`. It may be called before
    /// Bluetooth is on: `scanningState` says why the scan is not running, and the scan starts
    /// by itself once Bluetooth is available. Calling it again restarts the measurement in
    /// `timeToFirstResult`.
    ///
    /// Scanning costs battery: stop it when the list is not on screen. Does nothing after
    /// `close()`.
    public func startDiscovery() {
        guard !isClosed else { return }
        timeToFirstResult = nil
        discovery.start()
    }

    /// Stops looking for games and empties `discoveredGames`. Does nothing when discovery is
    /// not running. A session is not affected.
    public func stopDiscovery() {
        discovery.stop()
    }

    /// The seconds from the last `startDiscovery()` to the first sighting of this game. nil
    /// before discovery was ever started. A measurement for a debug screen.
    ///
    /// - Parameter game: one of `discoveredGames`.
    public func discoveryTime(of game: DiscoveredGame) -> TimeInterval? {
        discovery.discoveryTime(of: game)
    }

    private func gamesChanged(_ games: [DiscoveredGame]) {
        discoveredGames = games
        if timeToFirstResult == nil, let first = games.first, let time = discovery.discoveryTime(of: first) {
            timeToFirstResult = Int((time * 1000).rounded())
        }
    }

    // MARK: - Session

    /// Creates a new game with a random 4-character game code and starts hosting it under the
    /// name in `identity`. The returned session is also in `session`; its role is `.host` from
    /// the first state on.
    ///
    /// A session that is still open is left first. Leaving a hosted game gives its last
    /// messages up to 2 seconds to go out, and hosting again at once can cut them off: offer
    /// "Host" only while `session` is nil. Hosting needs the app in the foreground; see
    /// `onBackground()`. Must not be called after `close()`.
    @discardableResult
    public func host() -> GameSession {
        open(.host)
    }

    /// Joins a game under the name in `identity`. The returned session is also in `session`;
    /// its `state.connection` goes from `connecting` over `joining` to `joined`, or to `ended`
    /// when the host cannot be reached or refuses.
    ///
    /// A session that is still open is left first; offer the list only while `session` is nil.
    /// Must not be called after `close()`.
    ///
    /// - Parameter game: one of `discoveredGames`, as fresh as possible: a game is only
    ///   reachable while its host is in range.
    @discardableResult
    public func join(_ game: DiscoveredGame) -> GameSession {
        open(.join(game))
    }

    /// Leaves the game this device is in and sets `session` to nil. A host hands the game to
    /// its first successor, or ends it for everyone when there is none; a client tells the host
    /// that it is going and disconnects. The last messages get up to 2 seconds to go out in
    /// the background of the call, which itself returns at once.
    ///
    /// The session object is dead afterwards: its last state is `.ended(reason: .left)`.
    /// Does nothing while `session` is nil.
    public func leave() {
        session?.leave()
        if session != nil { session = nil }
    }

    /// Shuts the kit down for good: leaves the session as `leave()` does, stops discovery and
    /// stops publishing. Call it when the app is done with multiplayer. A kit that was closed
    /// is not used again; create a new one instead: `host()` and `join(_:)` stop the program
    /// on a closed kit, and the other methods do nothing. Calling `close()` twice is harmless.
    public func close() {
        guard !isClosed else { return }
        leave()
        discovery.stop()
        cancellables.removeAll()
        scanningState = .idle
        discoveredGames = []
        isClosed = true
    }

    /// Tells the SDK that the app went to the background. Call it when the scene phase becomes
    /// `.background`.
    ///
    /// iOS suspends an app in the background, and a suspended host cannot serve anyone. So a
    /// host hands the game over to its first successor here, and rejoins it as a client in
    /// `onForeground()`; with no successor it keeps the host role and the other players
    /// reconnect once the app is back. On iOS the SDK asks the system for the few seconds of
    /// background time the hand-over needs. A client does nothing here.
    ///
    /// It may be called more than once before `onForeground()`. Does nothing without a session.
    public func onBackground() {
        guard let session else { return }
        #if canImport(UIKit) && !os(watchOS)
        // Background time, so a host can finish handing the game over before the app is suspended.
        var task = UIBackgroundTaskIdentifier.invalid
        let end = {
            guard task != .invalid else { return }
            UIApplication.shared.endBackgroundTask(task)
            task = .invalid
        }
        task = UIApplication.shared.beginBackgroundTask(expirationHandler: end)
        session.enteredBackground(completion: end)
        #else
        session.enteredBackground(completion: {})
        #endif
    }

    /// Tells the SDK that the app is in the foreground again. Call it when the scene phase
    /// becomes `.active`.
    ///
    /// A host that handed its game over in `onBackground()` rejoins it as a client. A client
    /// checks at once whether the host went quiet or the reconnect window ran out while the
    /// app was suspended, because no timer ran in that time. Does nothing without a session.
    public func onForeground() {
        session?.returnedToForeground()
    }

    private func open(_ start: GameSession.Start) -> GameSession {
        precondition(!isClosed, "HyperBleKit.host() or join(_:) was called after close()")
        leave()
        let session = GameSession(start: start, config: config, identity: identity,
                                  makeHostTransport: makeHostTransport, makeClientTransport: makeClientTransport)
        self.session = session
        session.start()
        return session
    }
}
