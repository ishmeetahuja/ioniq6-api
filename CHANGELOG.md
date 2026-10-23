# Changelog

What changed in each version, in plain words with examples. Newest first.

**What to update for a new version:**
- **App:** install `walkaway-lock.apk` over the old one (settings are kept), or let Obtainium do it.
- **Server:** in your install folder, `sudo bash install.sh`.
- **ESP32:** only when an entry says so. Flash `esp32-car-tag.bin` at address `0x0`.

---

## Unreleased

- Nothing yet.

---

## v1.0.0 — first public release

Built and used daily on an IONIQ 6 before going public.

**Walk-away lock**
- Locks the car when the ESP32 tag's signal fades as you walk away. Your phone gives two quick taps
  (lock sent), then one firm buzz (locked), and ntfy records it with the reason.
  *Example:* park at the grocery store, walk in → "Car locked at 5:14 PM because you walked away
  from the car."
- **Home mode:** at home the tag reaches the house, so the car locks once per visit instead, a set
  time after you leave the car (60 s by default). It knows you're home from your Wi-Fi name or a
  spot on the map.
- If the tag loses power while the signal was strong (e.g. you started the car), that's never
  treated as walking away.

**Heading to the car?**
- After 5+ minutes away, as soon as the tag reconnects and the signal climbs for a couple of
  seconds, your phone buzzes three times and offers **Unlock** and **Start climate**, while you're
  still walking up. Never at home, never while driving, never unlocks by itself.

**Safety nets on the server**
- If your phone goes quiet for 10 minutes after you park (dead battery, no signal), the server
  locks on its own.
- A backup check every 6 hours locks a car that was left off and unlocked.

**Home screen**
- Quick controls: **Lock**, **Unlock** (press and hold), **Climate**, **Find car**, with a spinning
  ring while sending and a flash and buzz when done.
- **Vehicle status**, a **speed** gauge (phone GPS, while connected to the car), **Parked here**
  (distance, walking time and an arrow to your car; tap for Google Maps), and **Charging**
  (level, limit, controls).
- The status word is always worked out from what's freshest: your phone's link to the car, the
  tag, confirmed locks, then Hyundai's report.

**Easy on Hyundai**
- Signs in only when needed, never twice at once or within 2 minutes (except for a lock), and signs
  in ahead when you park, so the walk-away lock goes straight to the car.
- Reads Hyundai's saved status (no car wake-up) for routine checks; at most 4 automatic car
  wake-ups a day, none overnight.
- Backs off politely when Hyundai's Cloudflare says "too many requests", and keeps retrying a lock.

**Server**
- One-command installer for Linux, Raspberry Pi, Unraid and cloud servers, with free HTTPS (no
  domain needed), a `doctor` health check, and a watchdog that restarts it if it hangs.
- Two tokens: the phone's can lock but never unlock.
