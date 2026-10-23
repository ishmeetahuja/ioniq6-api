# Contributing

Thanks for helping! Bug reports, docs fixes, testing on other BlueLink Canada cars, and code are all
welcome. Please be kind and patient: this is a hobby project.

## Ground rules

1. **Nothing may unlock, start or open the car automatically.** Every such action needs a deliberate
   action by the owner. PRs that change this won't be merged.
2. **Changes to when the car locks** (the walk-away logic in `ProximityService.kt`, auto-lock in
   `server.js`) need an issue first, a clear explanation of the case they fix, and tests where possible.
3. **Be gentle with Hyundai.** Don't add sign-ins, car wake-ups (`rltmvhclsts`, commands, location) or
   polling without a strong reason; Cloudflare blocks IPs that overdo it. The server's caches and
   budgets exist for a reason.
4. **No secrets or personal data** in commits: no `.env`, tokens, keystores, VINs, home locations,
   Wi-Fi names or IPs.

## Project layout

```
android/    the app (Kotlin, no external libraries)
firmware/   the ESP32 tag (Arduino / PlatformIO)
server/     BlueLink Canada client, HTTP API + auto-lock (Node 18+, no dependencies), installer
server/test the server's end-to-end tests against a mock BlueLink
docs/       user guides
```

## Building

**Server** (Node 18+, no `npm install` needed):

```bash
bash server/test/run.sh          # 118 end-to-end tests against a mock BlueLink, ~2 min
cd server && docker build -t ioniq6-api .
```

**App:** open `android/` in Android Studio, or `gradle -p android assembleRelease` with Gradle 8.10
and JDK 17. Without signing secrets the APK is signed with the debug key: fine for testing, but it
won't install over an official release (uninstall first).

**Firmware:** with [PlatformIO](https://platformio.org/), `pio run -d firmware -e esp32dev -t upload`
(or `-e esp32c3` / `-e esp32s3`). Another board? Add an `[env:…]` to `firmware/platformio.ini` with
its LED pin (`LED_PIN`, `LED_ACTIVE_LOW`, `LED_RGB`) and a line in `docs/flasher/manifest.json`.
The tag's Bluetooth IDs in `firmware/src/main.cpp` must match `Ble.SERVICE_UUID` in the app.

Every push and PR runs all of this on GitHub Actions.

## Pull requests

- One topic per PR; describe what changed and how you tested it.
- Add a plain-language entry to `CHANGELOG.md` under **Unreleased**, with an example.
- For app changes, a screenshot or a snippet of the app's log helps a lot.

## Releases (maintainers)

**One-time: the signing key.** Generate it once, keep a backup somewhere safe (password manager),
and never commit it. Losing it means users must uninstall to update.

```bash
docker run --rm -it -v "$PWD":/k -w /k eclipse-temurin:17 \
  keytool -genkeypair -v -keystore release.jks -alias walkaway -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

Then in GitHub: **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | contents of `release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the password you typed |
| `ANDROID_KEY_PASSWORD` | the same password |
| `ANDROID_KEY_ALIAS` | `walkaway` |

Delete `release.jks.b64` afterwards and keep `release.jks` in your backup.

**One-time: the flasher page.** **Settings → Pages → Build and deployment → Source: GitHub
Actions.** Each release then publishes the one-click flasher with that release's firmware.

**Each release:**

1. In `CHANGELOG.md`, rename **Unreleased** to `## v1.2.3 — <date>` (the release notes are taken
   from that section).
2. **Actions → Build → Run workflow**, type the version (`1.2.3`), Run. Or push a tag `v1.2.3`.

It builds and tests everything, publishes the GitHub Release (APK, firmware, checksums), and the
server image as `:1.2.3` and `:latest`, which is what `install.sh` pulls.
