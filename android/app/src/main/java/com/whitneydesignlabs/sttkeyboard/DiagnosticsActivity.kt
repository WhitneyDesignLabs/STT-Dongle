package com.whitneydesignlabs.sttkeyboard

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.whitneydesignlabs.sttkeyboard.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.launch

/**
 * BLE console / diagnostics screen. Drives the *same* app-scoped [BleManager] as
 * the main screen (via [SttApp.ble]), so connect/disconnect here is the real link.
 *
 * Shows live connection state, the connected dongle's name/address, RSSI and the
 * negotiated MTU; lets you scan + pick a device, send arbitrary test text to the
 * dongle without speaking, and tune an optional client-side send delay.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val ble: BleManager by lazy { (application as SttApp).ble }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // Send test text -> the dongle's write queue (no speech needed).
        binding.btnSend.setOnClickListener { sendTestText() }
        binding.editTestText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTestText(); true } else false
        }

        binding.btnScan.setOnClickListener { ble.rescan() }
        binding.btnDisconnect.setOnClickListener { ble.disconnect() }

        // Send-delay slider (client-side spacing between GATT writes; dongle also
        // paces keystrokes). Reflect the value live.
        binding.seekPacing.progress = ble.interWriteDelayMs
        binding.textPacingValue.text = getString(R.string.console_pacing_fmt, ble.interWriteDelayMs)
        binding.seekPacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                ble.interWriteDelayMs = value
                binding.textPacingValue.text = getString(R.string.console_pacing_fmt, value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        observeFlows()
    }

    private fun sendTestText() {
        val text = binding.editTestText.text?.toString().orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.console_send_empty)); return
        }
        if (ble.state.value != BleManager.ConnectionState.READY &&
            ble.state.value != BleManager.ConnectionState.TYPING
        ) {
            toast(getString(R.string.console_send_not_ready)); return
        }
        ble.sendText(text)
    }

    private fun observeFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ble.state.collect { binding.textConsoleStatus.text = statusLabel(it) } }
                launch {
                    ble.deviceName.collect { renderDevice() }
                }
                launch {
                    ble.deviceAddress.collect { renderDevice() }
                }
                launch {
                    ble.rssi.collect {
                        binding.textConsoleRssi.text =
                            if (it == 0) getString(R.string.placeholder_dash)
                            else getString(R.string.console_rssi_fmt, it)
                    }
                }
                launch {
                    ble.mtu.collect {
                        binding.textConsoleMtu.text =
                            if (it == 0) getString(R.string.placeholder_dash)
                            else getString(R.string.console_mtu_fmt, it)
                    }
                }
                launch { ble.scanResults.collect { renderDeviceList(it) } }
            }
        }
    }

    private fun renderDevice() {
        val name = ble.deviceName.value
        val addr = ble.deviceAddress.value
        binding.textConsoleDevice.text = when {
            name != null && addr != null -> "$name\n$addr"
            addr != null -> addr
            else -> getString(R.string.placeholder_dash)
        }
    }

    /** Tracks the displayed device set so we only rebuild when it actually changes. */
    private var shownDeviceKey: String = ""

    /** Rebuild the tap-to-connect device list from the latest scan results. */
    private fun renderDeviceList(devices: List<BleManager.ScannedDevice>) {
        binding.textNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        // scanResults re-emits on every advertisement (RSSI churn). Only rebuild the
        // list when the SET of devices changes, or the rows would flicker constantly.
        val key = devices.joinToString(",") { it.address }
        if (key == shownDeviceKey) return
        shownDeviceKey = key

        val container = binding.devicesContainer
        container.removeAllViews()
        for (d in devices) {
            val row = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            val name = d.name ?: getString(R.string.console_unknown_device)
            row.text = "$name\n${d.address}   ${getString(R.string.console_rssi_fmt, d.rssi)}"
            row.isAllCaps = false
            row.setOnClickListener { ble.connectToAddress(d.address) }
            container.addView(row)
        }
    }

    /** Human-readable status label (mirrors MainActivity's status_* strings). */
    private fun statusLabel(state: BleManager.ConnectionState): String = getString(
        when (state) {
            BleManager.ConnectionState.DISCONNECTED -> R.string.status_disconnected
            BleManager.ConnectionState.SCANNING -> R.string.status_scanning
            BleManager.ConnectionState.CONNECTING -> R.string.status_connecting
            BleManager.ConnectionState.BONDING -> R.string.status_bonding
            BleManager.ConnectionState.READY -> R.string.status_ready
            BleManager.ConnectionState.TYPING -> R.string.status_typing
        }
    )

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
