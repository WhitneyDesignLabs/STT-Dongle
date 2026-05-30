# STT Keyboard Dongle

Talk into your phone; the words type themselves into any computer through a tiny
USB dongle that looks like an ordinary keyboard. No software, drivers, or pairing
on the target machine.

```
  Android phone            ESP32-S3 dongle              Target computer
  mic → on-device ASR  ──BLE──▶  BLE peripheral  ──USB-HID──▶  sees a normal
  BLE central          write text  + USB HID kbd  types keys    USB keyboard
```

See [`stt-keyboard-dongle-spec.md`](stt-keyboard-dongle-spec.md) for the product
spec, [`PROTOCOL.md`](PROTOCOL.md) for the frozen BLE contract, and
[`SESSION-LOG.md`](SESSION-LOG.md) for the latest build/validation status.

---

## Status

The **ESP32-S3** (HID-capable) board is on order. Everything that doesn't need it
is **built and validated on real hardware** using an **ESP32-C6 as a BLE proxy**
(C6 has BLE but no USB-OTG, so it echoes received text to serial instead of typing).
Proven end-to-end: phone → BLE → dongle, dictation, chunking/ordering, special keys.

| Path | What it is | Status |
|------|-----------|--------|
| `firmware/firmware.ino` | **ESP32-S3** production: USB-HID keyboard + bonded BLE GATT + paced typing + Enter/Tab/Backspace | ✅ compiles (52% flash) — awaits board |
| `firmware-ble-test/` | **ESP32-C6** BLE proxy: same BLE/UUIDs, echoes text to serial; LED activity; `CLEAN_SERIAL` raw-stream mode; `REQUIRE_BONDING` toggle | ✅ flashed + validated |
| `android/` | Native Kotlin app: BLE central + on-device STT | ✅ builds |
| `STT-Keyboard-debug.apk` | Installable app — **v0.7.0** | ✅ on the phone |
| `tools/stt_send.py` | Python (bleak) BLE harness — phone stand-in (Milestone 2) | ✅ |
| `tools/serial_type.py` | **Windows**: reads the dongle's serial and types it into the focused window (software HID, for the C6 proxy) | ✅ |
| `tools/watch-install.sh` | adb auto-install watcher | ✅ |
| `install.sh` / `install.bat` | One-command APK install | ✅ |
| `docs/` | BUILD_FLASH, TESTING (incl. M0 C6 pre-test), HARDWARE, TROUBLESHOOTING | ✅ |
| `PROTOCOL.md` | Frozen BLE contract | ✅ |

### App features (v0.7.0)
- Live on-device dictation → BLE → dongle, chunked & in order
- **BLE Console** (gear icon): scan/connect, device name/address/RSSI/MTU, send-test-text, send-delay
- **Multi-dongle**: dongles advertise unique `STT-Keyboard-XXXX` names; app matches the prefix and **remembers** the chosen dongle (tap another to switch)
- **Special keys** (Tier 1): Enter / Tab / Backspace via on-screen buttons **and** voice ("new line", "tab", "backspace")
- **Keep-screen-awake** while dictating; on-screen **version label**

---

## Quick start

### Phone app
Install `STT-Keyboard-debug.apk` (copy to the phone and tap, or `install.sh` /
`install.bat` over adb). Runs standalone (live transcript) until a dongle is in range.

### Firmware
```bash
# Production, ESP32-S3 (real keystrokes):
arduino-cli compile --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc firmware
# BLE proxy, ESP32-C6 (echoes to serial; for app/protocol testing without HID):
arduino-cli compile --fqbn esp32:esp32:esp32c6:CDCOnBoot=cdc firmware-ble-test
```
`USBMode=default` (TinyUSB) is **required** for HID on the S3. See `docs/BUILD_FLASH.md`.

### Test the dongle without the phone (Milestone 2)
```bash
cd tools && python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
python stt_send.py "hello world"     # writes text to the dongle
```

### Software-HID on Windows via the C6 proxy (no S3 needed)
Flash the C6 with `CLEAN_SERIAL=1`, put it on a Windows COM port, then:
```
python tools\serial_type.py          # types the dongle's serial into the focused field
```
Now dictate on the phone → it types into any Windows form. See `tools/README.md`.

---

## Build milestones (spec §8)
1. **USB-HID only** — `SELFTEST_TYPE_ON_BOOT 1`, flash S3, watch it type a banner.
2. **BLE → types** — `stt_send.py "hello"` writes; dongle types.
3. **App ↔ dongle** — app connects and writes text.
4. **STT end-to-end** — speak, watch it type.

M0 (BLE-only pre-test on the C6) is done; M1–M4 add real keystrokes on the S3.
See `docs/TESTING.md`.
