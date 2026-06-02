#!/usr/bin/env python3
"""
stt_send.py — Desktop BLE test harness for the STT-Keyboard dongle.

Stands in for the Android phone (the BLE central) so the dongle firmware can be
exercised at Milestones 2-3 before the Android app exists. Replaces poking at the
dongle by hand with nRF Connect.

Protocol source of truth: ../PROTOCOL.md (v0, frozen).

  Device name        : "STT-Keyboard"
  Service UUID       : 7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00
  Text Input char    : 7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00
                       Write (with response) + Write Without Response,
                       requires an ENCRYPTED, BONDED link to write.
  Encoding           : US-ASCII printable 0x20-0x7E only; everything else is
                       silently ignored by the dongle, so we drop it here too.
  MTU                : phone requests 247 -> ATT payload = negotiatedMTU - 3.
                       We chunk to (client.mtu_size - 3) and send back-to-back.
  Flow control       : Write With Response, exactly one write outstanding at a
                       time (we await each write -> naturally serialized).
  Security           : LE Secure Connections + bonding, "Just Works".

Requires: bleak (see requirements.txt). Python 3.8+.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from typing import List, Optional

try:
    from bleak import BleakClient, BleakScanner
    from bleak.backends.device import BLEDevice
except ImportError:  # pragma: no cover - import guard for friendlier UX
    sys.stderr.write(
        "ERROR: the 'bleak' package is not installed.\n"
        "       python -m pip install -r requirements.txt\n"
    )
    sys.exit(2)


# --- Protocol constants (copied from PROTOCOL.md §7) -------------------------

DEVICE_NAME = "STT-Keyboard"
SVC_TEXT_INPUT_UUID = "7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00"
CHR_TEXT_INPUT_UUID = "7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00"
CHR_CONTROL_UUID    = "7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00"  # Build B (#23) auth-token
REQUESTED_MTU = 247

ASCII_MIN = 0x20  # space
ASCII_MAX = 0x7E  # tilde

# Conservative ATT payload fallback if the backend won't expose mtu_size.
# 23 is the BLE default ATT MTU; 23 - 3 = 20 bytes guaranteed everywhere.
DEFAULT_ATT_MTU = 23
SCAN_TIMEOUT_S = 8.0


# --- Logging ----------------------------------------------------------------

_VERBOSE = False


def log(msg: str) -> None:
    """Always-on user-facing log line (to stderr; stdout stays clean)."""
    sys.stderr.write(msg + "\n")
    sys.stderr.flush()


def vlog(msg: str) -> None:
    """Verbose-only log line."""
    if _VERBOSE:
        sys.stderr.write(msg + "\n")
        sys.stderr.flush()


# --- Text filtering ---------------------------------------------------------

def filter_ascii_printable(text: str) -> tuple[bytes, int]:
    """
    Keep only US-ASCII printable bytes 0x20-0x7E, matching the dongle, which
    silently ignores anything else (newline, tab, control, non-ASCII).

    Returns (filtered_bytes, dropped_count).
    """
    kept = bytearray()
    dropped = 0
    for ch in text:
        code = ord(ch)
        if ASCII_MIN <= code <= ASCII_MAX:
            kept.append(code)
        else:
            dropped += 1
    return bytes(kept), dropped


def chunk_payload(data: bytes, max_chunk: int) -> List[bytes]:
    """Split data into back-to-back chunks of at most max_chunk bytes."""
    if max_chunk < 1:
        max_chunk = 1
    return [data[i:i + max_chunk] for i in range(0, len(data), max_chunk)]


# --- Scanning ---------------------------------------------------------------

def _name_of(device: BLEDevice, adv) -> Optional[str]:
    """Best-effort device name from the advertisement or the device record."""
    name = None
    if adv is not None:
        name = getattr(adv, "local_name", None)
    if not name:
        name = getattr(device, "name", None)
    return name


def _advertises_service(adv) -> bool:
    if adv is None:
        return False
    uuids = getattr(adv, "service_uuids", None) or []
    return SVC_TEXT_INPUT_UUID.lower() in {u.lower() for u in uuids}


async def scan_for_dongles(timeout: float = SCAN_TIMEOUT_S):
    """
    Scan and return a de-duplicated list of (BLEDevice, advertisement) tuples
    that match our service UUID or the advertised name.
    """
    log(f"Scanning {timeout:.0f}s for service {SVC_TEXT_INPUT_UUID} "
        f"or name '{DEVICE_NAME}' ...")

    # discover(return_adv=True) -> dict[address] = (BLEDevice, AdvertisementData)
    discovered = await BleakScanner.discover(timeout=timeout, return_adv=True)

    matches = []
    for device, adv in discovered.values():
        name = _name_of(device, adv)
        by_svc = _advertises_service(adv)
        by_name = (name == DEVICE_NAME)
        if by_svc or by_name:
            how = []
            if by_svc:
                how.append("service-uuid")
            if by_name:
                how.append("name")
            matches.append((device, adv))
            vlog(f"  match: {device.address}  name={name!r}  via={','.join(how)}")
        else:
            vlog(f"  skip:  {device.address}  name={name!r}")
    return matches


async def resolve_device(address: Optional[str], timeout: float):
    """
    Resolve a target BLEDevice.

    If an address override is given, try a targeted lookup first (fast path),
    then fall back to a full scan filtered by that address.  Otherwise scan and
    pick the first matching STT-Keyboard.  Returns a BLEDevice or None.
    """
    if address:
        vlog(f"Address override: {address} (targeted lookup)")
        device = await BleakScanner.find_device_by_address(address, timeout=timeout)
        if device is not None:
            return device
        log(f"Targeted lookup for {address} failed; falling back to full scan.")
        matches = await scan_for_dongles(timeout=timeout)
        for device, _adv in matches:
            if device.address.lower() == address.lower():
                return device
        # Last resort: address may be advertising without our service/name.
        discovered = await BleakScanner.discover(timeout=timeout, return_adv=True)
        for device, _adv in discovered.values():
            if device.address.lower() == address.lower():
                return device
        return None

    matches = await scan_for_dongles(timeout=timeout)
    if not matches:
        return None
    if len(matches) > 1:
        log(f"Found {len(matches)} matching devices; using the first. "
            f"Use --address to pick one, or --scan-only to list them.")
    return matches[0][0]


# --- Sending ----------------------------------------------------------------

def negotiated_payload_size(client: BleakClient) -> int:
    """
    Compute the max ATT write payload = negotiatedMTU - 3.

    bleak exposes the negotiated MTU as client.mtu_size on most backends. If it
    is unavailable (or implausibly small), fall back conservatively to the BLE
    default (20 usable bytes) so we never overrun a chunk.
    """
    mtu = None
    try:
        mtu = client.mtu_size
    except Exception as exc:  # backend may not implement it
        vlog(f"client.mtu_size unavailable ({exc!r}); using conservative default.")

    if not isinstance(mtu, int) or mtu < DEFAULT_ATT_MTU:
        if mtu is not None:
            vlog(f"Reported mtu_size={mtu!r} implausible; using default "
                 f"{DEFAULT_ATT_MTU}.")
        mtu = DEFAULT_ATT_MTU

    payload = mtu - 3
    if payload < 1:
        payload = 1
    return payload


async def send_text(
    client: BleakClient,
    text: str,
    char_delay: float,
) -> int:
    """
    Filter, chunk, and write text to the Text Input characteristic.

    Writes are Write-With-Response and awaited one at a time, so exactly one
    GATT write is ever outstanding (the rule that matters on Android).

    Returns the number of bytes actually sent (post-filter).
    """
    data, dropped = filter_ascii_printable(text)
    if dropped:
        log(f"WARNING: dropped {dropped} non-printable/non-ASCII char(s) "
            f"(only 0x20-0x7E are typed by the dongle in v0).")
    if not data:
        log("Nothing to send after filtering.")
        return 0

    max_chunk = negotiated_payload_size(client)
    chunks = chunk_payload(data, max_chunk)
    vlog(f"MTU payload = {max_chunk} bytes -> {len(data)} bytes in "
         f"{len(chunks)} chunk(s).")

    sent = 0
    for idx, chunk in enumerate(chunks, start=1):
        # response=True => Write With Response; awaiting serializes the queue.
        await client.write_gatt_char(CHR_TEXT_INPUT_UUID, chunk, response=True)
        sent += len(chunk)
        vlog(f"  wrote chunk {idx}/{len(chunks)} ({len(chunk)} bytes): "
             f"{chunk.decode('ascii')!r}")
        if char_delay > 0 and idx < len(chunks):
            await asyncio.sleep(char_delay)
    return sent


async def interactive_loop(client: BleakClient, char_delay: float) -> None:
    """REPL: read a line from stdin, send it, repeat. EOF/Ctrl-D quits."""
    log("Interactive mode. Type text + Enter to send. Ctrl-D (EOF) to quit.")
    loop = asyncio.get_event_loop()
    while True:
        try:
            # Read stdin off the event loop so we don't block GATT callbacks.
            line = await loop.run_in_executor(None, sys.stdin.readline)
        except (KeyboardInterrupt, EOFError):
            break
        if line == "":  # EOF
            log("\nEOF; leaving interactive mode.")
            break
        # Newline itself is non-printable and gets filtered; that's intended.
        await send_text(client, line.rstrip("\n"), char_delay)


# --- Connect + run ----------------------------------------------------------

async def run_send(args: argparse.Namespace, text_source: Optional[str]) -> int:
    device = await resolve_device(args.address, args.scan_timeout)
    if device is None:
        log("No STT-Keyboard dongle found. "
            "Is it powered and advertising? Try --scan-only, "
            "or pass --address. (See README for per-OS pairing notes.)")
        return 1

    name = getattr(device, "name", None) or DEVICE_NAME
    log(f"Connecting to {name} [{device.address}] ...")

    # bleak handles pairing/bonding implicitly on most platforms when an
    # encrypted characteristic is accessed; explicit pairing differs per OS
    # (see README). We connect, optionally request a larger MTU, then write.
    async with BleakClient(device) as client:
        if not client.is_connected:
            log("Connect reported not-connected.")
            return 1
        log("Connected.")

        # MTU exchange: BlueZ (Linux) usually auto-negotiates and ignores an
        # explicit request; other backends may honor it. Best-effort only.
        try:
            if hasattr(client, "_acquire_mtu"):
                await client._acquire_mtu()  # type: ignore[attr-defined]
        except Exception as exc:
            vlog(f"MTU acquire best-effort failed: {exc!r}")
        vlog(f"Negotiated MTU (client.mtu_size) reported as: "
             f"{getattr(client, 'mtu_size', 'unknown')}")

        # Build B (#23): unlock the dongle by writing the token to the Control char
        # before any text. (No-op against the open/shipped firmware, which has no
        # auth gate and simply accepts the write.)
        if args.token is not None:
            tok = args.token.encode("us-ascii", errors="ignore")
            await client.write_gatt_char(CHR_CONTROL_UUID, tok, response=True)
            log(f"Wrote auth token ({len(tok)} bytes) to Control char.")

        if args.interactive:
            await interactive_loop(client, args.char_delay)
            return 0

        assert text_source is not None
        total = 0
        for n in range(args.repeat):
            if args.repeat > 1:
                vlog(f"--- repeat {n + 1}/{args.repeat} ---")
            total += await send_text(client, text_source, args.char_delay)
        log(f"Done. Sent {total} byte(s) total. Disconnecting.")
    return 0


# --- CLI --------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="stt_send.py",
        description="Desktop BLE test harness for the STT-Keyboard dongle "
                    "(stands in for the phone).",
        epilog="Text is filtered to US-ASCII printable 0x20-0x7E before "
               "sending, matching the dongle. See ../PROTOCOL.md.",
    )
    p.add_argument(
        "text",
        nargs="?",
        help="Text to type on the dongle (positional). "
             "Omit when using --stdin or --interactive.",
    )

    src = p.add_argument_group("input source (choose one)")
    src.add_argument(
        "--stdin",
        action="store_true",
        help="Read all of stdin and send it.",
    )
    src.add_argument(
        "--interactive",
        action="store_true",
        help="REPL: each line you type is sent as a write.",
    )

    conn = p.add_argument_group("connection")
    conn.add_argument(
        "--address",
        metavar="ADDR",
        help="Connect to this BLE address/UUID directly (skip name/service "
             "filtering during selection).",
    )
    conn.add_argument(
        "--token",
        metavar="TOKEN",
        help="Build B (#23): write this auth token to the Control characteristic "
             "right after connecting (before any text), to unlock a dongle running "
             "the REQUIRE_AUTH_TOKEN firmware. Mirrors what the app will do.",
    )
    conn.add_argument(
        "--scan-only",
        action="store_true",
        help="Just scan and list matching dongles, then exit.",
    )
    conn.add_argument(
        "--scan-timeout",
        type=float,
        default=SCAN_TIMEOUT_S,
        metavar="SECS",
        help=f"BLE scan duration in seconds (default {SCAN_TIMEOUT_S:g}).",
    )

    snd = p.add_argument_group("sending")
    snd.add_argument(
        "--repeat",
        type=int,
        default=1,
        metavar="N",
        help="Send the text N times (default 1). Ignored in --interactive.",
    )
    snd.add_argument(
        "--char-delay",
        type=float,
        default=0.0,
        metavar="SECS",
        help="Optional client-side delay between chunks (default 0; the "
             "dongle paces keystrokes itself at ~8ms/char).",
    )

    p.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Verbose logging (scan detail, chunking, MTU).",
    )
    return p


def resolve_text_source(args: argparse.Namespace) -> Optional[str]:
    """
    Determine the text to send for non-interactive, non-scan modes.
    Returns the text string, or None if the source selection is invalid
    (the caller has already validated mutual exclusivity for the live modes).
    """
    if args.stdin:
        return sys.stdin.read()
    return args.text


def main(argv: Optional[List[str]] = None) -> int:
    global _VERBOSE
    parser = build_parser()
    args = parser.parse_args(argv)
    _VERBOSE = args.verbose

    # --scan-only: list matches and exit, regardless of other args.
    if args.scan_only:
        async def _scan() -> int:
            matches = await scan_for_dongles(timeout=args.scan_timeout)
            if not matches:
                log("No matching dongles found.")
                return 1
            log(f"Found {len(matches)} matching device(s):")
            for device, adv in matches:
                name = _name_of(device, adv) or "(no name)"
                rssi = getattr(adv, "rssi", None)
                rssi_s = f"  RSSI={rssi}" if rssi is not None else ""
                # stdout: machine-friendly "ADDRESS\tNAME"
                print(f"{device.address}\t{name}")
                log(f"  {device.address}  {name}{rssi_s}")
            return 0
        try:
            return asyncio.run(_scan())
        except KeyboardInterrupt:
            log("Interrupted.")
            return 130
        except Exception as exc:
            log(f"ERROR during scan: {exc}")
            return 1

    # Validate input-source selection for the live modes.
    modes = [bool(args.interactive), bool(args.stdin), args.text is not None]
    if sum(modes) == 0:
        parser.error("provide text, or --stdin, or --interactive "
                     "(or use --scan-only).")
    if args.interactive and (args.stdin or args.text is not None):
        parser.error("--interactive cannot be combined with --stdin or "
                     "positional text.")
    if args.stdin and args.text is not None:
        parser.error("--stdin cannot be combined with positional text.")
    if args.repeat < 1:
        parser.error("--repeat must be >= 1.")

    text_source = None if args.interactive else resolve_text_source(args)

    try:
        return asyncio.run(run_send(args, text_source))
    except KeyboardInterrupt:
        log("Interrupted.")
        return 130
    except Exception as exc:
        # bleak raises a variety of backend-specific errors; surface cleanly.
        log(f"ERROR: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
