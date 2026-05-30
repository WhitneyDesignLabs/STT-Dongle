# Session Log — 2026-05-29/30 (hardware bring-up on ESP32-C6 proxy)

What got built and proven this session, and how to pick up when the ESP32-S3 arrives.

## ✅ Proven on real hardware (ESP32-C6 BLE proxy)
The C6 has BLE but **no USB-OTG**, so it can't be a USB-HID keyboard — it was flashed
with `firmware-ble-test/` (echoes received text to serial) to validate the **entire
BLE half** before the S3 lands. Confirmed end-to-end:
- Phone app **scan → connect → MTU → chunked writes** ✓
- **Dictation → BLE → dongle**, text arriving **complete and in order** across
  multiple BLE writes (chunking + ordering) ✓
- Verified by reading the dongle's USB-serial: `cat /dev/ttyACM0` shows
  `[BLE] rx … / [TYPED] …`.

## Key findings
- **Connect/disconnect churn + repeated pairing chimes** were caused by a **stale
  bond**: after erasing/reflashing the C6, the phone still tried to encrypt with old
  keys the wiped dongle no longer had → link dropped → reconnect/re-pair loop.
- Switching the C6 test build to **open write (no bonding)** killed the chimes and
  the churn; connection is **stable** now. → set via `#define REQUIRE_BONDING 0` in
  `firmware-ble-test.ino` (production S3 firmware keeps bonding = 1).
- A bonded device only appears in Android's Bluetooth settings; the open build
  doesn't pair, so it **won't** show there — that's expected (the app finds it by
  BLE scan, not the system pairing list).
- **Dictation dies when the screen dims/sleeps** (Android suspends SpeechRecognizer).
  "Speak too long → text lost" was really the screen dimming mid-utterance.

## App state — v0.8.0 (`STT-Keyboard-debug.apk`)
- v0.3: review-hardened BLE (GATT-leak guards, single-thread write pipeline).
- v0.4: **multi-dongle** — dongles advertise unique `STT-Keyboard-XXXX` names; app
  matches the `STT-Keyboard` prefix and **remembers** the chosen dongle (tap another
  in the console to switch).
- v0.5: **keep screen awake while dictating** (`FLAG_KEEP_SCREEN_ON`) so it can't
  dim/sleep mid-utterance. (Foreground/background screen-off survival is task #10.)
- v0.6: **on-screen version label** (top-right, from packageInfo).
- v0.7: **Tier-1 special keys** — Enter/Tab/Backspace via on-screen buttons **and**
  whole-utterance voice commands ("new line"/"tab"/"backspace"). Sent as raw control
  bytes (`BleManager.sendKey`); firmware maps `0x0A/0x09/0x08` → Enter/Tab/Backspace.
- v0.8: **spoken punctuation** — "period"→".", "comma"→",", "question mark"→"?",
  "exclamation point"→"!", colon/semicolon/dash/hyphen/open+close paren, inline on the
  text path (printable ASCII, no firmware change). `Protocol.applySpokenPunctuation`.
- BLE Console (gear): scan/connect, name/address/RSSI/MTU, send-test-text, send-delay.

## Known limitations / field observations (all anticipated by the spec — polish, not surprises)
- **Voice punctuation required.** On-device ASR won't infer punctuation from prosody;
  the user speaks it (v0.8) — or add a post-processing layer later.
- **"new line" = Enter = send** in chat boxes. There's no soft-vs-hard distinction yet:
  the Tier-2 Control characteristic (#13) lets us map Shift+Enter (soft newline) vs
  Enter (send), as most chat apps do.
- **Silence chime every few seconds while listening.** Android's one-shot SpeechRecognizer
  hits its end-of-speech timeout on silence, returns empty, and the app re-arms it →
  a beep per restart. Fix later: suppress the system beep, or use a continuous/streaming
  recognizer. (Tracked.)
- **Non-prose strings (URLs, file paths, emails, code) dictate poorly.** ASR sentence-
  capitalizes ("Http", "Www") and spaces things oddly. Needs explicit dictation and/or a
  post-processing pass (force lowercase, collapse "dot org"→".org", suppress auto-caps).
- **Screen-off / backgrounding drops dictation** (#10) — v0.5 keeps the screen awake while
  foregrounded; full background survival needs a foreground service.
- **Write ordering is solid:** the serialized FIFO queue sends text chunks then the Enter
  keypress in order (one write outstanding at a time), so a "text… then new line" sequence
  flushes all text before Enter fires — no race.

## Firmware/tooling additions
- **LED activity indicator** on the C6 (`firmware-ble-test`): RGB on GPIO8 lights amber
  while text flows in. Port to the S3's LED pin later (task #11).
- **`CLEAN_SERIAL` mode** (C6, overridable `#define`): emits a *raw byte stream* (no
  debug markers) so a host tool can type it. Default 0 (debug); flash with 1 for the typer.
- **`tools/serial_type.py`** (+ `.bat`): Windows tool — reads the C6's clean serial and
  injects keystrokes (Unicode + Enter/Tab/Backspace) into the focused window via SendInput.
  Turns the C6 proxy into a working software-HID dictation device on Windows today.
  Requires the C6 on a Windows COM port (`usbipd unbind --busid <id>` to release from WSL).

## Open items (next rounds)
- **#10** — foreground service so dictation survives screen-off / backgrounding.
- **#11** — port the LED activity indicator to the S3 (different GPIO).
- Tier-2 special keys via the Control characteristic (arrows, Shift+Enter, shortcuts).
- Mouse / composite HID — **parked** (gets messy; deferred deliberately).
- Tune SpeechRecognizer silence thresholds; per-dongle friendly names; "forget device".

## Resuming on the ESP32-S3 (the real target)
1. Flash production firmware (HID-capable):
   `arduino-cli upload -p <PORT> --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc firmware`
2. Run the milestone plan in `docs/TESTING.md` from **M1** (HID self-test) → **M2**
   (BLE write types) → **M3/M4** (app + dictation = real keystrokes).
3. **Re-validate bonded-link stability on the S3** — the churn here was C6/stale-bond
   specific; the S3's mature BLE stack should bond cleanly. Production keeps bonding.

## Flashing from WSL (reference)
- `usbipd-win` is installed. Bind (one-time, admin/UAC) then attach:
  `usbipd bind --busid <X-Y> --force` → `usbipd attach --wsl --busid <X-Y>`.
- The C6 this session was **busid 4-2** (`303a:1001`, COM20) → `/dev/ttyACM0`.
- `EraseFlash=all` in the FQBN wipes the board first.
- Android toolchain for app builds: `source ~/android-tools/env.sh && (cd android && gradle assembleDebug)`.
