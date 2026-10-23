/*
 * IONIQ 6 proximity tag — ESP32 firmware.
 *
 * The ESP32 lives in the car (powered from a power bank that the car's USB port charges)
 * and does one simple thing: it is a Bluetooth Low Energy device the phone app stays connected
 * to. The phone measures the signal strength of that connection; when you walk away the signal
 * fades or the link drops, and the *phone* calls the auto-lock API. The ESP32 needs no Wi-Fi
 * and no internet.
 *
 * Blue LED (GPIO 2): blinks every 2 s = waiting for the phone; steady on = phone connected.
 * Serial (115200): a status line every 5 s, plus connection events.
 */
#include <Arduino.h>
#include <WiFi.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <esp_bt.h>

// Must match the Android app (Ble.kt).
#define SERVICE_UUID "7805ea83-4cbf-4a8d-b043-9e4a07355fc4"
#define INFO_UUID    "487e453a-6deb-4bae-8e5e-da2f321748c5"
#define DEVICE_NAME  "IONIQ6-PROX"
#define FW_VERSION   "1.1.0"

static const int LED_PIN = 2;

static BLEServer *server = nullptr;
static volatile bool phoneConnected = false;
static volatile bool restartAdvertising = false;

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
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Save power: no Wi-Fi, slower CPU (BLE is fine at 80 MHz).
  WiFi.mode(WIFI_OFF);
  setCpuFrequencyMhz(80);

  BLEDevice::init(DEVICE_NAME);
  // Full transmit power: a steadier signal through the car body makes the phone's
  // "you walked away" threshold more reliable.
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *info = service->createCharacteristic(INFO_UUID, BLECharacteristic::PROPERTY_READ);
  info->setValue("IONIQ6-PROX " FW_VERSION);
  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);  // the app finds the tag by this UUID
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("IONIQ6-PROX " FW_VERSION " advertising");
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
    digitalWrite(LED_PIN, HIGH);                 // steady: a phone is connected
  } else if (millis() - lastBlink > 2000) {
    lastBlink = millis();
    digitalWrite(LED_PIN, HIGH);                 // clear 200 ms blink: waiting for a phone
    delay(200);
    digitalWrite(LED_PIN, LOW);
  }

  if (millis() - lastStatus > 5000) {
    lastStatus = millis();
    Serial.printf("IONIQ6-PROX %s | %s | up %lus\n", FW_VERSION,
                  phoneConnected ? "phone connected" : "advertising, waiting for phone",
                  (unsigned long)(millis() / 1000));
  }

  delay(100);
}
