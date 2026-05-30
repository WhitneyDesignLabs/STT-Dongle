#!/usr/bin/env python3
r"""
serial_type.py — Windows: read the STT dongle's serial stream and TYPE it into
whatever window has focus (a software stand-in for the dongle's USB-HID keyboard).

Lets you put the cursor in any field (web form, editor, ...), launch this, and have
your phone dictation appear there — using the ESP32-C6 proxy, before the HID-capable
ESP32-S3 arrives.

Requires the dongle to run the BLE-test firmware in CLEAN_SERIAL mode (raw byte
stream, no debug markers). The C6 must be on a Windows COM port (NOT attached to WSL
via usbipd — run `usbipd detach --busid <id>` first so Windows gets the COM port back).

Deps: pyserial (already installed). No admin needed.
Run (Windows):  python serial_type.py            (auto-detects the ESP32 port)
                python serial_type.py --port COM20
                serial_type.bat                   (double-click wrapper)

Bytes handled: printable US-ASCII 0x20-0x7E -> typed as Unicode; 0x0A -> Enter,
0x09 -> Tab, 0x08 -> Backspace. Everything else ignored. Ctrl+C to stop.
"""
import argparse
import sys
import time

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    sys.stderr.write("ERROR: pyserial not installed. Run: python -m pip install pyserial\n")
    sys.exit(2)

import ctypes
from ctypes import wintypes

# --- Win32 SendInput plumbing (keyboard) ------------------------------------

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
VK_RETURN, VK_TAB, VK_BACK = 0x0D, 0x09, 0x08


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_void_p)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ctypes.c_void_p)]


class _INPUTunion(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("union", _INPUTunion)]


user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT


def _send(inputs):
    n = len(inputs)
    arr = (INPUT * n)(*inputs)
    sent = user32.SendInput(n, arr, ctypes.sizeof(INPUT))
    if sent != n:
        raise ctypes.WinError(ctypes.get_last_error())


def type_unicode(ch):
    """Type one printable character into the focused window."""
    code = ord(ch)
    down = INPUT(INPUT_KEYBOARD, _INPUTunion(ki=KEYBDINPUT(0, code, KEYEVENTF_UNICODE, 0, None)))
    up = INPUT(INPUT_KEYBOARD, _INPUTunion(ki=KEYBDINPUT(0, code, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0, None)))
    _send([down, up])


def tap_vk(vk):
    """Press + release a virtual key (Enter/Tab/Backspace)."""
    down = INPUT(INPUT_KEYBOARD, _INPUTunion(ki=KEYBDINPUT(vk, 0, 0, 0, None)))
    up = INPUT(INPUT_KEYBOARD, _INPUTunion(ki=KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP, 0, None)))
    _send([down, up])


# --- Serial port discovery --------------------------------------------------

ESPRESSIF_VID = 0x303A


def find_port():
    for p in serial.tools.list_ports.comports():
        if p.vid == ESPRESSIF_VID:
            return p.device
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="serial_type.py",
        description="Type the STT dongle's serial stream into the focused window.")
    ap.add_argument("--port", help="COM port (default: auto-detect the ESP32, VID 303A).")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--focus-delay", type=float, default=3.0,
                    help="Seconds to wait after launch so you can click your target field (default 3).")
    ap.add_argument("--no-enter", action="store_true",
                    help="Ignore Enter (0x0A) — handy in web forms where Enter submits.")
    ap.add_argument("--list", action="store_true", help="List serial ports and exit.")
    args = ap.parse_args(argv)

    if args.list:
        for p in serial.tools.list_ports.comports():
            vid = f"{p.vid:04X}" if p.vid else "----"
            print(f"{p.device}\tVID={vid}\t{p.description}")
        return 0

    port = args.port or find_port()
    if not port:
        sys.stderr.write("No ESP32 serial port found. Plug the dongle in, make sure it is on a\n"
                         "Windows COM port (usbipd detach it from WSL), or pass --port COMxx.\n"
                         "Use --list to see available ports.\n")
        return 1

    try:
        ser = serial.Serial(port, args.baud, timeout=0.1)
    except Exception as exc:
        sys.stderr.write(f"Could not open {port}: {exc}\n")
        return 1

    print(f"Connected to {port} @ {args.baud}.")
    print(f"Click your target field now — typing starts in {args.focus_delay:.0f}s. Ctrl+C to stop.")
    time.sleep(args.focus_delay)
    print("Typing... (focused window receives the dictation)")

    try:
        while True:
            data = ser.read(256)
            for b in data:
                if 0x20 <= b <= 0x7E:
                    type_unicode(chr(b))
                elif b == 0x0A:
                    if not args.no_enter:
                        tap_vk(VK_RETURN)
                elif b == 0x09:
                    tap_vk(VK_TAB)
                elif b == 0x08:
                    tap_vk(VK_BACK)
                # else: ignore non-printable/control
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        ser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
