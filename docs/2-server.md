# 2 · The server

The server is the part that talks to Hyundai. It holds **your** BlueLink login, sends the lock,
answers the app, and keeps the safety nets running (it locks on its own if your phone goes quiet
after you park). It runs in Docker and needs very little: a Raspberry Pi is plenty.

## What you need

- A machine that's **always on** with **Docker**: Raspberry Pi 4/5 (64-bit OS), a Linux PC or
  mini PC, a NAS that runs Docker, Unraid, or a small cloud server.
- **HTTPS from the internet** to that machine, so your phone can reach it from anywhere:
  - **At home:** forward **TCP port 443** (and port 80 if you can) on your router to the machine.
  - **Cloud server:** allow ports 443 and 80 in its firewall.
- **No domain needed.** The installer uses a free `<your-ip>.sslip.io` name with a real HTTPS
  certificate. You can use your own domain if you have one.

> [!TIP]
> **Home is better than the cloud.** Hyundai ties each sign-in to the IP address that made it,
> and is stricter with cloud IP ranges. A home connection's IP usually stays the same for months.
> If your IP does change, re-run the installer and it moves the address for you.

## Install

### Linux, Raspberry Pi, or a cloud server

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo docker run --rm ghcr.io/ishmeetahuja/ioniq6-api/server cat /app/install.sh > install.sh && sudo bash install.sh
```

### Unraid

1. **Apps** → install **Compose Manager Plus** (it provides `docker compose`).
2. Open the **Terminal** (`>_` at the top right) and paste:

   ```bash
   docker run --rm --pull always ghcr.io/ishmeetahuja/ioniq6-api/server cat /app/install.sh > /tmp/install.sh && bash /tmp/install.sh
   ```

   If it says port 443 is taken, that's Unraid's own web interface: **Settings → Management
   Access → HTTPS port: `4443`** (or *Use SSL/TLS: No*) → Apply, then paste the line again.

### What the installer asks

1. **Hostname:** press **Enter** to use the free `sslip.io` name, or type your own domain.
2. Your **BlueLink email, password and 4-digit PIN**. They're stored only in the `.env` file on
   this machine (readable by root only).
3. Hyundai treats the server as a new device, so it **emails (or texts) you a code** once. Type it in.

At the end it prints three things. Keep them handy for the app:

```
  Server:       https://203-0-113-7.sslip.io
  Phone token:  3f9c…(48 characters)
Notifications: install the ntfy app and subscribe to topic:  ioniq6-…
```

- **Server** (address) and **phone token** go into the app.
- **ntfy topic:** install [ntfy](https://ntfy.sh) on your phone and subscribe to it. Every lock is
  reported there, with the reason.
- The **admin token** (needed for Unlock, Climate, Charging and Find car in the app) is in the
  `.env` file: `sudo grep ^ADMIN_TOKEN= /opt/ioniq6/.env` (Unraid: `/mnt/user/appdata/ioniq6/.env`).

## Port forwarding at home

In your router's app or admin page, look for **Port forwarding** (sometimes under *Advanced*,
*NAT* or *Firewall*):

| Setting | Value |
|---|---|
| Protocol | TCP |
| External port | 443 (and a second rule for 80) |
| Internal IP | the server's local IP (give it a **reserved / static** address too) |
| Internal port | same as external |

If your internet provider's modem is also a router (common with Rogers, Bell, Telus), either put
it in **bridge mode** or forward the ports on it as well.

> [!NOTE]
> **Can't forward ports?** Some connections (carrier-grade NAT, some apartment and mobile
> internet) don't allow it. Then a small cloud server in a Canadian region is the simplest
> option. Tunnels (Cloudflare Tunnel, Tailscale Funnel) can work too but aren't set up by the
> installer yet.

## Everyday commands

All of these run in the install folder: `/opt/ioniq6` (Unraid: `/mnt/user/appdata/ioniq6`).

| What | Command |
|---|---|
| **Update** to the newest version | `sudo bash install.sh` |
| **Health check**: what's working, what isn't | `sudo bash install.sh doctor` |
| Redo Hyundai's email verification | `sudo bash install.sh login` |
| Live logs | `sudo docker compose logs -f api` |
| Stop / start | `sudo docker compose down` / `sudo docker compose up -d` |

The installer also sets up a **watchdog** that checks every 3 minutes and restarts the server if it
stops answering. It tells you on ntfy when it had to.

## Settings

Everything is in `.env` in the install folder, with comments. The ones you might touch:

| Setting | Default | What it does |
|---|---|---|
| `AUTOLOCK_GRACE_SECONDS` | `20` | Wait this long after "walked away" before locking. The app usually asks for an immediate lock, so this mostly applies to other triggers. |
| `GUARD_INTERVAL_MINUTES` | `30` (on) | Backup check on/off (`0` = off). It looks every `GUARD_EVERY_HOURS` (6). |
| `WATCH_SILENT_MINUTES` | `10` | If the phone goes quiet this long after parking without a lock, the server locks. |
| `NTFY_URL` / `TELEGRAM_*` | | Where notifications go. |
| `BLUELINK_VIN` | | Only if your account has more than one car. |

After changing `.env`: `sudo docker compose up -d`.

Full API reference and every setting: **[server/README.md](../server/README.md)**.

## Being kind to Hyundai's servers

Hyundai's API sits behind Cloudflare, which **temporarily blocks an IP that signs in too often**
(you'd see "error 1015" or "HTTP 429" in the logs). The server is built to avoid that:

- it signs in only when it must (and never twice within 2 minutes, except for a lock)
- it reads Hyundai's **saved** status for routine checks instead of waking the car
- it caps automatic car wake-ups (4 a day, none overnight)
- it backs off and retries a refused lock instead of hammering

If you ever do get blocked, **wait it out**. It clears by itself, usually within an hour.
Retrying, restarting or re-running the installer only makes it last longer.

---

Next: **[3 · Install the app](3-app.md)**
