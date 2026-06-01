# STT Keyboard Dongle

Talk into your phone; the words type themselves into any computer through a tiny
USB dongle that looks like an ordinary keyboard. No software, drivers, or pairing
on the target machine.

```
  Android phone            ESP32-S3 dongle              Target computer
  mic → on-device ASR  ──BLE──▶  BLE peripheral  ──USB-HID──▶  sees a normal
  BLE central          write text  + USB HID kbd  types keys    USB keyboard
```

## See it in action

<table>
  <tr>
    <td align="center" width="34%">
      <img src="docs/images/hardware1.jpg" alt="The 3D-printed STT dongle held in hand, status LED glowing through the translucent case" width="270"><br>
      <sub><b>The dongle</b> — an ESP32-S3 in a 3D-printed case. The status LED glows through the print; it plugs into any host as an ordinary USB keyboard.</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/images/software1.jpg" alt="STT Keyboard Android app main screen — connected and ready to dictate" width="175"><br>
      <sub><b>Main screen</b> — connected (“Ready”), a live transcript, Enter/Tab/Bksp keys, and a big <i>Start dictation</i> button.</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/images/software2.jpg" alt="STT Keyboard Android app BLE Console — connected to the dongle, showing live signal and MTU (address masked)" width="175"><br>
      <sub><b>BLE Console</b> (gear) — connected and <b>Ready</b>: live device name, signal (−50 dBm) and negotiated MTU, plus send-test-text, a per-write delay slider, and a nearby-devices list for switching dongles. <sub>(BLE address masked.)</sub></sub>
    </td>
  </tr>
</table>

See [`stt-keyboard-dongle-spec.md`](stt-keyboard-dongle-spec.md) for the product
spec, [`PROTOCOL.md`](PROTOCOL.md) for the frozen BLE contract, and
[`SESSION-LOG.md`](SESSION-LOG.md) for the latest build/validation status.

---

## Status — INTERNAL RELEASE `v0.9.0-internal` (open build)

**Working on real hardware.** The ESP32-S3 is flashed and runs as a USB-HID keyboard;
phone dictation types into any computer end-to-end (special keys + punctuation + activity
LED), validated over BLE with a stable link. Shipped for **personal/internal use**.

⚠️ **Security:** this release uses an **open BLE write** (no pairing) — chosen because the
bonded path hits the BLE SMP 30-second timeout and drops the link (see `SESSION-LOG.md`).
So **any nearby BLE device (~10 m) can inject keystrokes** (BadUSB-class, local only).
**Fine for a private bench; do not leave it unattended on CNC/robotics/shared machines.**
Next build (B, task #23) adds an app-level auth token; full bonding (C, #22) is future.

| Path | What it is | Status |
|------|-----------|--------|
| `firmware/firmware.ino` | **ESP32-S3** production: USB-HID keyboard + BLE GATT + paced typing + Enter/Tab/Backspace; `REQUIRE_BONDING` flag (**0/open in this release**) | ✅ flashed + working on the S3 |
| `firmware-ble-test/` | **ESP32-C6** BLE proxy: same BLE/UUIDs, echoes text to serial; LED activity; `CLEAN_SERIAL` raw-stream mode; `REQUIRE_BONDING` toggle | ✅ flashed + validated |
| `android/` | Native Kotlin app: BLE central + on-device STT | ✅ builds |
| `STT-Keyboard-debug.apk` | Installable app — **v0.9.0** | ✅ on the phone |
| `tools/stt_send.py` | Python (bleak) BLE harness — phone stand-in (Milestone 2) | ✅ |
| `tools/serial_type.py` | **Windows**: reads the dongle's serial and types it into the focused window (software HID, for the C6 proxy) | ✅ |
| `tools/watch-install.sh` | adb auto-install watcher | ✅ |
| `install.sh` / `install.bat` | One-command APK install | ✅ |
| `docs/` | BUILD_FLASH, TESTING (incl. M0 C6 pre-test), HARDWARE, TROUBLESHOOTING | ✅ |
| `PROTOCOL.md` | Frozen BLE contract | ✅ |

### App features (v0.9.0)
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

---

## License

[MIT](LICENSE) © 2026 Scott Whitney / Whitney Design Labs.

> ⚠️ This release ships an **open BLE link** (no pairing) — see the Status section
> above. It's a deliberate, documented trade-off for private/internal use; **do not
> deploy it on shared, unattended, or safety-critical machines** without first adding
> the auth-token (build B) or bonding (build C) layer on the roadmap.
