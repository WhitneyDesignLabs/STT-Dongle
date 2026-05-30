# STT Keyboard — Android App (BLE Central)

Native Kotlin app for the **STT Keyboard Dongle** project. The phone listens to
your speech, recognizes it on-device, and streams the recognized text over BLE to
the dongle, which types it into whatever computer it's plugged into.

> **Workflow:** plug the dongle into a host → open this app → tap the mic → talk →
> watch it type. (Spec §6.1 UX target.)

This module is the **BLE central**. The frozen wire contract lives in
[`../PROTOCOL.md`](../PROTOCOL.md) and is mirrored byte-for-byte in
[`Protocol.kt`](app/src/main/java/com/whitneydesignlabs/sttkeyboard/Protocol.kt).

---

## What it does

- **On-device speech-to-text** via `android.speech.SpeechRecognizer` with
  `EXTRA_PREFER_OFFLINE` (no API key, no cloud). Continuous dictation: each final
  utterance is forwarded; the recognizer is restarted to keep listening.
- **BLE central**: scans for the dongle by **service UUID**
  `7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00` (and/or the name `STT-Keyboard`),
  connects, requests MTU 247, discovers the **Text Input** characteristic
  `7a9b0001-…`, and writes recognized text to it.
- **Serialized, chunked writes**: text is encoded to US-ASCII printable
  (`0x20`–`0x7E`, others dropped), a trailing space is appended after each
  utterance, then it's chunked to `negotiatedMTU − 3` bytes and sent **one write
  at a time** (Write With Response) — the next chunk goes out only after
  `onCharacteristicWrite`. This matches PROTOCOL.md §3/§4.
- **Bonding**: the Text Input char requires an encrypted, bonded link. The first
  write triggers Android "Just Works" pairing; the app watches
  `ACTION_BOND_STATE_CHANGED` and retries the pending write once `BOND_BONDED`.
- **Auto-reconnect**: on disconnect, exponential backoff capped at ~10s. After the
  first bond, reconnection needs no user interaction (PROTOCOL.md §5).

---

## Project shape

```
android/
├─ settings.gradle, build.gradle, gradle.properties      # root Gradle (Groovy DSL)
├─ gradle/wrapper/gradle-wrapper.properties               # pins Gradle 8.7
└─ app/
   ├─ build.gradle, proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ res/…                                            # layout, theme, strings, icon
      └─ java/com/whitneydesignlabs/sttkeyboard/
         ├─ Protocol.kt        # UUIDs + constants + ASCII encoder (mirrors PROTOCOL.md)
         ├─ BleManager.kt      # scan/connect/bond/MTU/discovery + serialized write queue
         ├─ SttManager.kt      # continuous SpeechRecognizer dictation
         ├─ MainActivity.kt    # UI + permissions + lifecycle glue
         └─ SttApp.kt          # Application subclass
```

- **applicationId / namespace:** `com.whitneydesignlabs.sttkeyboard`
- **AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24**
- **compileSdk 34 / targetSdk 34 / minSdk 26** (Android 8.0+)
- AndroidX + Material 3 + ViewBinding; coroutines for async glue. No third-party
  BLE library — uses the platform `android.bluetooth.le` APIs directly.

---

## How to open / build / install

This repo is hand-authored source. **There is no `gradle-wrapper.jar`** committed —
that binary can't be hand-written. Android Studio regenerates it on first open,
or you can generate it from a CLI Gradle install:

```bash
# Option A — Android Studio (recommended):
#   File ▸ Open… ▸ select the `android/` folder.
#   Studio downloads Gradle 8.7 (per gradle-wrapper.properties) and syncs.

# Option B — command line, if you already have Gradle 8.7+ installed:
cd android
gradle wrapper --gradle-version 8.7   # creates gradlew + gradle-wrapper.jar
./gradlew assembleDebug                # builds app/build/outputs/apk/debug/

# Install onto a connected device:
./gradlew installDebug
# or:  adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Requires Android 8.0 (API 26) or newer** on the device. On-device speech
recognition (and an offline language pack) must be available for dictation —
most Pixel / recent devices have it; otherwise the app shows
"Speech recognition is not available on this device."

---

## Permissions rationale

| Permission | API level | Why |
|---|---|---|
| `RECORD_AUDIO` | all | Microphone input for speech recognition. |
| `BLUETOOTH_SCAN` (`neverForLocation`) | 31+ | Scan for the dongle. We never derive location from BLE, so we opt out of location coupling. |
| `BLUETOOTH_CONNECT` | 31+ | Connect, bond, request MTU, and write GATT characteristics. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | ≤30 | Legacy BLE access on Android 11 and below. |
| `ACCESS_FINE_LOCATION` | ≤30 | Pre-Android-12, a BLE scan that can surface device addresses requires location permission. |
| `INTERNET` | all | Reserved for a future cloud-ASR fallback (spec §6.1). **Not used in v0.** |

`<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>`
is declared — the app is useless without BLE.

Runtime permissions (mic + the correct BLE set for the device's API level) are
requested at launch. If Bluetooth is off, the app prompts to enable it.

---

## Notes / limits (v0)

- **Only the Text Input characteristic is implemented.** The Control (`7a9b0002`)
  and Status (`7a9b0003`) UUIDs are **reserved** and intentionally absent here.
- Newline/tab/control characters are dropped before sending (the dongle ignores
  them in v0); special keys are deferred to the reserved Control char.
- Keystroke pacing (~8 ms/char) lives on the **dongle**; the phone does not pace.
- Android BLE reliability varies by vendor (spec §6.2) — hence MTU read-back,
  chunking fallback to a safe 20-byte floor, single-outstanding writes, and
  backoff reconnection. Expect to tune these on real hardware.
```
