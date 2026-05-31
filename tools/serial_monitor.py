#!/usr/bin/env python3
"""
serial_monitor.py — passive, non-resetting serial monitor (Windows).

Reads the dongle's USB-CDC debug serial and timestamps each line to a log file,
WITHOUT toggling DTR/RTS (so opening the port doesn't reset the board / drop the
keyboard). Used to watch the firmware's [BLE] connect/disconnect logs live while
the dongle keeps working as a keyboard on the same (composite) port.

Usage (Windows):  python serial_monitor.py COM23 C:\\path\\to\\ble-monitor.log
"""
import sys
import time

import serial  # pyserial

port = sys.argv[1] if len(sys.argv) > 1 else "COM23"
logpath = sys.argv[2] if len(sys.argv) > 2 else None

s = serial.Serial()
s.port = port
s.baudrate = 115200
# DTR must be asserted or the ESP32 USB-CDC won't transmit (it gates output on a
# "terminal connected" state). Reset-to-bootloader is a 1200-baud touch, NOT a plain
# DTR, so DTR on @115200 enables logs without resetting the board. RTS left low.
s.dtr = True
s.rts = False
s.timeout = 0.3

out = open(logpath, "a", buffering=1, encoding="utf-8") if logpath else sys.stdout


def log(msg):
    out.write(time.strftime("%H:%M:%S ") + msg + "\n")
    out.flush()


try:
    s.open()
except Exception as e:
    log(f"[monitor] could not open {port}: {e}")
    sys.exit(1)

log(f"[monitor] opened {port} @115200 (dtr/rts off) — watching")
try:
    while True:
        try:
            line = s.readline()
        except Exception as e:
            log(f"[monitor] read error: {e}")
            break
        if line:
            log(line.decode("utf-8", "replace").rstrip("\r\n"))
except KeyboardInterrupt:
    pass
finally:
    s.close()
    log("[monitor] closed")
