# HARDWARE — Choosing & Wiring the Dongle Board

What board to buy, how to identify the right USB port, and how it is powered.
Spec refs: spec §2 (hardware constraint), §4 (Hardware), §10 (tech stack);
`PROTOCOL.md` for the wireless contract this board implements.

---

## 1. The board: ESP32-S3 with native USB-OTG

The dongle MCU is an **ESP32-S3 dev board with a native USB connector**. The S3
is chosen because a single chip does **both** jobs the dongle needs:

- **USB device (peripheral) mode with native USB-OTG** → can present as a real USB
  **HID keyboard** (boot protocol). This is the whole reason for the project —
  the target computer sees an ordinary keyboard, no driver, no setup (spec §2).
- **Integrated BLE** → acts as the BLE peripheral / GATT server the phone writes
  text to (`PROTOCOL.md`).

### Critical constraint (spec §2)
The dongle must act as a USB **device**, and that device must be able to present
as **native HID**. This rules out:

- **UART-bridge-only boards.** Many cheap dev boards expose USB *only* through a
  serial-bridge chip (CP2102 / CH340 / FTDI). That chip can flash the MCU and
  carry a serial console, but it **cannot present a HID keyboard** to the host.
  A board whose only USB connector goes to such a bridge **cannot do this job.**
- **USB-host-only setups.** The dongle must be the device/gadget, not the host.

If a board can only do UART-over-USB, or can only be a USB host, it is the wrong
board — full stop.

---

## 2. The two USB ports — how to tell them apart

Most ESP32-S3 dev boards ship with **two USB connectors**. Using the wrong one is
the #1 reason HID "doesn't work" (see `TROUBLESHOOTING.md`). Tell them apart:

| | Native USB (USE THIS) | UART-bridge USB (do NOT use for HID) |
|---|---|---|
| Silk-screen label | `USB`, `USB-OTG`, `OTG`, or `D+`/`D-` to the SoC | `UART`, `COM`, or near a CP2102/CH340 chip |
| Wired to | the ESP32-S3's built-in USB peripheral | a separate serial-bridge IC on the board |
| Does HID? | **Yes** | **No** |
| Flashes firmware? | Yes (in TinyUSB mode) | Yes |
| Serial port shows as | `/dev/ttyACM0` (WSL) / a USB CDC `COMx` | `/dev/ttyUSB0` (WSL) / a CP210x/CH340 `COMx` |

Ways to confirm which is which:
- **Look for a bridge chip** silk-screened CP2102 / CH340 / FTDI near one
  connector — that connector is the **bridge** (not for HID).
- **Trace the label.** The connector marked `USB`/`OTG` (not `UART`) is native.
- **Enumeration name.** On the native port the device appears as a USB CDC/ACM
  (and, once HID firmware runs, also as a keyboard); the bridge port appears as a
  Silicon Labs / WCH serial adapter.
- **Single-connector boards** wire that one connector straight to the native USB
  peripheral — no ambiguity, simplest choice.

Plug the dongle into the **native** port for normal operation **and** for
flashing in this project (see `BUILD_FLASH.md`).

---

## 3. Power (v0)

- **Bus-powered only.** The dongle draws **5 V from the target computer's USB
  port**. (spec §4, §10)
- **No battery, no charging circuit, no power management** in v0. (spec §1
  out-of-scope; §9 enclosure/battery deferred)
- Implication: the dongle is live only while plugged in. It boots, enumerates as
  HID, and starts BLE advertising/reconnecting on plug-in (spec §1 target flow).

---

## 4. Pin / wiring notes

**None required for v0.** The dongle uses only:
- the **native USB connector** (HID out + power in), and
- the **on-chip radio** (BLE).

No GPIO, no external sensors, no soldering, no level shifting. The BOM is "one
ESP32-S3 native-USB dev board + your phone" (spec §4).

---

## 5. What to buy — known-good board examples

Pick any ESP32-S3 dev board that exposes the chip's **native USB**. Known-good
families (verify the specific SKU has the native-USB connector, not bridge-only):

- **Espressif ESP32-S3-DevKitC-1 / ESP32-S3-DevKitM-1** — two USB ports; one is
  labeled **`USB`** (native) and one **`UART`** (bridge). Use the `USB` one.
- **Adafruit ESP32-S3 boards (Feather ESP32-S3, QT Py ESP32-S3)** — native USB on
  the single USB-C connector; well-supported by TinyUSB.
- **Seeed XIAO ESP32-S3** — single USB-C, native USB. Compact, good dongle form
  factor.
- **LilyGO / generic ESP32-S3 dev boards** — fine **only if** they break out the
  native USB; many do and label it `USB` vs `UART`.

Buying checklist:
1. Chip is **ESP32-S3** (not S2-only, not classic ESP32 — those lack the same
   native-USB + BLE combo this project relies on).
2. Board exposes the **native USB** connector (labeled `USB`/`OTG`), ideally
   single-connector to avoid port confusion.
3. Confirmed working with Arduino-ESP32 / TinyUSB.

Avoid: boards advertised only as "USB-to-serial" / "CH340" with no native-USB
connector — they **cannot enumerate as HID** (§1).

---

## See also
- `BUILD_FLASH.md` — flashing, FQBN, which port, re-enumeration.
- `TESTING.md` — Milestone 1 proves HID enumerates from this hardware.
- `TROUBLESHOOTING.md` — "HID doesn't enumerate → wrong USB port".
