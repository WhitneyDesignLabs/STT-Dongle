/*
 * STT Keyboard Dongle — ESP32-S3 firmware (v0)
 * Whitney Design Labs
 *
 * Two concurrent jobs run on the dongle (spec §3):
 *   1. USB HID  — enumerates as a standard boot-compatible keyboard and types
 *                 printable US-ASCII into whatever has focus on the host.
 *   2. BLE GATT — peripheral exposing one bonded/encrypted "Text Input" write
 *                 characteristic; the phone (BLE central) writes the text to type.
 *
 * Data flow (spec §5.3):
 *   BLE write (bytes) --> FreeRTOS stream buffer --> typing task:
 *       for each byte: if printable ASCII -> Keyboard.write(c) -> pace delay
 *
 * Contract: see ../PROTOCOL.md (UUIDs, encoding, pacing, bonding) — keep in sync.
 *
 * BOARD: ESP32-S3 dev board with NATIVE USB (use the native-USB port, NOT the
 *        UART-bridge port — the UART port cannot present as USB HID).
 * BUILD: arduino-cli compile/upload with FQBN
 *        esp32:esp32:esp32s3:USBMode=default,CDCOnBoot=cdc
 *   - USBMode=default  == "USB-OTG (TinyUSB)"  -> REQUIRED for USB HID
 *   - CDCOnBoot=cdc     enables a USB CDC serial port for debug logs alongside HID
 */

#include "USB.h"
#include "USBHIDKeyboard.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>

#include "freertos/FreeRTOS.h"
#include "freertos/stream_buffer.h"

// ---------------------------------------------------------------------------
// Protocol contract (mirror of ../PROTOCOL.md §7) — change there first.
// ---------------------------------------------------------------------------
// Advertised name = base + "-" + 4 hex digits of the chip MAC (e.g. STT-Keyboard-3F7A)
// so multiple dongles are distinguishable. The app matches on the "STT-Keyboard" prefix.
#define DEVICE_NAME_BASE       "STT-Keyboard"
static const char *SVC_TEXT_INPUT_UUID = "7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00";
static const char *CHR_TEXT_INPUT_UUID = "7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00";
// Control characteristic — Build B (#23) auth-token handshake (used when REQUIRE_AUTH_TOKEN).
static const char *CHR_CONTROL_UUID    = "7a9b0002-9c4e-4f1a-bc23-1e5f3a2d6b00";
// Provisioning characteristic — Build B (#23): readable token, but ONLY while the
// provisioning window is open (power-on until first successful auth). Lets the app
// tap-to-provision a new dongle with no typing. Closed = reads return empty.
static const char *CHR_PROVISION_UUID  = "7a9b0003-9c4e-4f1a-bc23-1e5f3a2d6b00";

static const uint8_t KEY_DELAY_MS = 8;   // pacing between keystrokes (5-10 ms, tunable)

// ---------------------------------------------------------------------------
// Build-time options
// ---------------------------------------------------------------------------
// Milestone 1 (spec §8): set to 1 to type a banner ~3 s after boot, proving the
// USB-HID half works on its own with zero host setup. Leave 0 for normal use.
#define SELFTEST_TYPE_ON_BOOT  0

// Build B (#23) — app-level AUTH TOKEN. When 1, the dongle ignores all Text-Input writes
// until the central writes the correct token to the Control characteristic (7a9b0002)
// after connecting; the link is unlocked only for that connection. Blocks casual
// proximity injection WITHOUT BLE bonding (still not sniffer-proof — that's Build C/#22).
// Default 0 = the shipped open build, byte-for-byte unchanged. Enable per-build for dev:
//   arduino-cli compile --build-property "compiler.cpp.extra_flags=-DREQUIRE_AUTH_TOKEN=1 -DAUTH_DEBUG=1"
#ifndef REQUIRE_AUTH_TOKEN
#define REQUIRE_AUTH_TOKEN 0
#endif
// AUTH_DEBUG: log auth + rx events to serial (dev/test only — never ship 1, it prints the token).
#ifndef AUTH_DEBUG
#define AUTH_DEBUG 0
#endif
// AUTH_RESET_TOKEN: wipe the stored token and generate a fresh one on boot (dev only).
#ifndef AUTH_RESET_TOKEN
#define AUTH_RESET_TOKEN 0
#endif

// ---------------------------------------------------------------------------
// Typing pipeline
// ---------------------------------------------------------------------------
#define TYPE_BUF_SIZE     2048   // bytes of pending text the dongle can hold
#define TYPE_BUF_TRIGGER  1      // wake the typing task as soon as 1 byte arrives

USBHIDKeyboard Keyboard;                 // standard HID keyboard (US layout ASCII map)
static StreamBufferHandle_t typeBuf;     // BLE -> typing task hand-off

// Activity LED: lights while text is being typed, off when idle (mirrors the C6 proxy).
// ESP32-S3 DevKitC boards expose an addressable RGB on GPIO48; override RGB_BUILTIN if
// the core didn't define it or your board uses a different pin. (A plain non-addressable
// power LED can't be driven this way — only an addressable RGB on the given pin.)
#ifndef RGB_BUILTIN
#define RGB_BUILTIN 48
#endif
static inline void activityLed(bool on) {
  rgbLedWrite(RGB_BUILTIN, on ? 40 : 0, on ? 18 : 0, 0);   // dim amber when active
}

// Drains the stream buffer one byte at a time, filtering to printable US-ASCII (+ the
// Tier-1 special keys), pacing each keystroke, and pulsing the activity LED. A 40 ms
// idle timeout turns the LED off between bursts.
static void typeTask(void *arg) {
  uint8_t ch;
  for (;;) {
    if (xStreamBufferReceive(typeBuf, &ch, 1, pdMS_TO_TICKS(40)) == 1) {
      activityLed(true);                 // text flowing -> LED on
      // Printable US-ASCII, plus the Tier-1 special keys: USBHIDKeyboard.write()
      // maps 0x0A->Enter, 0x09->Tab, 0x08->Backspace via its US ASCII table.
      if ((ch >= 0x20 && ch <= 0x7E) || ch == 0x0A || ch == 0x09 || ch == 0x08) {
        Keyboard.write(ch);              // press + release, US-layout scancode map
        delay(KEY_DELAY_MS);             // pacing -> hosts don't drop keys
      }
      // Other control bytes are still ignored (special keys beyond these: future).
    } else {
      activityLed(false);                // idle (no byte for 40 ms) -> LED off
    }
  }
}

// ---------------------------------------------------------------------------
// Auth (Build B / #23) — gate text input behind a shared token
// ---------------------------------------------------------------------------
#if REQUIRE_AUTH_TOKEN
#include <Preferences.h>             // NVS-backed token storage (only pulled in when gated)
// Per-dongle token, provisioned once to NVS on first boot and reused thereafter (so it
// survives reflashes that keep NVS). 16 uppercase-hex chars from the hardware RNG.
static char g_token[33] = {0};
// Single-connection peripheral, so one flag suffices. Volatile: written from the BLE
// task, read from the same; reset on every connect AND disconnect so a dropped/new link
// must re-authenticate (no carry-over).
static volatile bool g_authed = false;
// Provisioning window: token is readable until the first successful auth this boot
// (then closed; reopens on power-cycle). Lets a new phone tap-to-provision once.
// The window is enforced by the characteristic's VALUE (token while open, empty once
// closed) — NOT by onRead, because the ESP32 stack fetches the value before onRead runs.
static volatile bool g_provisionOpen = true;
static BLECharacteristic *g_provChar = nullptr;

// Load the token from NVS; generate + persist one on first boot. Set AUTH_RESET_TOKEN=1
// at build time to wipe and re-provision (dev only). Prints the token once when newly
// generated; AUTH_DEBUG also prints it every boot.
static void provisionToken() {
  Preferences prefs;
  prefs.begin("stt-auth", false);             // RW namespace
#if AUTH_RESET_TOKEN
  prefs.remove("token");
  Serial.println("[AUTH] AUTH_RESET_TOKEN set — cleared stored token");
#endif
  String t = prefs.getString("token", "");
  if (t.length() == 0) {
    static const char hexdig[] = "0123456789ABCDEF";
    char buf[17];
    for (int i = 0; i < 8; i++) {
      uint8_t b = (uint8_t)(esp_random() & 0xFF);
      buf[i * 2]     = hexdig[b >> 4];
      buf[i * 2 + 1] = hexdig[b & 0x0F];
    }
    buf[16] = '\0';
    t = String(buf);
    prefs.putString("token", t);
    Serial.printf("[AUTH] provisioned NEW token: %s  (enter this in the app)\n", t.c_str());
  }
  prefs.end();
  strncpy(g_token, t.c_str(), sizeof(g_token) - 1);
}
#endif

// ---------------------------------------------------------------------------
// BLE callbacks
// ---------------------------------------------------------------------------
class TextInputCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();            // bytes the phone wrote (a run of chars to type)
    size_t n = v.length();
#if REQUIRE_AUTH_TOKEN
    if (!g_authed) {                     // not unlocked this connection -> drop silently
#if AUTH_DEBUG
      Serial.printf("[AUTH] text write of %u byte(s) BLOCKED (not authed)\n", (unsigned)n);
#endif
      return;
    }
#endif
    if (n) {
#if AUTH_DEBUG
      Serial.printf("[BLE] rx %u byte(s) -> type\n", (unsigned)n);
#endif
      // Non-blocking enqueue; on overflow the excess is dropped rather than
      // stalling the BLE stack task. v0 utterance-sized writes fit easily.
      xStreamBufferSend(typeBuf, v.c_str(), n, 0);
    }
  }
};

#if REQUIRE_AUTH_TOKEN
// Control characteristic: the central writes the shared token here right after connecting.
// Correct token unlocks Text-Input for this connection; anything else (re-)locks it.
class ControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    bool ok = (v.length() == strlen(g_token)) && (v == g_token);
    g_authed = ok;
    if (ok) {
      g_provisionOpen = false;                              // close the window
      if (g_provChar) g_provChar->setValue((uint8_t *)"", 0);  // stop handing out the token
    }
#if AUTH_DEBUG
    Serial.printf("[AUTH] token %s — text input %s\n",
                  ok ? "OK" : "REJECTED", ok ? "UNLOCKED" : "locked");
#endif
  }
};

// Provisioning read: hand out the token only while the window is open; once closed,
// reads return empty so a later eavesdropper can't lift it (re-opens on power-cycle).
class ProvisionCallbacks : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic *c) override {
    // Value is managed externally (set at boot, blanked on auth); just log here.
#if AUTH_DEBUG
    Serial.printf("[AUTH] provision read — window %s\n",
                  g_provisionOpen ? "OPEN (token sent)" : "closed (empty)");
#endif
  }
};
#endif

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    Serial.println("[BLE] central connected");
#if REQUIRE_AUTH_TOKEN
    g_authed = false;                    // every new connection must re-authenticate
#if AUTH_DEBUG
    Serial.printf("[AUTH] (debug) expected token = %s\n", g_token);
#endif
#endif
    // Request a stable link right after connect: 30-50 ms interval (24-40 x 1.25 ms),
    // zero slave latency, 6 s supervision timeout (600 x 10 ms). Frequent keepalives +
    // a generous timeout stop the link dropping at the ~30 s mark when it goes idle or
    // Android slows it. Uses the connection-handle overload (no stack-specific type).
    s->updateConnParams(s->getConnId(), 24, 40, 0, 600);
  }
  void onDisconnect(BLEServer *s) override {
    Serial.println("[BLE] central disconnected; re-advertising");
#if REQUIRE_AUTH_TOKEN
    g_authed = false;                    // drop the unlock when the link goes away
#endif
    BLEDevice::startAdvertising();       // stay connectable for auto-reconnect
  }
};

// ---------------------------------------------------------------------------
// Setup / loop
// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);                  // USB CDC debug (CDCOnBoot=cdc)
  delay(200);
  Serial.println("\n[STT] STT Keyboard Dongle v0 booting");

  // --- USB HID keyboard ---
  Keyboard.begin();
  USB.begin();
  activityLed(false);                    // LED off at boot
  Serial.println("[USB] HID keyboard started");

  // --- typing pipeline ---
  typeBuf = xStreamBufferCreate(TYPE_BUF_SIZE, TYPE_BUF_TRIGGER);
  xTaskCreatePinnedToCore(typeTask, "typeTask", 4096, NULL, 1, NULL, APP_CPU_NUM);

  // --- BLE peripheral ---
  // Build a unique advertised name from the chip MAC so several dongles can coexist.
  char devName[24];
  snprintf(devName, sizeof(devName), "%s-%04X",
           DEVICE_NAME_BASE, (uint16_t)(ESP.getEfuseMac() & 0xFFFF));
  BLEDevice::init(devName);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  // REQUIRE_BONDING gates the write char's security.
  //   0 = OPEN write (no pairing) — the current INTERNAL RELEASE. Stable, but any nearby
  //       BLE device can inject keystrokes (BadUSB-class; local/proximity only, ~10 m).
  //       Fine for private bench use; NOT for unattended machines / CNC / robotics.
  //   1 = encrypted/bonded link (spec §7) — crypto-secure, BUT the BLE security/SMP path
  //       tears the link down every ~30 s (the SMP 30-second timeout) and must be fixed
  //       first. Confirmed via A/B test: a non-pairing PC central was also dropped at 31 s.
  // ROADMAP: (B, next build) keep the open link + an app-level AUTH TOKEN so casual
  // injection is blocked without BLE bonding; (C, future) proper bonding for crypto
  // strength once the SMP instability is solved (likely a NimBLE switch). See task #22.
#ifndef REQUIRE_BONDING
#define REQUIRE_BONDING 0
#endif

  BLEService *svc = server->createService(SVC_TEXT_INPUT_UUID);
  BLECharacteristic *txt = svc->createCharacteristic(
      CHR_TEXT_INPUT_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
#if REQUIRE_BONDING
  // Require an encrypted, bonded link to write -> a stranger cannot push keys.
  txt->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
#else
  txt->setAccessPermissions(ESP_GATT_PERM_WRITE);   // OPEN (test): no pairing required
#endif
  txt->setCallbacks(new TextInputCallbacks());

#if REQUIRE_AUTH_TOKEN
  provisionToken();                    // load/generate the token NOW so we can expose it

  // Control characteristic (open write): the central writes the shared token here to
  // unlock Text-Input for the connection. Open-write on purpose — the token IS the
  // gate; encrypting this write is Build C (#22), not B.
  BLECharacteristic *ctrl = svc->createCharacteristic(
      CHR_CONTROL_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  ctrl->setAccessPermissions(ESP_GATT_PERM_WRITE);
  ctrl->setCallbacks(new ControlCallbacks());

  // Provisioning (open read): exposes the token only during the provisioning window.
  // Value starts AS the token (window open); ControlCallbacks blanks it on first auth.
  BLECharacteristic *prov = svc->createCharacteristic(
      CHR_PROVISION_UUID, BLECharacteristic::PROPERTY_READ);
  prov->setAccessPermissions(ESP_GATT_PERM_READ);
  prov->setCallbacks(new ProvisionCallbacks());
  prov->setValue((uint8_t *)g_token, strlen(g_token));
  g_provChar = prov;
#endif

  svc->start();

#if REQUIRE_BONDING
  // --- security: LE Secure Connections + bonding, "Just Works" (no IO) ---
  BLESecurity *sec = new BLESecurity();
  sec->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  sec->setCapability(ESP_IO_CAP_NONE);
  sec->setKeySize(16);
  // Exchange BOTH the encryption key AND the identity key (IRK) in BOTH directions so
  // the dongle resolves the phone's rotating private address (RPA) on reconnect.
  sec->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  sec->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
#endif

  // --- advertising ---
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_TEXT_INPUT_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);            // iOS/Android-friendly connection interval hints
  adv->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.printf("[BLE] advertising as %s\n", devName);
#if REQUIRE_AUTH_TOKEN
  Serial.println("[AUTH] auth-token gate ENABLED — text input locked until token written");
#if AUTH_DEBUG
  Serial.printf("[AUTH] expected token = \"%s\"\n", g_token);
#endif
#endif

#if SELFTEST_TYPE_ON_BOOT
  // Milestone 1: type a banner so HID can be validated without any BLE writes.
  delay(3000);                           // give the host time to enumerate us
  const char *banner = "STT-Keyboard self-test OK ";
  for (const char *p = banner; *p; ++p) { Keyboard.write(*p); delay(KEY_DELAY_MS); }
  Serial.println("[USB] self-test banner typed");
#endif

  Serial.println("[STT] ready");
}

void loop() {
  delay(1000);                           // all work happens in callbacks / typeTask
}
