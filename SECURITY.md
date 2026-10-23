# Security

This project controls a car, so security reports are very welcome.

## Reporting a problem

Please **don't open a public issue** for a security problem. Use GitHub's private reporting:
**Security → Report a vulnerability** on this repository. You'll get a reply as soon as possible
(it's a hobby project, so allow a few days).

Helpful to include: what's affected (app, server, firmware), how to reproduce it, and what an
attacker could do with it.

## Design choices worth knowing

- **Nothing unlocks automatically.** Any change that would unlock, start or open the car without
  a deliberate action by the owner will not be accepted.
- **Two tokens.** The phone token can lock and drive auto-lock but can't unlock, start climate or
  read location. The admin token can do everything and is optional in the app.
- **Brute-force protection.** Ten wrong tokens from one IP lock it out for 15 minutes.
- **HTTPS only** from outside, via Caddy with automatic certificates. The API port isn't
  published to the network.
- **The ESP32 tag has no pairing or authentication.** A spoofed tag could, at worst, make the
  phone think you're still near the car and *delay* a lock; it can never cause an unlock. The
  server's "phone went quiet" timer and backup check still apply.
- **Release APKs** are signed with a key kept only in GitHub Actions secrets. Check downloads
  against `SHA256SUMS.txt` in each release.

## Supported versions

Only the latest release gets fixes.
