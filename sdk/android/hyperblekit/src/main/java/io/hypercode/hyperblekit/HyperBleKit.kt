package io.hypercode.hyperblekit

import android.content.Context
import android.os.SystemClock
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.session.HostSession
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.transport.GameHostTransport
import io.hypercode.hyperblekit.transport.GameScanner
import io.hypercode.hyperblekit.transport.ble.BleClientTransport
import io.hypercode.hyperblekit.transport.ble.BleGameScanner
import io.hypercode.hyperblekit.transport.ble.BleHostTransport
import io.hypercode.hyperblekit.transport.ble.BlePermissions
import io.hypercode.hyperblekit.transport.ble.BleUuids
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local multiplayer for phones that are near each other, over Bluetooth Low Energy only: no
 * internet, no Wi-Fi, no server. This class is the entry point of the SDK.
 *
 * One phone hosts a game and the others find and join it. The host runs the game's rules (a
 * [io.hypercode.hyperblekit.game.GameModule] the app provides) and sends every player their own
 * view of the game; the kit takes care of discovery, connections, keeping every phone's state the
 * same, reconnecting, and moving the game to another phone when the host disappears.
 *
 * Typical use:
 * 1. Create one kit for the app's lifetime (for example in a `ViewModel`) with the app's games.
 * 2. Ask the user for [requiredPermissions]. Until they are granted, nothing else works.
 * 3. Either call [host], or call [startDiscovery], show [discoveredGames] and call [join].
 * 4. Show the returned [GameSession]: follow its [GameSession.state] and call its functions.
 * 5. Call [onBackground] and [onForeground] from the activity's `onStop` and `onStart`.
 * 6. Call [leave] when the user leaves the game, and [close] when the kit is no longer needed.
 *
 * Threading: create the kit and call every function on the main thread. Every flow changes on the
 * main thread. Bluetooth callbacks are handled inside the kit and never reach the app directly.
 *
 * Create only one kit per process: a phone has one Bluetooth radio, and two kits would compete
 * for it.
 */
public class HyperBleKit private constructor(
    /** What the kit was created with. It does not change. */
    public val config: HyperBleKitConfig,
    wiring: Wiring,
) {
    /**
     * Creates a kit that uses the phone's Bluetooth radio. Nothing is switched on yet: Bluetooth
     * is first used by [host] or [startDiscovery].
     *
     * @param context any context; only its application context is kept.
     * @param config the app's games and, if it does not use the defaults, its Bluetooth identifiers.
     */
    public constructor(context: Context, config: HyperBleKitConfig) :
        this(config, bleWiring(context.applicationContext, config))

    /** A kit over other transports and its own clock: for running several kits in one process in tests. */
    internal constructor(
        config: HyperBleKitConfig,
        identity: LocalIdentity,
        hostTransport: GameHostTransport,
        scanner: GameScanner,
        clientTransportFactory: () -> GameClientTransport,
        scope: CoroutineScope,
        publishDispatcher: CoroutineDispatcher,
        elapsedMillis: () -> Long,
    ) : this(config, Wiring(identity, hostTransport, scanner, clientTransportFactory, scope, publishDispatcher, elapsedMillis))

    /** Everything a kit works with besides its configuration. */
    private class Wiring(
        val identity: LocalIdentity,
        val hostTransport: GameHostTransport,
        val scanner: GameScanner,
        /** One transport per client role, so one that is still flushing its `LEAVE` is left alone. */
        val clientTransportFactory: () -> GameClientTransport,
        /** Runs every timer of the runtimes; on the main thread, so a session is only touched from there. */
        val scope: CoroutineScope,
        /** Passes a runtime's changes on to a session's state; it always queues (see [GameSession]). */
        val publishDispatcher: CoroutineDispatcher,
        /** The monotonic clock every duration is measured with, in ms. */
        val elapsedMillis: () -> Long,
    )

    private val hostTransport = wiring.hostTransport
    private val scanner = wiring.scanner
    private val clientTransportFactory = wiring.clientTransportFactory
    private val scope = wiring.scope
    private val publishDispatcher = wiring.publishDispatcher
    private val elapsedMillis = wiring.elapsedMillis
    private var isClosed = false

    /**
     * Who this device is in a game: its permanent device ID and the player's name. Set
     * [LocalIdentity.storedPlayerName] before [host] or [join]: the name is read when a session
     * starts ([LocalIdentity.playerName] names the one case in which it is read again).
     */
    public val identity: LocalIdentity = wiring.identity

    /**
     * Whether this phone can host a game right now. It is true when the phone's Bluetooth chip
     * can advertise and Bluetooth is switched on, and false otherwise: with Bluetooth off, on a
     * phone without Bluetooth, on one of the Android phones that can only join, or if the system
     * refuses the question (which is also logged). It is asked from the system each time it is
     * read, so it follows the Bluetooth switch, but it is not a flow: read it again when needed.
     * A phone that cannot host can still join and play, but never takes a game over.
     */
    public val canHost: Boolean get() = hostTransport.canHost

    /**
     * Whether discovery is running, and why not if it failed (for example Bluetooth is off).
     * Changes after [startDiscovery], [stopDiscovery] and [join].
     */
    public val scanningState: StateFlow<ScanningState> = scanner.state

    /**
     * The games found nearby, strongest signal first. It is kept up to date while discovery runs;
     * a game that has not been seen for 10 seconds is dropped from the list. After [stopDiscovery]
     * the list keeps its last content and is no longer updated; [startDiscovery] empties it.
     */
    public val discoveredGames: StateFlow<List<DiscoveredGame>> = scanner.discoveredGames

    /**
     * How long discovery took to find its first game, in milliseconds from [startDiscovery]. Null
     * until a game appears, and again after the next [startDiscovery]. Useful for measuring only.
     */
    public val timeToFirstResult: StateFlow<Long?> = scanner.timeToFirstResultMillis

    private val _session = MutableStateFlow<GameSession?>(null)

    /**
     * The game this device is in, or null while it is in none. Set by [host] and [join], cleared
     * by [leave]. A session that ended by itself ([ConnectionState.Ended]) stays here, so its last
     * state can be shown, until [leave] is called.
     */
    public val session: StateFlow<GameSession?> = _session.asStateFlow()

    init {
        HyperBleLog.capacity = config.logCapacity
    }

    /**
     * Starts looking for games nearby; the results appear in [discoveredGames]. Calling it again
     * restarts the search with an empty list. Scanning uses the radio and the battery: call
     * [stopDiscovery] when the list is no longer shown.
     *
     * It never throws. If the search cannot start — Bluetooth is off, the phone has no Bluetooth
     * scanner, or the [requiredPermissions] are not all granted — [scanningState] becomes
     * [ScanningState.Failed] with the reason, and the log has a line about it. Call it again once
     * the cause is gone, for example after the user granted the permissions.
     */
    public fun startDiscovery() {
        if (isClosed) return
        HyperBleLog.event("looking for games")
        scanner.startScan()
    }

    /** Stops looking for games. [discoveredGames] keeps its last content. Does nothing if discovery is not running. */
    public fun stopDiscovery() {
        scanner.stopScan()
    }

    /**
     * Hosts a new game under a fresh, random game code and returns its session. This device is
     * player "p1" and the game starts in the lobby, waiting for others to join.
     *
     * If this phone cannot host (see [canHost]) the session is still returned, and its
     * [SessionState.advertising] says why nobody can find it.
     *
     * A session that is still open is left first, as with [leave]. Do not host again in the same
     * moment after leaving a hosted game: that game's last messages get up to 2 seconds to go
     * out, and a new game started at once can cut them off. Best is not to offer hosting while
     * [session] is not null.
     *
     * @return the new session; the same object [session] now holds.
     * @throws IllegalStateException if the kit was closed.
     */
    public fun host(): GameSession {
        check(!isClosed) { "This HyperBleKit was closed" }
        leave()
        val hostSession = HostSession.create(
            GameCode.generate(), identity.playerName, identity.deviceId, config.maxPlayers,
            elapsedMillis,
        )
        val session = newSession(hostSession.gameId, SessionRole.HOST)
        _session.value = session
        session.startHosting(hostSession)
        return session
    }

    /**
     * Joins a game from [discoveredGames] and returns its session. Discovery is stopped first,
     * because scanning and connecting compete for the radio.
     *
     * Joining takes a few seconds; follow [SessionState.connection], which goes from
     * [ConnectionState.Connecting] through [ConnectionState.Joining] to [ConnectionState.Joined],
     * or to [ConnectionState.Ended] if the connection fails or the host refuses.
     *
     * A session that is still open is left first, as with [leave].
     *
     * @param game an entry of [discoveredGames]. An entry from an old list may no longer be
     *   reachable; the session then ends with [EndReason.Disconnected].
     * @return the new session; the same object [session] now holds.
     * @throws IllegalStateException if the kit was closed.
     */
    public fun join(game: DiscoveredGame): GameSession {
        check(!isClosed) { "This HyperBleKit was closed" }
        leave()
        scanner.stopScan()
        val session = newSession(game.gameCode, SessionRole.CLIENT)
        _session.value = session
        session.startClient(game.id, steppedDown = null)
        return session
    }

    /**
     * Leaves the current session and sets [session] to null. Does nothing if there is none.
     *
     * A host hands the game to the first client that can host, which continues it; if there is no
     * such client the game ends for everyone. A client tells the host it is going, then
     * disconnects. The messages for this are sent in the background for up to 2 seconds after the
     * call returns. The session object is finished: its last state is [ConnectionState.Ended] with
     * [EndReason.Left].
     */
    public fun leave() {
        _session.value?.leave()
        _session.value = null
    }

    /**
     * Tells the kit that the app is no longer in front; call it from the activity's `onStop`,
     * except when the activity only stops for a configuration change.
     *
     * Android may freeze an app in the background, and a frozen host would stall the game for
     * everyone. So a host hands the game to the first client that can host and stops hosting;
     * with no such client it keeps hosting as well as it can. A client does nothing here.
     * Calling it again before [onForeground] changes nothing.
     */
    public fun onBackground() {
        _session.value?.onBackground()
    }

    /**
     * Tells the kit that the app is in front again; call it from the activity's `onStart`.
     *
     * A host that handed its game over in [onBackground] rejoins that game as a client, in the
     * same [GameSession]. A client checks at once everything it could not check while frozen:
     * whether the host went silent, and whether the time for reconnecting ran out.
     */
    public fun onForeground() {
        _session.value?.onForeground()
    }

    /**
     * Finishes the kit: leaves the current session as [leave] does, stops discovery, stops
     * hosting and cancels every timer the kit started. Call it when the object that owns the kit
     * goes away (for example in `ViewModel.onCleared`).
     *
     * A closed kit is not used again: [host] and [join] throw, the other functions do nothing.
     * Calling `close` again does nothing.
     */
    public fun close() {
        if (isClosed) return
        isClosed = true
        leave()
        scanner.stopScan()
        scope.cancel()
    }

    private fun newSession(gameCode: String, role: SessionRole) = GameSession(
        gameCode, role, config, identity, hostTransport, clientTransportFactory, scope, publishDispatcher,
        elapsedMillis,
    )

    /** What an app can ask the kit before it has one: the permissions to request, and fixed numbers of the SDK. */
    public companion object {
        /**
         * The runtime permissions the kit needs on this Android version, as `Manifest.permission`
         * names: on Android 12 and later `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and
         * `BLUETOOTH_ADVERTISE`; on Android 8 to 11 `ACCESS_FINE_LOCATION`, which Bluetooth
         * scanning requires there. Request them before using the kit. The app must also declare
         * them in its manifest (and, for Android 8 to 11, `BLUETOOTH` and `BLUETOOTH_ADMIN`); the
         * library's manifest declares none.
         *
         * Without them nothing crashes and nothing works: [startDiscovery] ends in
         * [ScanningState.Failed], a hosted session reports [AdvertisingState.Failed] in
         * [SessionState.advertising] and cannot be found, a join ends with
         * [EndReason.Disconnected], and [canHost] is false if the system refuses to answer. Each
         * case writes a line to the log. Granting the permissions later is enough; the kit does
         * not have to be created again.
         */
        public val requiredPermissions: List<String> get() = BlePermissions.required()

        /**
         * How many clients the host keeps ready to take the game over: the first this many of
         * [SessionState.successors] regularly receive a full copy of the game. Always 2.
         */
        public val snapshotSuccessors: Int get() = GameConstants.SNAPSHOT_SUCCESSORS

        private fun bleWiring(appContext: Context, config: HyperBleKitConfig): Wiring {
            val uuids = BleUuids(config)
            return Wiring(
                identity = LocalIdentity(appContext),
                hostTransport = BleHostTransport(appContext, uuids),
                scanner = BleGameScanner(appContext, uuids, config.localNamePrefix),
                clientTransportFactory = { BleClientTransport(appContext, uuids, config.localNamePrefix) },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                // Not `.immediate`: this one posts to the main thread's queue even from the main thread.
                publishDispatcher = Dispatchers.Main,
                elapsedMillis = SystemClock::elapsedRealtime,
            )
        }
    }
}
