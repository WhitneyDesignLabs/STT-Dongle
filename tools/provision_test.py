"""Validate the Build B (#23) auth/provisioning window on a dongle, over the PC's BLE.
Usage: python provision_test.py <BLE-ADDRESS>
Reads the provisioning char (token while window open), authenticates, sends text, then
reads again (should be empty — window closed after first auth)."""
import asyncio
import sys
from bleak import BleakClient, BleakScanner
ADDR = sys.argv[1] if len(sys.argv) > 1 else "AA:BB:CC:DD:EE:FF"
PROV = "7a9b0003-9c4e-4f1a-bc23-1e5f3a2d6b00"
CTRL = "7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00"
TEXT = "7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00"

async def main():
    dev = await BleakScanner.find_device_by_address(ADDR, timeout=15.0)
    if dev is None:
        print("Dongle not found in scan"); return
    async with BleakClient(dev) as c:
        print("connected:", c.is_connected)
        t1 = bytes(await c.read_gatt_char(PROV)).decode("ascii", "replace")
        print(f"read #1 (window OPEN)  -> token = {t1!r}")
        await c.write_gatt_char(CTRL, t1.strip().encode("ascii"), response=True)
        print("wrote token to control (auth)")
        await c.write_gatt_char(TEXT, b"PROV-OK ", response=True)
        print("wrote text 'PROV-OK'")
        t2 = bytes(await c.read_gatt_char(PROV)).decode("ascii", "replace")
        print(f"read #2 (after auth)   -> token = {t2!r}  (expect EMPTY)")

asyncio.run(main())
