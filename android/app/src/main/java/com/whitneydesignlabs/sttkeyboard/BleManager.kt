package com.whitneydesignlabs.sttkeyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the BLE central role: scan for the dongle by service UUID / name, connect,
 * bond, negotiate MTU, discover the Text Input characteristic, and drive a
 * SERIALIZED, chunked write queue.
 *
 * Threading model:
 *  - All GATT operations are funneled through [gatt] which dispatches on the
 *    Binder thread; callbacks arrive on an arbitrary binder thread.
 *  - The write queue and its single-outstanding invariant are guarded by [queueLock].
 *  - Public state is exposed as [StateFlow]s the UI collects on the main thread.
 *
 * Reliability (PROTOCOL.md §4/§5, spec §6.2):
 *  - We read the *negotiated* MTU and chunk to (mtu - 3). We never assume 244.
 *  - Exactly one GATT write is outstanding at a time; the next chunk is sent only
 *    after onCharacteristicWrite for the previous one.
 *  - Auto-reconnect uses exponential backoff capped at ~10s, because Android BLE
 *    reconnection behavior varies by vendor.
 *  - The Text Input char requires an encrypted/bonded link; the first write
 *    triggers Android pairing. We watch ACTION_BOND_STATE_CHANGED and retry the
 *    pending write once bonding completes.
 */
class BleManager(private val appContext: Context) {

    /** High-level connection state surfaced to the UI. */
    enum class ConnectionState {
        DISCONNECTED, SCANNING, CONNECTING, BONDING, READY, TYPING
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Emits the last fully-acknowledged text run we sent to the dongle. */
    private val _lastSent = MutableStateFlow("")
    val lastSent: StateFlow<String> = _lastSent.asStateFlow()

    // ---- Diagnostics surfaced to the BLE console screen ----

    /** A device seen during scanning (for the console's device list). */
    data class ScannedDevice(val address: String, val name: String?, val rssi: Int)

    /** Live, de-duplicated scan results (most-recently-seen RSSI per address). */
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    /** Name/address of the currently connected dongle, or null. */
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    private val _deviceAddress = MutableStateFlow<String?>(null)
    val deviceAddress: StateFlow<String?> = _deviceAddress.asStateFlow()

    /** Connected-link RSSI in dBm (polled while connected); 0 when unknown. */
    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    /** Negotiated ATT MTU (bytes); 0 until negotiated. */
    private val _mtu = MutableStateFlow(0)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    /** Optional client-side delay between successive chunk writes (ms). The dongle
     *  also paces individual keystrokes; this just spaces GATT writes if a host
     *  still drops keys. 0 = send chunks as fast as each ack arrives. */
    @Volatile
    var interWriteDelayMs: Int = 0

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    /**
     * The user's chosen dongle address, persisted across launches. We auto-reconnect
     * to this one; with several dongles around, the user switches by tapping a
     * different device in the console (which updates this).
     */
    private val prefs = appContext.getSharedPreferences("stt_ble", Context.MODE_PRIVATE)
    private var rememberedAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(v) { prefs.edit().putString(KEY_LAST_DEVICE, v).apply() }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var textInputChar: BluetoothGattCharacteristic? = null

    /** ATT payload size; updated from the negotiated MTU. Starts at the safe floor. */
    @Volatile
    private var maxPayload: Int = Protocol.DEFAULT_PAYLOAD_BYTES

    /** True only after services are discovered and the char is cached. */
    @Volatile
    private var ready = false

    /** Set when the user has asked us to be connected; controls auto-reconnect. */
    @Volatile
    private var wantConnection = false

    private var reconnectAttempts = 0
    private var scanning = false

    // ---- Write queue (FIFO of byte-array chunks, one outstanding at a time) ----
    private val queueLock = Any()
    private val writeQueue = ArrayDeque<ByteArray>()
    private val writeInFlight = AtomicBoolean(false)

    /**
     * Bytes of the utterance whose chunks are currently in the queue / in flight.
     * Used to report [lastSent] once the final chunk of an utterance is ack'd.
     */
    private var currentUtterance: String = ""
    private var chunksRemainingForUtterance = 0

    /** A write we attempted before bonding finished; retried after BOND_BONDED. */
    private var pendingWriteAwaitingBond: ByteArray? = null

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_CAP_MS = 10_000L
        private const val KEY_LAST_DEVICE = "last_device"
    }

    // ====================================================================
    // Lifecycle: start / stop
    // ====================================================================

    /** Returns true if BLE is usable (hardware present and adapter on). */
    fun isBluetoothReady(): Boolean = adapter != null && adapter.isEnabled

    /**
     * Begin trying to reach the dongle. Idempotent. Registers the bond receiver,
     * then either reconnects to an already-bonded dongle or starts a scan.
     */
    @SuppressLint("MissingPermission") // Callers gate this on runtime BLE permissions.
    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "start() but Bluetooth not ready")
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        wantConnection = true
        registerBondReceiver()

        // Idempotent: if a link is already live (e.g. we just came back from the
        // console screen), do NOT open another GATT client — that would leak the
        // existing one and create a duplicate connection.
        if (ready && gatt != null) {
            Log.i(TAG, "start(): already connected; nothing to do")
            return
        }

        // Fast path: connect straight to the remembered dongle if we have one
        // (PROTOCOL.md §5 — reconnect is automatic). If it's out of range the connect
        // fails and scheduleReconnect()->scan takes over.
        val remembered = rememberedAddress
        if (remembered != null) {
            try {
                Log.i(TAG, "Connecting to remembered dongle $remembered")
                connectTo(adapter.getRemoteDevice(remembered))
                return
            } catch (e: Exception) {
                Log.w(TAG, "Bad remembered address ($remembered); scanning instead: ${e.message}")
            }
        }
        // No chosen dongle yet: reuse a bonded one if present, else scan and adopt.
        val bonded = findBondedDongle()
        if (bonded != null) {
            Log.i(TAG, "Found bonded dongle ${bonded.address}; connecting directly")
            rememberedAddress = bonded.address
            connectTo(bonded)
        } else {
            startScan()
        }
    }

    /**
     * Full teardown: disconnect, release the GATT client, and unregister the bond
     * receiver. This is an *explicit* shutdown (e.g. a future "turn off" action) — it
     * is intentionally NOT called from the Activity lifecycle, because the manager is
     * app-scoped (shared across screens) and must survive backgrounding/navigation.
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        wantConnection = false
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScan()
        stopRssiPolling()
        unregisterBondReceiver()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        textInputChar = null
        ready = false
        clearQueue()
        _state.value = ConnectionState.DISCONNECTED
    }

    // ====================================================================
    // Scanning
    // ====================================================================

    @SuppressLint("MissingPermission")
    private fun findBondedDongle(): BluetoothDevice? {
        val remembered = rememberedAddress
        return adapter?.bondedDevices?.firstOrNull { dev ->
            dev.address == remembered || Protocol.isDongleName(dev.name)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        scanner = adapter?.bluetoothLeScanner ?: return
        scanning = true
        _state.value = ConnectionState.SCANNING

        // Filter by the advertised service UUID (preferred per PROTOCOL.md §1).
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Protocol.SVC_TEXT_INPUT))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i(TAG, "Starting BLE scan for service ${Protocol.SVC_TEXT_INPUT}")
        scanner?.startScan(listOf(filter), settings, scanCallback)

        // Safety net: stop scanning after a timeout and retry, so we don't scan forever.
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (scanning) {
            scanning = false
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed: ${e.message}")
            }
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (scanning && !ready) {
            Log.i(TAG, "Scan timeout; restarting scan")
            stopScan()
            if (wantConnection) startScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Publish every hit to the console's device list (dedupe by address,
            // keep the latest RSSI). This is independent of the auto-connect below.
            val seenName = result.scanRecord?.deviceName ?: device.name
            val entry = ScannedDevice(device.address, seenName, result.rssi)
            _scanResults.value = (_scanResults.value.filterNot { it.address == entry.address } + entry)
                .sortedByDescending { it.rssi }
            // Is this one of our dongles? The scan filter already restricts to the
            // service UUID; the name prefix is belt-and-suspenders (PROTOCOL.md §1).
            val isOurs = Protocol.isDongleName(seenName) ||
                result.scanRecord?.serviceUuids
                    ?.contains(ParcelUuid(Protocol.SVC_TEXT_INPUT)) == true
            if (!isOurs) return
            Log.i(TAG, "Scan hit: ${device.address} name=$seenName")

            // Auto-connect policy (multi-dongle aware): if the user has a remembered
            // dongle, only connect to that address; otherwise adopt the first one
            // found and remember it. To switch dongles the user taps another in the
            // console (connectToAddress), which updates the remembered address.
            val remembered = rememberedAddress
            when {
                remembered == null -> {
                    rememberedAddress = device.address
                    Log.i(TAG, "Adopting dongle ${device.address} ($seenName)")
                    stopScan(); connectTo(device)
                }
                device.address == remembered -> {
                    stopScan(); connectTo(device)
                }
                // else: a different dongle than the chosen one — listed, not auto-connected.
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            scheduleReconnect()
        }
    }

    // ====================================================================
    // Connect / GATT callback
    // ====================================================================

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        stopScan()
        mainHandler.removeCallbacks(reconnectRunnable)   // cancel any queued backoff reconnect
        // Tear down any existing GATT client BEFORE opening a new one. Nulling gatt
        // first means the old client's late callbacks are ignored (g !== gatt), and
        // close() prevents a leaked/duplicate connection.
        gatt?.let { old ->
            gatt = null
            try { old.disconnect(); old.close() } catch (_: Exception) { }
        }
        ready = false
        textInputChar = null
        writeInFlight.set(false)
        stopRssiPolling()
        _state.value = ConnectionState.CONNECTING
        // autoConnect=false for a fast, direct connection; we manage reconnect
        // ourselves with backoff (more predictable across vendors than autoConnect).
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                appContext, /* autoConnect = */ false, gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {                 // stale client superseded by a newer connect
                try { g.close() } catch (_: Exception) { }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                Log.i(TAG, "Connected; requesting MTU ${Protocol.REQUESTED_MTU}")
                reconnectAttempts = 0
                _state.value = ConnectionState.CONNECTING
                _deviceName.value = g.device?.name
                _deviceAddress.value = g.device?.address
                startRssiPolling()
                // Request the large MTU first; service discovery happens after the
                // MTU result so we know our chunk size up front (PROTOCOL.md §4).
                if (!g.requestMtu(Protocol.REQUESTED_MTU)) {
                    Log.w(TAG, "requestMtu returned false; discovering with default MTU")
                    g.discoverServices()
                }
            } else {
                Log.w(TAG, "Disconnected (status=$status, newState=$newState)")
                handleDisconnect(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            // Read the NEGOTIATED MTU; chunk to mtu - 3. Some stacks grant less than
            // we asked for, so we trust this value, not 247 (PROTOCOL.md §4).
            maxPayload = (mtu - Protocol.ATT_HEADER_BYTES).coerceAtLeast(
                Protocol.DEFAULT_PAYLOAD_BYTES
            )
            _mtu.value = mtu
            Log.i(TAG, "MTU negotiated=$mtu -> payload=$maxPayload (status=$status)")
            g.discoverServices()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssiValue: Int, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssiValue
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                handleDisconnect(g)
                return
            }
            val service = g.getService(Protocol.SVC_TEXT_INPUT)
            val chr = service?.getCharacteristic(Protocol.CHR_TEXT_INPUT)
            if (chr == null) {
                Log.e(TAG, "Text Input characteristic not found; disconnecting")
                handleDisconnect(g)
                return
            }
            textInputChar = chr
            ready = true
            _state.value = ConnectionState.READY
            Log.i(TAG, "Ready: Text Input characteristic cached")
            // If text was queued while we were connecting, start pumping now — on the
            // main looper so all queue/state mutation stays single-threaded.
            mainHandler.post { pump() }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g !== gatt) return
            // Resolve on the main looper so the write queue and state are mutated from
            // exactly one thread (pump/sendText/bond-retry all run there too).
            mainHandler.post { handleWriteResult(status) }
        }
    }

    /** Resolve a completed write. Runs on the main looper (see onCharacteristicWrite). */
    private fun handleWriteResult(status: Int) {
        when {
            status == BluetoothGatt.GATT_SUCCESS -> {
                // Done with this chunk; free the slot, pop it, advance the queue.
                writeInFlight.set(false)
                onChunkAcknowledged()
                val d = interWriteDelayMs
                if (d > 0) mainHandler.postDelayed({ pump() }, d.toLong()) else pump()
            }
            status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> {
                // First write to the encrypted char before bonding: Android kicked off
                // pairing. KEEP the in-flight slot reserved (do NOT free it) so nothing
                // else pumps; the chunk stays at the queue head and BOND_BONDED retries
                // it (PROTOCOL.md §5).
                Log.i(TAG, "Write needs encryption; awaiting bond then retry")
                _state.value = ConnectionState.BONDING
                pendingWriteAwaitingBond = lastAttemptedChunk
            }
            else -> {
                // Transient failure: free the slot and retry the SAME head chunk via the
                // gated pump() — never a second concurrent write.
                Log.w(TAG, "Write failed status=$status; retrying head chunk")
                writeInFlight.set(false)
                mainHandler.postDelayed({ pump() }, 100)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(g: BluetoothGatt) {
        ready = false
        textInputChar = null
        writeInFlight.set(false)
        stopRssiPolling()
        _deviceName.value = null
        _deviceAddress.value = null
        _rssi.value = 0
        _mtu.value = 0
        try {
            g.close()
        } catch (_: Exception) { }
        if (gatt === g) gatt = null
        _state.value = ConnectionState.DISCONNECTED
        if (wantConnection) scheduleReconnect()
    }

    // ====================================================================
    // Auto-reconnect with exponential backoff (cap ~10s)
    // ====================================================================

    /** The queued reconnect attempt (named so callers can cancel a pending backoff). */
    private val reconnectRunnable = Runnable {
        if (!wantConnection) return@Runnable
        // Scan rather than direct-connect: onScanResult reconnects to the remembered
        // dongle as soon as it reappears (no 30s connect timeouts while it's absent).
        startScan()
    }

    private fun scheduleReconnect() {
        if (!wantConnection) return
        val delay = (BACKOFF_BASE_MS * (1L shl reconnectAttempts.coerceAtMost(5)))
            .coerceAtMost(BACKOFF_CAP_MS)
        reconnectAttempts++
        Log.i(TAG, "Reconnect attempt $reconnectAttempts in ${delay}ms")
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    // ====================================================================
    // Write queue: encode -> chunk -> FIFO -> single-outstanding pump
    // ====================================================================

    /**
     * Public entry point. Encodes [text] to the dongle's ASCII stream, appends a
     * trailing space after the finalized utterance (word separation between
     * utterances), chunks to the negotiated payload, and enqueues the chunks.
     */
    fun sendText(text: String) {
        // Append a trailing space so consecutive utterances don't run together.
        val withSpace = if (text.endsWith(" ")) text else "$text "
        val bytes = Protocol.encodeForDongle(withSpace)
        if (bytes.isEmpty()) return

        val payload = maxPayload
        synchronized(queueLock) {
            currentUtterance = withSpace
            var offset = 0
            var chunkCount = 0
            while (offset < bytes.size) {
                val end = (offset + payload).coerceAtMost(bytes.size)
                writeQueue.addLast(bytes.copyOfRange(offset, end))
                offset = end
                chunkCount++
            }
            chunksRemainingForUtterance = chunkCount
        }
        pump()
    }

    /**
     * Send a single Tier-1 special key (Enter/Tab/Backspace) as a raw control byte —
     * no trailing space, not run through the printable filter. Goes through the same
     * serialized write queue as text.
     */
    fun sendKey(keyByte: Byte) {
        if (keyByte != Protocol.KEY_ENTER && keyByte != Protocol.KEY_TAB &&
            keyByte != Protocol.KEY_BACKSPACE
        ) return
        synchronized(queueLock) { writeQueue.addLast(byteArrayOf(keyByte)) }
        pump()
    }

    /** The chunk most recently handed to GATT (for retry-on-failure). */
    @Volatile
    private var lastAttemptedChunk: ByteArray? = null

    /**
     * Drive the queue: if no write is in flight and we're ready, dequeue one chunk
     * and send it. Exactly one write is ever outstanding (PROTOCOL.md §3).
     */
    @SuppressLint("MissingPermission")
    private fun pump() {
        if (!ready) return
        // Reserve the single in-flight slot atomically.
        if (!writeInFlight.compareAndSet(false, true)) return

        val chunk: ByteArray? = synchronized(queueLock) {
            if (writeQueue.isEmpty()) null else writeQueue.peekFirst()
        }
        if (chunk == null) {
            writeInFlight.set(false)
            if (_state.value == ConnectionState.TYPING) _state.value = ConnectionState.READY
            return
        }
        _state.value = ConnectionState.TYPING
        rawSend(chunk)
    }

    /** Issue a single GATT write with response for [chunk]. */
    @SuppressLint("MissingPermission")
    private fun rawSend(chunk: ByteArray) {
        val g = gatt
        val chr = textInputChar
        if (g == null || chr == null) {
            writeInFlight.set(false)
            return
        }
        lastAttemptedChunk = chunk
        // WRITE_TYPE_DEFAULT = Write With Response: gives backpressure + ordering
        // and is the preferred mode in PROTOCOL.md §3.
        val ok: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(
                chr, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            rc == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            chr.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(chr)
        }
        if (!ok) {
            Log.w(TAG, "writeCharacteristic dispatch failed; retrying shortly")
            writeInFlight.set(false)
            mainHandler.postDelayed({ pump() }, 100)
        }
    }

    /** Called from onCharacteristicWrite(SUCCESS): pop the acked chunk, track utterance. */
    private fun onChunkAcknowledged() {
        synchronized(queueLock) {
            if (!writeQueue.isEmpty()) writeQueue.pollFirst()
            if (chunksRemainingForUtterance > 0) {
                chunksRemainingForUtterance--
                if (chunksRemainingForUtterance == 0) {
                    // Whole utterance delivered; surface it to the UI.
                    _lastSent.value = currentUtterance.trimEnd()
                }
            }
        }
    }

    private fun clearQueue() {
        synchronized(queueLock) {
            writeQueue.clear()
            chunksRemainingForUtterance = 0
            currentUtterance = ""
        }
        writeInFlight.set(false)
        lastAttemptedChunk = null
        pendingWriteAwaitingBond = null
    }

    // ====================================================================
    // Bond state handling (retry the pending write once bonded)
    // ====================================================================

    private var bondReceiverRegistered = false

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val newBond = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR
            )
            when (newBond) {
                BluetoothDevice.BOND_BONDING -> {
                    Log.i(TAG, "Bonding in progress")
                    _state.value = ConnectionState.BONDING
                }
                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG, "Bonded; retrying pending write")
                    _state.value = if (ready) ConnectionState.READY
                    else ConnectionState.CONNECTING
                    // Retry the encrypted write that triggered pairing.
                    val pending = pendingWriteAwaitingBond
                    pendingWriteAwaitingBond = null
                    if (pending != null) {
                        // The in-flight slot was deliberately held reserved while bonding
                        // and the chunk is still at the queue head; free the slot and
                        // re-pump to retry it now that the link is encrypted.
                        writeInFlight.set(false)
                        pump()
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.w(TAG, "Bond removed/failed")
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        appContext.registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        try {
            appContext.unregisterReceiver(bondReceiver)
        } catch (_: Exception) { }
        bondReceiverRegistered = false
    }

    // ====================================================================
    // Console controls (used by DiagnosticsActivity)
    // ====================================================================

    /** Clear the device list and (re)start a scan. Auto-connects on a match. */
    @SuppressLint("MissingPermission")
    fun rescan() {
        if (adapter == null || !adapter.isEnabled) return
        wantConnection = true
        registerBondReceiver()
        mainHandler.removeCallbacks(reconnectRunnable)   // don't let a queued backoff fight the scan
        _scanResults.value = emptyList()
        startScan()
    }

    /**
     * Manually connect to a specific scanned address (user tapped a row in the
     * console). This becomes the remembered dongle, so future auto-reconnect targets
     * it — i.e. this is how you switch which dongle the app drives.
     */
    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        val dev = adapter?.getRemoteDevice(address) ?: return
        rememberedAddress = address
        wantConnection = true
        registerBondReceiver()
        reconnectAttempts = 0
        connectTo(dev)
    }

    /** User-initiated disconnect that also suppresses auto-reconnect. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        wantConnection = false
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScan()
        stopRssiPolling()
        // Proactively tear down + clear state so the console is consistent even if the
        // disconnected callback is dropped (nulling gatt first ignores any late callback).
        val g = gatt
        gatt = null
        g?.let { try { it.disconnect(); it.close() } catch (_: Exception) { } }
        ready = false
        textInputChar = null
        writeInFlight.set(false)
        clearQueue()
        _deviceName.value = null
        _deviceAddress.value = null
        _rssi.value = 0
        _mtu.value = 0
        _state.value = ConnectionState.DISCONNECTED
    }

    // ---- Connected-link RSSI polling (every 3s while a GATT link exists) ----

    private val rssiRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val g = gatt ?: return
            try { g.readRemoteRssi() } catch (_: Exception) { }
            mainHandler.postDelayed(this, 3000)
        }
    }

    private fun startRssiPolling() {
        mainHandler.removeCallbacks(rssiRunnable)
        mainHandler.postDelayed(rssiRunnable, 1500)
    }

    private fun stopRssiPolling() = mainHandler.removeCallbacks(rssiRunnable)

    // A handler bound to the main looper for posting delays / serialized callbacks.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
}
