# TESTING — Milestone Test Plan

Milestone-by-milestone test plan, mapped one-to-one to **spec §8 (Build /
Milestone Plan)**. Each milestone has concrete steps and explicit pass/fail
criteria. Do not advance to the next milestone until the current one passes —
each one isolates a single layer of the stack.

| # | Spec §8 milestone | Proves | Needs custom code? |
|---|-------------------|--------|--------------------|
| M1 | USB-HID example types a hardcoded string on boot | USB-HID half works, no BLE | No |
| M2 | BLE write characteristic; generic BLE app writes a string → it types | Full hardware chain, no app | No (uses nRF Connect or `stt_send.py`) |
| M3 | Android app: BLE central connects and writes text | App ↔ dongle link | Yes (the app) |
| M4 | Add STT; recognized text → BLE write | End-to-end product | Yes |

Cross-references: `BUILD_FLASH.md` (flashing), `HARDWARE.md` (board/ports),
`TROUBLESHOOTING.md`, and `PROTOCOL.md` (frozen BLE contract).

> **Dongle names.** Each dongle advertises a unique name `STT-Keyboard-XXXX`
> (`XXXX` = last 2 bytes of its chip MAC) so multiple dongles are distinguishable
> in a scan. The app and `stt_send.py` match on the **`STT-Keyboard` prefix** (and
> the service UUID), and the app **remembers the dongle you connect to** and
> auto-reconnects to it; to switch dongles, tap a different one in the BLE Console.

---

## M0 — BLE-only pre-test on a non-HID board (optional, no S3 needed)

**Goal:** validate the *entire BLE half* — advertising, bonding, MTU, chunked
writes, the app's scan/connect/console/send-test and dictation paths — on a board
that has BLE but **no native USB-OTG** (e.g. **ESP32-C6**, C3). These chips have
only a USB Serial/JTAG controller and **cannot be a USB HID keyboard**, so this
build does not type into a computer; instead it **echoes received text to the
serial monitor**. Use it to exercise everything except the final keystroke before
the HID-capable ESP32-S3 boards arrive.

### Steps
1. Flash the BLE-only firmware to the C6:
   ```bash
   arduino-cli compile --fqbn esp32:esp32:esp32c6:CDCOnBoot=cdc \
     /mnt/c/Users/homet/Documents/STT-Dongle/firmware-ble-test
   arduino-cli upload -p <PORT> --fqbn esp32:esp32:esp32c6:CDCOnBoot=cdc,EraseFlash=all \
     /mnt/c/Users/homet/Documents/STT-Dongle/firmware-ble-test
   ```
   (`EraseFlash=all` wipes whatever was on the board first. Find `<PORT>` with
   `arduino-cli board list`; from WSL the port must be forwarded via `usbipd`, or
   flash from Windows / Arduino IDE — board "ESP32C6 Dev Module", "USB CDC On Boot:
   Enabled".)
2. Open the serial monitor at **115200**:
   `arduino-cli monitor -p <PORT> -c baudrate=115200`. You should see
   `[BLE] advertising as STT-Keyboard-XXXX`.
3. In the **app** (v0.4+), open the BLE Console (gear), tap **Scan**, see
   `STT-Keyboard-XXXX`, tap it to connect, and **pair** when prompted.
4. Use **Send test text** (`hello world`) and/or tap **Start dictation** and speak.

### Pass / fail
- **PASS:** the console shows **Ready** with the device name/address/RSSI and a
  negotiated MTU; each string you send (or speak) appears in the serial monitor:
  ```
  [BLE] central connected
  [BLE] rx 12 byte(s): "hello world "
  [TYPED] hello world
  ```
  Long (>244-byte) strings arrive complete and in order (chunking works). This
  proves the full phone→BLE→dongle path; only USB-HID output is untested (the C6
  can't do it).
- **FAIL:**
  - Write rejected / insufficient authentication → not bonded; clear the bond on
    both ends and re-pair (`TROUBLESHOOTING.md`).
  - Connects but no text in the monitor → wrong serial port/baud, or you're looking
    at the upload port not the USB-Serial/JTAG console.

When the ESP32-S3 arrives, flash `firmware/firmware.ino` and start at M1 — the
exact same app drives it, now with real keystrokes.

---

## M1 — USB HID only (no BLE)

**Goal (spec §8 #1):** prove the dongle enumerates as a USB keyboard and can type,
with **zero software on the host**.

### Steps
1. In `/mnt/c/Users/homet/Documents/STT-Dongle/firmware/firmware.ino`, set the
   compile-time flag **`SELFTEST_TYPE_ON_BOOT = 1`**. With this set, the firmware
   types a fixed banner string (e.g. `STT-Keyboard self-test OK`) shortly after
   USB enumerates on boot.
2. Compile and flash per `BUILD_FLASH.md` (FQBN
   `esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc`). Plug into the board's
   **native USB** port.
3. On the host, open any plain text editor (Notepad, gedit, TextEdit, a terminal —
   anything with a text cursor) and **click into it so it has keyboard focus.**
4. Reset the dongle (tap `RESET`/`EN`) so it re-enumerates and runs the boot
   self-test with the editor focused.

### Pass / fail
- **PASS:** within ~1–2 s of boot, the banner string appears in the focused editor,
  typed character by character (pacing visible). The host required **no driver,
  no permission, no pairing**.
- **FAIL:** nothing types, or the host shows no keyboard device.
  - Wrong USB port (UART-bridge instead of native) — see `HARDWARE.md`.
  - Wrong `USBMode` (must be `default`, not `hwcdc`) — see `TROUBLESHOOTING.md`.
  - Garbled/dropped characters → keystroke pacing too fast; bump `KEY_DELAY_MS`
    (PROTOCOL.md §6 default 8 ms) — see `TROUBLESHOOTING.md`.

### After M1
Set **`SELFTEST_TYPE_ON_BOOT = 0`** and re-flash before M2 so the dongle no longer
auto-types on boot and only types what arrives over BLE.

---

## M2 — BLE write → it types (no custom app)

**Goal (spec §8 #2):** a generic BLE central writes a string to the Text Input
characteristic and the dongle types it — proving the **full hardware chain**
(BLE in → ASCII→scancode → USB HID out) with no custom app.

This milestone uses the **frozen BLE contract** in `PROTOCOL.md`:
- Device name: **`STT-Keyboard`**
- Service UUID: **`7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00`**
- Text Input char: **`7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00`** (Write / Write
  Without Response; **requires an encrypted, bonded link**)

### Setup
1. Default firmware (`SELFTEST_TYPE_ON_BOOT = 0`), flashed and plugged into the
   host's native USB port.
2. On the host, open a text editor and give it focus (same as M1).

### Option A — phone with nRF Connect
1. In nRF Connect, **Scan**; find **`STT-Keyboard`** (filter by name or by service
   UUID `7a9b0000-…`).
2. **Connect.** Because the Text Input characteristic requires an encrypted,
   bonded link, the phone will prompt to **pair/bond** ("Just Works", no passkey —
   PROTOCOL.md §5). Accept it.
3. Expand the service `7a9b0000-…`, find characteristic `7a9b0001-…`.
4. Write value — choose **Text/UTF-8**, enter `hello world`, send as a
   **Write Request** (Write With Response preferred — PROTOCOL.md §3).

### Option B — laptop harness `tools/stt_send.py`
This stands in for the phone for M2.
```bash
python3 /mnt/c/Users/homet/Documents/STT-Dongle/tools/stt_send.py "hello world"
```
The harness scans for `STT-Keyboard` / service `7a9b0000-…`, connects, bonds
(Just Works), negotiates MTU (requests 247), and writes the text to `7a9b0001-…`
as serialized Write-With-Response operations, chunked to `negotiatedMTU − 3`
bytes (PROTOCOL.md §3/§4). To exercise chunking, send a string longer than
~244 bytes, e.g.:
```bash
python3 /mnt/c/Users/homet/Documents/STT-Dongle/tools/stt_send.py "$(python3 -c 'print("the quick brown fox "*20)')"
```

### Pass / fail
- **PASS:** `hello world` appears in the focused editor, typed in order with
  pacing. A long string types fully and **in correct order with no gaps**
  (chunking + ordering work). Newline/tab/control bytes sent in the string are
  **silently dropped** (PROTOCOL.md §3 encoding) — only printable `0x20–0x7E`
  appear; this is correct v0 behavior, not a bug.
- **FAIL:**
  - Write is **rejected / insufficient authentication** → the link is not bonded;
    you skipped or dismissed the pairing prompt. Clear any stale bond on both ends
    and re-pair — see `TROUBLESHOOTING.md` ("BLE won't bond / writes rejected").
  - Connects but nothing types → HID side regressed; re-run M1.
  - Characters dropped/garbled/out of order → pacing (`KEY_DELAY_MS`) and/or the
    central is not serializing writes (PROTOCOL.md §3 — one outstanding write).

---

## M3 — Android app connects and writes text

**Goal (spec §8 #3):** the custom Kotlin app (BLE central) connects to the bonded
dongle and writes **hardcoded/test text** — proving the app↔dongle link before
STT is added.

### Steps
1. Build and install the app from
   `/mnt/c/Users/homet/Documents/STT-Dongle/android` (Android Studio, or
   `./gradlew installDebug` once the project exists).
2. Keep the dongle plugged into a host with a focused text editor.
3. Launch the app. It scans for service `7a9b0000-…` / name `STT-Keyboard`,
   connects, and bonds on first run (Just Works). On later launches it
   **auto-reconnects** to the bond (spec §6.2).
4. Use the app's test/debug control to send a hardcoded string (e.g. a "Send test
   text" button writing `app test 123`).

### Pass / fail
- **PASS:** the hardcoded string types into the focused host editor. Closing and
  re-opening the app reconnects **without** re-pairing (bond persists —
  PROTOCOL.md §5). The app requests MTU 247, reads the negotiated value, and
  chunks to `negotiatedMTU − 3` (PROTOCOL.md §4); a >244-byte test string types
  fully and in order.
- **FAIL:**
  - Won't connect or drops repeatedly → vendor BLE quirks; see
    `TROUBLESHOOTING.md` ("Android won't connect / drops").
  - Writes rejected → bonding/encryption issue (PROTOCOL.md §5); forget + re-pair.
  - Out-of-order / dropped text → app is not serializing GATT writes (must keep
    only **one write outstanding** — PROTOCOL.md §3).

---

## M4 — STT end-to-end

**Goal (spec §8 #4):** speak into the app; the recognized text is written over BLE
and typed by the dongle — the complete product.

### Steps
1. In the app, enable speech input (Android on-device `SpeechRecognizer`,
   spec §6.1). Grant microphone permission.
2. Dongle plugged into a host with a focused text editor.
3. Tap to start listening and **speak a short sentence** (e.g. "the quick brown
   fox jumps over the lazy dog").
4. Stop / let it finalize. The app pushes the recognized text to char
   `7a9b0001-…` (serialized, chunked).

### Pass / fail
- **PASS:** the spoken sentence appears typed in the focused host window, in order.
  Latency from finishing speech to typed output is acceptable for use. Long
  utterances (>244 bytes) type fully via chunking. The phone did all STT and
  pacing happened on the dongle (~8 ms/char).
- **FAIL:**
  - Recognition wrong → STT accuracy issue (consider cloud ASR fallback,
    spec §6.1); not a dongle problem.
  - Recognized correctly in-app but mistyped/dropped on host → BLE flow control or
    pacing — revisit M2/M3 criteria and `TROUBLESHOOTING.md`.
  - Punctuation/newlines spoken or inserted don't appear → v0 ignores non-printable
    and only types `0x20–0x7E` (PROTOCOL.md §3); special keys are deferred
    (spec §9). Expected, not a bug.

---

## Regression order
If anything breaks higher up the stack, **bisect downward**: M4 fail → verify M3 →
verify M2 → verify M1. Each milestone isolates exactly one layer, so the lowest
milestone that fails identifies the faulty layer.
