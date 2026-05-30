# Good morning — here's where the STT Keyboard Dongle stands

**TL;DR:** The whole project got built overnight. The demo Android app **compiled
into a real APK** and is sitting ready to install. The only thing standing between
you and the app running on your phone is one USB setting on the phone — about 10
seconds of tapping.

---

## ✅ What got built (and verified) overnight

| Piece | State |
|------|-------|
| **ESP32-S3 firmware** | Written and **compiles clean** (USB-HID keyboard + BLE bonded write char + paced typing). 52% flash. |
| **Android demo app** | Written **and built into `STT-Keyboard-debug.apk`** (5.7 MB). BLE central + on-device speech-to-text + live UI. |
| **Python test harness** | `tools/stt_send.py` — drives the dongle from a laptop so you can test it before the app. |
| **Docs** | `docs/BUILD_FLASH.md`, `TESTING.md`, `HARDWARE.md`, `TROUBLESHOOTING.md`. |
| **Build toolchain** | Installed a full Android toolchain (JDK 17 + SDK 34 + Gradle 8.7) in `~/android-tools` / `~/android-sdk`, no admin needed. |

The board isn't here yet, so the dongle half can't be flashed — but it's ready the
moment it arrives.

---

## 📱 Get the app on the phone — do this first (≈10 seconds)

Last night Windows could see the phone ("Pixel 8a") but it was in a **charging-only
USB state with no data interface** — so adb couldn't reach it. Developer Options
being on isn't enough; you also need **USB debugging on** and the USB mode set to
**data**.

On the phone:
1. **Settings → Developer options → USB debugging → ON.**
2. Pull down the notification shade, tap the **"Charging this device via USB"**
   notification, and choose **File transfer / Android Auto** (anything that's *not*
   "No data transfer").
3. A **"Allow USB debugging?"** dialog will pop up → tap **Allow** (check
   "Always allow from this computer").

That's it. A watcher is already running in the background and **will auto-install
and launch the app within ~20 seconds** of the phone authorizing.

- Watch it happen:  `cat ~/stt-install-watch.log`
- Or install manually anytime:  `bash install.sh`  (or double-click `install.bat`)
- Last-resort fallback: copy `STT-Keyboard-debug.apk` onto the phone and tap it
  (enable "install unknown apps" if prompted).

If `bash install.sh` still says *unauthorized*, re-check step 1–3 above, or try a
different USB cable/port (a data cable, not charge-only). Full checklist:
`docs/TROUBLESHOOTING.md`.

---

## 🎬 Demo script (once the app is on the phone)

The app **works standalone right now**, with no dongle:

1. Open **STT Keyboard**. Grant the **microphone** and **nearby-devices/Bluetooth**
   permissions when asked.
2. Status will read **"Disconnected"** (expected — there's no dongle yet) with a
   red dot.
3. Tap the big gold **Start dictation** button and talk.
4. Watch your words appear live in the **Live transcript** box, and each finished
   sentence land in **Last sent** — that's exactly the text it will type into a
   computer once the dongle is plugged in.

When the ESP32-S3 arrives and is flashed, the status flips to **Ready/Typing** and
the same finished sentences get typed into whatever has focus on the target PC.

---

## ⏭️ What's next (needs the board)

1. Flash the firmware: `docs/BUILD_FLASH.md` (FQBN `...:USBMode=default,CDCOnBoot=cdc`).
2. **Milestone 1:** set `SELFTEST_TYPE_ON_BOOT 1` in `firmware/firmware.ino`, flash,
   open a text editor — it should type a banner on boot (proves USB-HID).
3. **Milestone 2:** with default firmware, run `python tools/stt_send.py "hello world"`
   from a laptop — it should type into the focused window (proves the BLE→HID chain).
4. **Milestones 3–4:** open the app, let it pair (first write triggers bonding), then
   speak. End-to-end.

Test plan with pass/fail criteria for each: `docs/TESTING.md`.
