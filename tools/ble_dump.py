import asyncio
from bleak import BleakScanner

async def main():
    devs = await BleakScanner.discover(timeout=12.0, return_adv=True)
    print(f"saw {len(devs)} device(s):")
    for addr,(d,adv) in devs.items():
        uuids = ",".join(adv.service_uuids or [])
        print(f"  {addr}  rssi={adv.rssi}  name={d.name!r}  local={adv.local_name!r}  uuids=[{uuids}]")

asyncio.run(main())
