# STT Keyboard Dongle — Project Specification

**Status:** Seed / v0
**Author:** Whitney Design Labs
**Last updated:** 2026-05-29

A portable speech-to-text system. The phone listens and transcribes; a small USB
dongle plugged into any computer emulates a standard USB keyboard and types the
recognized text. The target computer needs no software, no drivers, and no
pairing — it sees an ordinary keyboard.

---

## 1. Goal & Use Case

**One-line:** Walk up to any computer, plug in a small dongle, open the phone
app, talk instead of type, unplug when done.

**Target flow:**

1. Plug the dongle into the target computer's USB-A port.
2. Dongle is bus-powered, boots, enumerates as a USB HID keyboard, and begins
   advertising / reconnecting over BLE.
3. Open the phone app; it auto-reconnects to the bonded dongle.
4. Speak. The phone transcribes and streams the text to the dongle.
5. The dongle types the text into whatever has focus on the target computer.
6. Unplug when finished.

**Explicitly out of scope for v0** (see §8 for deferred items):

- Unplugged / battery operation — the dongle is bus-powered only.
- Non-ASCII / Unicode input — US-ASCII printable characters only.
- Multi-device or shared use — single personal phone, single bonded dongle.

---

## 2. Why This Architecture

- **STT lives on the phone.** Modern phone microphones and on-device ASR are far
  better than anything that would fit on the dongle, and the phone already has
  the compute, connectivity, and battery.
- **The dongle is a USB HID keyboard.** HID boot-keyboard is the most universally
  accepted USB device class — accepted by any OS, at any login screen or BIOS
  prompt, with no driver and no permission. This is what makes it work
  *everywhere* with zero setup on the target.
- **BLE bridges the two.** The phone (BLE central) writes text to the dongle
  (BLE peripheral); the dongle converts text to keystrokes.

**Critical hardware constraint:** the dongle must act as a USB *device*
(peripheral/gadget), not a USB *host*, and its USB peripheral must be able to
present as native HID. Boards that only expose a UART-over-USB bridge chip, or
that can only act as USB host, cannot do this job.

---

## 3. System Architecture

```
   ┌─────────────────┐         BLE         ┌──────────────────┐      USB-HID      ┌────────────────┐
   │   Android Phone │  ───────────────▶   │   ESP32-S3       │  ──────────────▶  │ Target Computer│
   │                 │   write text to     │   Dongle         │  types keystrokes │ (any OS, no SW)│
   │  mic → ASR →    │   GATT char         │  BLE periph +    │  as a standard    │  sees a normal │
   │  BLE central    │                     │  USB HID kbd     │  USB keyboard     │  keyboard      │
   └─────────────────┘                     └──────────────────┘                   └────────────────┘
        powered by phone                    bus-powered (5V from target port)
```

Two independent jobs run concurrently on the dongle: the **USB HID** stack
(types into the host) and the **BLE GATT** server (receives text from the phone).

---

## 4. Hardware

| Item | Spec / Notes |
|------|--------------|
| MCU board | **ESP32-S3 dev board with native USB connector.** Native USB-OTG supports HID device mode; integrated BLE. Single chip does both radios/roles. |
| USB port | Must use the board's **native USB** port, not the UART-bridge USB port. Some S3 boards expose both — using the wrong one means no HID. |
| Power | **Bus-powered** from the target computer's 5V USB. No battery, no charging circuit, no power management for v0. |
| Phone | Any reasonably modern Android device with BLE and on-device speech recognition. |

**BOM for first prototype:** one ESP32-S3 (native-USB) dev board + your phone.
That's the whole prototype.

---

## 5. Firmware (ESP32-S3)

Toolchain: ESP-IDF or Arduino-ESP32, both support TinyUSB on the S3.

### 5.1 USB HID side

- On boot, initialize **TinyUSB in HID keyboard mode** using the standard
  boot-keyboard HID report descriptor.
- Enumerates to the host as a standard keyboard within ~1–2s of plug-in. No
  driver or permission required on any OS.
- **Type-a-character primitive:** send a HID report with `(modifier, keycode)`,
  then send an empty report to release the key.
- **ASCII → scancode table:** fixed lookup. Each printable ASCII character maps
  to a `(modifier, keycode)` pair, where `modifier` carries only the Shift bit
  (for uppercase letters and shifted symbols). Uses the **US keyboard layout**.
- **Keystroke pacing (required):** insert a small delay (~5–10 ms, tunable)
  between reports. Dumping characters back-to-back at full speed causes some
  hosts to drop keys. Pacing also makes typing human-visible.

### 5.2 BLE side

- Advertise a **custom GATT service** on boot.
- **v0 minimum:** one **writable characteristic**. The phone writes a UTF-8/ASCII
  string; the dongle receives the bytes, runs them through the ASCII→scancode
  table, and emits paced HID reports.
- **Bonding:** bond on first pairing so future connections are automatic and
  authenticated. (Security: see §7.)
- Advertise on boot and stay connectable so the phone app can auto-reconnect.

### 5.3 Firmware data flow

```
BLE write (string) → receive buffer → for each char:
    lookup (modifier, keycode) → send HID report → delay → send empty report → delay
```

---

## 6. Android App (Kotlin, BLE Central)

Native Kotlin (Android Studio) — talks directly to the BLE stack; avoids the
extra layer that cross-platform frameworks put between the app and low-level BLE.

### 6.1 Responsibilities

- **STT:** start with the on-device `SpeechRecognizer` (offline-capable on recent
  devices, no key, no cloud). Swap to a cloud ASR only if on-device accuracy is
  insufficient.
- **BLE central:** scan for / auto-reconnect to the bonded dongle on launch;
  write recognized text to the dongle's write characteristic.
- **UX target:** plug in → open app → talk → watch it type. Minimal taps.

### 6.2 Reliability items to build in early

- **MTU negotiation:** request a larger BLE MTU so a full utterance fits in one
  write. Default MTU is small; still implement chunking for strings that exceed
  the negotiated MTU.
- **Auto-reconnect / retry:** Android BLE behavior varies across phone vendors
  (connection reliability, MTU, reconnection). This is the flakiest part of the
  whole project — plan for connection-dropped/retry handling rather than assuming
  a clean persistent link.

---

## 7. Security

A BLE-controlled device that types into any computer is functionally the same
category as a "BadUSB" HID-injection tool. For personal use that is the intended
behavior, but:

- **Bond / authenticate the BLE link** so only the paired phone can drive the
  dongle — a nearby device must not be able to push keystrokes.
- Be conscious that "a wireless device that types into any computer" is a framing
  that draws scrutiny if this ever moves beyond personal use.

---

## 8. Build / Milestone Plan

Reach a working "write text over BLE → it types into any computer" device before
writing any custom app code.

| # | Milestone | Proves |
|---|-----------|--------|
| 1 | Flash ESP32-S3 with a TinyUSB HID example; it types a hardcoded string into a computer on boot. | USB-HID half works, no BLE. |
| 2 | Add a BLE GATT service with one write characteristic. Use a generic BLE app (e.g. nRF Connect) to write a string; dongle types it. | Full hardware chain works, no custom app. |
| 3 | Build the Android app: BLE central that connects and writes text. Test with hardcoded strings. | App ↔ dongle link works. |
| 4 | Add STT; wire recognized text to the BLE write. | End-to-end product. |

Milestone 2 is reachable in an evening or two on hardware already on the bench.
Milestones 3–4 are the larger effort — Android BLE plumbing, not anything exotic.

---

## 9. Open Decisions / Future (post-v0)

- **Special keys:** add a control characteristic (or in-band escape sequences)
  for Enter, Tab, Backspace, arrows, etc. v0 is printable ASCII only.
- **Status characteristic:** expose "connected / ready / typing" state to the app.
- **Unicode / non-US layouts:** would require OS-specific Unicode input methods,
  which breaks the "works identically everywhere" promise. Deliberately deferred.
- **Enclosure / battery:** only if an unplugged-then-plugged-later mode is ever
  wanted. Not needed for the plug-in-only use case.
- **Keystroke pacing tuning:** confirm a delay value that's reliable across the
  target machines actually used.

---

## 10. Tech Stack Summary

| Layer | Choice |
|-------|--------|
| Dongle MCU | ESP32-S3 (native USB) |
| USB stack | TinyUSB, HID keyboard (boot protocol), US layout |
| Wireless | BLE — dongle = peripheral/GATT server, phone = central |
| GATT (v0) | One writable characteristic (text in) |
| Phone app | Native Kotlin / Android Studio |
| STT | Android on-device `SpeechRecognizer` (cloud ASR as fallback) |
| Power | Bus-powered from target USB 5V |
