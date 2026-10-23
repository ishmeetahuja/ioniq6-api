# 4 · Calibrate and use

## Set the walk-away distance (once)

The app locks when the tag's signal gets weaker than a level you choose. Set it where *you* want:

1. Put the tag in its **final spot** in the car (moving it changes the signal).
2. Turn **Walk-away lock** on, so the app connects to the tag.
3. Walk to where you'd want the car to lock. **10–15 m** is a good start.
4. **Settings → Walk-away distance → Use current signal.**

Too eager (locks while you're still loading the trunk)? Set a lower number, e.g. −90 instead of
−85. Too slow? Set a higher one. The bar on the home screen shows the live signal against your line.

## Test it

1. **Settings → Background and testing → Test: Arm**, then walk away.
2. You should feel **two quick taps** (lock sent), then **one firm buzz** (locked), and get a
   "Car locked" notification on the phone and in ntfy.
3. Then do it for real: drive, park, switch off, walk away.

## Reading the home screen

The big word is the car's state, worked out from everything the app knows (newest first): your
phone's link to the car, the tag, locks the server confirmed, then Hyundai's report.

| You see | It means |
|---|---|
| **Driving** / **In the car** | Connected to the car's Bluetooth, moving or not (from the phone's GPS). |
| **Not locked yet** | Parked and you're still near; it locks when you walk away. |
| **Locked** / **Unlocked** | A confirmed lock or unlock, with when and why ("Locked at 8:34 a.m. by walk-away"). |
| **Not confirmed** | You parked but no lock has been confirmed since. Tap **Refresh** to ask the car. |
| **Locking…** | A walk-away lock is on its way. |

**ARMED** in the walk-away panel means the next walk-away will lock the car.

## Quick controls

| Button | What it does |
|---|---|
| **Lock** | Locks the car. Filled light blue while it's locked. |
| **Unlock** | **Press and hold for half a second** (an amber ring fills) so a pocket tap can't open the car. If walk-away lock is on, it also pauses it for 2 hours so it doesn't lock on whoever you opened it for; **Resume** is in the walk-away panel. |
| **Climate** | Temperature, how long, Start / Stop. Filled while running. |
| **Find car** | Asks the car where it is and opens Maps. (This wakes the car; *Parked here* doesn't.) |

Every button: **tick** when pressed → **spinning ring** while it's sent → **flash and buzz** when
done, or **coral, a shake and three long buzzes** if it failed (with the reason on screen).

## Feel it in your pocket

| Vibration | Meaning |
|---|---|
| Two quick taps | Lock sent |
| One firm buzz | Locked |
| Three quick taps | *Heading to the car?* |
| Three long buzzes | **Not locked**: check the notification |

## Home mode

At home the tag often reaches the house, so "signal dropped" means nothing there. Instead the car
locks **once per visit**: after you park, or after you've been right at the car (signal at the "At
the car" level for a few seconds), it locks once you've been away from it for 60 seconds
(adjustable). Walking around the house never counts as a visit. It switches on and off by itself
once your home Wi-Fi name or location is saved.

## Heading to the car?

Away from home, after being away from the car for **5+ minutes**: when the tag comes back in range
and the signal keeps getting stronger for a couple of seconds, your phone buzzes with **Unlock** and
**Start climate** buttons. The server signs in to Hyundai ahead of time so a tap goes straight
through. It fires once per return, never at home or while driving, and **never unlocks by itself**.
Turn it off in **Settings → Walking to the car**.

## Parked here

When your phone leaves the car's Bluetooth, the app remembers where it was (from the GPS it
already uses while you drive). The card shows how far away the car is, the walking time, and an
arrow that turns with your phone. Tap it for Google Maps walking directions. Nothing is sent to
Hyundai or anywhere else.

## Pause

**Pause for 2 hours** (in the walk-away panel or the app's notification) stops auto-lock on the
server: for a car wash, or someone waiting in the car. **Resume** turns it back on.

## Safety nets (nothing to do)

- **Your phone goes quiet** after you park (dead battery, no signal, app killed): the server locks
  on its own after 10 minutes.
- **Backup check:** every 6 hours the server looks at Hyundai's saved status. If the car is off and
  unlocked, it locks it (with a 60-second cancel window on ntfy). It stays quiet when a lock was
  already confirmed or your phone recently said it's at the car.
- **No network when you walk away:** the app keeps retrying for several minutes and alerts you so
  you can lock it yourself.
