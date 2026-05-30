/*
 * STT Keyboard Dongle — BLE-ONLY test firmware
 * Whitney Design Labs
 *
 * Purpose: validate the whole phone -> BLE -> dongle text path on a board that
 * has BLE but NO native USB-OTG (e.g. ESP32-C6, C3). These chips have only a USB
 * Serial/JTAG controller and CANNOT present as a USB HID keyboard, so instead of
 * typing keystrokes this build ECHOES the received text to the serial monitor.
 *
 * It uses the SAME advertised name, service/characteristic UUIDs and bonding as
 * the production firmware, so the real phone app connects to it unchanged. Use it
 * to exercise scan/connect/bond/MTU/chunked-writes and the app's "send test text"
 * and dictation paths before the ESP32-S3 (HID-capable) boards arrive.
 *
 * BOARD:  ESP32-C6 (or any BLE board without USB-OTG).
 * BUILD:  arduino-cli compile --fqbn esp32:esp32:esp32c6:CDCOnBoot=cdc firmware-ble-test
 * MONITOR: open the serial monitor at 115200 to watch received text arrive.
 *
 * NOTE: This does NOT type into a computer. That requires native USB-HID, which
 * only the ESP32-S3/S2 provide (see ../firmware/firmware.ino).
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>

#include "freertos/FreeRTOS.h"
#include "freertos/stream_buffer.h"

// ---- Protocol contract (mirror of ../PROTOCOL.md §7) — keep identical to prod ----
// Advertised name = base + "-" + 4 hex digits of the chip MAC (e.g. STT-Keyboard-3F7A).
#define DEVICE_NAME_BASE       "STT-Keyboard"
static const char *SVC_TEXT_INPUT_UUID = "7a9b0000-9c4e-4f1a-bc23-1e5f3a2d6b00";
static const char *CHR_TEXT_INPUT_UUID = "7a9b0001-9c4e-4f1a-bc23-1e5f3a2d6b00";

static const uint8_t KEY_DELAY_MS = 8;   // pacing (mirrors production timing)

// Bonding on this TEST board: 0 = OPEN write (no pairing -> no phone notification
// chimes, no re-pair churn); 1 = require an encrypted bonded link (like production).
// The production S3 firmware (../firmware) always bonds; this flag is only here so
// the C6 proxy can be hammered without the pairing prompts. Default 0 for testing.
#define REQUIRE_BONDING 0

// CLEAN_SERIAL: 0 = debug stream (markers + [BLE] logs — good for reading over `cat`);
// 1 = RAW byte stream only (printable + Enter/Tab/Backspace as 0x0A/0x09/0x08), so
// tools/serial_type.py can type it straight into a Windows field. Override at build:
//   arduino-cli compile --build-property "compiler.cpp.extra_flags=-DCLEAN_SERIAL=1" ...
#ifndef CLEAN_SERIAL
#define CLEAN_SERIAL 0
#endif

#define TYPE_BUF_SIZE     2048
#define TYPE_BUF_TRIGGER  1
static StreamBufferHandle_t typeBuf;

// Activity LED: lights while text is flowing in, off when idle — so the blink rate
// / on-duration visibly tracks the rate and amount of incoming text. Most ESP32-C6
// dev boards expose an addressable RGB on GPIO8; change this if yours differs (and
// it will differ on the S3 — see ../firmware for the production board).
#ifndef RGB_BUILTIN
#define RGB_BUILTIN 8
#endif
static inline void activityLed(bool on) {
  rgbLedWrite(RGB_BUILTIN, on ? 40 : 0, on ? 18 : 0, 0);   // dim amber when active
}

// "Types" a character by echoing it to the serial monitor (no USB HID on this chip)
// and pulses the activity LED. A 40 ms idle timeout turns the LED off between bursts.
static void typeTask(void *arg) {
  uint8_t ch;
  for (;;) {
    if (xStreamBufferReceive(typeBuf, &ch, 1, pdMS_TO_TICKS(40)) == 1) {
      activityLed(true);                 // text flowing -> LED on
      bool special = (ch == 0x0A || ch == 0x09 || ch == 0x08);  // Enter/Tab/Backspace
      if ((ch >= 0x20 && ch <= 0x7E) || special) {
#if CLEAN_SERIAL
        Serial.write(ch);                // raw byte stream for serial_type.py
#else
        if (ch >= 0x20 && ch <= 0x7E) Serial.write(ch);         // echo printable
        else if (ch == 0x0A)          Serial.print("\xE2\x8F\x8E");  // U+23CE Return
        else if (ch == 0x09)          Serial.print("\xE2\x87\xA5");  // U+21E5 Tab
        else                          Serial.print("\xE2\x8C\xAB");  // U+232B Backspace
#endif
        delay(KEY_DELAY_MS);             // pacing
      }
      // (other non-printables still ignored, per PROTOCOL §3)
    } else {
      activityLed(false);                // idle (no byte for 40 ms) -> LED off
    }
  }
}

class TextInputCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    size_t n = v.length();
    if (n) {
#if !CLEAN_SERIAL
      Serial.printf("\n[BLE] rx %u byte(s): \"%s\"\n[TYPED] ", (unsigned)n, v.c_str());
#endif
      xStreamBufferSend(typeBuf, v.c_str(), n, 0);
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
#if !CLEAN_SERIAL
    Serial.println("\n[BLE] central connected");
#endif
  }
  void onDisconnect(BLEServer *s) override {
#if !CLEAN_SERIAL
    Serial.println("\n[BLE] central disconnected; re-advertising");
#endif
    BLEDevice::startAdvertising();
  }
};

void setup() {
  Serial.begin(115200);
  delay(300);
#if !CLEAN_SERIAL
  Serial.println("\n[STT] BLE-ONLY test firmware booting (no USB-HID on this chip)");
#endif
  activityLed(false);                    // LED off at boot

  typeBuf = xStreamBufferCreate(TYPE_BUF_SIZE, TYPE_BUF_TRIGGER);
  xTaskCreate(typeTask, "typeTask", 4096, NULL, 1, NULL);

  char devName[24];
  snprintf(devName, sizeof(devName), "%s-%04X",
           DEVICE_NAME_BASE, (uint16_t)(ESP.getEfuseMac() & 0xFFFF));
  BLEDevice::init(devName);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *svc = server->createService(SVC_TEXT_INPUT_UUID);
  BLECharacteristic *txt = svc->createCharacteristic(
      CHR_TEXT_INPUT_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
#if REQUIRE_BONDING
  txt->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);   // bonded write only
#else
  txt->setAccessPermissions(ESP_GATT_PERM_WRITE);             // open write: no pairing prompts
#endif
  txt->setCallbacks(new TextInputCallbacks());
  svc->start();

#if REQUIRE_BONDING
  BLESecurity *sec = new BLESecurity();
  sec->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  sec->setCapability(ESP_IO_CAP_NONE);
  sec->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
#endif

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_TEXT_INPUT_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);
  adv->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
#if !CLEAN_SERIAL
  Serial.printf("[BLE] advertising as %s — connect with the phone app\n", devName);
  Serial.println("[STT] ready; received text will be echoed below");
#endif
}

void loop() {
  delay(1000);
}
