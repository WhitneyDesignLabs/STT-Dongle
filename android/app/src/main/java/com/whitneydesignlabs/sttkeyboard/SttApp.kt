package com.whitneydesignlabs.sttkeyboard

import android.app.Application

/**
 * Application subclass. Owns the single, app-scoped [BleManager] so that both the
 * main dictation screen and the BLE console screen drive the *same* connection
 * (navigating between activities must not drop or duplicate the GATT link).
 */
class SttApp : Application() {
    /** Process-wide BLE central. Created lazily on first access. */
    val ble: BleManager by lazy { BleManager(this) }
}
