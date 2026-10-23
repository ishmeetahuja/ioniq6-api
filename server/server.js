#!/usr/bin/env node
/*
 * ioniq6 API server — HTTP API for a Hyundai IONIQ 6 on BlueLink Canada, plus walk-away auto-lock.
 * Zero dependencies, Node 18+. Configuration is entirely environment variables (see .env.example).
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { BlueLinkCA, BlueLinkError, parseStamp } = require('./lib/bluelink-ca');

const VERSION = '2.1.0';
const env = process.env;
const clean = (v) => String(v).replace(/\s+#.*$/, '').trim(); // tolerate inline "# comments"
const num = (v, d) => {
  if (v === undefined || clean(v) === '') return d;
  const n = Number(clean(v));
  if (!Number.isFinite(n)) { console.error(`Config error: expected a number, got "${v}"`); process.exit(1); }
  return n;
};
const bool = (v, d) => (v === undefined || clean(v) === '' ? d : /^(1|true|yes|on)$/i.test(clean(v)));

const CFG = {
  port: num(env.PORT, 8080),
  host: env.HOST || '0.0.0.0',
  dataDir: env.DATA_DIR || path.join(__dirname, 'data'),
  publicUrl: (env.PUBLIC_URL || '').replace(/\/$/, ''),
  trustProxy: bool(env.TRUST_PROXY, false), // true only when behind Caddy/nginx/ALB
  adminToken: env.ADMIN_TOKEN || '',
  phoneToken: env.PHONE_TOKEN || '',
  creds: { username: env.BLUELINK_USERNAME, password: env.BLUELINK_PASSWORD, pin: env.BLUELINK_PIN },
  vin: env.BLUELINK_VIN || undefined,
  baseUrl: env.BLUELINK_BASE_URL || undefined,
  pollMs: num(env.BLUELINK_POLL_MS, 5000),
  minCooldownSec: num(env.BLUELINK_MIN_COOLDOWN_SECONDS, 600),
  loginRetrySec: num(env.BLUELINK_LOGIN_RETRY_SECONDS, 300),
  minSigninGapSec: num(env.BLUELINK_MIN_SIGNIN_GAP_SECONDS, 120),
  // App opens within this share one read of Hyundai's saved copy. Hyundai's sign-ins only last
  // ~20–30 min, so a read after that usually means a fresh sign-in; lock state and driving/parked
  // come from the phone now, so battery/range being up to 30 min old is fine.
  statusCacheSec: num(env.STATUS_CACHE_SECONDS, 1800),
  // Waking the car (live status, commands, location) is what Hyundai limits per day. Automatic
  // live refreshes follow the community tools' practice: rarely, within a daily budget, never at night.
  wake: {
    autoDailyMax: num(env.AUTO_REFRESH_DAILY_MAX, 4),      // automatic live refreshes per 24 h
    autoMinGapMin: num(env.AUTO_REFRESH_MIN_GAP_MINUTES, 120),
    totalSoftMax: num(env.CAR_WAKE_DAILY_SOFT_MAX, 12),    // all wake-ups; above this, automatic ones stop
    quietFrom: num(env.AUTO_REFRESH_QUIET_FROM_HOUR, 22),  // no automatic wake-ups from…
    quietTo: num(env.AUTO_REFRESH_QUIET_TO_HOUR, 6),       // …until (local time)
  },
  commandTimeout: num(env.COMMAND_TIMEOUT_SECONDS, 120),
  autolock: {
    enabled: bool(env.AUTOLOCK_ENABLED, true),
    graceSec: num(env.AUTOLOCK_GRACE_SECONDS, 20),
    checkFirst: bool(env.AUTOLOCK_CHECK_FIRST, false), // false = send lock straight away, inspect only on failure
    retrySec: num(env.AUTOLOCK_RETRY_SECONDS, 30),
    maxAttempts: num(env.AUTOLOCK_MAX_ATTEMPTS, 5),
    rateLimitGiveUpMin: num(env.AUTOLOCK_RATE_LIMIT_GIVE_UP_MINUTES, 45), // keep retrying a refused lock this long
    rateLimitBackoffSec: num(env.AUTOLOCK_RATE_LIMIT_BACKOFF_SECONDS, 30),
    rateLimitBackoffMaxSec: num(env.AUTOLOCK_RATE_LIMIT_BACKOFF_MAX_SECONDS, 600),
  },
  guard: {
    intervalMin: num(env.GUARD_INTERVAL_MINUTES, 0), // 0 = backup check off (any other value = on)
    everyHours: num(env.GUARD_EVERY_HOURS, 6),       // how often the backup check looks
    graceSec: num(env.GUARD_GRACE_SECONDS, 60),
  },
  // Dead-man's switch: after the phone says "parked", it keeps saying "still here" while you're
  // near the car. If it goes silent this long without a lock, the server locks on its own.
  watch: {
    silentMin: num(env.WATCH_SILENT_MINUTES, 10),
    nearMin: num(env.PHONE_NEAR_MINUTES, 15),        // "the phone said it's at the car" counts this long
  },
  ntfy: { url: env.NTFY_URL || '', token: env.NTFY_TOKEN || '' },
  telegram: { token: env.TELEGRAM_BOT_TOKEN || '', chat: env.TELEGRAM_CHAT_ID || '' },
};

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------------------------------------------------------------- startup checks

function fatal(msg) { console.error(`Config error: ${msg}`); process.exit(1); }
if (!CFG.creds.username || !CFG.creds.password || !CFG.creds.pin) fatal('BLUELINK_USERNAME, BLUELINK_PASSWORD and BLUELINK_PIN are required.');
if (CFG.adminToken.length < 24) fatal('ADMIN_TOKEN must be at least 24 characters (try: openssl rand -hex 24).');
if (CFG.phoneToken && CFG.phoneToken.length < 24) fatal('PHONE_TOKEN must be at least 24 characters.');
if (CFG.phoneToken && CFG.phoneToken === CFG.adminToken) fatal('PHONE_TOKEN must differ from ADMIN_TOKEN.');
fs.mkdirSync(CFG.dataDir, { recursive: true, mode: 0o700 });

// ---------------------------------------------------------------- persistence

const SESSION_FILE = path.join(CFG.dataDir, 'session.json');
const STATE_FILE = path.join(CFG.dataDir, 'state.json');
const readJson = (f) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return null; } };
const writeJson = (f, o) => {
  const tmp = f + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(o, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, f);
};

// Runtime settings (changeable via the API, persisted); env vars are only the starting defaults.
const state = Object.assign({
  autolockEnabled: CFG.autolock.enabled, pausedUntil: 0,
  graceSec: CFG.autolock.graceSec, checkFirst: CFG.autolock.checkFirst,
  wakeups: [],        // [{ t, ep, auto }] for the last 24 h: every time we made Hyundai contact the car
  watch: { armedAt: 0, lastSeen: 0 },   // dead-man's switch (see CFG.watch)
  lastNearAt: 0,      // last time the phone said it's at / in the car
}, readJson(STATE_FILE) || {});
const saveState = () => writeJson(STATE_FILE, {
  autolockEnabled: state.autolockEnabled, pausedUntil: state.pausedUntil, graceSec: state.graceSec, checkFirst: state.checkFirst,
  wakeups: state.wakeups, watch: state.watch, lastNearAt: state.lastNearAt,
});

// ---------------------------------------------------------------- car wake-up budget

const WAKE_ENDPOINTS = new Set(['rltmvhclsts', 'drlck', 'drulck', 'evc/rfon', 'evc/rfoff', 'evc/rcstrt', 'evc/rcstp', 'evc/setsoc', 'fndmcr']);
let wakeIsAuto = false;   // set while an automatic refresh runs, so the budget can tell them apart
function wakeups24h() {
  const cut = Date.now() - 24 * 3600e3;
  if (state.wakeups.length && state.wakeups[0].t < cut) state.wakeups = state.wakeups.filter((w) => w.t >= cut);
  return state.wakeups;
}
function noteWake(endpoint) {
  wakeups24h().push({ t: Date.now(), ep: endpoint, auto: wakeIsAuto || undefined });
  saveState();
}
function localHour() {
  return Number(new Date().toLocaleString('en-US', { hour: 'numeric', hourCycle: 'h23', timeZone: process.env.TZ || 'America/Toronto' }));
}
/** Why an automatic live refresh shouldn't happen now, or null if it may. */
function autoRefreshBlocked() {
  const w = wakeups24h();
  const { autoDailyMax, autoMinGapMin, totalSoftMax, quietFrom, quietTo } = CFG.wake;
  const h = localHour();
  if (quietFrom !== quietTo && (quietFrom > quietTo ? (h >= quietFrom || h < quietTo) : (h >= quietFrom && h < quietTo))) return 'night';
  const autos = w.filter((x) => x.auto && x.ep === 'rltmvhclsts');
  if (autos.length >= autoDailyMax) return 'daily-budget';
  if (w.length >= totalSoftMax) return 'daily-budget';
  const lastLive = w.filter((x) => x.ep === 'rltmvhclsts').pop();
  if (lastLive && Date.now() - lastLive.t < autoMinGapMin * 60000) return 'recent';
  return null;
}

// ---------------------------------------------------------------- BlueLink client (serialized)

// Every request to Hyundai, one line each, kept in the data volume so it survives updates:
// "2026-10-15T12:32:49.123Z drlck 200". Shown by `install.sh doctor` to see what Hyundai saw.
const CALL_LOG = path.join(CFG.dataDir, 'hyundai.log');
function logCall(endpoint, status, code) {
  if (status === 200 && WAKE_ENDPOINTS.has(endpoint)) noteWake(endpoint);
  const line = `${new Date().toISOString()} ${endpoint} ${status}${code ? ' ' + code : ''}\n`;
  fs.stat(CALL_LOG, (err, st) => {
    if (!err && st.size > 512 * 1024) { try { fs.renameSync(CALL_LOG, CALL_LOG + '.1'); } catch {} }
    fs.appendFile(CALL_LOG, line, { mode: 0o600 }, () => {});
  });
}

const bl = new BlueLinkCA({
  creds: CFG.creds,
  session: readJson(SESSION_FILE) || {},
  saveSession: (s) => writeJson(SESSION_FILE, s),
  baseUrl: CFG.baseUrl,
  pollMs: CFG.pollMs,
  minCooldownSec: CFG.minCooldownSec,
  loginRetrySec: CFG.loginRetrySec,
  minSigninGapSec: CFG.minSigninGapSec,
  debug: env.IONIQ6_DEBUG ? (m) => log('[debug]', m) : null,
  warn: (m) => log('[bluelink]', m),
  onCall: logCall,
  onNote: (text) => fs.appendFile(CALL_LOG, `${new Date().toISOString()} # ${text}\n`, { mode: 0o600 }, () => {}),
});

// Hyundai doesn't like concurrent sessions/commands from one device: run one operation at a time.
let chain = Promise.resolve();
function serial(fn) {
  const run = chain.then(fn, fn);
  chain = run.catch(() => {});
  return run;
}
const withCar = (fn) => serial(async () => { await bl.selectVehicle(CFG.vin); return fn(); });

// ---------------------------------------------------------------- notifications

const NTFY_PRIORITY = { min: 1, low: 2, default: 3, high: 4, urgent: 5 };

async function notify({ title, message, priority = 'default', tags = [], actions = [] }) {
  try {
    const jobs = [];
    if (CFG.ntfy.url) {
      // JSON publish (POST to the server root with the topic in the body): UTF-8 safe, unlike headers.
      const u = new URL(CFG.ntfy.url);
      const topic = u.pathname.split('/').filter(Boolean).pop();
      const root = u.origin + u.pathname.slice(0, u.pathname.lastIndexOf('/' + topic)) + '/';
      const body = {
        topic, title, message, tags, priority: NTFY_PRIORITY[priority] || 3,
        actions: actions.map((a) => ({ action: 'http', label: a.label, url: a.url, method: 'POST', clear: true })),
      };
      const h = { 'Content-Type': 'application/json' };
      if (CFG.ntfy.token) h.Authorization = `Bearer ${CFG.ntfy.token}`;
      jobs.push(fetch(root, { method: 'POST', headers: h, body: JSON.stringify(body), signal: AbortSignal.timeout(10000) }));
    }
    if (CFG.telegram.token && CFG.telegram.chat) {
      const body = { chat_id: CFG.telegram.chat, text: `${title}
${message}`, disable_notification: priority === 'low' || priority === 'min' };
      if (actions.length) body.reply_markup = { inline_keyboard: [actions.map((a) => ({ text: a.label, url: a.url }))] };
      jobs.push(fetch(`https://api.telegram.org/bot${CFG.telegram.token}/sendMessage`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(10000),
      }));
    }
    for (const r of await Promise.allSettled(jobs)) {
      if (r.status === 'rejected') log('notify failed:', r.reason?.message);
      else if (!r.value.ok) log('notify failed: HTTP', r.value.status);
    }
  } catch (e) {
    log('notify failed:', e.message);
  }
}

// ---------------------------------------------------------------- jobs (async commands)

let statusCache = null;   // absorbs repeated app opens: at most one Hyundai status read per few minutes
let liveCache = null;     // the last answer straight from the car
let lastPrepareAt = 0;
/**
 * Make sure a sign-in is ready before it's needed: when you park (the walk-away lock follows within
 * a minute or two) and when you walk back to the car (the Unlock button in the app's alert).
 * Whether the current sign-in is still good comes from what Hyundai said when it was made, so the
 * check itself costs no request. Only an expired sign-in leads to a new one — which the lock would
 * have needed a moment later anyway, so this moves a sign-in earlier, it doesn't add one.
 * At most once a minute, whoever asks; no retries; never during a rate-limit pause.
 */
async function prepareSignIn(why) {
  if (Date.now() - lastPrepareAt < 60000) return { ready: bl.loggedIn, throttled: true };
  lastPrepareAt = Date.now();
  const left = bl.signInMinutesLeft();
  const age = Date.now() - (bl.session.signedInAt || 0);
  if (left !== null ? left >= 5 : bl.loggedIn && age < 15 * 60000) {
    return { ready: true, signedInMinutesAgo: Math.round(age / 60000), minutesLeft: left === null ? null : Math.floor(left) };
  }
  if (bl.session.pendingOtp || bl.session.pendingMfa) return { ready: false, error: 'Hyundai is waiting for a verification code' };
  if (bl.cooldownUntil) return { ready: false, error: 'Hyundai asked us to wait', code: 'RATE_LIMIT' };
  try {
    // A sign-in that's about to run out gets renewed now (relogin); none at all gets one through the
    // read. The read is Hyundai's saved copy: it doesn't wake the car.
    const value = await withCar(async () => { if (bl.loggedIn) await bl.relogin(); return bl.status({ refresh: false }); });
    statusCache = { at: Date.now(), value };
    log('ready', `signed in ahead (${why})`);
    return { ready: true, signedInMinutesAgo: 0 };
  } catch (e) {
    log('ready', `couldn't sign in ahead (${why}): ${e.message}`);
    return { ready: false, error: e.message, code: e.code };
  }
}
// What our own confirmed commands tell us. Hyundai's saved status only refreshes when the car
// reports in, so after we lock or unlock we know better than it does until it catches up.
let known = { locked: null, at: 0 };
function withKnown(v) {
  if (!v || known.locked === null) return v;
  const upd = Date.parse(v.updated || '') || 0;
  if (known.at <= upd) return v;
  // A remote lock/unlock only goes through with the car switched off.
  return { ...v, locked: known.locked, car_on: false, locked_at: new Date(known.at).toISOString(), locked_by: 'command' };
}
const jobs = new Map();
function newJob(action, source) {
  const id = crypto.randomBytes(6).toString('hex');
  const job = { id, action, source, status: 'queued', createdAt: new Date().toISOString() };
  jobs.set(id, job);
  if (jobs.size > 100) jobs.delete(jobs.keys().next().value);
  return job;
}
function runJob(job, fn) {
  if (!String(job.source || '').startsWith('autolock:')) markActivity();
  return withCar(async () => {
    job.status = 'running';
    try {
      const r = await fn();
      Object.assign(job, { status: r.status || 'success', result: r });
    } catch (e) {
      Object.assign(job, { status: 'error', error: e.message, code: e.code, until: e.until });
    }
    if (job.status === 'success' && (job.action === 'lock' || job.action === 'unlock')) {
      known = { locked: job.action === 'lock', at: Date.now() };
    }
    job.finishedAt = new Date().toISOString();
    statusCache = null;   // the car just changed; next status read goes to Hyundai
    liveCache = null;
    log(`job ${job.id} ${job.action} -> ${job.status}${job.error ? ': ' + job.error : ''}`);
    return job;
  });
}

// ---------------------------------------------------------------- walk-away auto-lock

/*
 * Phone (or any trigger) reports "away" → short grace period (cancel window; default 20 s) → then:
 *
 *   fast mode (default): send LOCK immediately. Locking is harmless if the car is already locked.
 *     Only if the lock fails do we wake the car to find out why (usually a door/trunk open).
 *   check-first mode: wake the car for a live status first (+10–30 s), then decide. Quieter
 *     (no redundant lock when it was already locked) but slower.
 *
 * Inspection rules: car on → leave it; already locked → done; something open → re-check every
 * AUTOLOCK_RETRY_SECONDS up to AUTOLOCK_MAX_ATTEMPTS, then alert; otherwise → lock.
 * "near" (phone reconnected to the car) cancels a pending lock. The server never auto-unlocks.
 */
const AL = { phase: 'idle', events: [], timer: null, nonce: null, cancelled: false, locksAt: null, source: null, attempt: 0, checkFirst: null, guardUnlockedSince: null, quiet: false, delayStarted: 0, delayNotified: false };
let lastResult = null;   // outcome of the most recent auto-lock, so the phone can show it
/** Records how this auto-lock ended (the phone polls for it by id). */
function alFinish(outcome, detail) {
  lastResult = { id: AL.nonce, outcome, detail: detail || null, source: AL.source, at: new Date().toISOString() };
}
function hhmm(t) { return new Date(t).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: process.env.TZ || 'America/Toronto' }); }
/** Hyundai is rate-limiting: keep the lock pending and try again when allowed (never just drop it). */
function alDelay(untilIso, why) {
  if (!AL.delayStarted) AL.delayStarted = Date.now();
  if (Date.now() - AL.delayStarted > CFG.autolock.rateLimitGiveUpMin * 60000) {
    alEvent('gave-up', `${why}; Hyundai kept refusing for ${CFG.autolock.rateLimitGiveUpMin} min`);
    alFinish('failed', 'Hyundai kept rate-limiting the server');
    notify({ title: 'Car NOT locked', message: `Hyundai kept refusing requests for ${CFG.autolock.rateLimitGiveUpMin} minutes. Lock the car yourself.`, priority: 'high', tags: ['warning', 'car'] });
    alReset();
    return;
  }
  // Back off between tries (30 s, 1, 2, 4, 8, 10 min…) so retries never prolong Hyundai's block.
  AL.delays = (AL.delays || 0) + 1;
  const backoff = Math.min(CFG.autolock.rateLimitBackoffMaxSec, CFG.autolock.rateLimitBackoffSec * 2 ** (AL.delays - 1)) * 1000;
  const at = Math.max(Date.parse(untilIso || '') || 0, Date.now() + backoff) + 3000;
  AL.attempt = Math.max(0, AL.attempt - 1);   // a refused request isn't a real attempt
  AL.phase = 'delayed';
  AL.locksAt = at;
  AL.timer = setTimeout(() => alCheck(), at - Date.now());
  alEvent('delayed', `${why}; trying again at ${hhmm(at)}`);
  if (!AL.delayNotified) {
    AL.delayNotified = true;
    notify({ title: 'Lock delayed, still trying', message: `Locking because ${AL.reason || AL.source}, but Hyundai is busy, so it waits until about ${hhmm(at)} and keeps retrying. You'll get "Car locked" when it goes through. Lock it yourself if you can.`, priority: 'high', tags: ['hourglass', 'car'] });
  }
}
function alEvent(type, detail) {
  AL.events.unshift({ at: new Date().toISOString(), type, ...(detail ? { detail } : {}) });
  AL.events.length = Math.min(AL.events.length, 50);
  log(`autolock: ${type}${detail ? ' — ' + detail : ''}`);
}
function alReset() {
  clearTimeout(AL.timer);
  Object.assign(AL, { phase: 'idle', timer: null, nonce: null, cancelled: false, locksAt: null, source: null, attempt: 0, checkFirst: null, quiet: false, delayStarted: 0, delayNotified: false, delays: 0, reason: null });
}
function alPublic() {
  return {
    enabled: state.autolockEnabled,
    pausedUntil: state.pausedUntil > Date.now() ? new Date(state.pausedUntil).toISOString() : null,
    phase: AL.phase,
    id: AL.nonce,
    source: AL.source,
    last: lastResult,
    locksAt: AL.locksAt ? new Date(AL.locksAt).toISOString() : null,
    attempt: AL.attempt || undefined,
    graceSeconds: state.graceSec,
    checkFirst: state.checkFirst,
    backupCheckEveryHours: CFG.guard.intervalMin > 0 ? CFG.guard.everyHours : 0,
    watching: state.watch.armedAt ? { since: new Date(state.watch.armedAt).toISOString(), lastSeen: new Date(state.watch.lastSeen).toISOString() } : null,
    events: AL.events.slice(0, 20),
  };
}

// Why a lock was sent, in words, for ntfy ("Car locked at 5:03 PM because …").
const REASONS = {
  esp32: 'you walked away from the car',
  home: 'you left the car at home',
  'car-bluetooth': 'you parked (the ESP32 wasn\'t found)',
  'esp32-power-lost': 'the ESP32 lost power and the car didn\'t reconnect',
  'phone went quiet': 'your phone went quiet after you parked (dead battery, no signal or app closed)',
  'safety check': 'the 6-hour backup check found it off and unlocked',
};
function alAway(source = 'phone', { graceSec = state.graceSec, checkFirst = null, quiet = false, reason = null } = {}) {
  if (source !== 'safety check') markActivity();
  if (!state.autolockEnabled) { alEvent('ignored', `away from ${source}: auto-lock disabled`); return { phase: 'disabled' }; }
  if (state.pausedUntil > Date.now()) { alEvent('ignored', `away from ${source}: paused`); return { phase: 'paused', pausedUntil: new Date(state.pausedUntil).toISOString() }; }
  if (AL.phase !== 'idle') { alEvent('away', `${source} (already ${AL.phase})`); return alPublic(); }

  AL.phase = 'pending';
  AL.source = source;
  AL.checkFirst = checkFirst; // per-event override of state.checkFirst (null = use the setting)
  AL.quiet = quiet;           // the phone shows the result too; skip the "armed, cancel?" message
  AL.reason = reason || REASONS[source] || source;
  AL.nonce = crypto.randomBytes(16).toString('hex');
  AL.locksAt = Date.now() + graceSec * 1000;
  AL.timer = setTimeout(() => alCheck(), graceSec * 1000);
  alEvent('away', `${source}; locking in ${graceSec}s`);

  if (graceSec > 0 && !quiet) { // with no wait there's nothing to cancel
    const actions = CFG.publicUrl ? [{ label: 'Cancel auto-lock', url: `${CFG.publicUrl}/v1/autolock/cancel/${AL.nonce}` }] : [];
    notify({
      title: 'Auto-lock armed',
      message: `You left the car (${source}). Locking in ${graceSec < 90 ? graceSec + ' s' : Math.round(graceSec / 6) / 10 + ' min'}.`,
      priority: 'low', tags: ['car'], actions,
    });
  }
  return alPublic();
}

function alNear(source = 'phone') {
  markActivity();
  watchSeen();
  if (source === 'car-bluetooth') watchOff('driving');
  if (AL.phase === 'pending' || AL.phase === 'retrying' || AL.phase === 'delayed') {
    alEvent('cancelled', `back near the car (${source})`);
    alFinish('cancelled', 'back near the car');
    alReset();
  } else if (AL.phase === 'checking') {
    AL.cancelled = true;
    alEvent('cancelled', `back near the car (${source}) during check`);
  } else {
    alEvent('near', source);
  }
  AL.guardUnlockedSince = null;
  return alPublic();
}

function alCancel(by) {
  if (AL.phase === 'idle') return false;
  if (AL.phase === 'checking') AL.cancelled = true; else { alFinish('cancelled', by); alReset(); }
  alEvent('cancelled', by);
  return true;
}

function describeOpen(s) {
  const open = Object.entries(s.doorOpen || {}).filter(([, v]) => v).map(([k]) => k.replace(/([A-Z])/g, ' $1').toLowerCase() + ' door');
  if (s.trunkOpen) open.push('trunk');
  if (s.hoodOpen) open.push('frunk');
  return open;
}

async function alLock() {
  const job = newJob('lock', `autolock:${AL.source}`);
  return runJob(job, () => bl.urgent(() => bl.lock({ wait: true, timeout: CFG.commandTimeout })));
}

async function alCheck() {
  AL.phase = 'checking';
  AL.attempt += 1;
  const attempt = AL.attempt;
  const why = () => AL.reason || AL.source;
  const waited = () => (AL.delayNotified ? ' It waited a moment because Hyundai was busy.' : '');
  const cancelled = () => { if (!AL.cancelled) return false; alFinish('cancelled', 'back near the car'); alReset(); return true; };
  try {
    if (attempt === 1 && !(AL.checkFirst ?? state.checkFirst)) {
      if (cancelled()) return;
      const r = await alLock();
      if (r.status === 'success') {
        alEvent('locked', `via ${AL.source} (fast)`);
        alFinish('locked');
        notify({ title: 'Car locked', message: `Locked at ${hhmm(Date.now())} because ${why()}.${waited()}`, priority: 'default', tags: ['lock', 'car'] });
        alReset();
        return;
      }
      if (r.code === 'RATE_LIMIT') return alDelay(r.until, 'lock refused: Hyundai rate limit');
      alEvent('lock-refused', `${r.error || r.result?.detail || r.status}; checking why`);
    }

    const s = await withCar(() => bl.urgent(() => bl.rawStatus({ refresh: true })));
    if (cancelled()) return;
    if (s.engine) {
      alEvent('skipped', 'car is on');
      alFinish('car-on');
      notify({ title: 'Not locked: car is on', message: `Asked to lock because ${why()}, but the car is switched on.`, priority: 'low', tags: ['car'] });
      alReset(); return;
    }
    if (s.doorLock) {
      alEvent('skipped', 'already locked');
      alFinish('already-locked');
      known = { locked: true, at: Date.now() };
      notify({ title: 'Car locked', message: `Already locked at ${hhmm(Date.now())} (checked because ${why()}).`, priority: 'low', tags: ['lock', 'car'] });
      alReset(); return;
    }

    const open = describeOpen(s);
    if (open.length) {
      if (attempt >= CFG.autolock.maxAttempts) {
        alEvent('gave-up', `${open.join(', ')} still open`);
        alFinish('open', `${open.join(', ')} open`);
        notify({ title: 'Car NOT locked', message: `${open.join(', ')} still open after ${attempt} checks. Close it and lock manually.`, priority: 'high', tags: ['warning', 'car'] });
        alReset();
        return;
      }
      if (attempt === 1) notify({ title: 'Car unlocked — something is open', message: `${open.join(', ')} open. I'll keep checking and lock once it's closed.`, priority: 'default', tags: ['warning', 'car'] });
      alEvent('waiting', `${open.join(', ')} open; retry ${attempt + 1}/${CFG.autolock.maxAttempts} in ${CFG.autolock.retrySec}s`);
      lastResult = { id: AL.nonce, outcome: 'waiting', detail: `${open.join(', ')} open`, source: AL.source, at: new Date().toISOString() };
      AL.phase = 'retrying';
      AL.locksAt = Date.now() + CFG.autolock.retrySec * 1000;
      AL.timer = setTimeout(() => alCheck(), CFG.autolock.retrySec * 1000);
      return;
    }

    if (cancelled()) return;
    const r = await alLock();
    if (r.status === 'success') {
      alEvent('locked', `via ${AL.source}`);
      alFinish('locked');
      notify({ title: 'Car locked', message: `Locked at ${hhmm(Date.now())} because ${why()}.${waited()}`, priority: 'default', tags: ['lock', 'car'] });
    } else if (r.code === 'RATE_LIMIT') {
      return alDelay(r.until, 'lock refused: Hyundai rate limit');
    } else {
      alEvent('lock-failed', r.error || r.result?.detail || r.status);
      alFinish('failed', r.error || r.result?.detail || r.status);
      notify({ title: 'Auto-lock FAILED', message: `Lock command ${r.status}${r.error ? ': ' + r.error : ''}. Check the car.`, priority: 'high', tags: ['warning', 'car'] });
    }
    alReset();
  } catch (e) {
    if (e.code === 'RATE_LIMIT' && !AL.cancelled) return alDelay(e.until, 'status check refused: Hyundai rate limit');
    alEvent('error', e.message);
    if (attempt < 3 && !AL.cancelled) {
      AL.phase = 'retrying';
      AL.timer = setTimeout(() => alCheck(), CFG.autolock.retrySec * 1000);
    } else {
      alFinish('failed', e.message);
      notify({ title: 'Auto-lock error', message: `Couldn't check the car: ${e.message}`, priority: 'high', tags: ['warning', 'car'] });
      alReset();
    }
  }
}

// Safety net: look at the (cheap, cached) status; unlocked + off on two consecutive checks → run
// the normal auto-lock flow with a 60 s grace. Hyundai's sign-ins last only ~30 min and Cloudflare
// limits sign-ins hard, so this must not run every 30 min around the clock (that alone meant ~48
// sign-ins a day). It checks every GUARD_INTERVAL_MINUTES only for a few hours after the car was
// used (drive, park, command), every GUARD_IDLE_MINUTES otherwise, and not at all when we've
// confirmed a lock since the car was last used.
let lastActivity = 0;
let lastGuardAt = Date.now();      // no check right at start-up
function markActivity() { lastActivity = Date.now(); }
function guardDue() { return Date.now() - lastGuardAt >= CFG.guard.everyHours * 3600e3; }
/** Why the backup check should leave Hyundai alone right now, or null. */
function guardSkip() {
  if (!state.autolockEnabled || state.pausedUntil > Date.now() || AL.phase !== 'idle') return 'auto-lock off, paused or busy';
  if (known.locked && known.at > lastActivity) return 'a lock was confirmed since the car was last used';
  if (Date.now() - state.lastNearAt < CFG.watch.nearMin * 60000) return 'the phone says you are at the car';
  return null;
}
async function guardTick() {
  const skip = guardSkip();
  if (skip) { log(`backup check skipped: ${skip}`); return; }
  try {
    const s = await withCar(() => bl.rawStatus({ refresh: false }));
    if (s.engine || s.doorLock) return;
    // Hyundai's saved copy may predate a lock we confirmed ourselves.
    if (known.locked && known.at > (Date.parse(parseStamp(s.lastStatusDate) || '') || 0)) return;
    log('backup check: Hyundai says the car is off and unlocked; locking');
    alAway('safety check', { graceSec: CFG.guard.graceSec });
  } catch (e) {
    log('guard error:', e.message);
  }
}

// ---------------------------------------------------------------- HTTP plumbing

const failures = new Map(); // ip -> {n, until}
function tooManyFailures(ip) {
  const f = failures.get(ip);
  return f && f.n >= 10 && f.until > Date.now();
}
function recordFailure(ip) {
  const f = failures.get(ip) || { n: 0, until: 0 };
  if (f.until < Date.now()) f.n = 0;
  f.n += 1; f.until = Date.now() + 15 * 60 * 1000;
  failures.set(ip, f);
}
const safeEq = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};
function scopeOf(req) {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || '');
  const t = m ? m[1].trim() : (req.headers['x-api-key'] || '');
  if (!t) return null;
  if (safeEq(t, CFG.adminToken)) return 'admin';
  if (CFG.phoneToken && safeEq(t, CFG.phoneToken)) return 'phone';
  return null;
}
const clientIp = (req) => (CFG.trustProxy && (req.headers['x-forwarded-for'] || '').split(',').pop().trim()) || req.socket.remoteAddress;

function send(res, code, body) {
  const s = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
  res.writeHead(code, {
    'Content-Type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(s);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let d = '';
    req.on('data', (c) => { d += c; if (d.length > 16384) { reject(Object.assign(new Error('Body too large'), { http: 413 })); req.destroy(); } });
    req.on('end', () => {
      if (!d.trim()) return resolve({});
      try { resolve(JSON.parse(d)); } catch { reject(Object.assign(new Error('Body must be JSON'), { http: 400 })); }
    });
    req.on('error', reject);
  });
}
function httpError(e) {
  if (e.http) return [e.http, { error: e.message }];
  if (e instanceof BlueLinkError) {
    if (e.code === 'INPUT' || e.code === '7549') return [400, { error: e.message }];
    if (e.code === 'OTP_REQUIRED' || e.code === 'NO_OTP') return [409, { error: e.message, code: e.code, next: 'POST /v1/auth/login' }];
    if (e.code === 'NETWORK') return [504, { error: e.message, code: e.code }];
    if (e.code === 'RATE_LIMIT') return [429, { error: e.message, code: e.code, until: bl.cooldownUntil }];
    if (e.code === 'LOGIN_WAIT') return [503, { error: e.message, code: e.code }];
    return [502, { error: e.message, code: e.code }];
  }
  log('unexpected error:', e.stack);
  return [500, { error: 'Internal error' }];
}

// Commands: 202 + job by default; ?wait=true blocks until the car confirms.
async function command(req, res, url, action, fn) {
  const job = newJob(action, 'api');
  const p = runJob(job, () => fn({ wait: true, timeout: CFG.commandTimeout }));
  if (/^(1|true|yes)$/i.test(url.searchParams.get('wait') || '')) {
    await p;
    return send(res, job.status === 'success' ? 200 : job.status === 'error' ? 502 : 200, job);
  }
  return send(res, 202, { ...job, poll: `/v1/jobs/${job.id}` });
}

// [method, pattern, scope, handler]
const ROUTES = [
  ['GET', /^\/healthz$/, 'none', (req, res) => send(res, 200, { ok: true, version: VERSION })],
  ['GET', /^\/$/, 'none', (req, res) => send(res, 200, { name: 'ioniq6-api', version: VERSION, docs: 'see README.md' })],

  // auth bootstrap (one-time verification code for this server's device id)
  ['GET', /^\/v1\/auth$/, 'admin', (req, res) => send(res, 200, { loggedIn: bl.loggedIn, vehicle: bl.session.vin ? bl.vehicleInfo() : null, otpPending: !!bl.session.pendingOtp, rateLimitedUntil: bl.cooldownUntil })],
  ['POST', /^\/v1\/auth\/login$/, 'admin', async (req, res, url, body) => {
    const r = await serial(() => bl.login());
    if (r.ok) return send(res, 200, { status: 'logged_in', vehicle: await withCar(() => bl.vehicleInfo()) });
    const sent = await serial(() => bl.sendOtp(body.otpMethod === 'sms' ? 'sms' : 'email'));
    send(res, 200, { status: 'otp_sent', ...sent, next: 'POST /v1/auth/otp {"code":"123456"}' });
  }],
  ['POST', /^\/v1\/auth\/otp$/, 'admin', async (req, res, url, body) => {
    if (!body.code) return send(res, 400, { error: 'code is required' });
    await serial(() => bl.verifyOtp(body.code));
    send(res, 200, { status: 'logged_in', vehicle: await withCar(() => bl.vehicleInfo()) });
  }],

  // reads
  ['GET', /^\/v1\/vehicles$/, 'admin', async (req, res) => send(res, 200, await serial(() => bl.vehicles()))],
  ['GET', /^\/v1\/status$/, 'phone', async (req, res, url) => {
    let refresh = url.searchParams.get('refresh') === 'true';
    // ?auto=true: the app's own "this looks out of date" refresh. It only wakes the car within the
    // daily budget and not at night; otherwise it gets Hyundai's saved copy and the reason.
    const auto = url.searchParams.get('auto') === 'true';
    const skipped = refresh && auto ? autoRefreshBlocked() : null;
    if (skipped) refresh = false;
    const extra = () => ({ car_wakeups_24h: wakeups24h().length, ...(skipped ? { refresh_skipped: skipped } : {}) });
    if (!refresh && statusCache && Date.now() - statusCache.at < CFG.statusCacheSec * 1000) return send(res, 200, { ...withKnown(statusCache.value), ...extra() });
    // Two "ask the car" requests within a minute (an automatic one and a tap): answer both with one.
    if (refresh && liveCache && Date.now() - liveCache.at < 60000) return send(res, 200, { ...withKnown(liveCache.value), ...extra() });
    const value = await withCar(async () => {
      wakeIsAuto = refresh && auto;   // set inside the queue, so only this read is marked automatic
      try { return await bl.status({ refresh }); } finally { wakeIsAuto = false; }
    });
    statusCache = { at: Date.now(), value };
    if (refresh) liveCache = { at: Date.now(), value };
    send(res, 200, { ...withKnown(value), ...extra() });
  }],
  ['GET', /^\/v1\/location$/, 'admin', async (req, res) => send(res, 200, await withCar(() => bl.location()))],
  ['GET', /^\/v1\/charge-limit$/, 'admin', async (req, res) => send(res, 200, await withCar(() => bl.chargeLimits()))],
  ['GET', /^\/v1\/trips$/, 'admin', async (req, res) => send(res, 200, await withCar(() => bl.trips()))],
  ['GET', /^\/v1\/jobs\/([0-9a-f]+)$/, 'phone', (req, res, url, body, m) => {
    const j = jobs.get(m[1]);
    return j ? send(res, 200, j) : send(res, 404, { error: 'No such job' });
  }],

  // commands
  ['POST', /^\/v1\/lock$/, 'phone', (req, res, url) => command(req, res, url, 'lock', (o) => bl.lock(o))],
  ['POST', /^\/v1\/unlock$/, 'admin', (req, res, url) => command(req, res, url, 'unlock', (o) => bl.unlock(o))],
  ['POST', /^\/v1\/climate\/start$/, 'admin', (req, res, url, body) => {
    bl.climatePayload(body); // validate before queueing so bad input is a 400, not a failed job
    return command(req, res, url, 'climate-start', (o) => bl.startClimate(body, o));
  }],
  ['POST', /^\/v1\/climate\/stop$/, 'admin', (req, res, url) => command(req, res, url, 'climate-stop', (o) => bl.stopClimate(o))],
  ['POST', /^\/v1\/charge\/start$/, 'admin', (req, res, url) => command(req, res, url, 'charge-start', (o) => bl.startCharge(o))],
  ['POST', /^\/v1\/charge\/stop$/, 'admin', (req, res, url) => command(req, res, url, 'charge-stop', (o) => bl.stopCharge(o))],
  ['PUT', /^\/v1\/charge-limit$/, 'admin', (req, res, url, body) => {
    for (const k of ['ac', 'dc']) {
      const v = body[k];
      if (v !== undefined && !(Number(v) >= 50 && Number(v) <= 100 && Number(v) % 10 === 0)) return send(res, 400, { error: `${k} must be 50–100 in steps of 10` });
    }
    if (body.ac === undefined && body.dc === undefined) return send(res, 400, { error: 'Give ac and/or dc' });
    return command(req, res, url, 'charge-limit', (o) => bl.setChargeLimits(body, o));
  }],

  // walk-away auto-lock
  ['GET', /^\/v1\/autolock$/, 'phone', (req, res) => send(res, 200, alPublic())],
  ['POST', /^\/v1\/autolock\/away$/, 'phone', (req, res, url, body) => {
    const g = body.graceSeconds ?? url.searchParams.get('graceSeconds');
    if (g !== undefined && g !== null && !(Number(g) >= 0 && Number(g) <= 600)) return send(res, 400, { error: 'graceSeconds must be 0–600' });
    const source = String(body.source || url.searchParams.get('source') || 'phone').slice(0, 40);
    const opts = {};
    if (g !== undefined && g !== null) opts.graceSec = Number(g);
    if (typeof body.checkFirst === 'boolean') opts.checkFirst = body.checkFirst;
    if (body.quiet === true) opts.quiet = true;
    if (typeof body.reason === 'string' && body.reason.trim()) opts.reason = body.reason.trim().slice(0, 120);
    send(res, 202, alAway(source, opts));
  }],
  ['PUT', /^\/v1\/autolock\/settings$/, 'admin', (req, res, url, body) => {
    if (body.graceSeconds !== undefined) {
      if (!(Number(body.graceSeconds) >= 0 && Number(body.graceSeconds) <= 600)) return send(res, 400, { error: 'graceSeconds must be 0–600' });
      state.graceSec = Number(body.graceSeconds);
    }
    if (body.checkFirst !== undefined) state.checkFirst = !!body.checkFirst;
    saveState();
    alEvent('settings', `grace ${state.graceSec}s, ${state.checkFirst ? 'check first' : 'fast lock'}`);
    send(res, 200, alPublic());
  }],
  ['POST', /^\/v1\/autolock\/near$/, 'phone', (req, res, url, body) => send(res, 200, alNear(String(body.source || url.searchParams.get('source') || 'phone').slice(0, 40)))],
  // The phone thinks you're walking toward the car: sign in now (if the last sign-in is old), so a
  // tap on "Unlock" in its alert goes straight through. Reads only Hyundai's saved copy.
  ['POST', /^\/v1\/prepare$/, 'phone', async (req, res) => send(res, 200, await prepareSignIn('walking to the car'))],
  // dead-man's switch: the phone says "parked", then "still here" every couple of minutes near the car
  ['POST', /^\/v1\/autolock\/parked$/, 'phone', (req, res, url, body) => {
    markActivity();
    watchArm(String(body.source || 'phone').slice(0, 40));
    send(res, 200, alPublic());
    // You've just switched the car off; 99 times out of 100 a walk-away lock follows. Have the
    // sign-in ready so the lock doesn't start with one. Runs after the reply; never delays it.
    if (state.autolockEnabled && !(state.pausedUntil > Date.now())) prepareSignIn('parked').catch(() => {});
  }],
  ['POST', /^\/v1\/autolock\/here$/, 'phone', (req, res) => { watchSeen(); send(res, 200, { ok: true, watching: !!state.watch.armedAt }); }],
  ['POST', /^\/v1\/autolock\/cancel$/, 'phone', (req, res) => send(res, 200, { cancelled: alCancel('api'), ...alPublic() })],
  // single-use link from the notification; the nonce is the credential
  [/^(GET|POST)$/, /^\/v1\/autolock\/cancel\/([0-9a-f]{32})$/, 'none', (req, res, url, body, m) => {
    const ok = AL.nonce && safeEq(m[1], AL.nonce) && alCancel('notification button');
    send(res, ok ? 200 : 410, ok ? 'Auto-lock cancelled for this stop.' : 'Nothing to cancel (already locked, cancelled or expired).');
  }],
  ['POST', /^\/v1\/autolock\/pause$/, 'phone', (req, res, url, body) => {
    const min = Number(body.minutes ?? url.searchParams.get('minutes') ?? 60);
    if (!(min >= 0 && min <= 7 * 24 * 60)) return send(res, 400, { error: 'minutes must be 0–10080' });
    state.pausedUntil = min ? Date.now() + min * 60000 : 0; saveState();
    if (min) alCancel('paused');
    alEvent(min ? 'paused' : 'resumed', min ? `${min} min` : undefined);
    send(res, 200, alPublic());
  }],
  ['POST', /^\/v1\/autolock\/(enable|disable)$/, 'admin', (req, res, url, body, m) => {
    state.autolockEnabled = m[1] === 'enable'; saveState();
    if (!state.autolockEnabled) alCancel('disabled');
    alEvent(m[1] + 'd');
    send(res, 200, alPublic());
  }],
];

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const ip = clientIp(req);
  const route = ROUTES.find(([m, p]) => (m instanceof RegExp ? m.test(req.method) : m === req.method) && p.test(url.pathname));
  try {
    if (!route) return send(res, ROUTES.some(([, p]) => p.test(url.pathname)) ? 405 : 404, { error: 'Not found' });
    const [, pattern, need, handler] = route;
    if (need !== 'none') {
      if (tooManyFailures(ip)) return send(res, 429, { error: 'Too many failed attempts; try later' });
      const scope = scopeOf(req);
      if (!scope) { recordFailure(ip); return send(res, 401, { error: 'Missing or invalid token' }); }
      if (need === 'admin' && scope !== 'admin') return send(res, 403, { error: 'This token cannot do that' });
    }
    const body = ['POST', 'PUT'].includes(req.method) ? await readBody(req) : {};
    await handler(req, res, url, body, pattern.exec(url.pathname));
  } catch (e) {
    const [code, payload] = httpError(e);
    if (!res.headersSent) send(res, code, payload);
  } finally {
    if (url.pathname !== '/healthz') log(`${req.method} ${url.pathname.replace(/cancel\/[0-9a-f]+/, 'cancel/…')} ${res.statusCode} ${ip}`);
  }
});

server.listen(CFG.port, CFG.host, () => {
  log(`ioniq6-api ${VERSION} listening on ${CFG.host}:${CFG.port}`);
  log(`auto-lock ${state.autolockEnabled ? 'on' : 'off'}, grace ${state.graceSec}s, ${state.checkFirst ? 'check first' : 'fast lock'}; backup check ${CFG.guard.intervalMin ? 'every ' + CFG.guard.everyHours + ' h' : 'off'}; locks if the phone goes quiet for ${CFG.watch.silentMin} min after parking; notifications: ${[CFG.ntfy.url && 'ntfy', CFG.telegram.token && 'telegram'].filter(Boolean).join(', ') || 'none'}`);
  if (!CFG.publicUrl) log('PUBLIC_URL not set: notifications will have no "Cancel auto-lock" button');
  if (!bl.loggedIn) log('Not logged in to BlueLink yet: POST /v1/auth/login with the admin token.');
});
if (CFG.guard.intervalMin > 0) {
  setInterval(() => { if (guardDue()) { lastGuardAt = Date.now(); guardTick(); } },
    Math.min(60000, CFG.guard.everyHours * 3600e3)).unref();
}

// ---------------------------------------------------------------- dead-man's switch

function watchArm(source) {
  state.watch = { armedAt: Date.now(), lastSeen: Date.now() };
  saveState();
  alEvent('watching', `parked (${source}); locks if the phone goes quiet for ${CFG.watch.silentMin} min without a lock`);
}
function watchOff(why) {
  if (!state.watch.armedAt) return;
  state.watch = { armedAt: 0, lastSeen: 0 };
  saveState();
  alEvent('watch-off', why);
}
function watchSeen() {
  state.lastNearAt = Date.now();
  if (state.watch.armedAt) state.watch.lastSeen = Date.now();
  saveState();
}
function watchTick() {
  const w = state.watch;
  if (!w.armedAt) return;
  if (known.locked && known.at > w.armedAt) return watchOff('lock confirmed');
  if (Date.now() - w.lastSeen < CFG.watch.silentMin * 60000) return;
  if (!state.autolockEnabled || state.pausedUntil > Date.now()) return watchOff('auto-lock off or paused');
  if (AL.phase !== 'idle') return;   // a lock is already on its way; look again next time
  watchOff('phone went quiet');
  // Not quiet: the phone may be dead, so this one is announced on ntfy.
  alAway('phone went quiet', { graceSec: 0 });
}
setInterval(watchTick, Math.min(30000, CFG.watch.silentMin * 60000)).unref();

process.on('unhandledRejection', (e) => log('unhandled rejection:', e?.stack || e));

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => { log(`${sig}: shutting down`); server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 3000).unref(); });
}
