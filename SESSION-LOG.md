# Session Log — 2026-05-29/30 (hardware bring-up on ESP32-C6 proxy)

What got built and proven this session.

## 🌐 2026-05-31 (session 2) — PUBLIC on GitHub + README screenshots
Made the repo **public**: <https://github.com/WhitneyDesignLabs/STT-Dongle> (MIT,
default branch `main`, tag `v0.9.0-internal` pushed).

- **Pre-publish secret sweep** (5 independent finders over all 47 tracked files +
  the full git history, with a verification pass) came back **clean**: no API
  keys/tokens/private keys, no secret files ever tracked, git history clean. Only
  "review" items were the local Windows username `homet` in some paths + the
  author email in commit metadata.
- **Genericized local paths** before publishing: `install.sh` and
  `tools/watch-install.sh` now self-locate the APK via `wslpath` (no hardcoded
  username); docs use `/path/to/STT-Dongle`.
- Added **MIT `LICENSE`** + a README License section restating the open-link caveat.
- README now has a **"See it in action"** showcase: the 3D-printed dongle, the app
  main screen, and the **connected** BLE Console. The console shot's BLE MAC is
  pixel-masked (the raw unmasked capture was never committed). Image tool on this
  box for any future redaction: **PIL/Pillow** (no ImageMagick).
- Repurposed the stale `MORNING.md` → forward-looking [`ROADMAP.md`](ROADMAP.md).

**NEXT SESSION:** Build **B** (task #23) — app-level auth token so the open link
can't be casually injected into. Then Build **C** (#22, full bonding via NimBLE).
See `ROADMAP.md`.

## 📦 2026-05-31 — INTERNAL RELEASE `v0.9.0-internal` (open build)
**Shipped for personal/internal use.** App **v0.9.0** + firmware **open build**
(`REQUIRE_BONDING 0`). Phone dictation → BLE → ESP32-S3 USB-HID keyboard types into any
computer; special keys + punctuation; activity LED. Stable: holds the BLE link with no
drops (paragraph-long dictation, clean).

**BLE bonding saga — resolved by diagnosis, deferred by design:** earlier builds dropped
the link every ~31 s (constant re-pair chimes). Root cause, proven by A/B test (a
non-pairing PC bleak central was *also* dropped at exactly +31 s, and an open build held
steady): the **BLE Security Manager (SMP) 30-second timeout** on the bonded/security path
— *not* the phone, Android, or app logic. Tried: both-direction IRK exchange, connection
supervision-timeout (6 s) — neither fixed it. The open build removes the SMP path → stable.

**Security posture of this release (known, accepted for now):** the open write characteristic
means **any nearby BLE device (~10 m) can connect and inject keystrokes** — BadUSB-class,
~2/10 difficulty with a free app, *local/proximity only (not remote)*. Acceptable for
private bench use; **do NOT leave it connected unattended on CNC/robotics or shared machines.**

**Security roadmap:** **B (next build, task #23)** — keep the stable open link + an app-level
**auth token** the firmware requires before it will type (blocks casual injection, ~6–7/10;
not sniffer-proof). **C (future, task #22)** — proper BLE bonding for crypto strength, once
the SMP instability is solved (likely a NimBLE switch).

## 🎉 2026-05-30 — ESP32-S3 FLASHED + VALIDATED END-TO-END (the real product works)
The production board arrived and worked nearly first try. `firmware/firmware.ino` flashed
to the S3 via its **native USB port** (`USBMode=default`, erase) → the board enumerated on
the host as a **standard HID Keyboard Device** (no driver), paired cleanly over BLE, and
**typed live phone dictation into a Windows field** — including special keys and punctuation.
All four spec §8 milestones met on real hardware (M1 HID, M2 BLE→types, M3 app↔dongle,
M4 STT end-to-end). Bonding was stable (the C6 churn was indeed stale-bond/test-build
specific). The `serial_type.py` bridge is now obsolete — the dongle IS the keyboard.

**Note:** the board has TWO USB-C ports — the **native USB** one (Espressif `303a:1001`,
also the HID port) and a **CH343 UART** bridge (`1a86:55d3`). Use the native port for both
flashing and HID. Remaining work is polish only (see Open items): Tier-2 control char,
screen-off dictation, silence chime, non-prose post-processing, enclosure.

**Hardware units:** 3× ESP32-S3 (QFN56 rev v0.2) flashed with identical production
firmware as spares; each has its own BLE name `STT-Keyboard-XXXX` (so pair each one
separately). Flashing a fresh board: plug the **native USB-C** port (comes up as
`303a:1001` USB-Serial/JTAG, flashes directly — no BOOT/RESET needed), `usbipd
bind --force` + `attach --wsl`, then `arduino-cli compile --upload --fqbn
esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc,EraseFlash=all firmware`. Afterward
`usbipd unbind --all` so the boards aren't claimed by usbipd on this PC (a "Shared"
board won't act as a keyboard here — but works fine on any other computer). 3D-printed
cases in progress.

---

(Earlier in the session, before the S3 arrived:)

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

## App state — v0.9.0 (`STT-Keyboard-debug.apk`) — SHIPPABLE for personal use
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
- v0.9: mute `STREAM_MUSIC` while listening to suppress the recognizer restart chime
  (partial — earcon is on a protected stream on the Pixel; full fix = on-device
  recognizer, tabled in #14).
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
- **LED activity indicator**: RGB lights amber while text flows in — on the C6
  (`firmware-ble-test`, GPIO8) and now on the **S3 production firmware** (GPIO48,
  `RGB_BUILTIN`). Confirmed working on the S3. (#11 done.)
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
