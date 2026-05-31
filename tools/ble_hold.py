#!/usr/bin/env python3
"""
ble_hold.py — connect to the STT-Keyboard dongle from THIS machine (PC Bluetooth)
and just HOLD the link, logging connect/disconnect with timestamps. A/B test vs the
phone: if the PC link also drops on a ~31 s cadence, the dongle is the cause; if it
holds, the phone/Android side is. Does NOT bond or write (pure link-stability probe).

Run on Windows (phone's Bluetooth OFF so only the PC connects):
    python ble_hold.py            # 150 s hold
"""
import asyncio
import sys
import time

from bleak import BleakClient, BleakScanner

NAME_PREFIX = "STT-Keyboard"
SVC = "7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00"
HOLD_S = int(sys.argv[1]) if len(sys.argv) > 1 else 150


def ts():
    return time.strftime("%H:%M:%S")


async def main():
    print(ts(), "scanning ~8s for STT-Keyboard ...", flush=True)
    dev = None
    found = await BleakScanner.discover(timeout=8.0, return_adv=True)
    for d, adv in found.values():
        name = (adv.local_name if adv else None) or d.name
        uuids = [u.lower() for u in (getattr(adv, "service_uuids", None) or [])]
        if (name and name.startswith(NAME_PREFIX)) or SVC in uuids:
            dev = d
            print(ts(), f"found {name!r} [{d.address}]", flush=True)
            break
    if not dev:
        print(ts(), "STT-Keyboard not found (phone BT off? dongle powered/advertising?)", flush=True)
        return 1

    dropped_at = []
    t0 = time.time()

    def on_disconnect(_c):
        print(ts(), f"*** stack reported DISCONNECT at +{time.time()-t0:.0f}s ***", flush=True)
        dropped_at.append(time.time() - t0)

    print(ts(), "connecting ...", flush=True)
    try:
        async with BleakClient(dev, disconnected_callback=on_disconnect) as client:
            print(ts(), f"CONNECTED (is_connected={client.is_connected}); holding {HOLD_S}s, watching for drops", flush=True)
            t_connect = time.time()
            while time.time() - t_connect < HOLD_S:
                await asyncio.sleep(2)
                if not client.is_connected:
                    print(ts(), f"link is down at +{time.time()-t_connect:.0f}s", flush=True)
                    break
            up = time.time() - t_connect
            print(ts(), f"held for {up:.0f}s; drops observed: {len(dropped_at)}", flush=True)
    except Exception as e:
        print(ts(), f"connect/hold error: {e}", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
