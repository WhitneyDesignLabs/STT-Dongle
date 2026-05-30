# BUILD_FLASH — Compiling & Flashing the STT Dongle Firmware

How to build and flash the ESP32-S3 firmware sketch with `arduino-cli`.

- **Sketch:** `/mnt/c/Users/homet/Documents/STT-Dongle/firmware/firmware.ino`
- **Toolchain:** `arduino-cli` 1.4.0, core `esp32:esp32@3.3.5`
- **Board:** ESP32-S3 dev board **with native USB** (see `HARDWARE.md`)
- **Spec refs:** spec §5 (Firmware), §8 Milestone 1; `PROTOCOL.md` §6/§7 (constants)

---

## 0. The one thing you cannot get wrong: the FQBN

USB HID on the S3 **only works in USB-OTG (TinyUSB) mode**. The fully-qualified
board name (FQBN) below selects that mode. Use it for **both** compile and upload.

```
esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc
```

- `USBMode=default` == "USB-OTG (TinyUSB)" — **REQUIRED** for USB HID.
- `USBMode=hwcdc` will **NOT** give HID. Do not use it.
- `CDCOnBoot=cdc` keeps a USB serial port available for logs alongside HID.

If HID never enumerates, the FQBN is the first suspect — see `TROUBLESHOOTING.md`.

---

## 1. One-time setup (verify toolchain)

```bash
arduino-cli version                 # expect 1.4.0
arduino-cli core list               # expect esp32:esp32  3.3.5
```

If the core is missing:

```bash
arduino-cli config init             # if no config yet
arduino-cli config add board_manager.additional_urls \
  https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
arduino-cli core update-index
arduino-cli core install esp32:esp32@3.3.5
```

---

## 2. Compile

Point the command at the **firmware directory** (arduino-cli compiles the sketch
folder containing `firmware.ino`):

```bash
arduino-cli compile \
  --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc \
  /mnt/c/Users/homet/Documents/STT-Dongle/firmware
```

A clean compile prints flash/RAM usage and exits 0. Fix any errors before
flashing. (For Milestone 1 you may set `SELFTEST_TYPE_ON_BOOT=1` in the sketch
before compiling — see `TESTING.md`.)

---

## 3. Find the serial port

You upload over the serial/USB port. Plug into the board's **native USB** port
(see `HARDWARE.md` and §6 below for which port).

### Windows (recommended for flashing)
- Open **Device Manager → Ports (COM & LPT)**. The board shows as `COMx`
  (e.g. `COM5`). Note the number.
- Or list from a Windows shell: `arduino-cli board list` shows `COMx` + detected
  board.

### WSL
WSL does not see USB serial devices by default. Two options:

1. **Flash from Windows (simplest):** run `arduino-cli` from PowerShell/cmd on
   Windows using the `COMx` port. No passthrough needed. This is the path of
   least resistance on this machine.
2. **USB passthrough into WSL via `usbipd`:** from an *admin* PowerShell:
   ```powershell
   usbipd list                       # find the BUSID of the ESP32-S3
   usbipd bind   --busid <BUSID>     # one-time
   usbipd attach --wsl --busid <BUSID>
   ```
   Then inside WSL the device appears as `/dev/ttyACM0` (native-USB CDC) or
   `/dev/ttyUSB0` (UART-bridge chip). Confirm with:
   ```bash
   ls -l /dev/ttyACM* /dev/ttyUSB* 2>/dev/null
   arduino-cli board list
   ```
   - **`/dev/ttyACM0`** = the native-USB CDC port (what you want for HID boards).
   - **`/dev/ttyUSB0`** = a UART-bridge chip (CP210x/CH340). On a board that has
     both, this is the *bridge* port, not the native-USB port.
   - You may need `sudo usermod -aG dialout $USER` (then re-login) for permission.

---

## 4. Upload (flash)

Replace `<PORT>` with your `COMx` (Windows) or `/dev/ttyACM0` (WSL):

```bash
arduino-cli upload \
  -p <PORT> \
  --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc \
  /mnt/c/Users/homet/Documents/STT-Dongle/firmware
```

Examples:
```bash
# Windows
arduino-cli upload -p COM5 --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc /mnt/c/Users/homet/Documents/STT-Dongle/firmware
# WSL with usbipd passthrough
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc /mnt/c/Users/homet/Documents/STT-Dongle/firmware
```

Compile + upload in one step is also fine:
```bash
arduino-cli compile -u -p <PORT> \
  --fqbn esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc \
  /mnt/c/Users/homet/Documents/STT-Dongle/firmware
```

---

## 5. Download / boot mode (if auto-reset fails)

Most S3 boards auto-enter the bootloader for upload. If upload fails with a
connection/sync error, force download mode manually:

1. **Hold the `BOOT` button** (sometimes labeled `IO0`).
2. **Tap (press and release) the `RESET`/`EN` button** while still holding BOOT.
3. **Release `BOOT`.**

The board is now in serial-download mode. Re-run the `upload` command.

> On the native-USB port, entering download mode re-enumerates the device, so the
> port name can change (e.g. it may briefly become a different `COMx` or appear as
> a generic "ESP32-S3" download device). Re-check the port with
> `arduino-cli board list` if upload can't find it.

---

## 6. Which physical USB port

ESP32-S3 dev boards commonly have **two USB connectors**:

- **Native USB ("USB" / "USB-OTG" / labeled `D+`/`D-` to the SoC)** — **USE THIS.**
  This is the only port that can present as a USB HID keyboard and also accepts
  firmware uploads in TinyUSB mode.
- **UART-bridge USB ("UART" / "COM", a CP210x/CH340 chip)** — flashing works here,
  but **HID will not enumerate** through it. Avoid for this project.

Boards with only a single connector wired to the native USB peripheral are simplest
(no ambiguity). See `HARDWARE.md` for telling the ports apart and known-good boards.

---

## 7. Re-enumeration after flashing (expected, not a bug)

Because the firmware drives the **native USB** peripheral, the device tears down
and re-creates its USB identity after a flash/reset:

- The serial/`COMx` (or `/dev/ttyACM0`) port **disappears and reappears** — Windows
  may play the disconnect/connect chime, and the port number can change.
- On the host, the dongle now also enumerates as a **HID keyboard** (Milestone 1
  proof — see `TESTING.md`).
- If you flashed with `usbipd`, the WSL attach may drop on re-enumeration; re-run
  `usbipd attach --wsl --busid <BUSID>` if you need serial again.

This re-enumeration is normal for any native-USB board. Wait ~2s after reset
before expecting the new ports.

---

## See also
- `HARDWARE.md` — choosing the board, identifying the two USB ports, power.
- `TESTING.md` — Milestone 1 (`SELFTEST_TYPE_ON_BOOT`) and beyond.
- `TROUBLESHOOTING.md` — "HID doesn't enumerate", upload sync errors, ports.
- `PROTOCOL.md` — frozen BLE constants the firmware must match.
