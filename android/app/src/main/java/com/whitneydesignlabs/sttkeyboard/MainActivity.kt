package com.whitneydesignlabs.sttkeyboard

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.whitneydesignlabs.sttkeyboard.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Single screen: connection status, live transcript, last-sent text, and a big
 * mic toggle. MainActivity is the glue that:
 *  - requests the right runtime permissions per API level,
 *  - starts/stops [BleManager] with the Activity lifecycle,
 *  - collects [SttManager] final results and forwards them to the BLE queue,
 *  - reflects [BleManager.state] / [SttManager] flows into the UI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager
    private lateinit var stt: SttManager

    /** True once the user has granted everything we need to actually run. */
    private var permissionsGranted = false

    // ---- Permission launchers --------------------------------------------

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] != false &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        val bleOk = blePermissions().all { hasPermission(it) }
        if (!micOk) toast(getString(R.string.perm_needed_mic))
        if (!bleOk) toast(getString(R.string.perm_needed_ble))
        permissionsGranted = micOk && bleOk
        if (permissionsGranted) onPermissionsReady()
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ble.isBluetoothReady()) ble.start()
        else toast(getString(R.string.bt_disabled))
    }

    // ---- Lifecycle --------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = (application as SttApp).ble
        stt = SttManager(applicationContext)

        // Show the app version so testers can confirm which build is running.
        binding.textVersion.text = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }

        binding.micButton.setOnClickListener { toggleDictation() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        // Tier-1 special-key buttons (also available by voice: "new line"/"tab"/"backspace").
        binding.keyEnter.setOnClickListener { ble.sendKey(Protocol.KEY_ENTER) }
        binding.keyTab.setOnClickListener { ble.sendKey(Protocol.KEY_TAB) }
        binding.keyBackspace.setOnClickListener { ble.sendKey(Protocol.KEY_BACKSPACE) }

        observeFlows()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Begin trying to reach the dongle whenever we're in the foreground and
        // permissions are in place. Idempotent.
        if (permissionsGranted) ensureBluetoothThenStart()
    }

    override fun onStop() {
        super.onStop()
        // Release the mic when backgrounded. The BLE link is app-scoped (shared
        // with the console screen via SttApp), so we deliberately do NOT stop it
        // here — opening the console must not drop the dongle connection.
        stt.stop()
        setMicButtonState(listening = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stt.destroy()
        // ble is app-scoped (owned by SttApp); leave it running.
    }

    // ---- Permissions ------------------------------------------------------

    /** The BLE runtime permissions that actually need granting on this API level. */
    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: SCAN (neverForLocation) + CONNECT, no location needed.
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android 11 and below: a BLE scan needs fine location at runtime.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun allNeededPermissions(): Array<String> =
        (arrayOf(Manifest.permission.RECORD_AUDIO) + blePermissions())

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val needed = allNeededPermissions().filter { !hasPermission(it) }
        if (needed.isEmpty()) {
            permissionsGranted = true
            onPermissionsReady()
        } else {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun onPermissionsReady() {
        if (!stt.isAvailable()) toast(getString(R.string.stt_unavailable))
        ensureBluetoothThenStart()
    }

    // ---- Bluetooth enable + BLE start ------------------------------------

    private fun ensureBluetoothThenStart() {
        if (ble.isBluetoothReady()) {
            ble.start()
        } else {
            // Prompt the user to turn Bluetooth on, then start on the result.
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    // ---- Dictation toggle -------------------------------------------------

    private fun toggleDictation() {
        if (!permissionsGranted) {
            requestNeededPermissions()
            return
        }
        if (stt.listening.value) {
            stt.stop()
        } else {
            stt.start()
        }
    }

    // ---- Flow observation -> UI ------------------------------------------

    private fun observeFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Each collector runs in its own child coroutine.
                launch {
                    ble.state.collect { state -> renderConnectionState(state) }
                }
                launch {
                    ble.lastSent.collect { sent ->
                        binding.textLastSent.text =
                            if (sent.isEmpty()) getString(R.string.placeholder_dash) else sent
                    }
                }
                launch {
                    stt.partial.collect { partial ->
                        binding.textPartial.text =
                            if (partial.isEmpty()) getString(R.string.placeholder_dash) else partial
                    }
                }
                launch {
                    stt.listening.collect { setMicButtonState(it) }
                }
                launch {
                    stt.finalResults.collect { text ->
                        // Whole-utterance voice command (e.g. "new line") -> a special
                        // key; otherwise send as text. Showing it immediately lets the
                        // app demo standalone; ble.lastSent re-confirms on GATT ack.
                        val key = Protocol.voiceCommandByte(text)
                        if (key != null) {
                            ble.sendKey(key)
                            binding.textLastSent.text = keyLabel(key)
                        } else {
                            binding.textLastSent.text = text
                            ble.sendText(text)
                        }
                    }
                }
                launch {
                    stt.errors.collect { msg -> toast(msg) }
                }
            }
        }
    }

    private fun renderConnectionState(state: BleManager.ConnectionState) {
        val (label, colorRes) = when (state) {
            BleManager.ConnectionState.DISCONNECTED ->
                getString(R.string.status_disconnected) to R.color.status_red
            BleManager.ConnectionState.SCANNING ->
                getString(R.string.status_scanning) to R.color.status_amber
            BleManager.ConnectionState.CONNECTING ->
                getString(R.string.status_connecting) to R.color.status_amber
            BleManager.ConnectionState.BONDING ->
                getString(R.string.status_bonding) to R.color.status_amber
            BleManager.ConnectionState.READY ->
                getString(R.string.status_ready) to R.color.status_green
            BleManager.ConnectionState.TYPING ->
                getString(R.string.status_typing) to R.color.status_green
        }
        binding.textStatus.text = label
        tintStatusDot(colorRes)
    }

    private fun tintStatusDot(colorRes: Int) {
        val drawable = binding.statusDot.background?.mutate() ?: return
        val wrapped = DrawableCompat.wrap(drawable)
        DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, colorRes))
        binding.statusDot.background = wrapped
    }

    private fun setMicButtonState(listening: Boolean) {
        // Keep the screen on while dictating so it can't dim/sleep mid-utterance and
        // kill the recognizer (which loses in-progress text). Cleared when listening
        // stops or the activity backgrounds. (Surviving full screen-off is task #10.)
        if (listening) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val btn: MaterialButton = binding.micButton
        if (listening) {
            btn.text = getString(R.string.mic_stop)
            btn.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
        } else {
            btn.text = getString(R.string.mic_start)
            btn.setBackgroundColor(ContextCompat.getColor(this, R.color.wdl_gold))
        }
    }

    /** Display label for a special key that was sent (shown in "Last sent"). */
    private fun keyLabel(key: Byte): String = getString(
        when (key) {
            Protocol.KEY_ENTER -> R.string.sent_key_enter
            Protocol.KEY_TAB -> R.string.sent_key_tab
            Protocol.KEY_BACKSPACE -> R.string.sent_key_backspace
            else -> R.string.placeholder_dash
        }
    )

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
