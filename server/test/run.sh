#!/bin/bash
# End-to-end tests of server.js against a mock BlueLink API (server/test/mock.js).
# Run from anywhere:  bash server/test/run.sh      (needs Node 18+ and curl; takes ~2 minutes)
# Exits non-zero if any test fails.
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$HERE/.." && pwd)
SP=$(mktemp -d)
trap 'pkill -f "$HERE/mock.js" 2>/dev/null; pkill -f "$APP/server.js" 2>/dev/null; rm -rf "$SP"' EXIT
M=http://127.0.0.1:18080/tods/api
S=http://127.0.0.1:18090
ADMIN=admin_aaaaaaaaaaaaaaaaaaaaaaaaaaaa
PHONE=phone_bbbbbbbbbbbbbbbbbbbbbbbbbbbb
export NO_PROXY=127.0.0.1 no_proxy=127.0.0.1
pass=0; fail=0
ok()   { echo "  PASS $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL $1 :: $2"; fail=$((fail+1)); }
chk()  { if echo "$2" | grep -q -- "$3"; then ok "$1"; else bad "$1" "$2"; fi; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
A=(-H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json')
P=(-H "Authorization: Bearer $PHONE" -H 'Content-Type: application/json')
car()  { curl -s -X POST $M/__car -d "$1" >/dev/null; }
notes(){ curl -s $M/__notes; }
allcalls(){ curl -s $M/__calls | node -e "const c=JSON.parse(require('fs').readFileSync(0));console.log(Object.entries(c).filter(([k])=>!k.startsWith('__')).reduce((a,[,v])=>a+v,0))"; }
calls(){ curl -s $M/__calls | node -e "const c=JSON.parse(require('fs').readFileSync(0));console.log(c['$1']||0)"; }
waitphase() { for i in $(seq 1 60); do p=$(curl -s "${P[@]}" $S/v1/autolock | node -e 'console.log(JSON.parse(require("fs").readFileSync(0)).phase)'); [ "$p" = "$1" ] && return 0; sleep 0.25; done; return 1; }

pkill -f "$HERE/mock.js" 2>/dev/null; pkill -f "$APP/server.js" 2>/dev/null; sleep 0.3
node $HERE/mock.js > $SP/mock.log 2>&1 &
sleep 0.5
rm -rf $SP/srvdata
start_server() {
  BLUELINK_USERNAME=me@x.ca BLUELINK_PASSWORD=pw BLUELINK_PIN=1234 BLUELINK_BASE_URL=http://127.0.0.1:18080 BLUELINK_POLL_MS=150 \
  ADMIN_TOKEN=$ADMIN PHONE_TOKEN=$PHONE PORT=18090 DATA_DIR=$SP/srvdata PUBLIC_URL=$S \
  NTFY_URL=http://127.0.0.1:18080/my-car-topic AUTOLOCK_GRACE_SECONDS=2 AUTOLOCK_RETRY_SECONDS=1 AUTOLOCK_MAX_ATTEMPTS=3 \
  GUARD_INTERVAL_MINUTES=${GUARD:-0} GUARD_GRACE_SECONDS=1 BLUELINK_MIN_COOLDOWN_SECONDS=3 AUTOLOCK_RATE_LIMIT_BACKOFF_SECONDS=1 AUTO_REFRESH_QUIET_FROM_HOUR=0 AUTO_REFRESH_QUIET_TO_HOUR=0 CAR_WAKE_DAILY_SOFT_MAX=1000 GUARD_EVERY_HOURS=0.0003 WATCH_SILENT_MINUTES=0.05 PHONE_NEAR_MINUTES=0.01 BLUELINK_LOGIN_RETRY_SECONDS=2 BLUELINK_MIN_SIGNIN_GAP_SECONDS=${GAP:-0} STATUS_CACHE_SECONDS=${CACHE:-0} \
  node $APP/server.js > $SP/server.log 2>&1 &
  for i in $(seq 1 40); do curl -s $S/healthz >/dev/null && return; sleep 0.1; done
}

echo "== config validation"
out=$(ADMIN_TOKEN=short BLUELINK_USERNAME=a BLUELINK_PASSWORD=b BLUELINK_PIN=1 node $APP/server.js 2>&1); chk "rejects short admin token" "$out" "at least 24"

start_server
echo "== auth & scopes"
chk "healthz open" "$(curl -s $S/healthz)" '"ok": true'
[ "$(code $S/v1/status)" = 401 ] && ok "no token -> 401" || bad "no token" ""
[ "$(code -H 'Authorization: Bearer nope' $S/v1/status)" = 401 ] && ok "bad token -> 401" || bad "bad token" ""
r=$(curl -s "${P[@]}" $S/v1/status); chk "not logged in -> OTP_REQUIRED" "$r" OTP_REQUIRED
[ "$(code "${P[@]}" -X POST $S/v1/auth/login)" = 403 ] && ok "phone token can't log in" || bad "phone login" ""
r=$(curl -s "${A[@]}" -X POST $S/v1/auth/login -d '{"otpMethod":"sms"}'); chk "login sends SMS code" "$r" '"to": "416-555-0100"'
[ "$(code "${A[@]}" -X POST $S/v1/auth/otp -d '{"code":"000000"}')" = 400 ] && ok "wrong code -> 400" || bad "wrong code" ""
r=$(curl -s "${A[@]}" -X POST $S/v1/auth/otp -d '{"code":"123456"}'); chk "right code -> logged in" "$r" '"status": "logged_in"'
chk "vehicle selected" "$r" 'KMHM00000000'

echo "== reads"
car '{"doorLock":false,"engine":false}'
r=$(curl -s "${P[@]}" $S/v1/status); chk "status (phone)" "$r" '"battery": 74'
chk "status shows car_on" "$r" '"car_on": false'
r=$(curl -s "${A[@]}" $S/v1/location); chk "location" "$r" '"lat": 43.65'
[ "$(code "${P[@]}" $S/v1/location)" = 403 ] && ok "phone can't read location" || bad "phone location" ""
r=$(curl -s "${A[@]}" $S/v1/charge-limit); chk "charge limit" "$r" '"ac": 90'

echo "== commands"
[ "$(code "${P[@]}" -X POST $S/v1/unlock)" = 403 ] && ok "phone token can't unlock" || bad "phone unlock" ""
r=$(curl -s "${A[@]}" -X POST "$S/v1/unlock?wait=true"); chk "unlock ?wait=true" "$r" '"status": "success"'
r=$(curl -s "${P[@]}" -X POST $S/v1/lock); chk "lock (phone) -> 202 job" "$r" '"poll": "/v1/jobs/'
id=$(echo "$r" | node -e 'console.log(JSON.parse(require("fs").readFileSync(0)).id)')
sleep 1.5; r=$(curl -s "${P[@]}" $S/v1/jobs/$id); chk "job finished" "$r" '"status": "success"'
[ "$(code "${A[@]}" -X POST $S/v1/climate/start -d '{"temp":45}')" = 400 ] && ok "bad temp -> 400" || bad "bad temp" ""
[ "$(code "${A[@]}" -X POST $S/v1/climate/start -d '{"seats":{"driver":"lava"}}')" = 400 ] && ok "bad seat -> 400" || bad "bad seat" ""
r=$(curl -s "${A[@]}" -X POST "$S/v1/climate/start?wait=true" -d '{"temp":22,"duration":15,"seats":{"driver":"high-heat"}}'); chk "climate start" "$r" '"temp": 22'
[ "$(code "${A[@]}" -X PUT $S/v1/charge-limit -d '{"dc":55}')" = 400 ] && ok "bad limit -> 400" || bad "bad limit" ""
r=$(curl -s "${A[@]}" -X PUT "$S/v1/charge-limit?wait=true" -d '{"ac":80}'); chk "set limit" "$r" '"ac": 80'
[ "$(code "${A[@]}" -X POST $S/v1/lock -d 'not json')" = 400 ] && ok "non-JSON body -> 400" || bad "json" ""
notes >/dev/null

echo "== auto-lock: walk away from an unlocked car"
car '{"doorLock":false,"engine":false}'
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/away -d '{"source":"car-bluetooth"}'); chk "away -> pending" "$r" '"phase": "pending"'
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/away); chk "second away doesn't reset timer" "$r" '"phase": "pending"'
waitphase idle; n=$(notes)
chk "armed notification" "$n" 'Auto-lock armed'
chk "ntfy topic parsed from URL" "$n" '"topic":"my-car-topic"'
chk "has cancel button" "$n" 'Cancel auto-lock'
chk "locked notification" "$n" 'Car locked'
chk "car actually locked" "$(curl -s -X POST $M/__car -d '{}')" '"doorLock":true'
r=$(curl -s "${P[@]}" $S/v1/autolock); chk "fast mode: locked without a status check first" "$r" 'via car-bluetooth (fast)'

echo "== auto-lock: per-request grace of 0 (no wait, no armed notice)"
car '{"doorLock":false}'; notes >/dev/null; t0=$(date +%s%N)
curl -s "${P[@]}" -X POST $S/v1/autolock/away -d '{"source":"beacon","graceSeconds":0}' >/dev/null
waitphase idle; t1=$(date +%s%N); n=$(notes)
chk "locked" "$n" 'Car locked'
if echo "$n" | grep -q 'Auto-lock armed'; then bad "no armed notice at grace 0" "$n"; else ok "no armed notice at grace 0"; fi
ms=$(( (t1-t0)/1000000 )); [ $ms -lt 2000 ] && ok "grace 0 locked in ${ms} ms (mock car)" || bad "grace 0 speed" "$ms ms"
[ "$(code "${P[@]}" -X POST $S/v1/autolock/away -d '{"graceSeconds":9999}')" = 400 ] && ok "absurd grace rejected" || bad "grace bounds" ""

echo "== auto-lock: come back before it fires"
car '{"doorLock":false}'
curl -s "${P[@]}" -X POST $S/v1/autolock/away >/dev/null
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/near -d '{"source":"car-bluetooth"}'); chk "near cancels" "$r" '"phase": "idle"'
sleep 2.5; chk "car left unlocked" "$(curl -s -X POST $M/__car -d '{}')" '"doorLock":false'
notes >/dev/null

echo "== auto-lock: cancel from the notification button"
curl -s "${P[@]}" -X POST $S/v1/autolock/away >/dev/null
link=$(notes | node -e 'const n=JSON.parse(require("fs").readFileSync(0));const a=n.map(x=>x.actions).filter(x=>x&&x.length).pop();console.log(a[0].url)')
r=$(curl -s -X POST "$link"); chk "cancel link works without token" "$r" 'cancelled'
[ "$(code -X POST "$link")" = 410 ] && ok "cancel link is single-use" || bad "single use" ""
[ "$(code -X POST "$S/v1/autolock/cancel/0123456789abcdef0123456789abcdef")" = 410 ] && ok "random cancel link rejected" || bad "random link" ""
sleep 2.5; chk "still unlocked after cancel" "$(curl -s -X POST $M/__car -d '{}')" '"doorLock":false'

echo "== auto-lock: fast lock refused because a door is open -> inspects, waits, locks"
car '{"doorLock":false,"doorOpen":{"frontLeft":0,"frontRight":0,"backLeft":1,"backRight":0}}'
curl -s "${P[@]}" -X POST $S/v1/autolock/away >/dev/null
waitphase retrying && ok "waits while a door is open" || bad "retrying" ""; sleep 0.3
chk "door-open warning" "$(notes)" 'back left door'
car '{"doorOpen":{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0}}'
waitphase idle; chk "locks once closed" "$(notes)" 'Car locked'

echo "== auto-lock: door never closed -> gives up loudly"
car '{"doorLock":false,"trunkOpen":true}'
curl -s "${P[@]}" -X POST $S/v1/autolock/away >/dev/null
sleep 1; waitphase idle; n=$(notes); chk "gives up with high-priority alert" "$n" 'Car NOT locked'
chk "names the trunk" "$n" 'trunk still open'
car '{"trunkOpen":false}'

echo "== per-request checkFirst skips the redundant lock"
car '{"doorLock":true,"engine":false}'; before=$(calls drlck)
curl -s "${P[@]}" -X POST $S/v1/autolock/away -d '{"source":"esp32","graceSeconds":0,"checkFirst":true}' >/dev/null
sleep 0.5; waitphase idle
r=$(curl -s "${P[@]}" $S/v1/autolock); chk "checked instead of locking" "$r" 'already locked'
[ "$(calls drlck)" = "$before" ] && ok "no lock command sent" || bad "lock sent" "$before -> $(calls drlck)"

echo "== settings API"
[ "$(code "${P[@]}" -X PUT $S/v1/autolock/settings -d '{"graceSeconds":5}')" = 403 ] && ok "phone can't change settings" || bad "phone settings" ""
r=$(curl -s "${A[@]}" -X PUT $S/v1/autolock/settings -d '{"checkFirst":true}'); chk "switch to check-first" "$r" '"checkFirst": true'

echo "== auto-lock (check-first): car is on / already locked / paused / disabled"
car '{"doorLock":false,"engine":true}'; before=$(calls drlck)
curl -s "${P[@]}" -X POST $S/v1/autolock/away >/dev/null; waitphase idle; sleep 2.3; waitphase idle
r=$(curl -s "${P[@]}" $S/v1/autolock); chk "skips when car is on" "$r" 'car is on'
car '{"doorLock":true,"engine":false}'
curl -s "${P[@]}" -X POST $S/v1/autolock/away >/dev/null; sleep 2.3; waitphase idle
r=$(curl -s "${P[@]}" $S/v1/autolock); chk "skips when already locked" "$r" 'already locked'
[ "$(calls drlck)" = "$before" ] && ok "no lock command sent in either case" || bad "extra lock calls" "$before -> $(calls drlck)"
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/pause -d '{"minutes":30}'); chk "pause" "$r" '"pausedUntil": "20'
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/away); chk "away ignored while paused" "$r" '"phase": "paused"'
curl -s "${P[@]}" -X POST $S/v1/autolock/pause -d '{"minutes":0}' >/dev/null
[ "$(code "${P[@]}" -X POST $S/v1/autolock/disable)" = 403 ] && ok "phone can't disable auto-lock" || bad "phone disable" ""
curl -s "${A[@]}" -X POST $S/v1/autolock/disable >/dev/null
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/away); chk "away ignored when disabled" "$r" '"phase": "disabled"'
curl -s "${A[@]}" -X POST $S/v1/autolock/enable >/dev/null
r=$(curl -s "${A[@]}" -X PUT $S/v1/autolock/settings -d '{"checkFirst":false,"graceSeconds":3}'); chk "back to fast, grace 3" "$r" '"graceSeconds": 3'

echo "== odd Hyundai replies"
curl -s -X POST $M/__odd -d '{"left":1,"status":503}' >/dev/null
r=$(curl -s "${P[@]}" $S/v1/status); chk "one bad reply is retried away" "$r" '"battery": 74'
curl -s -X POST $M/__odd -d '{"left":5,"status":503}' >/dev/null
r=$(curl -s "${P[@]}" $S/v1/status); chk "persistent bad reply explains itself" "$r" 'unexpected answer to lstvhclsts (HTTP 503: Service Unavailable)'
curl -s -X POST $M/__odd -d '{"left":5,"status":429}' >/dev/null
code429=$(curl -s -o /dev/null -w '%{http_code}' "${P[@]}" $S/v1/status); [ "$code429" = 429 ] && ok "rate limit -> HTTP 429" || bad "rate limit code" "$code429"
curl -s -X POST $M/__odd -d '{"left":0}' >/dev/null
sleep 3.5   # let the 429 cool-down pass
chk "server log shows the raw reply" "$(cat $SP/server.log)" '\[bluelink\] lstvhclsts: unexpected reply (HTTP 503)'

echo "== odd reply during automatic re-login"
curl -s -X POST $M/__oddlogin -d '{"left":5}' >/dev/null
r=$(curl -s "${P[@]}" $S/v1/status); chk "re-login failure explains itself" "$r" 'unexpected answer to v2/login (HTTP 403: Request blocked)'
chk "re-login reply is logged" "$(cat $SP/server.log)" 'v2/login: unexpected reply (HTTP 403)'
curl -s -X POST $M/__oddlogin -d '{"left":0}' >/dev/null
r=$(curl -s "${P[@]}" $S/v1/status); chk "no immediate second sign-in after a failure" "$r" 'LOGIN_WAIT'
sleep 2.5

echo "== Cloudflare rate limit -> back off"
curl -s -X POST $M/__cf429 -d '{"left":1}' >/dev/null
before=$(calls v2/login)
r=$(curl -s "${P[@]}" $S/v1/status); chk "429 reported with a resume time" "$r" '"until": "20'
chk "message says it recovers" "$r" 'recovers on its own'
r=$(curl -s "${P[@]}" $S/v1/status); chk "still paused" "$r" 'RATE_LIMIT'
[ "$(calls v2/login)" = "$((before+1))" ] && ok "didn't knock again during the pause" || bad "kept knocking" "$before -> $(calls v2/login)"
sleep 3.5
r=$(curl -s "${P[@]}" $S/v1/status); chk "recovers after the pause" "$r" '"battery": 74'

echo "== fewer Hyundai calls"
pkill -f "$APP/server.js"; sleep 0.5; CACHE=60 start_server
curl -s "${P[@]}" $S/v1/status >/dev/null
b1=$(calls lstvhclsts); n1=$(calls nxtsvc)
curl -s "${P[@]}" $S/v1/status >/dev/null; curl -s "${P[@]}" $S/v1/status >/dev/null
[ "$(calls lstvhclsts)" = "$b1" ] && ok "repeat status within a minute is served from cache" || bad "status cache" "$b1 -> $(calls lstvhclsts)"
[ "$(calls nxtsvc)" = "$n1" ] && ok "service info not re-fetched" || bad "svc cache" ""

echo "== IP change (7606) mid-session -> silent re-login"
car '{"doorLock":false}'
curl -s -X POST $M/__expire >/dev/null
r=$(curl -s "${P[@]}" -X POST "$S/v1/lock?wait=true"); chk "lock survives token invalidation" "$r" '"status": "success"'

echo "== persistence across restart (no new OTP)"
pkill -f "$APP/server.js"; sleep 0.5; start_server
r=$(curl -s "${P[@]}" $S/v1/status); chk "still logged in after restart" "$r" '"battery": 74'
r=$(curl -s "${P[@]}" $S/v1/autolock); chk "enabled flag persisted" "$r" '"enabled": true'
chk "grace setting persisted" "$r" '"graceSeconds": 3'
perms=$(stat -c %a $SP/srvdata/session.json); [ "$perms" = 600 ] && ok "session.json is 600" || bad "perms" "$perms"

echo "== a lock refused by Hyundai's rate limit is delayed, not dropped"
car '{"doorLock":false,"engine":false}' >/dev/null; notes >/dev/null
curl -s -X POST $M/__cmd429 -d '{"left":1}' >/dev/null
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/away -d '{"source":"test","graceSeconds":0,"quiet":true}')
id=$(echo "$r" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>console.log(JSON.parse(d).id||""))')
[ -n "$id" ] && ok "away returns an id" || bad "away id" "$r"
for i in $(seq 1 60); do r=$(curl -s "${P[@]}" $S/v1/autolock); echo "$r" | grep -q "\"id\": \"$id\"" && echo "$r" | grep -q '"outcome": "locked"' && break; sleep 0.5; done
chk "delayed lock still locked" "$r" '"outcome": "locked"'
chk "result carries the same id" "$r" "\"id\": \"$id\""
chk "delay was logged" "$(cat $SP/server.log)" 'autolock: delayed'
n=$(notes); chk "user told about the delay" "$n" 'Lock delayed, still trying'
chk "…and told when it went through after all" "$n" '"title":"Car locked"'
car '{"doorLock":false,"engine":false}'; notes >/dev/null
r=$(curl -s "${P[@]}" -X POST $S/v1/autolock/away -d '{"source":"home","graceSeconds":0,"quiet":true,"reason":"you had been away from the car for 20 s (home rule)"}')
for i in $(seq 1 40); do curl -s "${P[@]}" $S/v1/autolock | grep -q '"outcome": "locked"' && break; sleep 0.25; done; sleep 0.5
n=$(notes); chk "every lock goes to ntfy" "$n" '"title":"Car locked"'
chk "…saying why it locked" "$n" 'because you had been away from the car for 20 s (home rule)'
echo "$n" | grep -q 'Auto-lock armed' && bad "quiet: no 'armed' message" "$n" || ok "quiet: no 'armed' message"
r=$(curl -s "${P[@]}" $S/v1/status); chk "status shows our confirmed lock" "$r" '"locked_by": "command"'
chk "confirmed lock means locked" "$r" '"locked": true'

chk "every Hyundai request is recorded" "$(cat $SP/srvdata/hyundai.log 2>/dev/null)" ' drlck 429'
echo "== going easy on Hyundai"
b=$(calls rltmvhclsts)
r=$(curl -s "${P[@]}" "$S/v1/status?refresh=true"); [ "$(calls rltmvhclsts)" = "$((b+1))" ] && ok "a tap on Refresh asks the car" || bad "manual refresh" "$b -> $(calls rltmvhclsts)"
chk "status reports wake-ups" "$r" '"car_wakeups_24h"'
r=$(curl -s "${P[@]}" "$S/v1/status?refresh=true&auto=true"); chk "automatic refresh right after is skipped" "$r" '"refresh_skipped": "recent"'
[ "$(calls rltmvhclsts)" = "$((b+1))" ] && ok "…and didn't wake the car" || bad "auto refresh woke the car" "$(calls rltmvhclsts)"
chk "…but still answers with the saved copy" "$r" '"battery": 74'
b=$(calls rltmvhclsts); curl -s "${P[@]}" "$S/v1/status?refresh=true" >/dev/null
[ "$(calls rltmvhclsts)" = "$b" ] && ok "two Refresh taps within a minute ask the car once" || bad "refresh coalescing" "$b -> $(calls rltmvhclsts)"
v=$(calls vrfypin); j=$(curl -s "${P[@]}" -X POST $S/v1/lock | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>console.log(JSON.parse(d).id))')
for i in $(seq 1 40); do curl -s "${P[@]}" $S/v1/jobs/$j | grep -q '"status": "success"' && break; sleep 0.25; done
[ "$(calls vrfypin)" = "$((v+1))" ] && ok "one PIN check per command (not per poll)" || bad "PIN checks per command" "$v -> $(calls vrfypin)"

echo "== dead-man's switch"
car '{"doorLock":false,"engine":false}'; notes >/dev/null
curl -s "${P[@]}" -X POST $S/v1/autolock/parked -d '{"source":"test"}' >/dev/null
for i in $(seq 1 8); do curl -s "${P[@]}" -X POST $S/v1/autolock/here >/dev/null; sleep 0.5; done
chk "phone saying 'still here' holds the lock off" "$(curl -s "${P[@]}" $S/v1/autolock)" '"watching": {'
grep -q 'locked — via phone went quiet' $SP/server.log && bad "locked while phone was still there" "" || ok "no lock while the phone keeps checking in"
for i in $(seq 1 40); do grep -q 'locked — via phone went quiet' $SP/server.log && break; sleep 0.25; done
chk "phone went quiet -> server locked" "$(cat $SP/server.log)" 'locked — via phone went quiet'
chk "…and said so on ntfy" "$(notes)" 'Car locked'
curl -s "${P[@]}" -X POST $S/v1/autolock/parked -d '{"source":"test"}' >/dev/null
curl -s "${P[@]}" -X POST $S/v1/autolock/near -d '{"source":"car-bluetooth"}' >/dev/null
chk "driving switches the watch off" "$(cat $SP/server.log)" 'watch-off — driving'

echo "== getting ready as you walk to the car"
r=$(curl -s "${P[@]}" -X POST $S/v1/prepare); chk "prepare answers ready" "$r" '"ready": true'
b=$(calls v2/login); curl -s "${P[@]}" -X POST $S/v1/prepare >/dev/null
[ "$(calls v2/login)" = "$b" ] && ok "prepare doesn't sign in again when the sign-in is fresh" || bad "prepare signed in again" "$b -> $(calls v2/login)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST $S/v1/prepare); [ "$code" = 401 ] && ok "prepare needs a token" || bad "prepare without token" "$code"

echo "== signing in ahead when you park"
parked(){ curl -s "${P[@]}" -X POST $S/v1/autolock/parked -d '{"source":"test"}' >/dev/null; sleep 1; curl -s "${P[@]}" -X POST $S/v1/autolock/near -d '{"source":"car-bluetooth"}' >/dev/null; }
curl -s -X POST $M/__expirein -d '{"sec":240}' >/dev/null
curl -s -X POST $M/__expire >/dev/null; curl -s "${A[@]}" $S/v1/status >/dev/null   # Hyundai ends it; the new one lasts 4 min
pkill -f "$APP/server.js"; sleep 0.5; GAP=120 start_server
b=$(calls v2/login); parked
[ "$(calls v2/login)" = "$b" ] && ok "no second sign-in within 2 minutes of the last" || bad "signed in again too soon" "$b -> $(calls v2/login)"
chk "…and says why" "$(cat $SP/server.log)" 'not signing in again so soon'
curl -s -X POST $M/__expire >/dev/null; car '{"doorLock":false,"engine":false}'; notes >/dev/null
curl -s "${P[@]}" -X POST $S/v1/autolock/away -d '{"source":"test"}' >/dev/null; sleep 0.5; waitphase idle
[ "$(calls v2/login)" = "$((b+1))" ] && ok "a lock is never held back by that rule" || bad "lock sign-in" "$b -> $(calls v2/login)"
chk "…and locked" "$(notes)" 'Car locked'
curl -s -X POST $M/__expire >/dev/null; curl -s "${A[@]}" $S/v1/status >/dev/null   # nothing fresh: 4-min sign-in again
GAP=0; pkill -f "$APP/server.js"; sleep 0.5; start_server
curl -s -X POST $M/__expirein -d '{"sec":86400}' >/dev/null
b=$(calls v2/login); parked
[ "$(calls v2/login)" = "$((b+1))" ] && ok "parking renews a sign-in that's about to run out" || bad "no early sign-in" "$b -> $(calls v2/login)"
chk "…logged as signed in ahead" "$(cat $SP/server.log)" 'signed in ahead (parked)'
pkill -f "$APP/server.js"; sleep 0.5; start_server
b=$(allcalls); parked
[ "$(allcalls)" = "$b" ] && ok "a sign-in with time left is checked without asking Hyundai at all" || bad "asked Hyundai" "$b -> $(allcalls)"
curl -s "${A[@]}" -X POST "$S/v1/autolock/pause" -d '{"minutes":5}' >/dev/null
pkill -f "$APP/server.js"; sleep 0.5; start_server
curl -s -X POST $M/__expire >/dev/null; b=$(allcalls); parked
[ "$(allcalls)" = "$b" ] && ok "no early sign-in while auto-lock is paused" || bad "signed in while paused" "$b -> $(allcalls)"
curl -s "${A[@]}" -X POST "$S/v1/autolock/pause" -d '{"minutes":0}' >/dev/null
curl -s "${A[@]}" $S/v1/status >/dev/null

echo "== safety check (guard) catches a car left unlocked"
pkill -f "$APP/server.js"; sleep 0.5; GUARD=0.02 start_server
notes >/dev/null; car '{"doorLock":false,"engine":false}'
for i in $(seq 1 40); do grep -q 'autolock: locked' $SP/server.log && break; sleep 0.25; done
chk "guard locked it" "$(cat $SP/server.log)" 'autolock: locked — via safety check'
chk "guard notified" "$(notes)" 'Car locked'

echo "== brute-force protection"
for i in $(seq 1 10); do curl -s -o /dev/null -H 'Authorization: Bearer wrongwrongwrongwrongwrong' $S/v1/status; done
[ "$(code "${P[@]}" $S/v1/status)" = 429 ] && ok "locked out after 10 bad tokens" || bad "429" ""

echo "== logs never contain secrets"
if grep -qE "$ADMIN|$PHONE|1234\"|\"pw\"|T[0-9]+\"" $SP/server.log; then bad "secrets in log" "$(grep -E "$ADMIN|$PHONE" $SP/server.log | head -2)"; else ok "no tokens/PIN/password in server log"; fi

pkill -f "$APP/server.js"; pkill -f "$HERE/mock.js"
echo; echo "RESULT: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
