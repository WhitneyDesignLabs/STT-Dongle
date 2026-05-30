# TROUBLESHOOTING — STT Dongle

Symptom → cause → fix. Grouped by layer (USB/HID, keystrokes, BLE/bonding,
Android, adb). Cross-refs: `BUILD_FLASH.md`, `HARDWARE.md`, `TESTING.md`,
`PROTOCOL.md` (frozen BLE contract), spec §5–§8.

---

## A. HID doesn't enumerate (host sees no keyboard)

The host never shows a keyboard device; Milestone 1 self-test types nothing
(`TESTING.md` M1).

**Cause 1 — wrong USB port (UART-bridge instead of native).**
- The UART-bridge connector (CP2102 / CH340 / FTDI) **cannot present HID**. Only
  the **native USB** port can. (spec §2, §4; `HARDWARE.md` §2)
- **Fix:** plug into the connector labeled `USB`/`OTG` (the native one), not the
  `UART` connector. On a single-connector board, that one connector is native.

**Cause 2 — wrong `USBMode` in the FQBN.**
- HID requires **USB-OTG (TinyUSB)** mode: `USBMode=default`. With `USBMode=hwcdc`
  there is **no HID**. (`BUILD_FLASH.md` §0)
- **Fix:** recompile and reflash with FQBN
  `esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc`.

**Cause 3 — board not actually reset / still in download mode after flashing.**
- After flashing, the native USB **re-enumerates** (`BUILD_FLASH.md` §7). If it's
  still in serial-download mode it won't run firmware.
- **Fix:** tap `RESET`/`EN` (no BOOT held) to run the flashed firmware, wait ~2 s.

---

## B. Dropped or garbled keystrokes

Characters are missing, doubled, or wrong when typing (M1 or M2).

**Cause 1 — pacing too fast.** Back-to-back HID reports overrun some hosts
(spec §5.1; `PROTOCOL.md` §6).
- **Fix:** increase **`KEY_DELAY_MS`** in `firmware.ino`. Default is **8 ms**
  (tunable 5–10 ms per spec); try 10–15 ms on a flaky host, reflash, retest.

**Cause 2 — wrong keyboard layout.** Symbols/punctuation come out wrong (e.g. `@`
↔ `"`, `#` ↔ `£`).
- The dongle's ASCII→scancode table assumes the **US keyboard layout**
  (`PROTOCOL.md` §3; spec §5.1). If the **host OS** is set to a non-US layout, the
  scancodes are interpreted differently.
- **Fix (v0):** set the host's active keyboard layout to **US English** while using
  the dongle. Non-US layouts are deliberately out of scope for v0 (spec §9).

**Cause 3 — central not serializing writes (out-of-order/dropped over BLE).**
- See §D — the central must keep only one GATT write outstanding.

> Note: newline/tab/control bytes never type — that's correct. v0 ignores anything
> outside printable `0x20–0x7E` (`PROTOCOL.md` §3). Not a bug.

---

## C. Upload / flash fails (sync, connection error)

`arduino-cli upload` errors out trying to connect to the board.

- **Auto-reset failed → enter download mode manually:** hold `BOOT` (`IO0`), tap
  `RESET`/`EN`, release `BOOT`, then re-run upload (`BUILD_FLASH.md` §5).
- **Wrong / stale port:** native USB re-enumerates and the port number can change.
  Re-check with `arduino-cli board list` and pass the current `<PORT>`
  (`BUILD_FLASH.md` §3, §7).
- **WSL can't see the port:** flash from Windows with `COMx`, or attach via
  `usbipd attach --wsl --busid <BUSID>` and use `/dev/ttyACM0`
  (`BUILD_FLASH.md` §3). Permission denied on `/dev/ttyACM0` → add user to
  `dialout` group and re-login.

---

## D. BLE won't bond / writes rejected

Phone or harness connects but the write to `7a9b0001-…` fails with
"insufficient authentication / encryption" or is silently ignored.

**Cause — the Text Input characteristic requires an ENCRYPTED, BONDED link.**
(`PROTOCOL.md` §2/§5; spec §7). Writing on a non-bonded link is rejected by design
so a nearby device cannot inject keystrokes.

**Fix:**
1. Make sure you completed the **pairing/bond prompt** ("Just Works", no passkey)
   on first connect. If you dismissed it, the link is encrypted-less.
2. **Clear the stale bond on BOTH ends** and re-pair:
   - Phone: Settings → Bluetooth → forget **`STT-Keyboard`**; in nRF Connect also
     remove the bond.
   - Dongle: erase stored bonds (reflash, or use the firmware's
     clear-bonds/factory-reset path if present), then reset.
3. Reconnect and accept the pairing prompt again. After a successful bond,
   reconnection is automatic (`PROTOCOL.md` §5).

> Mismatched bond state (phone thinks it's bonded, dongle doesn't, or vice-versa)
> is the most common cause — always clear **both** sides together.

---

## E. Android won't connect / connection drops

Vendor BLE stacks vary widely; this is the flakiest part of the project
(spec §6.2). Work through these:

1. **Serialize GATT writes** — keep **only ONE write outstanding at a time**.
   Android allows a single in-flight GATT op; issuing the next before the previous
   completes drops or reorders data. Prefer **Write With Response** for natural
   backpressure (`PROTOCOL.md` §3). This is the most common app-side bug.
2. **MTU:** request **247**, then **read the negotiated MTU** and chunk to
   `negotiatedMTU − 3` — some stacks grant less than 244 (`PROTOCOL.md` §4). Do
   not hardcode 244.
3. **Toggle Bluetooth** on the phone (off/on) to reset a wedged stack.
4. **Forget + re-pair** the dongle (see §D) — clears a half-broken bond.
5. **Auto-reconnect/retry:** implement reconnect-on-drop rather than assuming a
   persistent link (spec §6.2). Some vendors silently drop idle links.
6. **Re-advertise:** the dongle re-advertises immediately on disconnect
   (`PROTOCOL.md` §1); if it doesn't reappear in a scan, reset the dongle.

---

## F. adb: phone not detected (LIVE ISSUE on this machine)

`adb devices` shows nothing with the phone plugged in. Windows adb is at
`/mnt/c/platform-tools/adb.exe`. Work the checklist top to bottom:

1. **Enable Developer Options:** Settings → About phone → tap **Build number** 7×.
2. **Enable the separate `USB debugging` toggle:** Settings → System → Developer
   options → **USB debugging** = ON. (This is a *distinct* switch from enabling
   Developer Options — both are required.)
3. **Set USB connection mode to data, not charge-only:** on the phone's USB
   notification, choose **"File transfer / Android Auto"** (MTP) — **not**
   "Charging only / No data transfer". Charge-only never exposes adb.
4. **Accept the RSA prompt:** unlock the phone and tap **Allow** on the
   *"Allow USB debugging?"* dialog (check "Always allow from this computer"). If it
   never appeared, toggle USB debugging off/on or replug.
5. **Restart the adb server, then list:**
   ```bash
   /mnt/c/platform-tools/adb.exe kill-server
   /mnt/c/platform-tools/adb.exe devices
   ```
   Expect the device serial with `device` (not `unauthorized`, not `offline`).
   - `unauthorized` → the RSA prompt (step 4) was not accepted.
   - `offline` → replug / toggle USB debugging.
6. **Try a different cable/port:** use a known **data** cable (many cables are
   charge-only) and a different USB port. This alone resolves a large share of
   "not detected" cases.

> Note: adb is for installing/debugging the **Android app** (M3/M4). It is **not**
> needed for the dongle hardware itself (M1/M2) — the target computer sees the
> dongle as a plain keyboard with no adb or drivers involved (spec §2).

---

## Quick layer map (where to look)
- Types on boot but not over BLE → BLE/bond layer (§D) or app (§E); HID is fine.
- Types over BLE but app can't → app/Android layer (§E) or adb (§F).
- Nothing types at all → HID/USB layer (§A) first; re-run `TESTING.md` M1.
