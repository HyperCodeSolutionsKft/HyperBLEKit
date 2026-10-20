package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.diagnostics.ClientSpeedTestState
import io.hypercode.hyperblekit.diagnostics.HostSpeedTestState
import io.hypercode.hyperblekit.runtime.ClientRuntime
import io.hypercode.hyperblekit.runtime.HostRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The debug tools of one session, reached through [GameSession.diagnostics]. They are test
 * equipment for checking a Bluetooth link on real phones and never touch the game or its state:
 *
 * - **The JSON test frame**: one fixed JSON document of about 480 bytes, sent as a single message. It
 *   is larger than one Bluetooth packet, so it proves that a message is split, carried and put
 *   together again unchanged.
 * - **The speed test**: the host moves a dot and sends its position many times a second over the
 *   real-time frame lane; every client shows the dot, sends inputs back and both sides count what
 *   arrived, once a second.
 *
 * The object follows the session's role: after a role change the same flows describe the new
 * role. Call the functions on the main thread; the flows change on the main thread.
 */
public class SessionDiagnostics internal constructor(
    private val host: () -> HostRuntime?,
    private val client: () -> ClientRuntime?,
) {
    internal val testResultState = MutableStateFlow<String?>(null)
    internal val testJsonReceivedState = MutableStateFlow<String?>(null)
    internal val pingAllState = MutableStateFlow<PingAllResult?>(null)
    internal val clientLogsState = MutableStateFlow<ClientLogCollection?>(null)
    internal val hostSpeedTestState = MutableStateFlow<HostSpeedTestState?>(null)
    internal val clientSpeedTestState = MutableStateFlow<ClientSpeedTestState?>(null)
    internal val canRunSpeedTestState = MutableStateFlow(false)

    /**
     * The outcome of the last JSON test on this device, as one English line for the screen: what
     * was sent ("Test JSON: sent 480 B to 2 client(s)") or what arrived and whether it matched
     * the expected bytes. Null until there is one; it starts again from null after a role change.
     */
    public val testResult: StateFlow<String?> = testResultState.asStateFlow()

    /**
     * The content of the last test frame this device received, as indented JSON text for a
     * monospaced view. Null until one arrives.
     */
    public val testJsonReceived: StateFlow<String?> = testJsonReceivedState.asStateFlow()

    /**
     * What the host's speed-test panel shows: how many clients are linked, whether the test runs,
     * the dot and the statistics of the last second. Null while this device is a client and after
     * the session was left.
     */
    public val hostSpeedTest: StateFlow<HostSpeedTestState?> = hostSpeedTestState.asStateFlow()

    /**
     * What a client's speed-test view shows. It is set while the host's speed-test frames arrive
     * and becomes null two seconds after the last one, so a screen can show the view whenever this
     * is not null. Always null while this device is the host.
     */
    public val clientSpeedTest: StateFlow<ClientSpeedTestState?> = clientSpeedTestState.asStateFlow()

    /**
     * Whether [startSpeedTest] would start a test now: this device is the host, the session is in
     * the lobby and at least one client is linked. Show the start control while this is true (and
     * the stop control while [HostSpeedTestState.isRunning]).
     */
    public val canRunSpeedTest: StateFlow<Boolean> = canRunSpeedTestState.asStateFlow()

    /**
     * The last on-demand ping-all on this device ([pingAll]): one round trip per connected client.
     * Null until a ping-all has run; always null while this device is a client (it is host-only).
     */
    public val pingAllResult: StateFlow<PingAllResult?> = pingAllState.asStateFlow()

    /**
     * The debug logs collected from the clients ([requestClientLogs]): one entry per client that
     * uploaded anything, filling live as batches arrive. Null until a collection is requested, and
     * always null while this device is a client (collection is host-only).
     */
    public val clientLogs: StateFlow<ClientLogCollection?> = clientLogsState.asStateFlow()

    /**
     * Sends the JSON test frame: a host sends it to every joined client, a client sends it to the
     * host. The outcome appears in [testResult] on this device, and in [testResult] and
     * [testJsonReceived] on the receiving ones. A client that has not joined yet sends nothing
     * (only a log line says so); a host without clients reports that in [testResult].
     */
    public fun sendTestJson() {
        val host = host()
        if (host != null) host.sendTestJson() else client()?.sendTestJson()
    }

    /**
     * Pings every joined client at once and, after a short wait, reports each one's round trip in
     * [pingAllResult] (a client that did not answer gets a null time). Host only: on a client it
     * does nothing. Use it as a liveness and latency check after connecting clients.
     */
    public fun pingAll() {
        host()?.pingAll()
    }

    /**
     * Asks every joined client to upload its debug log to the host for end-of-run collection, and
     * publishes the logs in [clientLogs] as they arrive. Host only: on a client it does nothing.
     * A new call starts a fresh collection. Each client answers automatically — the operator only
     * taps this once on the host — so the whole run's logs can be exported from one device.
     */
    public fun requestClientLogs() {
        host()?.requestClientLogs()
    }

    /**
     * Starts the speed test. Host only, and only in the lobby; otherwise, and while a test is
     * already running, it does nothing. The test stops by itself when a game starts or hosting
     * ends.
     *
     * @param frameRate world frames sent to each client per second; one of
     *   [io.hypercode.hyperblekit.diagnostics.SpeedTestOptions.FRAME_RATES].
     * @param payloadSize size of each world frame's payload in bytes; one of
     *   [io.hypercode.hyperblekit.diagnostics.SpeedTestOptions.PAYLOAD_SIZES]. A frame that does not
     *   fit one Bluetooth packet on a client's link is not sent to that client, and the log says so.
     */
    public fun startSpeedTest(frameRate: Int, payloadSize: Int) {
        host()?.startSpeedTest(frameRate, payloadSize)
    }

    /**
     * Stops the speed test. Host only; it does nothing when no test is running.
     *
     * @param screenClosed false (the default) when the user pressed a stop control, true when the
     *   test stops because the screen that shows it went away. It only decides the reason written
     *   to the log ("stopped on the host" or "host screen closed").
     */
    public fun stopSpeedTest(screenClosed: Boolean = false) {
        host()?.stopSpeedTest(if (screenClosed) "host screen closed" else "stopped on the host")
    }

    /**
     * Reports a tap on the client's speed-test field: it is counted and sent to the host at once,
     * so the host's statistics show how quickly an input arrives. Client only, and only while
     * [clientSpeedTest] is not null; otherwise it does nothing.
     */
    public fun tapSpeedTest() {
        client()?.tapSpeedTest()
    }
}
