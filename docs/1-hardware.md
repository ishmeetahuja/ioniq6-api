# 1 · The tag: ESP32 + power bank

The tag is a small ESP32 board that sits in the car and does one thing: it's a Bluetooth device
your phone stays connected to. As you walk away, the phone sees the signal fade and acts. The tag
needs no Wi-Fi, no internet and no setup beyond flashing one file.

## What to buy

| Part | What to look for | Rough price (CAD) |
|---|---|---|
| **ESP32 board** | Any of the three below, with a USB port. | $6–15 |
| **USB cable** | A **data** cable for flashing (some cables only charge), plus whatever cable powers it in the car | – |
| **USB power bank** | Must keep powering a very small load (see below). Small and cheap is fine. | $15–30 |
| *Optional:* a small case | Any small plastic project box or 3D-printed case | – |

**Which board?** All three work the same; the flasher picks the right file for you.

| Board | Common names | Status LED |
|---|---|---|
| **ESP32** (classic) | ESP32-WROOM-32 DevKit, ESP32 DevKitC / DevKitV1, ELEGOO or HiLetgo ESP-WROOM-32 | blue LED |
| **ESP32-C3** | ESP32-C3 SuperMini (tiny, cheap, low power) | blue LED |
| **ESP32-S3** | ESP32-S3-DevKitC-1 | RGB LED, shown in blue |

The classic ESP32 is the one this was built and tested on. C3 and S3 builds are new: if you try
one, please say how it went in an issue.

## Flash it (2 minutes, nothing to install)

**The easy way:** open the **[one-click flasher](https://ishmeetahuja.github.io/ioniq6-api/)** in
**Chrome or Edge** on a computer, plug the board in with a **data** USB cable, click **Connect &
install** and pick the board's port. It detects your board and installs the right file.

**By hand** (any browser that can flash, or `esptool`):

1. Download the file for your board from the [latest release](https://github.com/ishmeetahuja/ioniq6-api/releases/latest):
   `esp32-car-tag.bin` (classic), `esp32c3-car-tag.bin` or `esp32s3-car-tag.bin`.
2. Open **<https://espressif.github.io/esptool-js/>**, click **Connect**, pick the board's port.
3. Set **Flash Address** to **`0x0`**, choose the file, click **Program**.

**If it won't connect:** try another cable or port. Windows may need the **CP210x** or **CH340**
USB driver (the chip name is printed next to the board's USB port). Or hold the board's **BOOT**
button while clicking Connect.

**Then unplug the board and plug it back in.** The LED blinks every 2 seconds while it's waiting
for a phone, and stays on once the app connects. With the
[nRF Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) app, a scan
shows a device called **`WALKAWAY-TAG`**.

## Power: the one thing to get right

Car USB → power bank → ESP32. The power bank keeps the tag running after the car is switched off
(most cars cut USB power then), and the car recharges the bank while you drive.

**Test your power bank before you rely on it:** plug the ESP32 into it, wait **5 minutes**, and
check the LED is still blinking. Many power banks switch off when the load is as small as an
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
