# Troubleshooting

Two places to look first:

- **The app's log:** bottom of **Settings**. It says what the app saw and did ("Away: signal −91
  dBm", "away → HTTP 202", "Lock result: Car locked").
- **The server's health check:** in the install folder, `sudo bash install.sh doctor`. It checks
  the containers, HTTPS from outside, the Hyundai sign-in, and recent requests to Hyundai.

## The app can't reach the server

- Open `https://<your server address>/healthz` in the phone's browser. It should say `"ok": true`.
- Works on home Wi-Fi but not on mobile data? Port **443** isn't forwarded to the server. See
  [port forwarding](2-server.md#port-forwarding-at-home).
- Certificate error? Port **80** or **443** wasn't reachable when Caddy asked for the certificate.
  Fix the forwarding, then `sudo docker compose restart caddy`.
- Your home IP changed: re-run `sudo bash install.sh`. It moves the address; put the new one in the app.

## "Rate limited", "error 1015", "HTTP 429"

Hyundai's Cloudflare is pausing your IP because of too many sign-ins. **Wait.** It clears by itself,
usually within an hour. The server backs off and keeps retrying any lock. Restarting or re-running
the installer only extends the block. If it keeps happening, run `doctor`: it counts sign-ins
and requests per hour, and tells you how long each sign-in lasted.

## Hyundai keeps asking for a verification code

`sudo bash install.sh login` redoes it. The server's device id is kept in its Docker data volume, so
restarts and updates don't ask again. Removing that volume does.

## The tag isn't found / the LED isn't blinking

- No blue blink at all: the power bank switched off. Test it for 5 minutes on the bench, and use
  its low-current mode or another bank ([details](1-hardware.md#power-the-one-thing-to-get-right)).
- Blinking but the app can't find it: Bluetooth and **Nearby devices** permission on? Is the tag
  within a few metres when you tap **Find**?
- Re-flash if in doubt: unplug and replug after flashing, or the board can stay in flashing mode.

## It didn't lock when I walked away

Check the app's log:

- **No "Away" line:** the signal never dropped below your line. Set a lower number (e.g. −90), or
  you were at home (home mode locks once per visit instead).
- **"Away" but "can't reach the server":** the phone had no network. It retries for a while and
  shows an alert.
- **"Car NOT locked":** the notification says why (something open, Hyundai refused). The server
  keeps trying if something was open.

The app being killed in the background is the most common cause on Samsung, Xiaomi, OnePlus and
Huawei phones: see [dontkillmyapp.com](https://dontkillmyapp.com) and allow the app to run
unrestricted.

## It locks while I'm still at the car

Your line is too close to the car's normal signal. Set a lower number (more negative), or move the
tag somewhere with a steadier signal. Locking with you beside the car is harmless: the key in your
pocket opens it at the handle.

## "Heading to the car?" never shows

- It needs 5+ minutes away first, and only fires away from home.
- **Settings → Walking to the car** must be on.
- Check the phone isn't in a "do not disturb" mode that hides notifications from the app.

## Battery, range or climate look old

Those come from Hyundai's saved copy, which updates when the car reports in. The app doesn't wake
the car often, to stay under Hyundai's daily limits. Tap **Refresh** (next to "Updated …") to ask
the car itself.

## Still stuck?

Open an issue with the [bug form](https://github.com/ishmeetahuja/ioniq6-api/issues/new/choose).
Include the app log and the `doctor` output: neither contains passwords or tokens. You can blank
out your server address.
