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
// Reserved (NOT implemented in v0): control 7a9b0002-..., status 7a9b0003-...

static const uint8_t KEY_DELAY_MS = 8;   // pacing between keystrokes (5-10 ms, tunable)

// ---------------------------------------------------------------------------
// Build-time options
// ---------------------------------------------------------------------------
// Milestone 1 (spec §8): set to 1 to type a banner ~3 s after boot, proving the
// USB-HID half works on its own with zero host setup. Leave 0 for normal use.
#define SELFTEST_TYPE_ON_BOOT  0

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
// BLE callbacks
// ---------------------------------------------------------------------------
class TextInputCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();            // bytes the phone wrote (a run of chars to type)
    size_t n = v.length();
    if (n) {
      // Non-blocking enqueue; on overflow the excess is dropped rather than
      // stalling the BLE stack task. v0 utterance-sized writes fit easily.
      xStreamBufferSend(typeBuf, v.c_str(), n, 0);
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    Serial.println("[BLE] central connected");
    // Request a stable link right after connect: 30-50 ms interval (24-40 x 1.25 ms),
    // zero slave latency, 6 s supervision timeout (600 x 10 ms). Frequent keepalives +
    // a generous timeout stop the link dropping at the ~30 s mark when it goes idle or
    // Android slows it. Uses the connection-handle overload (no stack-specific type).
    s->updateConnParams(s->getConnId(), 24, 40, 0, 600);
  }
  void onDisconnect(BLEServer *s) override {
    Serial.println("[BLE] central disconnected; re-advertising");
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
