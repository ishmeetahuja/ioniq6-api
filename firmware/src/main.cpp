/*
 * Walk-away Lock tag — ESP32 firmware.
 *
 * The tag lives in the car (powered from a power bank that the car's USB port charges) and does
 * one simple thing: it is a Bluetooth Low Energy device the phone app stays connected to. The
 * phone measures the signal strength of that connection; when you walk away the signal fades or
 * the link drops, and the *phone* calls the server. The tag needs no Wi-Fi and no internet.
 *
 * Status LED: blinks every 2 s = waiting for the phone; steady on = phone connected.
 *   classic ESP32 boards: the blue LED (GPIO 2)
 *   ESP32-C3 "SuperMini" boards: the blue LED (GPIO 8, lit when low)
 *   ESP32-S3 DevKitC boards: the RGB LED, in blue (GPIO 48)
 * Serial (115200): a status line every 5 s, plus connection events.
 */
#include <Arduino.h>
#include <WiFi.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <esp_bt.h>

// Must match the Android app (Core.kt → Ble.SERVICE_UUID).
#define SERVICE_UUID "2097e612-aa66-44a1-9bd2-72d554ba99e5"
#define INFO_UUID    "2067ecdf-d3e1-4e4b-b654-997612c3980c"
#define DEVICE_NAME  "WALKAWAY-TAG"
#define FW_VERSION   "2.0.0"

// Board differences come from platformio.ini (build_flags).
#ifndef LED_PIN
#define LED_PIN 2
#endif
#ifndef LED_ACTIVE_LOW
#define LED_ACTIVE_LOW 0
#endif
#ifndef LED_RGB
#define LED_RGB 0
#endif
#ifndef BOARD_NAME
#define BOARD_NAME "ESP32"
#endif

static BLEServer *server = nullptr;
static volatile bool phoneConnected = false;
static volatile bool restartAdvertising = false;

static void led(bool on) {
#if LED_RGB
  neopixelWrite(LED_PIN, 0, 0, on ? 40 : 0);   // dim blue
#else
  digitalWrite(LED_PIN, (on != (bool)LED_ACTIVE_LOW) ? HIGH : LOW);
#endif
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    phoneConnected = true;
    Serial.println("phone connected");
  }
  void onDisconnect(BLEServer *s) override {
    phoneConnected = false;
    restartAdvertising = true;  // the BLE stack stops advertising once a client connects
    Serial.println("phone disconnected");
  }
};

void setup() {
  Serial.begin(115200);
#if !LED_RGB
  pinMode(LED_PIN, OUTPUT);
#endif
  led(false);

  // Save power: no Wi-Fi, slower CPU (BLE is fine at 80 MHz).
  WiFi.mode(WIFI_OFF);
  setCpuFrequencyMhz(80);

  BLEDevice::init(DEVICE_NAME);
  // A strong, steady transmit power makes the phone's "you walked away" threshold more reliable
  // through the car body.
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *info = service->createCharacteristic(INFO_UUID, BLECharacteristic::PROPERTY_READ);
  info->setValue(DEVICE_NAME " " FW_VERSION " " BOARD_NAME);
  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);  // the app finds the tag by this UUID
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println(DEVICE_NAME " " FW_VERSION " (" BOARD_NAME ") advertising");
}

void loop() {
  static uint32_t lastBlink = 0;
  static uint32_t lastStatus = 0;

  if (restartAdvertising) {
    restartAdvertising = false;
    delay(200);
    BLEDevice::startAdvertising();
    Serial.println("advertising again");
  }

  if (phoneConnected) {
    led(true);                                   // steady: a phone is connected
  } else if (millis() - lastBlink > 2000) {
    lastBlink = millis();
    led(true);                                   // clear 200 ms blink: waiting for a phone
    delay(200);
    led(false);
  }

  if (millis() - lastStatus > 5000) {
    lastStatus = millis();
    Serial.printf("%s %s | %s | up %lus\n", DEVICE_NAME, FW_VERSION,
                  phoneConnected ? "phone connected" : "advertising, waiting for phone",
                  (unsigned long)(millis() / 1000));
  }

  delay(100);
}
