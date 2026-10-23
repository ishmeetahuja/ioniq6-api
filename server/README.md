# Server: BlueLink Canada API + walk-away auto-lock

The part of [Walk-away Lock](../README.md) that talks to Hyundai. Node 18+, no dependencies. To
install it, see **[docs/2-server.md](../docs/2-server.md)**; this page is the reference.

| File | What it is |
|---|---|
| `server.js` | HTTP API, auto-lock engine, safety nets, notifications |
| `lib/bluelink-ca.js` | The BlueLink Canada client (sign-in, rate-limit back-off, commands) |
| `ioniq6.js` | A command-line tool using the same client |
| `install.sh` | Installer, updater and `doctor` health check |
| `Dockerfile`, `docker-compose.yml`, `Caddyfile`, `.env.example` | Deployment |
| `test/` | End-to-end tests against a mock BlueLink: `bash test/run.sh` |

## HTTP API

Every request except `/healthz` and the notification cancel link needs
`Authorization: Bearer <token>`:

- **ADMIN_TOKEN:** everything.
- **PHONE_TOKEN:** status, **lock**, and auto-lock. It can't unlock, start climate or read the car's
  location, so a leaked phone token can't open the car.

Ten bad tokens from one IP lock that IP out for 15 minutes.

| Method & path | Token | Body / query | Notes |
|---|---|---|---|
| `GET /healthz` | none | | Liveness |
| `GET /v1/status` | phone | `?refresh=true` · `?auto=true` | Battery, range, charging, locks, doors, climate… `refresh` wakes the car for live data; `auto` is the app's automatic refresh, which only wakes the car within the daily budget. |
| `GET /v1/location` | admin | | `{lat, lon, time, maps}` (wakes the car) |
| `GET /v1/charge-limit` | admin | | `{ac, dc}` |
| `GET /v1/trips` | admin | | Recent EV trips |
| `GET /v1/vehicles` | admin | | Vehicles on the account |
| `POST /v1/lock` | phone | `?wait=true` | |
| `POST /v1/unlock` | admin | `?wait=true` | |
| `POST /v1/climate/start` | admin | `{"temp":22,"duration":15,"defrost":true,"steering":true,"rearWindow":true,"seats":{"driver":"high-heat"}}` | Seats: `off low-heat med-heat high-heat low-cool med-cool high-cool` |
| `POST /v1/climate/stop` | admin | | |
| `POST /v1/charge/start` · `/stop` | admin | | |
| `PUT /v1/charge-limit` | admin | `{"ac":80,"dc":90}` | 50–100 in steps of 10 |
| `GET /v1/jobs/:id` | phone | | Result of a command |
| `POST /v1/auth/login` | admin | `{"otpMethod":"email"\|"sms"}` | One-time device verification (the installer does this) |
| `POST /v1/auth/otp` | admin | `{"code":"123456"}` | |
| `GET /v1/auth` | admin | | Signed in? Which car? |
| `GET /v1/autolock` | phone | | State, last result, last 20 events |
| `POST /v1/autolock/away` | phone | `{"source":"esp32","graceSeconds":0,"checkFirst":false,"reason":"…"}` | "I've walked away." Optional per-event overrides. |
| `POST /v1/autolock/near` | phone | `{"source":"car-bluetooth"}` | "I'm back / driving": cancels a pending lock |
| `POST /v1/autolock/parked` | phone | `{"source":"car-bluetooth"}` | Starts the "phone went quiet" timer; signs in ahead if needed |
| `POST /v1/autolock/here` | phone | | Heartbeat while near the car |
| `POST /v1/prepare` | phone | | Sign in ahead (at most once a minute) |
| `POST /v1/autolock/cancel` | phone | | Cancel a pending lock |
| `POST /v1/autolock/pause` | phone | `{"minutes":120}` | `0` resumes |
| `POST /v1/autolock/enable` · `/disable` | admin | | Persistent on/off |
| `PUT /v1/autolock/settings` | admin | `{"graceSeconds":15,"checkFirst":false}` | Change defaults (persisted) |

**Commands are asynchronous:** they return `202` with a job id (the car takes 10–60 s), and you poll
`GET /v1/jobs/:id`, or add `?wait=true` to wait for the car's answer. Commands run one at a time.

```bash
API=https://car.example.com
curl -H "Authorization: Bearer $ADMIN" $API/v1/status
curl -X POST -H "Authorization: Bearer $ADMIN" "$API/v1/lock?wait=true"
curl -X POST -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"temp":22,"duration":15}' $API/v1/climate/start
```

## Auto-lock, in short

1. `away` arrives (from the app, with no grace period by default).
2. After the grace period it sends a plain **lock**. Locking an already-locked car is harmless.
3. If Hyundai refuses, it wakes the car to find out why: something **open** → tells you, re-checks
   every 30 s, locks once it's closed (high-priority alert after 5 tries); **car on** or **already
   locked** → leaves it.
4. Every outcome goes to ntfy / Telegram with the reason.

Plus the safety nets: **phone went quiet** (10 min after `parked` with no lock and no heartbeat)
and the **backup check** (every 6 h, Hyundai's saved status only). Details:
[docs/how-it-works.md](../docs/how-it-works.md).

## Configuration

All settings are in `.env`, documented in [`.env.example`](.env.example): BlueLink login, tokens,
auto-lock timings, safety nets, how often the car may be woken, and notifications.

## CLI

```bash
node ioniq6.js setup                 # email, password, PIN -> ~/.ioniq6/config.json (mode 600)
node ioniq6.js login                 # first time: Hyundai emails/texts a code
node ioniq6.js status [--refresh]    # --refresh wakes the car for live data
node ioniq6.js location
node ioniq6.js lock | unlock
node ioniq6.js start --temp 22 --duration 15 --defrost --steering --seat-driver high-heat
node ioniq6.js stop
node ioniq6.js charge start | stop
node ioniq6.js charge-limit [--ac 80 --dc 90]
node ioniq6.js trips
node ioniq6.js logout [--forget-device]
```

`--json` for scripting, `--no-wait` to skip waiting for the car, `IONIQ6_DEBUG=1` for raw API
responses (tokens masked).

## Caveats

- This uses Hyundai's unofficial web API. It can break when Hyundai changes the portal, and
  automated use may be against BlueLink's terms of service.
- Hyundai's API sits behind Cloudflare, which temporarily blocks IPs that sign in too often. The
  server is built to stay well under that, but don't add polling on top of it.
- Protocol ported from [hyundai_kia_connect_api](https://github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api).
