# STT-Keyboard — Desktop BLE Test Harness

`stt_send.py` is a cross-platform command-line BLE central that **stands in for
the Android phone**. It connects to the STT-Keyboard dongle and writes text to
the Text Input characteristic, so you can test the dongle firmware at
**Milestones 2–3 before the Android app exists**.

It is the scripted, repeatable replacement for poking the dongle by hand in
**nRF Connect**: instead of pasting hex into a characteristic and tapping write,
you run one command (or pipe a file in), and it scans, connects, filters,
chunks to the negotiated MTU, and serializes the writes exactly the way the
phone is required to.

Protocol source of truth: [`../PROTOCOL.md`](../PROTOCOL.md) (v0, frozen). The
constants in `stt_send.py` are copied from there.

| Item | Value |
|------|-------|
| Device name | `STT-Keyboard` |
| Service UUID | `7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00` |
| Text Input char | `7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00` (Write + Write Without Response) |
| Encoding | US-ASCII printable `0x20`–`0x7E` only; everything else is dropped |
| MTU | requests 247; chunks to `negotiatedMTU − 3` |
| Flow control | Write With Response, one write outstanding at a time |
| Security | LE Secure Connections + bonding, "Just Works" |

---

## Install

Python 3.8+ and a working BLE adapter required.

```bash
# from this tools/ directory
python -m venv .venv

# activate the venv:
#   Linux / macOS:
source .venv/bin/activate
#   Windows (PowerShell):
#   .venv\Scripts\Activate.ps1
#   Windows (cmd):
#   .venv\Scripts\activate.bat

python -m pip install -r requirements.txt
```

The only dependency is [`bleak`](https://github.com/hbldh/bleak), the
cross-platform BLE library (uses BlueZ on Linux, WinRT on Windows, CoreBluetooth
on macOS).

---

## Usage

`stt_send.py` writes all status/log output to **stderr** and keeps **stdout**
clean (scan results print as `ADDRESS<TAB>NAME`), so it scripts cleanly.

### Scan only — find the dongle (replaces "scan" in nRF Connect)

```bash
python stt_send.py --scan-only
python stt_send.py --scan-only --verbose          # show non-matching devices too
python stt_send.py --scan-only --scan-timeout 15  # longer scan
```

Lists every device advertising the service UUID or the name `STT-Keyboard`.

### Send a one-off string (positional)

```bash
python stt_send.py "hello world"
```

### Send the contents of a file or a pipe (`--stdin`)

```bash
cat utterance.txt | python stt_send.py --stdin
echo "transcribed text from the STT engine" | python stt_send.py --stdin
```

`--stdin` is the closest match to "the phone got a transcription and forwarded
it": long text is automatically split into back-to-back `MTU−3` writes.

### Interactive REPL (`--interactive`)

```bash
python stt_send.py --interactive
```

Each line you type + Enter becomes one write to the dongle. Ctrl-D (EOF) quits.
Great for live-watching the dongle type into a text editor.

### Target a specific device

```bash
python stt_send.py --address AA:BB:CC:DD:EE:FF "hello"   # Linux/Windows (MAC)
python stt_send.py --address 0000-... "hello"            # macOS (CoreBluetooth UUID)
```

On macOS, BLE peripherals are identified by an opaque CoreBluetooth UUID, not a
MAC address — copy the value `--scan-only` prints.

### Other options

```bash
python stt_send.py "type me five times" --repeat 5
python stt_send.py "slow it down" --char-delay 0.05   # client-side delay between chunks
python stt_send.py "debug me" --verbose               # MTU, chunking, per-write detail
```

`--char-delay` defaults to **0** because keystroke pacing lives on the dongle
(~8 ms/char). Use it only if you want to deliberately throttle the harness.

### Exit codes

`0` success · `1` failure (no device, connect/write error) · `130` Ctrl-C.

---

## How this replaces nRF Connect for Milestone 2

Milestone 2 is "the dongle accepts a BLE write and types it over USB-HID." With
nRF Connect you would manually scan, connect, bond, find the `7a9b0001`
characteristic, type bytes in as hex, and tap write — every time, by hand.

`stt_send.py` does all of that in one command **and enforces the parts of the
contract the phone must honor**, which nRF Connect does not do for you:

- **ASCII filtering** — drops anything outside `0x20–0x7E` (with a warning),
  exactly like the dongle, so what you send is what should be typed.
- **MTU-aware chunking** — reads the negotiated MTU (`client.mtu_size`) and
  splits to `MTU−3` bytes, instead of you guessing a payload size.
- **Serialized Write-With-Response** — awaits each write so only one GATT
  operation is ever outstanding (the rule that bites on Android). nRF Connect
  lets you fire writes without that discipline.
- **Repeatable + scriptable** — pipe a file, loop with `--repeat`, drive it from
  a test script. No tapping a phone screen.

When the Android app is ready, it must behave the same way; this harness is the
reference for "what the phone does," and stays useful for regression testing the
firmware in isolation.

---

## Per-OS pairing / bonding notes

The Text Input characteristic requires an **encrypted, bonded** link (Just
Works). `bleak` does not implement pairing uniformly across platforms; on the
first connection you usually have to bond **once** at the OS level, after which
reconnection is automatic.

### Linux (BlueZ)

`bleak` uses BlueZ over D-Bus. BlueZ typically triggers Just Works pairing
automatically when the script first writes to the encrypted characteristic. If
the write fails with an "insufficient authentication/encryption" error, bond the
dongle once with `bluetoothctl`:

```bash
bluetoothctl
  power on
  agent on
  default-agent
  scan on            # wait until STT-Keyboard / its address appears
  scan off
  pair AA:BB:CC:DD:EE:FF
  trust AA:BB:CC:DD:EE:FF
  quit
```

Then run `stt_send.py` normally. (BlueZ usually ignores explicit MTU requests
and auto-negotiates, so a smaller `MTU−3` is expected and handled.)

### Windows 10/11 (WinRT)

`bleak` uses the WinRT BLE stack. Easiest path: pair the dongle once through
**Settings → Bluetooth & devices → Add device → Bluetooth**, accept the Just
Works prompt, then run the script. WinRT generally honors a 247-byte MTU
request, so you'll usually see the full `244`-byte payload. If a write fails
with an access/encryption error, remove the device in Settings and re-add it to
re-bond.

### macOS (CoreBluetooth)

`bleak` uses CoreBluetooth, which performs pairing on demand: the OS pops the
pairing dialog the first time the script accesses the encrypted characteristic —
accept it. Devices are addressed by an opaque per-host UUID (not a MAC), so use
the value from `--scan-only` with `--address`. On recent macOS the app/terminal
also needs **Bluetooth permission** (System Settings → Privacy & Security →
Bluetooth) granted to your terminal or Python.

---

## Notes & limitations (v0)

- Only the **Text Input** characteristic (`7a9b0001`) is used. Control
  (`7a9b0002`) and Status (`7a9b0003`) are reserved and intentionally
  **not** implemented here.
- Newline, tab, and all other control/non-ASCII characters are filtered out
  before sending — the dongle ignores them anyway. Special keys are deferred.
- If `client.mtu_size` is unavailable on a backend, the harness falls back to a
  conservative 20-byte payload (ATT default `23 − 3`) so it never overruns.

---

# `serial_type.py` — Windows software-HID (serial → keystrokes)

A separate tool: reads the **dongle's serial output** and **types it into whatever
window has focus** (web form, editor, …) via Win32 `SendInput`. This makes the
**ESP32-C6 proxy a working dictation device on Windows today**, before the
HID-capable ESP32-S3 arrives — the C6 can't be a USB keyboard, but the PC-side
script injects the keystrokes for it.

**Setup**
1. Flash the C6 BLE-test firmware in **`CLEAN_SERIAL=1`** mode (raw byte stream, no
   debug markers): edit the `#define`, or
   `arduino-cli compile --build-property "compiler.cpp.extra_flags=-DCLEAN_SERIAL=1" ...`.
2. Make sure the C6 is on a **Windows COM port** (if it's attached to WSL via usbipd,
   run `usbipd unbind --busid <id>` from an elevated prompt to release it back to Windows).
3. `python serial_type.py --list` to find the port (auto-detects Espressif VID `303A`).

**Run (Windows)**
```
python tools\serial_type.py          # auto-detect port, 3s to focus your field
python tools\serial_type.py --port COM20 --focus-delay 5
python tools\serial_type.py --no-enter   # ignore Enter (handy in web forms that submit on Enter)
tools\serial_type.bat                 # double-click wrapper
```
Click your target field, then dictate on the phone → it types there. Handles
printable ASCII + Enter/Tab/Backspace. Deps: `pyserial` (already installed); no admin.
