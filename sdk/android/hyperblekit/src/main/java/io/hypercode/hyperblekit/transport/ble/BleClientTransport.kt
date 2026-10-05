package io.hypercode.hyperblekit.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.hypercode.hyperblekit.GameCode
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.ChunkException
import io.hypercode.hyperblekit.transport.Chunker
import io.hypercode.hyperblekit.transport.ClientTransportEvent
import io.hypercode.hyperblekit.transport.FrameFilter
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.FrameSlot
import io.hypercode.hyperblekit.transport.GameClientTransport
import io.hypercode.hyperblekit.transport.RealtimeFrames
import io.hypercode.hyperblekit.transport.ReassembledMessage
import io.hypercode.hyperblekit.transport.Reassembler
import io.hypercode.hyperblekit.transport.ReceivedFrame
import java.util.UUID

/**
 * Client side over BLE: one [BluetoothGatt] connection to one host.
 *
 * Setup follows wire specification "Connection Lifecycle" strictly in sequence — each step is started from
 * the callback of the previous one, so there is never more than one GATT operation in flight:
 *
 * connect → `requestMtu(247)` → `onMtuChanged` → `discoverServices` → read `GameInfo` → write the
 * CCCD of `HostToClient` → `onDescriptorWrite` → `requestConnectionPriority(HIGH)` →
 * [ClientTransportEvent.Ready]. Only then can messages be sent. The priority request comes last
 * so the connection-parameter update never overlaps discovery.
 *
 * After that the only operations are writes with response: chunks wait in a queue and the next
 * one goes out from `onCharacteristicWrite` of the previous one. A real-time frame ([sendFrame])
 * waits in a single slot instead, behind the write in flight and before the next chunk; a newer
 * frame replaces it. Frames are not logged one by one.
 *
 * For a reconnect, [connectByGameCode] puts a search in front of that: a scan filtered by the
 * service UUID, and the first result that advertises the wanted game code is connected to. The
 * scan result's own [BluetoothDevice] is used, never a remembered address — an iOS host rotates
 * its address and an Android host gets a new one when it restarts advertising. That scan is kept
 * running across failed attempts (Android throttles apps that start more than 5 scans in 30 s)
 * and is stopped when the link is ready or by [disconnect].
 *
 * Threading: GATT callbacks arrive on a binder thread; values are copied there and everything
 * else continues on the main thread. Public methods must be called on the main thread.
 *
 * Permissions (BLUETOOTH_CONNECT on 12+) are verified by the app before any call here, hence the
 * class-level [SuppressLint]; calls are still guarded against a revoked permission.
 */
@SuppressLint("MissingPermission")
internal class BleClientTransport(
    context: Context,
    private val uuids: BleUuids,
    private val localNamePrefix: String,
) : GameClientTransport {

    private enum class Phase { IDLE, SEARCHING, CONNECTING, NEGOTIATING_MTU, DISCOVERING, READING_GAME_INFO, SUBSCRIBING, READY }

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())

    private var listener: ((ClientTransportEvent) -> Unit)? = null
    private var phase = Phase.IDLE
    private var gatt: BluetoothGatt? = null
    private var mtu = BleConstants.DEFAULT_MTU
    private var clientToHost: BluetoothGattCharacteristic? = null
    private var hostToClient: BluetoothGattCharacteristic? = null
    private var chunker = Chunker()
    private var reassembler = Reassembler()

    /**
     * Chunks not yet confirmed by the host, oldest first. The first one is in flight when
     * [isWriting] and not [isFrameInFlight].
     */
    private val outgoing = ArrayDeque<ByteArray>()
    private var isWriting = false

    /** The newest real-time frame not written yet; it goes before [outgoing]. */
    private var frames = FrameSlot()
    private var frameFilter = FrameFilter()

    /** The write in flight is a frame, not the first chunk of [outgoing]. */
    private var isFrameInFlight = false

    /** A frame write was refused or failed; logged once per link, not per frame. */
    private var frameFailureLogged = false

    /** [disconnect] was called: flush the queue, then close. */
    private var isClosing = false

    /** The game code [connectByGameCode] is looking for while in [Phase.SEARCHING]. */
    private var searchCode: String? = null

    /** The running search scan; null when none is running. */
    private var searchScanner: BluetoothLeScanner? = null
    private var searchCallback: ScanCallback? = null

    /** Addresses [skipCurrentHost] ruled out for the search that is running. */
    private val skippedAddresses = HashSet<String>()

    /** Which attempt at the current device this is; see [retryAfterGattError]. */
    private var connectAttempt = 1

    /** The pending [GATT_ERROR][BleConstants.GATT_ERROR] retry; null when none is scheduled. */
    private var gattErrorRetry: Runnable? = null

    private val connectTimeout = Runnable {
        finish("Connecting timed out after ${BleConstants.CONNECT_TIMEOUT_MILLIS / MILLIS_PER_SECOND} s")
    }
    private val flushTimeout = Runnable { finish(null) }

    /** When (`elapsedRealtime`) the armed timeout is due; null while none is armed. */
    private var connectDeadlineMillis: Long? = null
    private var flushDeadlineMillis: Long? = null

    private fun armConnectTimeout() {
        connectDeadlineMillis = SystemClock.elapsedRealtime() + BleConstants.CONNECT_TIMEOUT_MILLIS
        handler.postDelayed(connectTimeout, BleConstants.CONNECT_TIMEOUT_MILLIS)
    }

    private fun cancelConnectTimeout() {
        connectDeadlineMillis = null
        handler.removeCallbacks(connectTimeout)
    }

    /**
     * Handler delays stand still while the app is frozen, so the deadlines are also kept in real
     * elapsed time and enforced here.
     */
    override fun checkTimeouts() {
        val now = SystemClock.elapsedRealtime()
        val connectDeadline = connectDeadlineMillis
        val flushDeadline = flushDeadlineMillis
        if (connectDeadline != null && now >= connectDeadline) {
            cancelConnectTimeout()
            connectTimeout.run()
        } else if (flushDeadline != null && now >= flushDeadline) {
            handler.removeCallbacks(flushTimeout)
            flushTimeout.run()
        }
    }

    override fun setListener(listener: ((ClientTransportEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(gameId: String) {
        teardown()
        stopSearchScan()
        phase = Phase.CONNECTING

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            finish("Connect failed: Bluetooth is off")
            return
        }
        val device = BleDeviceCache.get(gameId) ?: try {
            HyperBleLog.event(
                "$gameId is not in the scan list, connecting by address only (address type unknown)",
                LogLevel.WARN,
            )
            adapter.getRemoteDevice(gameId)
        } catch (_: IllegalArgumentException) {
            finish("Connect failed: $gameId is not a Bluetooth address")
            return
        }

        connectTo(device)
    }

    override fun connectByGameCode(gameCode: String) {
        teardown()
        phase = Phase.SEARCHING
        searchCode = gameCode

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            // A scan does not survive Bluetooth going off: the next attempt must start a new one.
            searchScanner = null
            searchCallback = null
            finish("Connect failed: Bluetooth is off")
            return
        }
        if (searchCallback != null) {
            HyperBleLog.event("searching for game $gameCode (scan already running)")
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            finish("Connect failed: no BLE scanner available")
            return
        }
        val callback = SearchCallback()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(uuids.serviceParcel).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (guarded("startScan") { leScanner.startScan(filters, settings, callback) } == null) {
            finish("Connect failed: could not start scanning")
            return
        }
        searchScanner = leScanner
        searchCallback = callback
        HyperBleLog.event("searching for game $gameCode (scan started)")
    }

    /** A scan result while searching: connect if it advertises the wanted game code. */
    private fun searchResult(result: ScanResult) {
        if (phase != Phase.SEARCHING) return
        val record = result.scanRecord
        val code = GameCode.fromLocalName(record?.deviceName, localNamePrefix)
            ?: GameCode.fromManufacturerData(record?.getManufacturerSpecificData(uuids.manufacturerId))
        if (code == null || code != searchCode || result.device.address in skippedAddresses) return
        HyperBleLog.event("found game $code at ${result.device.address} rssi=${result.rssi}dBm")
        phase = Phase.CONNECTING
        listener?.invoke(ClientTransportEvent.HostFound(result.device.address))
        // The listener may have disconnected.
        if (phase == Phase.CONNECTING) connectTo(result.device)
    }

    override fun skipCurrentHost() {
        val address = gatt?.device?.address
        if (phase == Phase.IDLE || phase == Phase.SEARCHING) return
        if (searchCallback == null) {
            // Not found by a search, so there is nothing else to try.
            finish("Connect failed: not the wanted host")
            return
        }
        if (address != null) skippedAddresses += address
        HyperBleLog.event("skipping $address for the rest of this search, still scanning", LogLevel.WARN)
        teardown()
        phase = Phase.SEARCHING
    }

    private fun searchFailed(errorCode: Int) {
        searchScanner = null
        searchCallback = null
        if (phase == Phase.SEARCHING) {
            finish("Connect failed: scan failed (error $errorCode)")
        } else {
            HyperBleLog.event("search scan failed (error $errorCode)", LogLevel.WARN)
        }
    }

    private fun stopSearchScan() {
        val scanner = searchScanner
        val callback = searchCallback
        searchScanner = null
        searchCallback = null
        searchCode = null
        skippedAddresses.clear()
        if (scanner != null && callback != null) {
            runCatching { scanner.stopScan(callback) }
            HyperBleLog.event("search scan stopped")
        }
    }

    /** Opens the GATT connection; [phase] is already [Phase.CONNECTING]. */
    private fun connectTo(device: BluetoothDevice, attempt: Int = 1) {
        connectAttempt = attempt
        HyperBleLog.event(
            if (attempt == 1) "connecting to ${device.address}"
            else "connecting to ${device.address} (attempt $attempt)"
        )
        // autoConnect=false: a direct connection attempt, which fails fast instead of waiting forever.
        gatt = guarded("connectGatt") { openGatt(device) }
        if (gatt == null) {
            finish("Connect failed: could not open a GATT connection")
            return
        }
        armConnectTimeout()
    }

    /**
     * The settings-based `connectGatt` that replaces this overload only exists from API 37, so
     * the one available since minSdk is used on every version.
     */
    @Suppress("DEPRECATION")
    private fun openGatt(device: BluetoothDevice): BluetoothGatt? =
        device.connectGatt(appContext, false, GattCallback(), BluetoothDevice.TRANSPORT_LE)

    override fun send(message: ByteArray): Int {
        if (phase != Phase.READY || isClosing) {
            HyperBleLog.event("send skipped: not connected", LogLevel.WARN)
            return 0
        }
        return try {
            val chunks = chunker.split(message, BleConstants.maxChunkLength(mtu))
            outgoing.addAll(chunks)
            writeNext()
            chunks.size
        } catch (e: ChunkException) {
            HyperBleLog.event("send failed: ${e.message}", LogLevel.ERROR)
            0
        }
    }

    /** Not logged: the caller counts what it sends, frames are too many for a line each. */
    override fun sendFrame(payload: ByteArray): FrameSendResult {
        if (phase != Phase.READY || isClosing) return FrameSendResult.FAILED
        // A frame is one packet, chunk header included.
        val maxPayload = RealtimeFrames.maxPayload(BleConstants.maxChunkLength(mtu))
        if (payload.size > maxPayload) return FrameSendResult.FAILED
        val replaced = frames.put(payload)
        writeNext()
        return if (replaced) FrameSendResult.REPLACED else FrameSendResult.QUEUED
    }

    override val staleFramesDropped: Int get() = frameFilter.staleCount

    override fun disconnect() {
        stopSearchScan()
        if (phase == Phase.IDLE || isClosing) return
        if (outgoing.isEmpty()) {
            finish(null)
        } else {
            isClosing = true
            cancelConnectTimeout()
            flushDeadlineMillis = SystemClock.elapsedRealtime() + BleConstants.DISCONNECT_FLUSH_TIMEOUT_MILLIS
            handler.postDelayed(flushTimeout, BleConstants.DISCONNECT_FLUSH_TIMEOUT_MILLIS)
        }
    }

    /** Ends the connection, whatever state it is in, closes the [BluetoothGatt] and reports it once. */
    private fun finish(reason: String?) {
        if (phase == Phase.IDLE) return
        teardown()
        HyperBleLog.event(reason ?: "disconnected", if (reason == null) LogLevel.INFO else LogLevel.ERROR)
        listener?.invoke(ClientTransportEvent.Disconnected(reason))
    }

    /**
     * Closes the [BluetoothGatt] and resets everything that belongs to one connection, without
     * reporting anything. A search scan is left alone: it outlives failed attempts.
     */
    private fun teardown() {
        phase = Phase.IDLE
        cancelConnectTimeout()
        gattErrorRetry?.let(handler::removeCallbacks)
        gattErrorRetry = null
        flushDeadlineMillis = null
        handler.removeCallbacks(flushTimeout)
        val closing = gatt
        gatt = null
        if (closing != null) {
            guarded("close GATT") {
                closing.disconnect()
                closing.close()
            }
        }
        clientToHost = null
        hostToClient = null
        outgoing.clear()
        isWriting = false
        isClosing = false
        mtu = BleConstants.DEFAULT_MTU
        chunker = Chunker()
        reassembler = Reassembler()
        // Every link starts after a teardown, so frame counters start over with it.
        frames = FrameSlot()
        frameFilter = FrameFilter()
        isFrameInFlight = false
        frameFailureLogged = false
    }

    /**
     * Runs a Bluetooth call. A revoked permission or an argument the stack rejects is logged and
     * reported as a failed call (null) instead of crashing.
     */
    private inline fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            HyperBleLog.event("$what: Bluetooth permission missing (${e.message})", LogLevel.ERROR)
            null
        } catch (e: IllegalArgumentException) {
            HyperBleLog.event("$what: rejected by the Bluetooth stack (${e.message})", LogLevel.ERROR)
            null
        }

    // --- Setup steps (main thread) ---

    private fun connectionStateChanged(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            HyperBleLog.event("connected status=$status, requesting MTU ${BleConstants.REQUESTED_MTU}")
            phase = Phase.NEGOTIATING_MTU
            if (guarded("requestMtu") { gatt.requestMtu(BleConstants.REQUESTED_MTU) } != true) {
                finish("Connect failed: MTU request was refused")
            }
            return
        }
        HyperBleLog.event("connection state=$newState status=$status", LogLevel.WARN)
        if (newState == BluetoothProfile.STATE_CONNECTING || newState == BluetoothProfile.STATE_DISCONNECTING) return
        if (status == BleConstants.GATT_ERROR && phase == Phase.CONNECTING && !isClosing &&
            connectAttempt <= BleConstants.GATT_ERROR_RETRIES
        ) {
            retryAfterGattError(gatt.device)
            return
        }
        finish(
            when {
                isClosing -> null
                phase == Phase.READY -> "Connection lost (status $status)"
                status == BleConstants.GATT_ERROR ->
                    "Connect failed (status $status, attempt $connectAttempt): host out of range or busy, try again"
                else -> "Connect failed (status $status)"
            }
        )
    }

    /**
     * Android's catch-all 133 before the link came up is mostly transient (stack busy, the host
     * just restarted advertising), so the same device is tried again with a fresh [BluetoothGatt]
     * after a short pause. Only attempts that never connected are retried, so the listener has
     * seen nothing beyond [ClientTransportEvent.HostFound] and is not told about the retry.
     */
    private fun retryAfterGattError(device: BluetoothDevice) {
        val next = connectAttempt + 1
        HyperBleLog.event(
            "GATT_ERROR on attempt $connectAttempt, retrying in ${BleConstants.GATT_ERROR_RETRY_DELAY_MILLIS} ms",
            LogLevel.WARN,
        )
        teardown()
        phase = Phase.CONNECTING
        val retry = Runnable {
            gattErrorRetry = null
            if (phase == Phase.CONNECTING && gatt == null) connectTo(device, next)
        }
        gattErrorRetry = retry
        handler.postDelayed(retry, BleConstants.GATT_ERROR_RETRY_DELAY_MILLIS)
    }

    private fun mtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mtu = newMtu
            HyperBleLog.event("MTU = $newMtu")
        } else {
            HyperBleLog.event("MTU request failed status=$status, staying at $mtu", LogLevel.WARN)
        }
        if (phase != Phase.NEGOTIATING_MTU) return

        phase = Phase.DISCOVERING
        if (guarded("discoverServices") { gatt.discoverServices() } != true) {
            finish("Connect failed: service discovery could not start")
        }
    }

    private fun servicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (phase != Phase.DISCOVERING) return
        val service = gatt.getService(uuids.service)
        if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
            finish("Game service not found (status $status)")
            return
        }
        clientToHost = service.getCharacteristic(uuids.clientToHost)
        hostToClient = service.getCharacteristic(uuids.hostToClient)
        if (clientToHost == null || hostToClient == null) {
            finish("Game characteristics not found: not offered by host")
            return
        }
        HyperBleLog.event("services discovered")

        val gameInfo = service.getCharacteristic(uuids.gameInfo)
        if (gameInfo == null) {
            HyperBleLog.event("host has no GameInfo characteristic", LogLevel.WARN)
            subscribe(gatt)
            return
        }
        // Subscribing continues once the value arrives in onCharacteristicRead.
        phase = Phase.READING_GAME_INFO
        if (guarded("readCharacteristic") { gatt.readCharacteristic(gameInfo) } != true) {
            HyperBleLog.event("GameInfo read could not start", LogLevel.WARN)
            subscribe(gatt)
        }
    }

    private fun characteristicRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        if (uuid != uuids.gameInfo || phase != Phase.READING_GAME_INFO) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            listener?.invoke(ClientTransportEvent.GameInfoRead(value))
        } else {
            HyperBleLog.event("GameInfo read failed status=$status", LogLevel.WARN)
        }
        // The listener may have disconnected.
        if (phase == Phase.READING_GAME_INFO) subscribe(gatt)
    }

    /** Enables notifications locally, then on the host by writing the CCCD. */
    private fun subscribe(gatt: BluetoothGatt) {
        val characteristic = hostToClient ?: return
        phase = Phase.SUBSCRIBING
        val descriptor = characteristic.getDescriptor(BleUuids.CCCD)
        if (descriptor == null) {
            finish("Subscribe failed: HostToClient has no CCCD")
            return
        }
        val started = guarded("subscribe") {
            gatt.setCharacteristicNotification(characteristic, true) && writeCccd(gatt, descriptor)
        }
        if (started != true) finish("Subscribe failed: could not write the CCCD")
    }

    @Suppress("DEPRECATION")
    private fun writeCccd(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

    private fun descriptorWritten(gatt: BluetoothGatt, uuid: UUID, status: Int) {
        if (uuid != BleUuids.CCCD || phase != Phase.SUBSCRIBING) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            finish("Subscribe failed (status $status)")
            return
        }
        cancelConnectTimeout()
        stopSearchScan()
        // Setup is done, so the parameter update cannot get in the way of discovery.
        val prioritized = guarded("requestConnectionPriority") {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }
        HyperBleLog.event("connection priority HIGH requested: ${prioritized == true}")
        phase = Phase.READY
        listener?.invoke(ClientTransportEvent.Ready(BleConstants.maxChunkLength(mtu)))
    }

    /**
     * The host's GATT database changed. A host that cannot disconnect its clients (iOS) stops a
     * game by removing the service, and this is how that shows up here.
     */
    private fun serviceChanged() {
        if (phase != Phase.READY) {
            // During setup the stack may report this for its own reasons; the timeout covers it.
            HyperBleLog.event("host services changed during setup, ignored", LogLevel.WARN)
            return
        }
        finish("Host stopped the game")
    }

    // --- Data (main thread) ---

    /**
     * Writes the waiting frame, else one chunk; the next one goes out from [characteristicWritten].
     * A link that is closing only flushes its chunks.
     */
    private fun writeNext() {
        if (isWriting) return
        val gatt = gatt ?: return
        val characteristic = clientToHost ?: return

        val frame = if (isClosing) null else frames.take()
        if (frame != null) {
            if (guarded("writeCharacteristic") { write(gatt, characteristic, frame) } == true) {
                isWriting = true
                isFrameInFlight = true
                return
            }
            // Only this frame is lost; the next one replaces it anyway.
            frameFailed("frame write refused")
        }

        val chunk = outgoing.firstOrNull() ?: return

        if (guarded("writeCharacteristic") { write(gatt, characteristic, chunk) } == true) {
            isWriting = true
        } else {
            // The transport never retries; the host's reassembler drops what is incomplete.
            HyperBleLog.event("write refused, dropped ${outgoing.size} queued chunks", LogLevel.ERROR)
            outgoing.clear()
            if (isClosing) finish(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, chunk: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = chunk
            gatt.writeCharacteristic(characteristic)
        }

    private fun characteristicWritten(uuid: UUID, status: Int) {
        if (uuid != uuids.clientToHost || !isWriting) return
        isWriting = false
        if (isFrameInFlight) {
            isFrameInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) frameFailed("frame write failed status=$status")
        } else {
            outgoing.removeFirstOrNull()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // The host's reassembler drops the rest of this message when the next one starts.
                HyperBleLog.event("write failed status=$status", LogLevel.WARN)
            }
        }
        if (outgoing.isEmpty() && isClosing) finish(null) else writeNext()
    }

    private fun frameFailed(what: String) {
        if (frameFailureLogged) return
        frameFailureLogged = true
        HyperBleLog.event("$what (logged once per link)", LogLevel.WARN)
    }

    private fun characteristicChanged(uuid: UUID, chunk: ByteArray) {
        if (uuid != uuids.hostToClient) return
        val received = reassembler.receive(chunk, SystemClock.elapsedRealtime()) {
            HyperBleLog.event(it, LogLevel.WARN)
        }
        when (received) {
            is ReassembledMessage ->
                listener?.invoke(ClientTransportEvent.MessageReceived(received.bytes, received.chunks))
            is ReceivedFrame ->
                if (frameFilter.take(received.frameSeq)) {
                    listener?.invoke(ClientTransportEvent.FrameReceived(received.payload))
                }
            null -> Unit
        }
    }

    /**
     * Copies what a binder thread hands over and continues on the main thread. Callbacks of a
     * connection that was already closed are ignored.
     *
     * Before API 33 a value lives in the shared characteristic object and the next callback
     * overwrites it, so it is copied here, before leaving the binder thread. From API 33 on the
     * system calls the overloads that carry the value.
     */
    private inner class GattCallback : BluetoothGattCallback() {
        private fun onMain(source: BluetoothGatt, block: () -> Unit) {
            handler.post { if (source === gatt) block() }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
            onMain(gatt) { connectionStateChanged(gatt, status, newState) }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            onMain(gatt) { mtuChanged(gatt, mtu, status) }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            onMain(gatt) { servicesDiscovered(gatt, status) }

        @Deprecated("Called before API 33 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            onMain(gatt) { characteristicRead(gatt, uuid, value, status) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val uuid = characteristic.uuid
            val copy = value.copyOf()
            onMain(gatt) { characteristicRead(gatt, uuid, copy, status) }
        }

        @Deprecated("Called before API 33 only")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            onMain(gatt) { characteristicChanged(uuid, value) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val uuid = characteristic.uuid
            val copy = value.copyOf()
            onMain(gatt) { characteristicChanged(uuid, copy) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val uuid = characteristic.uuid
            onMain(gatt) { characteristicWritten(uuid, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.uuid
            onMain(gatt) { descriptorWritten(gatt, uuid, status) }
        }

        /** Called from API 31 on. */
        override fun onServiceChanged(gatt: BluetoothGatt) =
            onMain(gatt) { serviceChanged() }
    }

    /** Scan callbacks arrive on the main thread; those of a scan that was stopped are ignored. */
    private inner class SearchCallback : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (searchCallback === this) searchResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (searchCallback === this) results.forEach(::searchResult)
        }

        override fun onScanFailed(errorCode: Int) {
            if (searchCallback === this) searchFailed(errorCode)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
