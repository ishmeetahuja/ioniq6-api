#!/bin/bash
# ioniq6 API — one-shot installer for a Linux box with Docker (Ubuntu/Debian/Raspberry Pi OS, Unraid, a small cloud server).
#
#   curl -fsSL https://get.docker.com | sudo sh
#   sudo docker run --rm ghcr.io/ishmeetahuja/ioniq6-api/server cat /app/install.sh > install.sh && sudo bash install.sh
#
# First run: asks for your BlueLink login, generates tokens, sets up HTTPS (free certificate,
# no domain needed), starts everything and does Hyundai's one-time email verification.
# Run it again later to update to the newest version:  cd /opt/ioniq6 && sudo bash install.sh
# Redo Hyundai's email verification:                  cd /opt/ioniq6 && sudo bash install.sh login
# Find out what's broken:                             cd /opt/ioniq6 && sudo bash install.sh doctor
set -euo pipefail
SELF=$(readlink -f "$0")

# Unraid keeps persistent data under /mnt/user/appdata (its root filesystem lives in RAM).
if [ -f /etc/unraid-version ]; then UNRAID=1; DEFAULT_DIR=/mnt/user/appdata/ioniq6; else UNRAID=""; DEFAULT_DIR=/opt/ioniq6; fi
DIR=${IONIQ6_DIR:-$DEFAULT_DIR}
IMAGE=${IONIQ6_IMAGE:-ghcr.io/ishmeetahuja/ioniq6-api/server:latest}

if [ "$(id -u)" -ne 0 ]; then echo "Run as root: sudo bash install.sh"; exit 1; fi
if [ -n "$UNRAID" ]; then
  command -v docker >/dev/null || { echo "Docker isn't running. Unraid → Settings → Docker → Enable Docker: Yes."; exit 1; }
  docker compose version >/dev/null 2>&1 || { echo "Needs Docker Compose: Unraid → Apps → search \"Compose Manager Plus\" → Install, then run this again."; exit 1; }
else
  command -v docker >/dev/null || { echo "Docker isn't installed. Run: curl -fsSL https://get.docker.com | sudo sh"; exit 1; }
fi
mkdir -p "$DIR"
[ "$SELF" != "$DIR/install.sh" ] && cp "$SELF" "$DIR/install.sh"
cd "$DIR"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
api()  { # api METHOD PATH [JSON] — calls the API from inside its container with the admin token
  docker compose exec -T -e M="$1" -e P="$2" -e B="${3:-}" api node -e '
    fetch("http://127.0.0.1:8080" + process.env.P, {
      method: process.env.M,
      headers: { Authorization: "Bearer " + process.env.ADMIN_TOKEN, "Content-Type": "application/json" },
      body: process.env.B || undefined,
    }).then((r) => r.text()).then((t) => console.log(t)).catch((e) => console.log(JSON.stringify({ error: e.message })));'
}
wait_healthy() {
  for _ in $(seq 1 30); do
    docker compose exec -T api node -e "fetch('http://127.0.0.1:8080/healthz').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "The API didn't start. Logs:"; docker compose logs --tail 30 api; exit 1
}

do_login() {
  say "Signing in to BlueLink"
  local R CODE
  R=$(api POST /v1/auth/login '{"otpMethod":"email"}')
  if echo "$R" | grep -q '"otp_sent"'; then
    echo "Hyundai just emailed a verification code to your BlueLink address."
    for _ in 1 2 3; do
      read -rp "Code from the email: " CODE
      R=$(api POST /v1/auth/otp "{\"code\":\"${CODE//[^0-9]/}\"}")
      echo "$R" | grep -q '"logged_in"' && break
      echo "Not accepted: $R"
    done
  fi
  if echo "$R" | grep -q '"logged_in"'; then
    echo "Signed in."
  else
    echo "BlueLink sign-in didn't finish: $R"
    echo "Retry with:  cd $DIR && bash install.sh login"
  fi
}

show_details() {
  local url token ntfy
  url=$(grep '^PUBLIC_URL=' .env | cut -d= -f2-)
  token=$(grep '^PHONE_TOKEN=' .env | cut -d= -f2-)
  ntfy=$(grep '^NTFY_URL=' .env | cut -d= -f2-)
  say "For the phone app:"
  echo "  Server:       ${url}"
  echo "  Phone token:  ${token}"
  [ -n "$ntfy" ] && echo "  ntfy topic:   ${ntfy##*/}"
}

# Small instances (512 MB) have no swap by default; a memory spike (image pulls, OS updates)
# can freeze the whole box. 1 GB of swap on disk prevents that.
ensure_swap() {
  local mem_mb
  mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
  [ "$mem_mb" -lt 1500 ] || return 0
  [ "$(swapon --show --noheadings | wc -l)" -eq 0 ] || return 0
  say "Adding 1 GB of swap (this server has ${mem_mb} MB of RAM)"
  fallocate -l 1G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=1024 status=none
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
}

# The free *.sslip.io name contains the server's IP. If the IP changed (restart without a
# static IP, or a static IP attached later), point the name at the new IP.
fix_ip() {
  local dom ip want
  dom=$(grep '^DOMAIN=' .env | cut -d= -f2-)
  case "$dom" in *.sslip.io) ;; *) return 0 ;; esac
  ip=$(curl -fsS https://checkip.amazonaws.com 2>/dev/null || true)
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 0
  want="${ip//./-}.sslip.io"
  [ "$dom" = "$want" ] && return 0
  say "This server's IP address changed"
  echo "  old: $dom"
  echo "  new: $want"
  sed -i "s|^DOMAIN=.*|DOMAIN=${want}|; s|^PUBLIC_URL=.*|PUBLIC_URL=https://${want}|" .env
  echo "Put the new server address (below) in the phone app."
  echo "To stop this happening again: on a cloud server, attach a static IP (then run this installer once"
  echo "more); at home, most ISPs keep the same IP for months, so just re-run this when it changes."
}

# 443 is required. 80 is optional: Caddy can get its certificate over 443 alone (TLS-ALPN),
# so on boxes where something else owns port 80 (Unraid's web UI) we simply don't bind it.
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
ours_running() { [ -n "$(docker compose ps -q --status running caddy 2>/dev/null)" ]; }

check_ports() {
  if ! ours_running && port_busy 443; then
    say "Port 443 is already in use on this machine"
    if [ -n "$UNRAID" ]; then
      echo "That's usually Unraid's own web interface (HTTPS). Move it so the car server can use 443:"
      echo "  Unraid → Settings → Management Access → HTTPS port: 4443 (or set Use SSL/TLS: No) → Apply"
      echo "Then open Unraid at https://<server-ip>:4443 and run this installer again."
    else
      echo "Something else is listening on 443. Stop it, or put this server on another machine."
    fi
    exit 1
  fi
  if grep -q '^BIND_HTTP=no' .env 2>/dev/null; then BIND_HTTP=no
  elif ours_running || ! port_busy 80; then BIND_HTTP=yes
  else BIND_HTTP=no; echo "(Port 80 is taken here, so only 443 is used. That's fine.)"
  fi
  if [ "$BIND_HTTP" = yes ]; then PORTS='"80:80", "443:443"'; else PORTS='"443:443"'; fi
}

write_files() {
  cat > Caddyfile <<'EOF2'
{$DOMAIN} {
	encode gzip
	reverse_proxy api:8080
}
EOF2
  cat > docker-compose.yml <<EOF2
x-logging: &logging
  driver: json-file
  options: { max-size: "10m", max-file: "3" }
services:
  api:
    image: ${IMAGE}
    env_file: .env
    environment:
      TRUST_PROXY: "true"
    volumes:
      - ioniq6_data:/data
    restart: unless-stopped
    logging: *logging
  caddy:
    image: caddy:2
    ports: [${PORTS}]
    environment:
      DOMAIN: \${DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on: [api]
    restart: unless-stopped
    logging: *logging
volumes:
  ioniq6_data:
  caddy_data:
  caddy_config:
EOF2
}

# Every 3 minutes: if HTTPS stops answering, bring the containers back up (and say so via ntfy).
install_watchdog() {
  cat > "$DIR/watchdog.sh" <<'EOF2'
#!/bin/bash
# Installed by install.sh — keeps the IONIQ 6 API answering.
cd "__DIR__" || exit 0
dom=$(grep '^DOMAIN=' .env | cut -d= -f2-)
ntfy=$(grep '^NTFY_URL=' .env | cut -d= -f2-)
healthy() { curl --noproxy '*' -fsS -m 10 --resolve "${dom}:443:127.0.0.1" "https://${dom}/healthz" >/dev/null 2>&1; }
healthy && exit 0
sleep 15; healthy && exit 0            # a single slow answer isn't an outage
logger -t ioniq6-watchdog "API not answering; starting containers"
docker compose up -d >/dev/null 2>&1
for _ in $(seq 1 12); do sleep 5; healthy && break; done
if ! healthy; then
  logger -t ioniq6-watchdog "still down; restarting containers"
  docker compose restart >/dev/null 2>&1
  for _ in $(seq 1 12); do sleep 5; healthy && break; done
fi
if healthy; then msg="The IONIQ 6 server had stopped answering and restarted itself. It's working again."
else msg="The IONIQ 6 server is down and couldn't restart itself. Run: cd __DIR__ && bash install.sh doctor"; fi
logger -t ioniq6-watchdog "$msg"
[ -n "$ntfy" ] && curl -fsS -m 10 -H "Title: IONIQ 6 server" -d "$msg" "$ntfy" >/dev/null 2>&1
exit 0
EOF2
  sed -i "s|__DIR__|$DIR|g" "$DIR/watchdog.sh"
  chmod 755 "$DIR/watchdog.sh"
  if [ -n "$UNRAID" ]; then
    # Unraid loads cron entries from /boot/config/plugins/*/*.cron (kept on the USB stick).
    mkdir -p /boot/config/plugins/ioniq6
    printf '# IONIQ 6 API watchdog\n*/3 * * * * %s/watchdog.sh > /dev/null 2>&1\n' "$DIR" > /boot/config/plugins/ioniq6/ioniq6-watchdog.cron
    if command -v update_cron >/dev/null; then update_cron; echo "Watchdog on: checks the server every 3 minutes and restarts it if needed."
    else echo "Watchdog script installed; it starts with the next reboot (or use the User Scripts plugin to run $DIR/watchdog.sh every 3 min)."; fi
    return 0
  fi
  if ! command -v systemctl >/dev/null || [ ! -d /run/systemd/system ]; then
    echo "(No systemd here — skipping the automatic watchdog.)"; return 0
  fi
  cat > /etc/systemd/system/ioniq6-watchdog.service <<EOF2
[Unit]
Description=IONIQ 6 API watchdog
After=docker.service
[Service]
Type=oneshot
ExecStart=${DIR}/watchdog.sh
EOF2
  cat > /etc/systemd/system/ioniq6-watchdog.timer <<'EOF2'
[Unit]
Description=Check the IONIQ 6 API every 3 minutes
[Timer]
OnBootSec=2min
OnUnitActiveSec=3min
[Install]
WantedBy=timers.target
EOF2
  systemctl enable docker >/dev/null 2>&1 || true
  systemctl daemon-reload
  systemctl enable --now ioniq6-watchdog.timer >/dev/null 2>&1
  echo "Watchdog on: checks the server every 3 minutes and restarts it if needed."
}

# ------------------------------------------------------------------ doctor: find what's broken
doctor() {
  local dom ip code err local_ok=0 public_ok=0 fails=0
  pass() { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
  fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; fails=$((fails + 1)); }
  dom=$(grep '^DOMAIN=' .env | cut -d= -f2-)
  ip=$(curl -fsS -m 5 https://checkip.amazonaws.com 2>/dev/null || true)
  say "Checking the IONIQ 6 server"
  echo "  App's server address should be: https://${dom}"
  echo "  This server's public IP:        ${ip:-unknown}"
  case "$dom" in
    *.sslip.io)
      if [ "${ip//./-}.sslip.io" = "$dom" ]; then pass "address matches this server's IP"
      else fail "address doesn't match this server's IP — run: sudo bash install.sh"; fi ;;
  esac

  for svc in api caddy; do
    if [ -n "$(docker compose ps -q --status running "$svc" 2>/dev/null)" ]; then pass "$svc container running"
    else fail "$svc container is NOT running"; fi
  done
  if docker compose exec -T api node -e "fetch('http://127.0.0.1:8080/healthz').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))" >/dev/null 2>&1; then pass "API answering inside the server"
  else fail "API not answering"; fi

  err=$(mktemp)
  code=$(curl --noproxy "*" -sS -m 10 -o /dev/null -w "%{http_code}" --resolve "${dom}:443:127.0.0.1" "https://${dom}/healthz" 2>"$err" || true)
  if [ "$code" = 200 ]; then local_ok=1; pass "HTTPS + certificate work on the server itself"
  else fail "HTTPS doesn't work even on the server itself ($(head -c 160 "$err"))"; fi
  code=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "https://${dom}/healthz" 2>"$err" || true)
  if [ "$code" = 200 ]; then public_ok=1; pass "HTTPS reachable through the public address"
  else fail "HTTPS NOT reachable through the public address ($(head -c 160 "$err"))"; fi
  rm -f "$err"

  if { command -v systemctl >/dev/null && systemctl is-active --quiet ioniq6-watchdog.timer 2>/dev/null; } || \
     { [ -n "$UNRAID" ] && { crontab -l 2>/dev/null; cat /etc/cron.d/root /var/spool/cron/crontabs/root 2>/dev/null; } | grep -q ioniq6/watchdog.sh; }; then
    pass "watchdog running (restarts the server if it stops)"
  else
    if [ -n "$UNRAID" ]; then fail "watchdog not running — run: cd $DIR && bash install.sh"
    else fail "watchdog not running — run: sudo bash install.sh"; fi
  fi
  if journalctl -t ioniq6-watchdog --since "-7 days" 2>/dev/null | grep -q "restarted itself"; then
    echo "  note  the watchdog restarted the server in the last 7 days:"
    journalctl -t ioniq6-watchdog --since "-7 days" -o short 2>/dev/null | grep -i "restart" | tail -3 | sed 's/^/        /'
  fi

  local mem swap
  mem=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
  swap=$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)
  if [ "$mem" -lt 1500 ] && [ "$swap" -eq 0 ]; then fail "${mem} MB RAM and no swap — can freeze (sudo bash install.sh adds swap)"
  else pass "memory: ${mem} MB RAM, ${swap} MB swap"; fi
  if journalctl -k -b -1 2>/dev/null | grep -qiE "out of memory|killed process"; then
    echo "  note  before the last restart the server ran out of memory"
  fi

  say "Hyundai"
  local auth until calls since last notes
  auth=$(api GET /v1/auth 2>/dev/null || true)
  if echo "$auth" | grep -q '"loggedIn": true'; then pass "signed in to BlueLink"
  else fail "not signed in to BlueLink right now (it signs in again by itself; if this stays, run: bash install.sh login)"; fi
  until=$(echo "$auth" | sed -n 's/.*"rateLimitedUntil": "\([^"]*\)".*/\1/p')
  if [ -n "$until" ]; then
    echo "  note  Hyundai is rate-limiting this server; ordinary requests pause until $(date -d "$until" '+%-I:%M %p' 2>/dev/null || echo "$until")."
    echo "        Walk-away locks still retry on their own."
  fi
  calls=$(docker compose exec -T api sh -c 'cat /data/hyundai.log.1 /data/hyundai.log 2>/dev/null; true' 2>/dev/null || true)
  if [ -n "$calls" ]; then
    since=$(date -u -d '-24 hours' +%FT%T 2>/dev/null || echo 0)
    echo "  Requests to Hyundai in the last 24 h (endpoint, count):"
    echo "$calls" | awk -v s="$since" '$1 >= s && $2 != "#" { n[$2]++; if ($3 == 429) r[$2]++ }
      END { for (e in n) printf "        %-26s %4d%s\n", e, n[e], (r[e] ? "   (" r[e] " refused: rate limit)" : "") }' | sort -k2 -nr
    last=$(echo "$calls" | awk '$3 == 429 { t = $1 } END { print t }')
    [ -n "$last" ] && echo "  last rate limit: $(date -d "$last" '+%b %-d, %-I:%M %p' 2>/dev/null || echo "$last")"
    notes=$(echo "$calls" | awk '$2 == "#"' | tail -4)
    if [ -n "$notes" ]; then
      echo "  How long Hyundai's sign-ins last (latest):"
      echo "$notes" | while read -r t _ rest; do echo "        $(date -d "$t" '+%-I:%M %p' 2>/dev/null || echo "$t")  $rest"; done
    fi
  else
    echo "  (no record of Hyundai requests yet; it starts with this version)"
  fi

  say "What to do"
  if [ "$fails" -eq 0 ]; then
    echo "  Everything checks out from the server. If the phone still can't connect, open"
    echo "  https://${dom}/healthz in the phone's browser; if that fails, see docs/troubleshooting.md."
  elif [ "$local_ok" = 1 ] && [ "$public_ok" = 0 ]; then
    echo "  The server works, but the outside can't reach it: something is blocking HTTPS (TCP 443)."
    echo "  At home: forward TCP 443 (and 80 if you can) to this machine in your router."
    echo "  Cloud server: allow TCP 443 and 80 in its firewall / security group. Then run this check again."
  elif [ "$local_ok" = 0 ]; then
    echo "  The HTTPS front-end (caddy) isn't working. Its recent log:"
    docker compose logs --tail 15 caddy 2>&1 | sed 's/^/    /'
    echo "  If it mentions certificates/ACME, ports 80/443 are probably not reaching this machine."
    echo "  Still stuck? Open an issue with this output (no passwords or tokens in it; blank out your address if you like)."
  else
    echo "  Still stuck? Open an issue with this output (no passwords or tokens in it; blank out your address if you like)."
  fi
}

if [ "${1:-}" = "doctor" ]; then
  [ -f .env ] || { echo "Not installed yet."; exit 1; }
  doctor
  exit 0
fi

# ------------------------------------------------------------------ redo verification only
if [ "${1:-}" = "login" ]; then
  [ -f .env ] || { echo "Not installed yet — run without 'login' first."; exit 1; }
  wait_healthy
  do_login
  show_details
  exit 0
fi

# ------------------------------------------------------------------ update an existing install
if [ -f .env ]; then
  say "Existing install in $DIR — updating to the latest version"
  ensure_swap
  if [ -z "${IONIQ6_REEXEC:-}" ]; then
    docker compose pull
    # Continue with the installer from the new image, so updates also update this script.
    NEW=$(mktemp)
    docker run --rm "$IMAGE" cat /app/install.sh > "$NEW" 2>/dev/null || true
    if [ -s "$NEW" ] && ! cmp -s "$NEW" "$DIR/install.sh"; then
      cp "$NEW" "$DIR/install.sh"; rm -f "$NEW"
      echo "Installer updated — continuing with the new version."
      IONIQ6_REEXEC=1 exec bash "$DIR/install.sh" "$@"
    fi
    rm -f "$NEW"
  fi
  fix_ip
  check_ports
  write_files
  docker compose up -d
  wait_healthy
  install_watchdog
  # Don't sign in here: every sign-in counts against Hyundai's limit, and the server signs in by
  # itself the next time it needs Hyundai. Only an unfinished email verification needs us.
  auth=$(api GET /v1/auth || true)
  if echo "$auth" | grep -q '"otpPending": true'; then
    echo "Hyundai's email verification isn't finished yet — finishing that now."
    do_login
  elif echo "$auth" | grep -q '"loggedIn": true'; then
    echo "Updated. Signed in to BlueLink."
  else
    echo "Updated. The server signs in to BlueLink by itself the next time it needs to"
    echo "(each sign-in counts against Hyundai's limit, so it doesn't do one now)."
  fi
  show_details
  exit 0
fi

# ------------------------------------------------------------------ first install
say "IONIQ 6 API setup"
ensure_swap
check_ports
IP=$(curl -fsS https://checkip.amazonaws.com 2>/dev/null || true)
DEFAULT_HOST=""
[ -n "$IP" ] && DEFAULT_HOST="${IP//./-}.sslip.io"
echo "Use a domain you own, or press Enter for a free name based on this server's IP."
read -rp "Hostname [${DEFAULT_HOST}]: " HOST
HOST=${HOST:-$DEFAULT_HOST}
[ -n "$HOST" ] || { echo "Need a hostname."; exit 1; }

read -rp  "BlueLink email: " BL_USER
read -rsp "BlueLink password: " BL_PASS; echo
read -rsp "BlueLink 4-digit PIN: " BL_PIN; echo
[[ "$BL_PIN" =~ ^[0-9]{4}$ ]] || { echo "The PIN must be 4 digits."; exit 1; }
for v in "$BL_USER" "$BL_PASS"; do
  [[ "$v" == *"'"* ]] && { echo "Sorry, this installer can't handle a ' in the email or password. Edit $DIR/.env by hand instead."; exit 1; }
done

ADMIN=$(openssl rand -hex 24)
PHONE=$(openssl rand -hex 24)
TOPIC="ioniq6-$(openssl rand -hex 8)"

umask 077
cat > .env <<EOF
DOMAIN=${HOST}
PUBLIC_URL=https://${HOST}
BLUELINK_USERNAME='${BL_USER}'
BLUELINK_PASSWORD='${BL_PASS}'
BLUELINK_PIN=${BL_PIN}
ADMIN_TOKEN=${ADMIN}
PHONE_TOKEN=${PHONE}
AUTOLOCK_ENABLED=true
AUTOLOCK_GRACE_SECONDS=20
AUTOLOCK_CHECK_FIRST=false
GUARD_INTERVAL_MINUTES=30
NTFY_URL=https://ntfy.sh/${TOPIC}
BIND_HTTP=${BIND_HTTP}
EOF
umask 022

write_files

say "Starting…"
docker compose pull
docker compose up -d
wait_healthy
install_watchdog

do_login

# ------------------------------------------------------------------ HTTPS check
say "Getting the HTTPS certificate (up to a minute)"
OK=""
for _ in $(seq 1 30); do
  if curl -fsS "https://${HOST}/healthz" >/dev/null 2>&1; then OK=1; break; fi
  sleep 2
done
if [ -z "$OK" ]; then
  if [ -n "$UNRAID" ]; then
    echo "HTTPS isn't answering from the internet yet. Forward TCP port 443 to this server in your router"
    echo "(in your router's app or admin page: \"port forwarding\", TCP 443 → this server's local IP). Then check with:"
    echo "  cd $DIR && bash install.sh doctor"
  else
    echo "HTTPS isn't answering yet. Most likely TCP 443 isn't reaching this machine: forward it in your"
    echo "router (at home) or allow it in the firewall (cloud server). Then: cd $DIR && bash install.sh doctor"
  fi
fi

say "Done. Put these in the phone app:"
echo "  Server:       https://${HOST}"
echo "  Phone token:  ${PHONE}"
echo
echo "Notifications: install the ntfy app and subscribe to topic:  ${TOPIC}"
echo
echo "Admin token (keep private — it can unlock the car): saved in ${DIR}/.env"
echo "Update later:  cd ${DIR} && bash install.sh"
