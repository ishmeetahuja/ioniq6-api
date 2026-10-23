# 1 · The tag: ESP32 + power bank

The tag is a small ESP32 board that sits in the car and does one thing: it's a Bluetooth device
your phone stays connected to. As you walk away, the phone sees the signal fade and acts. The tag
needs no Wi-Fi, no internet and no setup beyond flashing one file.

## What to buy

| Part | What to look for | Rough price (CAD) |
|---|---|---|
| **ESP32 dev board** | The classic **ESP32-WROOM-32** "DevKit" kind, with a USB port and a blue LED. Sold as ESP32 DevKitC, ESP32-DevKitV1, ELEGOO/HiLetgo ESP-WROOM-32, etc. | $8–15 |
| **USB cable** | A **data** cable for flashing (some cables only charge), plus whatever cable powers it in the car | – |
| **USB power bank** | Must keep powering a very small load (see below). Small and cheap is fine. | $15–30 |
| *Optional:* a small case | Any small plastic project box or 3D-printed case | – |

> [!NOTE]
> Right now the release file is built for the classic ESP32. The newer ESP32-C3 and ESP32-S3
> boards also do Bluetooth LE and are planned; if you build the firmware yourself with
> PlatformIO you can add a board environment for them (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## Flash it (5 minutes, nothing to install)

Use **Chrome or Edge** on a computer:

1. Download **`esp32-car-tag.bin`** from the [latest release](https://github.com/ishmeetahuja/ioniq6-api/releases/latest).
2. Plug the ESP32 into the computer with a **data** USB cable.
3. Open **<https://espressif.github.io/esptool-js/>**, click **Connect**, and pick the board's port.
   - Nothing shows up? Windows may need the **CP210x** or **CH340** USB driver (the chip name is
     printed next to the board's USB port).
   - Won't connect? Hold the board's **BOOT** button while clicking Connect.
4. Set **Flash Address** to **`0x0`**, choose the `.bin` file, click **Program**. It takes about a minute.
5. Close the flasher tab, then **unplug the board and plug it back in**.

**How to tell it worked:** the **blue** LED blinks every 2 seconds, which means it's waiting for a
phone. Once the app connects it stays on. (The red LED is just power.) If you have the
[nRF Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) app, a scan
shows a device called **`IONIQ6-PROX`**.

## Power: the one thing to get right

Car USB → power bank → ESP32. The power bank keeps the tag running after the car is switched off
(most cars cut USB power then), and the car recharges the bank while you drive.

**Test your power bank before you rely on it:** plug the ESP32 into it, wait **5 minutes**, and
check the blue LED is still blinking. Many power banks switch off when the load is as small as an
ESP32's. If yours does:

- look for a **low-current / "trickle" mode** (often a double-press or long-press of its button), or
- try a different bank. Basic ones without a display often work best.

Also good to know:

- Many banks **without pass-through charging** cut their output while they're being charged. That
  means the tag is off while you drive and starts up when you park. That's fine: the app looks for
  the tag as soon as you park. What matters is that the bank turns its output back on by itself
  when charging stops.
- If your car has a USB port that **stays powered with the car off**, you can skip the power bank.

## Where to put it

**Glovebox or centre console** works well. Avoid the dashboard in direct sun. Put it in its final
spot *before* you calibrate the walk-away distance in the app, because moving it changes the
signal.

## How many tags can live near each other?

As many as you like. Every tag shows the same name, but the app pairs with **your** tag's own
address, so a neighbour's tag in the next parking spot doesn't interfere.

---

Next: **[2 · Run the server](2-server.md)**
