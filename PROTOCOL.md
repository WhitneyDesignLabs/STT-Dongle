# STT Keyboard Dongle — BLE Protocol Contract (v0)

**Status:** Frozen for v0. All three components (firmware, Android app, test
harness) MUST agree on the values in this file. Change here first, then in code.

This is the single source of truth for the wireless link between the phone (BLE
central) and the dongle (BLE peripheral). See `stt-keyboard-dongle-spec.md` for
the product spec.

---

## 1. Identity & advertising

| Item | Value |
|------|-------|
| Advertised device name | `STT-Keyboard-XXXX` (XXXX = chip-MAC suffix, so multiple dongles are distinct). The central matches the **`STT-Keyboard` prefix**. |
| Primary service advertised | the Text Input service UUID below |
| Connectable | Always, while powered. Re-advertises immediately on disconnect. |

The phone scans for the **service UUID** (preferred) and/or the **name**
`STT-Keyboard`, then connects and bonds.

## 2. GATT layout

### Text Input service
```
Service UUID:  7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00
```

### Characteristics

| Role | UUID | Properties | Permissions |
|------|------|------------|-------------|
| **Text Input** (v0) | `7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00` | Write, Write Without Response | Write requires an **encrypted, bonded** link |
| Control *(reserved, not in v0)* | `7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00` | Write | Encrypted |
| Status *(reserved, not in v0)* | `7a9b0003-9c4e-4f1a-bc23-1e5f3a2d6b00` | Read, Notify | Encrypted |

Only **Text Input** (`7a9b0001`) is implemented in v0. The Control and Status
UUIDs are reserved so future firmware/app versions can add special keys and a
connection-state indicator without re-bonding (spec §9).

## 3. Text Input semantics

The Text Input characteristic is a **raw byte stream of characters to type**.

- Each GATT write delivers a run of characters. The dongle types them, in order,
  with keystroke pacing, then waits for the next write.
- **No framing, no length header, no reassembly.** Ordering is guaranteed by the
  single GATT connection; the dongle appends each write's bytes to its type
  queue. Splitting a long utterance across multiple writes is therefore
  transparent — the dongle cannot tell where one write ended and the next began,
  which is exactly what we want.

### Encoding (v0)
- **US-ASCII printable: `0x20`–`0x7E`** — typed as text.
- **Tier-1 special keys (in-band):** `0x0A` → **Enter**, `0x09` → **Tab**,
  `0x08` → **Backspace**. The dongle maps these to the matching HID keys
  (`USBHIDKeyboard.write()` already does so via its US-ASCII table). The phone
  sends them as **raw control bytes** (`sendKey`), bypassing the trailing-space /
  printable-filter text path; they may be triggered by on-screen buttons or by
  whole-utterance voice commands ("new line", "tab", "backspace").
- **All other control bytes are still ignored.** Richer keys (arrows, Shift+Enter,
  Esc, Ctrl-shortcuts) remain deferred to the reserved Control characteristic
  (`7a9b0002`, spec §9).
- The dongle types using the **US keyboard layout** scancode table.

### Flow control & ordering (phone side)
- Prefer **Write With Response** for the Text Input characteristic. The response
  provides natural backpressure and preserves ordering.
- The central MUST keep **only one write outstanding at a time** (serialize the
  write queue). Android in particular allows a single in-flight GATT operation.
- Write Without Response is permitted for low-latency bursts but the phone is
  then responsible for not overrunning the dongle's type buffer.

## 4. MTU & chunking

| Item | Value |
|------|-------|
| MTU the phone requests | **247** (ATT payload = MTU − 3 = **244** bytes) |
| Max bytes per write | `negotiatedMTU − 3` |
| Chunking | Phone splits any text longer than the max payload into back-to-back writes. The dongle needs no chunk awareness (see §3). |

The phone must **read the negotiated MTU** after negotiation and chunk to
`negotiatedMTU − 3`, not assume 244 — some stacks grant less.

## 5. Security / bonding

> ⚠️ **Current internal release (`v0.9.0-internal`) ships with an OPEN write — no
> bonding.** `firmware.ino`'s `REQUIRE_BONDING` flag is **0**. The bonded path
> below is correct in principle but, on the Arduino-ESP32 Bluedroid stack, hits the
> **BLE Security Manager (SMP) 30-second timeout** and drops the link every ~31 s
> (proven by A/B test — see `SESSION-LOG.md`). So the design below is the *target*,
> not what's flashed today. **Open-write trade-off:** any nearby BLE device (~10 m)
> can connect and inject keystrokes (BadUSB-class, local-only). Accepted for private
> bench use; mitigated next by an **app-level auth token** (build B, task #23); full
> bonding restored in build C (task #22), likely after a NimBLE switch.

The intended (build C) model:

- Link uses **BLE LE Secure Connections with bonding**.
- The Text Input characteristic requires an **encrypted, bonded** link to write,
  so a non-bonded nearby device cannot push keystrokes (spec §7).
- v0 uses **"Just Works"** pairing (the dongle has no display/keypad). This gives
  encryption + bonding but not MITM protection. A passkey/numeric-comparison
  flow is a documented future hardening step.
- After first bond, reconnection is automatic and requires no user interaction.

## 6. Keystroke pacing

| Item | Value |
|------|-------|
| Default delay between characters | **8 ms** (`KEY_DELAY_MS`, tunable 5–10 ms per spec §5.1) |

Pacing prevents hosts from dropping keys on back-to-back reports and makes typing
human-visible. Tunable per target machine (spec §9).

## 7. Constant reference (copy into code)

```
DEVICE_NAME            = "STT-Keyboard"
SVC_TEXT_INPUT_UUID    = 7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00
CHR_TEXT_INPUT_UUID    = 7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00
CHR_CONTROL_UUID       = 7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00   (reserved)
CHR_STATUS_UUID        = 7a9b0003-9c4e-4f1a-bc23-1e5f3a2d6b00   (reserved)
REQUESTED_MTU          = 247
ASCII_RANGE            = 0x20 .. 0x7E   (printable; others ignored in v0)
KEY_DELAY_MS           = 8
PAIRING                = (target) LE Secure Connections, bonding, Just Works
                         (shipped v0.9.0-internal) OPEN, no pairing  [REQUIRE_BONDING=0]
WRITE_TYPE             = Write With Response (serialized, one outstanding)
```
