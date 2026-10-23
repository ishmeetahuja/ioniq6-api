# 3 · The app

Android 12 or newer. The app isn't on the Play Store: Google's rules for background Bluetooth and
location make that impractical for a hobby project. You install the APK directly.

## Install

**Option A: download it**

1. On your phone, open the [latest release](https://github.com/ishmeetahuja/ioniq6-api/releases/latest)
   and tap **`walkaway-lock.apk`**.
2. Open the download. Android asks to allow installs from your browser: allow it once.
3. Updates: install the newer APK over the old one. Your settings are kept.

**Option B: let [Obtainium](https://github.com/ImranR98/Obtainium) keep it updated**

Install Obtainium, tap **Add app**, paste `https://github.com/ishmeetahuja/ioniq6-api`, and it
installs new releases for you.

> [!TIP]
> Each release lists a `SHA256SUMS.txt` if you want to check the file you downloaded.

## Set it up

Open the app and tap the **settings button** (top right). Go through it top to bottom:

1. **Server:** paste the **server address** and **phone token** from the installer and tap
   **Save and test**.
   - The phone token is enough for the walk-away lock and the Lock button.
   - For **Unlock, Climate, Charging and Find car**, also add the **admin token**:
     `sudo grep ^ADMIN_TOKEN= /opt/ioniq6/.env` on the server. With it saved, anyone holding
     your *unlocked* phone could unlock the car, so it's your call.
2. **Car → Car Bluetooth:** pick your car from the paired devices. This is how the app knows
   you're driving or have parked.
3. **Car → Car photo** *(optional)*: a photo of your car; otherwise a generic drawing shows.
4. **ESP32 tag → Find:** with the tag powered and nearby, pick it from the list.
5. **Background and testing → Battery → Allow.** On **Samsung**, also: phone *Settings → Battery →
   Background usage limits → Never sleeping apps* → add the app. Other brands have similar
   "don't optimize" settings ([dontkillmyapp.com](https://dontkillmyapp.com) lists them).
6. **Walk-away distance:** see [4 · Calibrate and use](4-using.md).
7. **Home** *(optional but recommended)*: tap **Use current Wi-Fi** while on your home Wi-Fi
   (and/or **Use current location** on your driveway), and allow location **"Allow all the
   time"** when asked. Then **Am I home?** confirms it.
8. Back on the home screen, turn on **Walk-away lock**.

## Permissions, and why

| Permission | Why it's needed |
|---|---|
| **Nearby devices** (Bluetooth) | To connect to the tag and to know when you're connected to the car. |
| **Notifications** | Lock confirmations, the *Heading to the car?* alert, problems. |
| **Location: "Allow all the time"** | Android only reveals the Wi-Fi name with location access. It's also used for home detection, *Parked here*, and the speed gauge, all **on the phone**. The app never sends your location anywhere; only *Find car* asks Hyundai where the car is. |
| **Ignore battery optimization** | So Android doesn't put the app to sleep while it's watching the tag. |

The app keeps a small ongoing notification while it's watching. That's Android's rule for apps
that work in the background. On Android 14+ you can swipe it away and monitoring carries on.

---

Next: **[4 · Calibrate and use](4-using.md)**
