#!/usr/bin/env node
/*
 * ioniq6 — command-line control for a Hyundai IONIQ 6 on Hyundai BlueLink Canada (mybluelink.ca).
 * Zero dependencies, Node 18+. Protocol lives in lib/bluelink-ca.js.
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const readline = require('readline');
const { BlueLinkCA, BlueLinkError } = require('./lib/bluelink-ca');

const VERSION = '2.1.0';
const HOME = process.env.IONIQ6_HOME || path.join(os.homedir(), '.ioniq6');
const CONFIG_FILE = path.join(HOME, 'config.json');
const SESSION_FILE = path.join(HOME, 'session.json');

class CliError extends Error {
  constructor(msg, code = 1) { super(msg); this.exitCode = code; }
}

/** "2026-10-13T13:15:00Z" -> "Oct 13, 9:15 AM" in this computer's time zone. */
function localStamp(iso) {
  const d = iso ? new Date(iso) : null;
  if (!d || isNaN(d)) return iso || 'unknown';
  return d.toLocaleString('en-CA', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}
function writeJson(file, obj) {
  fs.mkdirSync(HOME, { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, JSON.stringify(obj, null, 2), { mode: 0o600 });
  try { fs.chmodSync(file, 0o600); } catch { /* windows */ }
}

function prompt(question, { hidden = false } = {}) {
  if (!process.stdin.isTTY) throw new CliError(`Need input for "${question.trim()}" but stdin is not a terminal.`);
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    if (hidden) rl._writeToOutput = (s) => rl.output.write(s.includes(question) ? s : '');
    rl.question(question, (ans) => {
      rl.close();
      if (hidden) process.stdout.write('\n');
      resolve(ans.trim());
    });
  });
}

function printStatus(x) {
  const yes = (b) => (b ? 'yes' : 'no');
  const L = [];
  L.push(`${x.vehicle}  (as of ${localStamp(x.updated)})`);
  L.push('');
  L.push(`  Battery      ${x.battery ?? '?'}%   range ${x.range_km ?? '?'} km`);
  let plug = x.plugged_in ? 'plugged in' : 'unplugged';
  if (x.charging) plug += `, charging${x.charge_time_left_min != null ? ` — ${x.charge_time_left_min} min left` : ''}`;
  L.push(`  Charging     ${plug}`);
  if (x.charge_limit_ac != null) L.push(`  Limits       AC ${x.charge_limit_ac}%  /  DC ${x.charge_limit_dc}%`);
  L.push(`  Locked       ${yes(x.locked)}${x.doors_open.length ? '   OPEN: ' + x.doors_open.join(', ') : ''}${x.trunk_open ? '   trunk open' : ''}${x.frunk_open ? '   frunk open' : ''}`);
  if (x.windows_open.length) L.push(`  Windows      open: ${x.windows_open.join(', ')}`);
  let cl = x.climate_on ? `on, set ${x.climate_set_c} °C` : 'off';
  const extras = [x.defrost && 'defrost', x.steering_heat && 'steering wheel', x.rear_window_heat && 'rear window'].filter(Boolean);
  if (extras.length) cl += ` (+ ${extras.join(', ')})`;
  L.push(`  Climate      ${cl}`);
  if (x.seat_driver && x.seat_driver !== 'off') L.push(`  Seats        driver ${x.seat_driver}, passenger ${x.seat_passenger}`);
  if (x.aux_12v_percent != null) L.push(`  12V battery  ${x.aux_12v_percent}%`);
  if (x.odometer_km != null) L.push(`  Odometer     ${Number(x.odometer_km).toLocaleString('en-CA')} km`);
  const warn = [x.tire_warning && 'tire pressure', x.washer_fluid_low && 'washer fluid'].filter(Boolean);
  if (warn.length) L.push(`  Warnings     ${warn.join(', ')}`);
  console.log(L.join('\n'));
}

function parseArgs(argv) {
  const pos = [], flags = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const [k, v] = a.slice(2).split('=');
      if (v !== undefined) flags[k] = v;
      else if (argv[i + 1] !== undefined && !argv[i + 1].startsWith('--')) flags[k] = argv[++i];
      else flags[k] = true;
    } else if (a === '-h') flags.help = true;
    else if (a === '-j') flags.json = true;
    else pos.push(a);
  }
  return { pos, flags };
}

function loadCreds() {
  const cfg = readJson(CONFIG_FILE) || {};
  const c = {
    username: process.env.IONIQ6_USERNAME || cfg.username,
    password: process.env.IONIQ6_PASSWORD || cfg.password,
    pin: process.env.IONIQ6_PIN || cfg.pin,
  };
  const missing = Object.entries(c).filter(([, v]) => !v).map(([k]) => k);
  if (missing.length) throw new CliError(`Missing ${missing.join(', ')}. Run \`ioniq6 setup\` or set IONIQ6_USERNAME / IONIQ6_PASSWORD / IONIQ6_PIN.`);
  return c;
}

const HELP = `ioniq6 ${VERSION} — control your Hyundai IONIQ 6 via BlueLink Canada

Usage: ioniq6 <command> [options]

Setup
  setup                       Save username / password / PIN to ~/.ioniq6/config.json
  login                       Sign in (handles the email/SMS code on first use)
      --otp-method email|sms  Pick where the code goes (non-interactive)
      --otp <code>            Finish a pending verification
  logout [--forget-device]    Drop the saved token (and device id, which forces a new code)
  vehicles                    List vehicles on the account

Status
  status [--refresh]          Battery, range, charging, locks, climate.
                              --refresh wakes the car for live data (slower, uses 12V)
  location                    GPS position + Google Maps link

Commands  (each waits for the car to confirm; add --no-wait to fire and forget)
  lock | unlock
  start                       Start climate
      --temp <°C>             14–31.5 in 0.5 steps (default 21)
      --duration <min>        1–30 (default 10)
      --defrost               Front defrost
      --steering              Heated steering wheel
      --rear-window           Rear window defrost
      --seat-driver <lvl>     off|low-heat|med-heat|high-heat|low-cool|med-cool|high-cool
      --seat-passenger <lvl>  (same)   --seat-rear-left / --seat-rear-right <lvl>
  stop                        Stop climate
  charge start|stop           Start / stop charging (must be plugged in)
  charge-limit                Show limits;  --ac <pct> --dc <pct>  to set (50–100, steps of 10)
  trips                       Recent EV trip energy breakdown

Global options
  --json, -j                  Machine-readable output
  --vin <VIN>                 Pick a vehicle if the account has several
  --timeout <sec>             How long to wait for the car (default 120)

Credentials can also come from IONIQ6_USERNAME, IONIQ6_PASSWORD, IONIQ6_PIN.
Set IONIQ6_DEBUG=1 to print raw API responses (tokens masked).`;

async function doLogin(api, flags) {
  if (flags.otp && flags.otp !== true) return api.verifyOtp(flags.otp);
  const r = await api.login();
  if (r.ok) return r;

  let method = flags['otp-method'];
  const interactive = process.stdin.isTTY && !method;
  if (!method) {
    if (interactive && r.phone) {
      console.log('Hyundai needs to verify this new device.');
      console.log(`  1) Email  ${r.email}`);
      console.log(`  2) SMS    ${r.phone}`);
      method = (await prompt('Send the code by [1/2]: ')) === '2' ? 'sms' : 'email';
    } else method = 'email';
  }
  const sent = await api.sendOtp(method);
  console.error(`Verification code sent by ${sent.via === 'sms' ? 'SMS' : 'email'} to ${sent.to}.`);
  if (!interactive) throw new CliError('Finish with:  ioniq6 login --otp <code>', 2);
  return api.verifyOtp(await prompt('Enter the code: '));
}

async function main() {
  const { pos, flags } = parseArgs(process.argv.slice(2));
  const cmd = pos[0];
  const out = (human, data) => console.log(flags.json ? JSON.stringify(data, null, 2) : human);

  if (!cmd || flags.help || cmd === 'help') return console.log(HELP);
  if (cmd === 'version' || flags.version) return console.log(VERSION);

  if (cmd === 'setup') {
    const cfg = readJson(CONFIG_FILE) || {};
    const username = (await prompt(`BlueLink email${cfg.username ? ` [${cfg.username}]` : ''}: `)) || cfg.username;
    const password = (await prompt('BlueLink password: ', { hidden: true })) || cfg.password;
    const pin = (await prompt('BlueLink 4-digit PIN: ', { hidden: true })) || cfg.pin;
    if (!/^\d{4}$/.test(String(pin))) throw new CliError('PIN must be 4 digits.');
    writeJson(CONFIG_FILE, { username, password, pin: String(pin) });
    return console.log(`Saved to ${CONFIG_FILE} (readable only by you). Next: ioniq6 login`);
  }

  if (cmd === 'logout') {
    const s = readJson(SESSION_FILE) || {};
    writeJson(SESSION_FILE, flags['forget-device'] ? {} : { deviceId: s.deviceId });
    return console.log(flags['forget-device'] ? 'Logged out and forgot this device (next login will need a code).' : 'Logged out.');
  }

  const api = new BlueLinkCA({
    creds: loadCreds(),
    session: readJson(SESSION_FILE) || {},
    saveSession: (s) => writeJson(SESSION_FILE, s),
    baseUrl: process.env.IONIQ6_BASE_URL,
    pollMs: Number(process.env.IONIQ6_POLL_MS) || undefined,
    debug: process.env.IONIQ6_DEBUG ? (m) => console.error(`[debug] ${m}`) : null,
  });

  if (cmd === 'login') {
    await doLogin(api, flags);
    const v = await api.selectVehicle(flags.vin);
    return out(`Signed in. Vehicle: ${v.year} ${v.model} (${v.vin})`, { ok: true, vin: v.vin });
  }

  if (cmd === 'vehicles') {
    const list = await api.vehicles();
    return out(
      list.map((v) => `${v.modelYear} ${v.modelName}  "${v.nickName}"  VIN ${v.vin}`).join('\n'),
      list.map((v) => ({ vin: v.vin, model: v.modelName, year: v.modelYear, nickname: v.nickName, fuel: v.fuelKindCode })),
    );
  }

  await api.selectVehicle(flags.vin);
  const timeout = Number(flags.timeout) || 120;
  const tty = process.stderr.isTTY && !flags.json;
  let dots = false;
  const opts = {
    wait: !flags['no-wait'],
    timeout,
    onProgress: () => {
      if (!tty) return;
      if (!dots) { process.stderr.write('Waiting for the car'); dots = true; }
      process.stderr.write('.');
    },
  };
  const report = (label, r) => {
    if (dots) process.stderr.write('\n');
    const msg = {
      success: `${label}: done.`,
      sent: `${label}: sent (not waiting for confirmation).`,
      timeout: `${label}: sent, but the car hadn't confirmed after ${timeout}s. It may still complete.`,
      failed: `${label}: the car reported failure${r.detail ? ' (' + r.detail + ')' : ''}.`,
    }[r.status];
    out(msg, { action: label, ...r });
    if (r.status === 'failed') process.exitCode = 1;
  };

  switch (cmd) {
    case 'status': {
      const x = await api.status({ refresh: !!flags.refresh });
      return flags.json ? console.log(JSON.stringify(x, null, 2)) : printStatus(x);
    }
    case 'location': {
      const r = await api.location();
      return out(`${r.lat}, ${r.lon}  (as of ${localStamp(r.time)})\n${r.maps}`, r);
    }
    case 'lock': return report('Lock', await api.lock(opts));
    case 'unlock': return report('Unlock', await api.unlock(opts));
    case 'start': {
      const r = await api.startClimate({
        temp: flags.temp !== undefined ? Number(flags.temp) : undefined,
        duration: flags.duration !== undefined ? Number(flags.duration) : undefined,
        defrost: !!flags.defrost,
        steering: !!flags.steering,
        rearWindow: !!flags['rear-window'],
        seats: {
          driver: flags['seat-driver'], passenger: flags['seat-passenger'],
          rearLeft: flags['seat-rear-left'], rearRight: flags['seat-rear-right'],
        },
      }, opts);
      return report(`Climate ${r.temp} °C for ${r.duration} min`, r);
    }
    case 'stop': return report('Climate off', await api.stopClimate(opts));
    case 'charge': {
      if (pos[1] === 'start') return report('Start charging', await api.startCharge(opts));
      if (pos[1] === 'stop') return report('Stop charging', await api.stopCharge(opts));
      throw new CliError('Usage: ioniq6 charge start|stop');
    }
    case 'charge-limit': {
      if (flags.ac === undefined && flags.dc === undefined) {
        const c = await api.chargeLimits();
        return out(`AC limit ${c.ac}%   DC limit ${c.dc}%`, c);
      }
      const r = await api.setChargeLimits({ ac: flags.ac, dc: flags.dc }, opts);
      return report(`Charge limits AC ${r.ac}% / DC ${r.dc}%`, r);
    }
    case 'trips': {
      const trips = await api.trips();
      if (flags.json) return console.log(JSON.stringify(trips, null, 2));
      if (!trips.length) return console.log('No recent trips.');
      const c = (v, w) => String(v ?? '-').padStart(w);
      console.log('Trip start             dist   total   drive  climate  accessories  battery-care   regen   (energy as reported by Hyundai)');
      for (const t of trips) {
        console.log(`${t.startdate}  ${c(t.distance, 5)}  ${c(t.totalused, 6)}  ${c(t.drivetrain, 6)}  ${c(t.climate, 7)}  ${c(t.accessories, 11)}  ${c(t.batterycare, 12)}  ${c(t.regen, 6)}`);
      }
      return;
    }
    default:
      throw new CliError(`Unknown command "${cmd}". Run \`ioniq6 help\`.`);
  }
}

main().catch((e) => {
  let msg = e.message;
  if (e instanceof BlueLinkError && e.code === 'OTP_REQUIRED') msg += ' Run: ioniq6 login';
  if (e instanceof BlueLinkError && e.code === 'NO_OTP') msg += ' Run `ioniq6 login` first.';
  if (!(e instanceof CliError) && !(e instanceof BlueLinkError) && process.env.IONIQ6_DEBUG) msg = e.stack;
  console.error(`Error: ${msg}`);
  process.exit(e.exitCode || 1);
});
