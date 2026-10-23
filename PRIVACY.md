# Privacy

Short version: **everything runs on your own devices.** Nothing is sent to the author of this
project or to any third party, apart from Hyundai (to control your car) and the notification
service you choose.

| Data | Where it lives | Who it's sent to |
|---|---|---|
| BlueLink email, password, PIN | `.env` on **your server** (root-only file) | Hyundai, to sign in |
| Hyundai sign-in session | Your server's Docker data volume | Hyundai |
| Car status (battery, locks, doors…) | Your server (cache) and your phone | Comes from Hyundai |
| Car location (*Find car*) | Shown on your phone, not stored | Comes from Hyundai, only when you tap Find car |
| Your phone's location (home detection, *Parked here*, speed) | **Only on your phone** | Nobody |
| Home Wi-Fi name, home spot | Only on your phone | Nobody |
| Phone / admin tokens | Your server and your phone | Only between them |
| Notifications ("Car locked at 8:34…") | | The ntfy topic or Telegram chat **you** configure |

Notes:

- On the public **ntfy.sh** server, anyone who knows your topic name can read it. The installer
  makes a long random one; keep it private, or self-host ntfy.
- The server keeps a small log of requests to Hyundai (time, endpoint, result) in its data volume,
  for the `doctor` health check. It contains no passwords or tokens.
- The app has no analytics, ads or crash reporting.
