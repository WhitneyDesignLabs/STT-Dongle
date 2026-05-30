package com.whitneydesignlabs.sttkeyboard

import java.util.UUID

/**
 * BLE protocol contract for the STT Keyboard Dongle (v0).
 *
 * This object is the single source of truth in the app and MUST match
 * `PROTOCOL.md` exactly. Change PROTOCOL.md first, then mirror it here.
 *
 * See PROTOCOL.md §1, §2, §3, §4, §7.
 */
object Protocol {

    /**
     * Advertised-name PREFIX of the dongle (PROTOCOL.md §1). Each dongle advertises
     * "STT-Keyboard-XXXX" (XXXX = chip-MAC suffix) so multiple dongles are
     * distinguishable, so match on this prefix rather than the exact string.
     */
    const val DEVICE_NAME = "STT-Keyboard"

    /** True if an advertised name belongs to one of our dongles. */
    fun isDongleName(name: String?): Boolean = name != null && name.startsWith(DEVICE_NAME)

    /** Text Input service UUID (PROTOCOL.md §2). */
    val SVC_TEXT_INPUT: UUID =
        UUID.fromString("7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00")

    /**
     * Text Input characteristic — the only characteristic implemented in v0.
     * Properties: Write (with response) + Write Without Response.
     * Permission: requires an encrypted, bonded link to write (PROTOCOL.md §2, §5).
     */
    val CHR_TEXT_INPUT: UUID =
        UUID.fromString("7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00")

    // NOTE: Control (7a9b0002-...) and Status (7a9b0003-...) are RESERVED and
    // intentionally NOT defined or used here. Do not add them in v0 (PROTOCOL.md §2).

    /**
     * MTU the phone requests. ATT payload = negotiatedMtu - 3. We always read the
     * *negotiated* value back and chunk to (negotiatedMtu - 3) — never assume 244,
     * since some stacks grant less (PROTOCOL.md §4).
     */
    const val REQUESTED_MTU = 247

    /** Bytes subtracted from the negotiated MTU to get the usable ATT payload. */
    const val ATT_HEADER_BYTES = 3

    /**
     * Conservative default payload before MTU negotiation completes. 23 is the
     * BLE-mandated minimum ATT MTU, so 20 bytes is the safe floor.
     */
    const val DEFAULT_PAYLOAD_BYTES = 23 - ATT_HEADER_BYTES // = 20

    /** Lowest printable US-ASCII byte accepted by the dongle (space). */
    const val ASCII_MIN = 0x20

    /** Highest printable US-ASCII byte accepted by the dongle (tilde). */
    const val ASCII_MAX = 0x7E

    // ---- Tier-1 special keys (sent as raw control bytes, not via the text path) ----
    /** Enter / hard return. The dongle maps 0x0A -> HID Return. */
    const val KEY_ENTER: Byte = 0x0A
    /** Tab. The dongle maps 0x09 -> HID Tab. */
    const val KEY_TAB: Byte = 0x09
    /** Backspace. The dongle maps 0x08 -> HID Backspace. */
    const val KEY_BACKSPACE: Byte = 0x08

    /**
     * Map a whole-utterance voice command to a special-key byte, or null if the
     * utterance is ordinary text. Matched only when the entire (trimmed) utterance is
     * the command, to avoid firing on the words appearing mid-sentence.
     */
    fun voiceCommandByte(text: String): Byte? = when (text.trim().lowercase()) {
        "new line", "newline", "new paragraph", "enter", "return", "press enter" -> KEY_ENTER
        "tab", "press tab" -> KEY_TAB
        "backspace", "back space", "delete", "scratch that" -> KEY_BACKSPACE
        else -> null
    }

    /**
     * Encode a recognized string for the dongle's raw character stream.
     *
     * Per PROTOCOL.md §3: keep only US-ASCII printable bytes 0x20..0x7E. Newline,
     * tab and all other control bytes are silently ignored by the dongle in v0,
     * so we drop them here rather than wasting GATT writes on bytes that do nothing.
     *
     * @return a byte array of accepted characters (may be empty).
     */
    fun encodeForDongle(text: String): ByteArray {
        // US-ASCII maps each printable char to a single byte; non-ASCII chars are
        // dropped here. We filter on the resulting bytes to be precise.
        val bytes = text.toByteArray(Charsets.US_ASCII)
        return bytes.filter { b ->
            val v = b.toInt() and 0xFF
            v in ASCII_MIN..ASCII_MAX
        }.toByteArray()
    }
}
