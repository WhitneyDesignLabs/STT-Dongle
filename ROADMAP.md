# Roadmap & next steps

Where the STT Keyboard Dongle stands, and what's next. For the build/validation
history see [`SESSION-LOG.md`](SESSION-LOG.md); for the frozen BLE contract see
[`PROTOCOL.md`](PROTOCOL.md).

## Where it stands (2026-05-31)

**Shipped and working** — internal release `v0.9.0-internal`, now public at
<https://github.com/WhitneyDesignLabs/STT-Dongle> (MIT).

- ESP32-S3 dongle types live phone dictation into any host over USB-HID — special
  keys (Enter/Tab/Backspace), spoken punctuation, and an activity LED. App v0.9.0.
- 3× S3 units flashed as spares; 3D-printed cases.
- BLE link is **stable** in the shipped build.

⚠️ **Security posture (deliberate, documented):** this build uses an **open BLE
write** (no pairing) because the bonded path hits the BLE Security-Manager (SMP)
30-second timeout and drops the link (see `SESSION-LOG.md`). So any nearby BLE
device (~10 m) can inject keystrokes — BadUSB-class, **local/proximity only**.
Fine for a private bench; **not** for unattended/shared/CNC/robotics machines.
Closing this gap is the headline of the next build.

## Next up — the major improvements

### ▶ Build B — app-level auth token (NEXT)  · task #23
Keep the stable open link, but require a shared secret before the firmware will
type. Blocks casual injection without re-entering the SMP/bonding swamp.
- Firmware: on connect, ignore Text-Input writes until a correct token is written
  to a (new) control characteristic — or prefix-frame each payload with the token.
  Store the token in NVS; generate/show it once.
- App: store the token (per-dongle), send it on connect / with writes.
- Decide token UX: QR/printed pairing code, or a value shown in the BLE Console.
- Threat level after B: casual injection blocked (~6–7/10); not sniffer-proof
  (the link is still unencrypted) — that's what Build C fixes.

### Build C — full BLE bonding for crypto strength (FUTURE)  · task #22
Restore encrypted + bonded link (LE Secure Connections). Requires solving the SMP
30 s teardown first — most likely by switching the firmware from the Bluedroid
BLE stack to **NimBLE**, which handles the security flow differently. This is the
real fix; Build B is the pragmatic stopgap until it lands.

## Polish & feature backlog (smaller, independent)

- **#10** — keep dictation alive when the phone screen sleeps (foreground service).
- **#13** — Tier-2 special keys via the Control characteristic (arrows, Shift+Enter,
  shortcut combos).
- **#14** — silence chime on recognizer restart (continuous/streaming recognizer, or
  fully suppress the earcon).
- **#15** — non-prose dictation (URLs / file paths / emails / code) post-processing.
- **#17** — cold-boot reliability + an accessible reset in the enclosure.
- **#18** — media / consumer-control HID keys via voice.
- **#19** — text snippets / macros via voice.
- **#20** — physical re-pair / clear-bond button + richer RGB status.
- **#21** — WiFi OTA updates + a web config page.

## Picking up next session

1. Skim `SESSION-LOG.md` (top entry) for the latest state.
2. Start **Build B** (#23): sketch the token handshake on the Control characteristic,
   add it behind a firmware flag, mirror it in the app, bump to v0.10.
3. Build/flash refs are in `docs/BUILD_FLASH.md`; app build is
   `source ~/android-tools/env.sh && (cd android && gradle --no-daemon assembleDebug)`.
