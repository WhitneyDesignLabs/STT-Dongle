# Roadmap & next steps

Where the STT Keyboard Dongle stands, and what's next. For the build/validation
history see [`SESSION-LOG.md`](SESSION-LOG.md); for the frozen BLE contract see
[`PROTOCOL.md`](PROTOCOL.md).

## Where it stands (2026-06-02)

**Working** — public at <https://github.com/WhitneyDesignLabs/STT-Dongle> (MIT).
Current build: **`v0.10` (Build B — auth token)**, the preferred/default firmware.

- ESP32-S3 dongle types live phone dictation into any host over USB-HID — special
  keys (Enter/Tab/Backspace), spoken punctuation, and an activity LED. App v0.10.1.
- **Build B done & validated** (incl. live voice): per-dongle auth token gates
  keystroke injection; the app **tap-to-provisions** the token over BLE on first
  connect (no typing) and re-sends it each reconnect.
- Each dongle advertises a **unique** name (folded from device-unique MAC bytes).
- Three S3 units flashed to the gated production build; six more incoming get the
  same. App is backward compatible (drives deprecated open-build dongles too).

✅ **Security posture:** casual proximity injection is **blocked** (a stranger can't
type without the token). The **open build is deprecated** (opt-out only). Not yet
sniffer-proof — the BLE link is still unencrypted; that's Build C.

## Done

### ✅ Build B — app-level auth token  · task #23
Per-connection token gate on the Control characteristic (`7a9b0002`); token
generated + stored in NVS; **tap-to-provision** via a read-once provisioning
characteristic (`7a9b0003`) with a power-on→first-auth window. Default build
(`REQUIRE_AUTH_TOKEN=1`). Firmware + app + harness, validated end-to-end. See
`PROTOCOL.md` §5 and `SESSION-LOG.md`.

## Next up — the major improvement

### ▶ Build C — full BLE bonding for crypto strength (NEXT major)  · task #22
Restore encrypted + bonded link (LE Secure Connections) for sniffer/MITM
resistance. Requires solving the SMP 30 s teardown first — most likely by switching
the firmware from the Bluedroid BLE stack to **NimBLE**. Build B is the pragmatic
layer that secures everyday use until this lands.

### Provisioning polish (smaller)
- Optional **QR-on-case** provisioning (token printed/scanned, no over-air window) —
  considered for a future/commercial rev; tap-to-provision covers current use.
- App: surface a clear "couldn't auto-provision — power-cycle & retry" message when
  the provisioning window is closed and no token is saved.

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
