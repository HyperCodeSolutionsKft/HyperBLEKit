package io.hypercode.hyperblekit.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.hypercode.hyperblekit.Platform
import io.hypercode.hyperblekit.log.HyperBleLog
import io.hypercode.hyperblekit.log.LogLevel
import io.hypercode.hyperblekit.transport.AdvertiseConfig
import io.hypercode.hyperblekit.AdvertisingState
import io.hypercode.hyperblekit.transport.ChunkException
import io.hypercode.hyperblekit.transport.Chunker
import io.hypercode.hyperblekit.transport.FrameFilter
import io.hypercode.hyperblekit.transport.FrameSendResult
import io.hypercode.hyperblekit.transport.FrameSlot
import io.hypercode.hyperblekit.transport.GameAdvertiser
import io.hypercode.hyperblekit.transport.GameHostTransport
import io.hypercode.hyperblekit.transport.HostTransportEvent
import io.hypercode.hyperblekit.transport.LinkId
import io.hypercode.hyperblekit.transport.RealtimeFrames
import io.hypercode.hyperblekit.transport.ReassembledMessage
import io.hypercode.hyperblekit.transport.Reassembler
import io.hypercode.hyperblekit.transport.ReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Host side over BLE: a [BluetoothGattServer] serves the game service (wire specification "GATT Service") and
 * the [advertiser] keeps the game discoverable for the whole session.
 *
 * Order matters: the service is added first and advertising only starts from `onServiceAdded`, so
 * a client never connects to a host without the service.
 *
 * Per connected central this keeps its own MTU (23 until `onMtuChanged`), chunker, reassembler,
 * notification queue and prepared-write buffer; everything is dropped when it disconnects. A
 * message is chunked for its recipient's MTU, and the next notification to a device is only sent
 * after `onNotificationSent` for the previous one.
 *
 * Real-time frames ([sendFrame]) do not queue: each central has one frame slot, and a waiting
 * frame goes out before the next chunk. Frames are not logged one by one.
 *
 * Threading: GATT server callbacks arrive on binder threads and are posted to the main thread, so
 * all state here is only touched from main. Public methods must be called on the main thread.
 *
 * Permissions (BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE on 12+) are verified by the app before any
 * call here, hence the class-level [SuppressLint]; calls are still guarded against a revoked
 * permission.
 */
@SuppressLint("MissingPermission")
internal class BleHostTransport(
    context: Context,
    private val uuids: BleUuids,
    private val advertiser: GameAdvertiser = BleGameAdvertiser(context, uuids),
) : GameHostTransport {

    /** One connected central. The address is a link handle only, never a player identity. */
    private class Peer(val device: BluetoothDevice) {
        val address: String get() = device.address
        var mtu = BleConstants.DEFAULT_MTU

        /** Notifications enabled through the CCCD: only then is it sent to and listened to. */
        var subscribed = false
        var chunker = Chunker()
        var reassembler = Reassembler()

        /** Chunks not yet handed to the stack, oldest first. */
        val outgoing = ArrayDeque<ByteArray>()

        /** The newest real-time frame not yet handed to the stack; it goes before [outgoing]. */
        var frames = FrameSlot()
        var frameFilter = FrameFilter()

        /** A frame notification was refused by the stack; logged once per link, not per frame. */
        var frameRefusalLogged = false

        /** A notification was sent and `onNotificationSent` has not arrived yet. */
        var notificationInFlight = false

        /** Bytes of a prepared (long) write, assembled by offset until `onExecuteWrite`. */
        var preparedWrite: ByteArray? = null

        /** The GameInfo value this central started reading, so a long read stays consistent. */
        var gameInfoRead: ByteArray? = null
    }

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<AdvertisingState>(AdvertisingState.Idle)
    override val state: StateFlow<AdvertisingState> = _state.asStateFlow()

    /** False while Bluetooth is off: asked each time a client is about to report `canHost`. */
    override val canHost: Boolean
        get() = guarded("isMultipleAdvertisementSupported") {
            bluetoothManager.adapter?.isMultipleAdvertisementSupported
        } == true

    private var listener: ((HostTransportEvent) -> Unit)? = null

    /** [stopAfterFlush] is waiting for the notification queues to drain. */
    private var isFlushing = false
    private val finishStop = Runnable { stop() }

    /** The code of the session the caller wants hosted, from [start] until [stop]. */
    private var wantedGameCode: String? = null

    /** The code we are serving and advertising right now, null while the GATT server is down. */
    private var gameCode: String? = null

    /**
     * Bluetooth being switched off kills the GATT server and every link without a callback per
     * device; when it comes back the session is served again under the same code.
     * Registered from [start] until [stop]; `onReceive` runs on the main thread.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> bluetoothTurnedOff()
                BluetoothAdapter.STATE_ON -> bluetoothTurnedOn()
            }
        }
    }
    private var isStateReceiverRegistered = false
    private var gameInfo = ByteArray(0)
    private var gattServer: BluetoothGattServer? = null

    /** Callbacks of an earlier server are ignored once this no longer points at them. */
    private var callback: ServerCallback? = null
    private var hostToClient: BluetoothGattCharacteristic? = null
    private var advertisingMirror: Job? = null
    private val peers = LinkedHashMap<String, Peer>()

    /**
     * On some devices advertising stops once a central connects, and the legacy advertising API
     * has no callback for that — so it is restarted after every connection.
     */
    private val restartAdvertising = Runnable {
        if (gameCode != null && !isFlushing && _state.value !is AdvertisingState.Unsupported) {
            HyperBleLog.event("advertising restart after a central connected")
            startAdvertising()
        }
    }

    override fun setListener(listener: ((HostTransportEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun setGameInfo(info: ByteArray) {
        gameInfo = info
    }

    override fun start(gameCode: String) {
        stop()
        wantedGameCode = gameCode
        ContextCompat.registerReceiver(
            appContext,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isStateReceiverRegistered = true
        open(gameCode)
    }

    /** Opens the GATT server and adds the service; advertising follows from `onServiceAdded`. */
    private fun open(gameCode: String) {
        teardown()

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off — turn it on to host.")
            return
        }
        _state.value = AdvertisingState.Starting

        val serverCallback = ServerCallback()
        val server = guarded("openGattServer") { bluetoothManager.openGattServer(appContext, serverCallback) }
        if (server == null) {
            fail("Could not open the GATT server.")
            return
        }
        this.gameCode = gameCode
        callback = serverCallback
        gattServer = server

        val service = BluetoothGattService(uuids.service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                uuids.gameInfo,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                uuids.clientToHost,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        val notify = BluetoothGattCharacteristic(
            uuids.hostToClient,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                BleUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(notify)
        hostToClient = notify

        // Advertising starts from onServiceAdded.
        if (guarded("addService") { server.addService(service) } != true) {
            fail("Could not add the game service.")
        }
    }

    override fun stop() {
        wantedGameCode = null
        if (isStateReceiverRegistered) {
            appContext.unregisterReceiver(bluetoothStateReceiver)
            isStateReceiverRegistered = false
        }
        teardown()
        _state.value = AdvertisingState.Idle
    }

    override fun stopAfterFlush() {
        if (gattServer == null) {
            stop()
            return
        }
        // No new client should find a host that is on its way out, and Bluetooth coming back
        // must not revive it.
        wantedGameCode = null
        isFlushing = true
        handler.removeCallbacks(restartAdvertising)
        advertisingMirror?.cancel()
        advertisingMirror = null
        advertiser.stopAdvertising()
        _state.value = AdvertisingState.Idle
        handler.postDelayed(finishStop, BleConstants.DISCONNECT_FLUSH_TIMEOUT_MILLIS)
        stopIfFlushed()
    }

    /** While flushing: once nothing is queued or in flight any more, close shortly after. */
    private fun stopIfFlushed() {
        if (!isFlushing) return
        if (peers.values.any { it.outgoing.isNotEmpty() || it.notificationInFlight }) return
        handler.removeCallbacks(finishStop)
        handler.postDelayed(finishStop, BleConstants.HOST_STOP_LINGER_MILLIS)
    }

    override fun send(message: ByteArray, to: LinkId): Int {
        val peer = peers[to]
        if (peer == null || !peer.subscribed) {
            HyperBleLog.event("send skipped: $to is not subscribed", LogLevel.WARN)
            return 0
        }
        return try {
            // Chunk size is per recipient; this central may have a different MTU than the others.
            val chunks = peer.chunker.split(message, BleConstants.maxChunkLength(peer.mtu))
            peer.outgoing.addAll(chunks)
            sendNextNotification(peer)
            chunks.size
        } catch (e: ChunkException) {
            HyperBleLog.event("send failed to $to: ${e.message}", LogLevel.ERROR)
            0
        }
    }

    /** Not logged: the caller counts what it sends, frames are too many for a line each. */
    override fun sendFrame(payload: ByteArray, to: LinkId): FrameSendResult {
        val peer = peers[to]
        if (peer == null || !peer.subscribed) return FrameSendResult.FAILED
        // A frame is one packet, chunk header included, on this central's MTU.
        val maxPayload = RealtimeFrames.maxPayload(BleConstants.maxChunkLength(peer.mtu))
        if (payload.size > maxPayload) return FrameSendResult.FAILED
        val replaced = peer.frames.put(payload)
        sendNextNotification(peer)
        return if (replaced) FrameSendResult.REPLACED else FrameSendResult.QUEUED
    }

    override fun staleFramesDropped(link: LinkId): Int = peers[link]?.frameFilter?.staleCount ?: 0

    // --- Lifecycle ---

    private fun startAdvertising() {
        val code = gameCode ?: return
        advertiser.startAdvertising(AdvertiseConfig(code, Platform.THIS_DEVICE))
        if (advertisingMirror == null) {
            // The advertiser goes through Idle on every restart; that is not worth showing.
            advertisingMirror = scope.launch {
                advertiser.state.collect { if (it !is AdvertisingState.Idle) _state.value = it }
            }
        }
    }

    /** Stops advertising, closes the GATT server and forgets every central. */
    private fun teardown() {
        isFlushing = false
        handler.removeCallbacksAndMessages(null)
        advertisingMirror?.cancel()
        advertisingMirror = null
        advertiser.stopAdvertising()
        gameCode = null
        callback = null
        hostToClient = null

        val server = gattServer
        gattServer = null
        if (server != null) {
            guarded("close GATT server") {
                peers.values.forEach { server.cancelConnection(it.device) }
                server.clearServices()
                server.close()
            }
            HyperBleLog.event("GATT server closed")
        }

        val subscribed = peers.values.filter { it.subscribed }.map { it.address }
        peers.clear()
        subscribed.forEach { emit(HostTransportEvent.LinkDown(it)) }
    }

    /** Every link is gone with the adapter: report them down and wait for Bluetooth to come back. */
    private fun bluetoothTurnedOff() {
        if (wantedGameCode == null || (gattServer == null && _state.value is AdvertisingState.Failed)) return
        HyperBleLog.event("Bluetooth switched off: dropping all links", LogLevel.WARN)
        fail("Bluetooth is off — turn it on to keep hosting.")
    }

    private fun bluetoothTurnedOn() {
        val code = wantedGameCode ?: return
        if (gattServer != null) return
        HyperBleLog.event("Bluetooth is back on: hosting $code again")
        open(code)
        emit(HostTransportEvent.Restarted)
    }

    private fun fail(reason: String) {
        teardown()
        _state.value = AdvertisingState.Failed(reason)
        HyperBleLog.event(reason, LogLevel.ERROR)
    }

    private fun emit(event: HostTransportEvent) {
        listener?.invoke(event)
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

    private fun peer(device: BluetoothDevice): Peer = peers.getOrPut(device.address) { Peer(device) }

    // --- GATT server events (main thread) ---

    private fun serviceAdded(status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Adding the game service failed (status $status).")
            return
        }
        HyperBleLog.event("GATT service added, starting to advertise")
        startAdvertising()
    }

    private fun connectionStateChanged(device: BluetoothDevice, status: Int, newState: Int) {
        val address = device.address
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                peers[address] = Peer(device)
                HyperBleLog.event("central $address connected status=$status (${peers.size} connected)")
                handler.removeCallbacks(restartAdvertising)
                handler.postDelayed(restartAdvertising, BleConstants.ADVERTISING_RESTART_DELAY_MILLIS)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                HyperBleLog.event("central $address disconnected status=$status")
                val peer = peers.remove(address)
                if (peer != null && peer.subscribed) emit(HostTransportEvent.LinkDown(address))
            }
            else -> HyperBleLog.event("central $address connection state=$newState status=$status")
        }
    }

    private fun mtuChanged(device: BluetoothDevice, mtu: Int) {
        peer(device).mtu = mtu
        HyperBleLog.event("MTU ${device.address} = $mtu")
    }

    private fun characteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, uuid: java.util.UUID) {
        if (uuid != uuids.gameInfo) {
            respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            return
        }
        // A long value is read in several requests; each one continues at `offset` of the value
        // the read started with, even if the game info changes meanwhile.
        val peer = peer(device)
        if (offset == 0) {
            peer.gameInfoRead = gameInfo
            HyperBleLog.event("GameInfo read by ${peer.address} bytes=${gameInfo.size}")
        }
        val value = peer.gameInfoRead ?: gameInfo
        if (offset > value.size) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
        } else {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    private fun characteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        uuid: java.util.UUID,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        if (uuid != uuids.clientToHost) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
            return
        }
        val peer = peer(device)
        if (preparedWrite) {
            // A long write: a chunk bigger than the client's MTU arrives in parts, by offset.
            val end = offset + value.size
            if (end > BleConstants.MAX_ATTRIBUTE_LENGTH) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH)
                return
            }
            // Offset 0 starts a new long write: what an abandoned one left behind must not leak in.
            val current = (if (offset == 0) null else peer.preparedWrite) ?: ByteArray(0)
            val buffer = if (current.size < end) current.copyOf(end) else current
            value.copyInto(buffer, offset)
            peer.preparedWrite = buffer
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            return
        }
        if (offset != 0) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
            return
        }
        if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        chunkReceived(peer, value)
    }

    private fun executeWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
        val peer = peer(device)
        val chunk = peer.preparedWrite
        peer.preparedWrite = null
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS)
        when {
            chunk == null -> Unit
            execute -> chunkReceived(peer, chunk)
            else -> HyperBleLog.event("${peer.address}: long write cancelled, dropped bytes=${chunk.size}", LogLevel.WARN)
        }
    }

    private fun descriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, uuid: java.util.UUID) {
        if (uuid != BleUuids.CCCD) {
            respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            return
        }
        val value = if (peer(device).subscribed) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        if (offset > value.size) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
        } else {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    private fun descriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptorUuid: java.util.UUID,
        characteristicUuid: java.util.UUID?,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        if (descriptorUuid != BleUuids.CCCD || characteristicUuid != uuids.hostToClient) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
            return
        }
        if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)

        val peer = peer(device)
        if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            // A new subscription is a new connection: chunk and frame counters start over in both directions.
            peer.subscribed = true
            peer.chunker = Chunker()
            peer.reassembler = Reassembler()
            peer.outgoing.clear()
            peer.frames = FrameSlot()
            peer.frameFilter = FrameFilter()
            peer.frameRefusalLogged = false
            emit(HostTransportEvent.LinkUp(peer.address, BleConstants.maxChunkLength(peer.mtu)))
        } else if (peer.subscribed) {
            peer.subscribed = false
            peer.outgoing.clear()
            peer.frames.clear()
            HyperBleLog.event("central ${peer.address} unsubscribed")
            emit(HostTransportEvent.LinkDown(peer.address))
        }
    }

    private fun notificationSent(device: BluetoothDevice, status: Int) {
        val peer = peers[device.address] ?: return
        peer.notificationInFlight = false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            HyperBleLog.event("notification to ${peer.address} failed status=$status", LogLevel.WARN)
        }
        sendNextNotification(peer)
        stopIfFlushed()
    }

    // --- Data ---

    private fun chunkReceived(peer: Peer, chunk: ByteArray) {
        if (!peer.subscribed) {
            // The usual cause: a client that sends JOIN_REQUEST before subscribing to HostToClient.
            HyperBleLog.event(
                "ignored write from ${peer.address} bytes=${chunk.size}: not subscribed to HostToClient yet",
                LogLevel.WARN,
            )
            return
        }
        val received = peer.reassembler.receive(chunk, SystemClock.elapsedRealtime()) {
            HyperBleLog.event("${peer.address}: $it", LogLevel.WARN)
        }
        when (received) {
            is ReassembledMessage ->
                emit(HostTransportEvent.MessageReceived(peer.address, received.bytes, received.chunks))
            is ReceivedFrame ->
                if (peer.frameFilter.take(received.frameSeq)) {
                    emit(HostTransportEvent.FrameReceived(peer.address, received.payload))
                }
            null -> Unit
        }
    }

    /**
     * Sends the waiting frame, else the oldest queued chunk, unless a notification is still in
     * flight to this device.
     */
    private fun sendNextNotification(peer: Peer) {
        if (peer.notificationInFlight) return
        val server = gattServer ?: return
        val characteristic = hostToClient ?: return

        val frame = peer.frames.take()
        if (frame != null) {
            if (guarded("notify") { notify(server, characteristic, peer.device, frame) } == true) {
                peer.notificationInFlight = true
                return
            }
            // Only this frame is lost; the next one replaces it anyway.
            if (!peer.frameRefusalLogged) {
                peer.frameRefusalLogged = true
                HyperBleLog.event("frame notification to ${peer.address} refused (logged once per link)", LogLevel.WARN)
            }
        }

        val chunk = peer.outgoing.firstOrNull() ?: return

        if (guarded("notify") { notify(server, characteristic, peer.device, chunk) } == true) {
            peer.outgoing.removeFirst()
            peer.notificationInFlight = true
        } else {
            // The transport never retries; the session recovers through its own rules.
            HyperBleLog.event(
                "notification to ${peer.address} refused, dropped ${peer.outgoing.size} queued chunks",
                LogLevel.ERROR,
            )
            peer.outgoing.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(
        server: BluetoothGattServer,
        characteristic: BluetoothGattCharacteristic,
        device: BluetoothDevice,
        chunk: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, chunk) == BluetoothStatusCodes.SUCCESS
        } else {
            // The value is shared by all devices before API 33; safe because we are single-threaded
            // and the stack copies it during the call.
            characteristic.value = chunk
            server.notifyCharacteristicChanged(device, characteristic, false)
        }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int = 0, value: ByteArray? = null) {
        val sent = guarded("sendResponse") { gattServer?.sendResponse(device, requestId, status, offset, value) }
        if (sent != true) {
            HyperBleLog.event("response to ${device.address} could not be sent (status $status)", LogLevel.WARN)
        }
    }

    /** Copies what a binder thread hands over and continues on the main thread. */
    private inner class ServerCallback : BluetoothGattServerCallback() {
        private fun onMain(block: () -> Unit) {
            handler.post { if (callback === this) block() }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) =
            onMain { serviceAdded(status) }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) =
            onMain { connectionStateChanged(device, status, newState) }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) =
            onMain { mtuChanged(device, mtu) }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val uuid = characteristic.uuid
            onMain { characteristicReadRequest(device, requestId, offset, uuid) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val uuid = characteristic.uuid
            val bytes = value?.copyOf() ?: ByteArray(0)
            onMain { characteristicWriteRequest(device, requestId, uuid, preparedWrite, responseNeeded, offset, bytes) }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) =
            onMain { executeWrite(device, requestId, execute) }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val uuid = descriptor.uuid
            onMain { descriptorReadRequest(device, requestId, offset, uuid) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val descriptorUuid = descriptor.uuid
            val characteristicUuid = descriptor.characteristic?.uuid
            val bytes = value?.copyOf() ?: ByteArray(0)
            onMain {
                descriptorWriteRequest(device, requestId, descriptorUuid, characteristicUuid, responseNeeded, offset, bytes)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) =
            onMain { notificationSent(device, status) }
    }
}
