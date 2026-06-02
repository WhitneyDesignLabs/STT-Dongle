# STT Keyboard Dongle — BLE Protocol Contract (v0.10 / Build B)

**Status:** Current. All three components (firmware, Android app, test harness)
MUST agree on the values in this file. Change here first, then in code. Build B
adds the auth-token gate + tap-to-provision (§5) on top of the v0 text path.

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
`STT-Keyboard`, then connects and authenticates with the shared token (§5).

> The `XXXX` suffix is a 4-hex fold of the device-unique eFuse-MAC bytes, so every
> dongle gets a distinct name. (Do **not** use the low 16 bits of the MAC alone —
> those are the shared Espressif OUI prefix and collide on every board.)

## 2. GATT layout

### Text Input service
```
Service UUID:  7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00
```

### Characteristics

| Role | UUID | Properties | Permissions |
|------|------|------------|-------------|
| **Text Input** | `7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00` | Write, Write Without Response | Open write; **gated by the auth token** (§5) |
| **Control / auth** | `7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00` | Write, Write Without Response | Open write |
| **Provisioning** | `7a9b0003-9c4e-4f1a-bc23-1e5f3a2d6b00` | Read | Open read, but token-bearing **only during the provisioning window** (§5) |

All three are implemented in the current (Build B) firmware. **Text Input**
(`7a9b0001`) carries the characters to type; **Control** (`7a9b0002`) receives the
shared auth token that unlocks Text Input for the connection; **Provisioning**
(`7a9b0003`) lets a new app read the token over the air once (tap-to-provision).
Richer special keys (arrows, Shift+Enter, Ctrl-combos) may extend the Control
characteristic later (spec §9).

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

## 5. Security — auth token (Build B) + provisioning

The current build gates keystroke injection with a **per-dongle shared token**,
without BLE bonding (which on the Arduino-ESP32 Bluedroid stack hits the SMP
30-second timeout and drops the link — see `SESSION-LOG.md`). This is the
**preferred, default** model. The earlier open build (no gate) is **deprecated**.

**The handshake (every connection):**
1. On connect the dongle is **locked** — it ignores all Text Input writes.
2. The central writes the shared token to the **Control** characteristic
   (`7a9b0002`). A correct token **unlocks** Text Input for *that connection only*;
   a wrong/absent token leaves it locked (writes are silently dropped).
3. The dongle **re-locks on every (re)connect**, so the central re-sends the token
   each time (the app does this automatically).

**The token:** 16 hex chars, generated from the hardware RNG and stored in NVS on
first boot; stable across reboots/reflashes (survives unless NVS is erased).

**Provisioning (tap-to-provision, no typing):**
- The **Provisioning** characteristic (`7a9b0003`) returns the token on read, but
  **only while the provisioning window is open** — from power-on **until the first
  successful auth** this boot. After that it returns empty. The window **reopens on
  power-cycle**.
- On first connect to a dongle it has no saved token for, the app **reads** the
  token here, stores it (keyed by BLE address), then authenticates — all with no
  user typing. Once authenticated, the window closes so the token is no longer
  readable over the air.

**Threat model:** blocks casual proximity injection (a nearby device can't type
without the token) and brute-forcing it — the firmware uses a **constant-time** token
compare and an **exponential-backoff lockout** after repeated wrong tokens (global, so
reconnecting doesn't reset it). It is **not** sniffer-proof — the link is unencrypted,
and the token is readable during the brief provisioning window. Encrypted/bonded
transport (LE Secure Connections) is **Build C** (task #22), likely after a NimBLE
switch; that's the path to MITM/sniffer resistance.

> ⚠️ **The provisioning window assumes a trusted RF environment at power-on.** It is
> *first-connection-wins*: from boot until the first successful auth, anyone in range can
> read the token off `7a9b0003`. If an attacker is present and beats your phone to it at
> power-on, they could provision silently instead of you. Provision new dongles where you
> trust the airspace; power-cycle to re-open the window if a provisioning attempt was
> interrupted. QR-on-device provisioning (token never broadcast) is a future hardening option.

**Backward compatibility:** the deprecated open build exposes no Control
characteristic, so a token-aware central that finds no Control char simply sends
text directly (no token) — the app drives both old and new dongles.

## 6. Keystroke pacing

| Item | Value |
|------|-------|
| Default delay between characters | **8 ms** (`KEY_DELAY_MS`, tunable 5–10 ms per spec §5.1) |

Pacing prevents hosts from dropping keys on back-to-back reports and makes typing
human-visible. Tunable per target machine (spec §9).

## 7. Constant reference (copy into code)

```
DEVICE_NAME            = "STT-Keyboard"   (suffix = fold of device-unique MAC bytes)
SVC_TEXT_INPUT_UUID    = 7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00
CHR_TEXT_INPUT_UUID    = 7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00
CHR_CONTROL_UUID       = 7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00   (auth token, write)
CHR_PROVISION_UUID     = 7a9b0003-9c4e-4f1a-bc23-1e5f3a2d6b00   (token read, window only)
REQUESTED_MTU          = 247
ASCII_RANGE            = 0x20 .. 0x7E   (printable; others ignored)
KEY_DELAY_MS           = 8
SECURITY               = app-level AUTH TOKEN gate (default; REQUIRE_AUTH_TOKEN=1)
                         token = 16 hex, NVS-stored; tap-to-provision over BLE
                         (DEPRECATED opt-out: open build, no gate, REQUIRE_AUTH_TOKEN=0)
                         (FUTURE: LE Secure Connections + bonding = Build C / #22)
WRITE_TYPE             = Write With Response (serialized, one outstanding)
```
