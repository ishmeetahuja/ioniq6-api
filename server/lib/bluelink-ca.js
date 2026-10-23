'use strict';
/*
 * Hyundai BlueLink Canada (mybluelink.ca) client. Zero dependencies, Node 18+.
 *
 * Protocol ported from hyundai_kia_connect_api (KiaUvoApiCA.py, Oct 2026), the actively
 * maintained reverse-engineering of the Canadian web portal. Canadian quirks handled here:
 *   - Email/SMS OTP the first time a new "device" logs in (error 7110)
 *   - A stable Deviceid must be reused forever, or every login re-triggers OTP
 *   - Access tokens are bound to the IP that issued them (error 7606) -> silent re-login
 *   - Every remote command needs a short-lived pAuth obtained by verifying the PIN
 *   - Find-my-car needs `from: SPA` + `Referer: /remote/` or it fails with 6459
 *
 * This module never prompts or prints; callers (CLI, HTTP server) handle the UI.
 */

const crypto = require('crypto');

class BlueLinkError extends Error {
  /** code: Hyundai error code ('7404'…) or one of OTP_REQUIRED, NO_OTP, INPUT, NETWORK, HTTP */
  constructor(msg, code) { super(msg); this.code = code; }
}

const AUTH_ERRORS = new Set(['7403', '7602', '7606']); // expired / deleted / IP changed
const ERROR_HINTS = {
  '7402': 'Account locked out — unlock it at mybluelink.ca or in the app.',
  '7404': 'Wrong username or password.',
  '7445': 'Request could not be processed by Hyundai (often a wrong PIN, or the car is asleep / out of coverage).',
  '7710': 'Device ID rejected — forget the device and log in again.',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const get = (o, p) => p.split('.').reduce((a, k) => (a == null ? a : a[k]), o);

// Cabin temperature is an index into 14.0–31.5 °C (0.5 steps), hex + "H" (pre-2020 models: 16.0–31.5).
const TEMP_MAX = 31.5;
const tempMin = (year) => (year >= 2020 ? 14.0 : 16.0);
function tempToHex(c, year = 2023) {
  const min = tempMin(year);
  if (typeof c !== 'number' || Number.isNaN(c) || c < min || c > TEMP_MAX || (c * 2) % 1 !== 0) {
    throw new BlueLinkError(`Temperature must be ${min}–${TEMP_MAX} °C in 0.5 steps (got ${c}).`, 'INPUT');
  }
  return (Math.round((c - min) * 2).toString(16) + 'H').padStart(3, '0').toUpperCase();
}
function hexToTemp(v, year = 2023) {
  if (typeof v !== 'string' || !v.endsWith('H')) return v ?? null;
  return tempMin(year) + parseInt(v.slice(0, -1), 16) / 2;
}
function parseStamp(s) {
  // Hyundai Canada sends UTC without saying so: "20261013131500" -> "2026-10-13T13:15:00Z"
  if (typeof s !== 'string' || !/^\d{12,14}$/.test(s)) return s ?? null;
  const p = (a, b) => s.slice(a, b);
  return `${p(0, 4)}-${p(4, 6)}-${p(6, 8)}T${p(8, 10)}:${p(10, 12)}:${s.length >= 14 ? p(12, 14) : '00'}Z`;
}

const SEAT = { off: 0, 'low-heat': 6, 'med-heat': 7, 'high-heat': 8, 'low-cool': 3, 'med-cool': 4, 'high-cool': 5 };
const SEAT_NAMES = { 0: 'off', 1: 'on', 2: 'off', 3: 'low cool', 4: 'med cool', 5: 'high cool', 6: 'low heat', 7: 'med heat', 8: 'high heat' };

class BlueLinkCA {
  /**
   * @param {object} o
   * @param {{username,password,pin}} o.creds
   * @param {object} [o.session]       persisted state (deviceId, token, vehicle) — mutated in place
   * @param {(session)=>void} [o.saveSession]
   * @param {string} [o.baseUrl]       defaults to https://mybluelink.ca
   * @param {(msg)=>void} [o.debug]
   * @param {number} [o.pollMs]        delay between action-status polls (default 5000)
   */
  constructor({ creds, session, saveSession, baseUrl, debug, warn, pollMs, minCooldownSec, loginRetrySec, minSigninGapSec, onCall, onNote } = {}) {
    if (!creds?.username || !creds?.password || !creds?.pin) {
      throw new BlueLinkError('BlueLink username, password and PIN are all required.', 'INPUT');
    }
    this.creds = { ...creds, pin: String(creds.pin) };
    this.session = session || {};
    this.saveSession = saveSession || (() => {});
    this.host = (baseUrl || 'https://mybluelink.ca').replace(/\/$/, '');
    this.api = this.host + '/tods/api/';
    this.debug = debug || null;
    this.warn = warn || ((m) => console.warn(`[bluelink] ${m}`));
    this.pollMs = pollMs || 5000;
    this.onCall = onCall || null;   // (endpoint, httpStatus | 'network', errorCode) after every request
    this.onNote = onNote || null;   // (text) for facts worth keeping, e.g. how long sign-ins last
    // Back-off: Hyundai sits behind Cloudflare, which bans an IP that keeps knocking.
    this.minCooldownMs = (minCooldownSec ?? 600) * 1000;   // after a 429, stay away at least this long
    this.loginRetryMs = (loginRetrySec ?? 300) * 1000;     // after a failed sign-in, wait before the next
    this.minSigninGapMs = (minSigninGapSec ?? 120) * 1000; // after a sign-in, only a lock may sign in again this soon
    this.signingIn = null;                                 // the sign-in in progress, shared by everyone who needs one
    this.cache = {};                                       // slow-changing reads (service info, charge limits)
    if (!this.session.deviceId) {
      // Generated once and persisted; reusing it is what stops repeat OTP prompts.
      this.session.deviceId = Buffer.from(crypto.randomUUID().replace(/-/g, '')).toString('base64');
      this.save();
    }
  }

  save() { this.saveSession(this.session); }

  headers(extra = {}) {
    return {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
      Accept: 'application/json, text/plain, */*',
      'Accept-Language': 'en-CA,en-US;q=0.8,en;q=0.5,fr;q=0.3',
      'Content-Type': 'application/json;charset=UTF-8',
      from: 'CWP',
      offset: String(-new Date().getTimezoneOffset() / 60),
      language: '0',
      Origin: this.host,
      Referer: this.host + '/login',
      'Sec-Fetch-Dest': 'empty',
      'Sec-Fetch-Mode': 'cors',
      'Sec-Fetch-Site': 'same-origin',
      Pragma: 'no-cache',
      'Cache-Control': 'no-cache',
      client_id: 'HATAHSPACA0232141ED9722C67715A0B',
      client_secret: 'CLISCR01AHSPA',
      Deviceid: this.session.deviceId,
      ...extra,
    };
  }

  /** ISO time until which we don't contact Hyundai (rate-limited), or null. */
  get cooldownUntil() {
    const t = this.session.cooldownUntil || 0;
    return t > Date.now() ? new Date(t).toISOString() : null;
  }

  coolDown(seconds, why, { escalate = 0 } = {}) {
    // Two clocks: Cloudflare's own wait (retry_after, at least 30 s), and our longer, polite pause
    // for everything that can wait. A lock (urgent) only has to respect the first.
    // escalate = how many times in a row this was refused: each refusal doubles both pauses
    // (polite: 10, 20, 40, 80, 120 min; urgent: 30 s … 15 min), because knocking again while
    // Cloudflare is still blocking us only keeps the block going.
    const k = escalate > 1 ? 2 ** Math.min(escalate - 1, 6) : 1;
    const hardFloor = Math.min(30_000, this.minCooldownMs);
    const hard = Math.max(Math.min(hardFloor * k, 15 * 60_000), (Number(seconds) || 0) * 1000);
    const soft = Math.max(Math.min(this.minCooldownMs * k, 12 * this.minCooldownMs), hard);
    this.session.hardCooldownUntil = Date.now() + hard;
    this.session.cooldownUntil = Date.now() + soft;
    this.save();
    this.warn(`${why}: not contacting Hyundai until ${new Date(this.session.cooldownUntil).toISOString()} (locks may retry after ${new Date(this.session.hardCooldownUntil).toISOString()})`);
  }

  /** Runs fn as urgent (locking the car): allowed again as soon as Cloudflare's own wait is over. */
  async urgent(fn) {
    const was = this.isUrgent;
    this.isUrgent = true;
    try { return await fn(); } finally { this.isUrgent = was; }
  }

  /** ms timestamp until which this call may not contact Hyundai, or 0. */
  blockedUntil() {
    const t = (this.isUrgent ? this.session.hardCooldownUntil : this.session.cooldownUntil) || 0;
    return t > Date.now() ? t : 0;
  }

  rateLimitError(untilMs = this.session.cooldownUntil) {
    const until = new Date(untilMs);
    const hhmm = until.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: process.env.TZ || 'America/Toronto' });
    const e = new BlueLinkError(`Hyundai is rate-limiting this server, so it's pausing requests until about ${hhmm}. It recovers on its own${this.isUrgent ? '' : '; walk-away locks still go through'}.`, 'RATE_LIMIT');
    e.until = until.toISOString();
    return e;
  }

  async raw(endpoint, { body, headers = {} } = {}) {
    const blocked = this.blockedUntil();
    if (blocked) throw this.rateLimitError(blocked);
    let lastErr;
    for (let attempt = 0, delay = 2000; attempt < 4; attempt++, delay *= 2) {
      try {
        const res = await fetch(this.api + endpoint, {
          method: 'POST',
          headers: this.headers(headers),
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: AbortSignal.timeout(45000),
        });
        const text = await res.text();
        let json;
        try { json = JSON.parse(text); } catch { json = undefined; }
        try { this.onCall?.(endpoint, res.status, json?.error?.errorCode); } catch {}
        if (res.status === 429) {
          const ra = (json && (json.retry_after ?? json.retryAfter)) || res.headers.get('retry-after');
          if (json) this.warn(`${endpoint}: unexpected reply (HTTP 429) ${BlueLinkCA.mask(json).slice(0, 500)}`);
          const refusals = endpoint === 'v2/login' ? (this.session.loginRefusals = (this.session.loginRefusals || 0) + 1) : 0;
          this.coolDown(ra, `${endpoint}: rate-limited by Hyundai (HTTP 429${refusals ? `, sign-in refused ${refusals}× in a row` : ''}; retry_after ${ra ?? 'not given'})`, { escalate: refusals });
          throw this.rateLimitError(this.blockedUntil() || this.session.cooldownUntil);
        }
        if (json === undefined) {
          if (res.status === 403 || /cloudflare|cf-ray/i.test(text)) {
            throw new BlueLinkError(`Blocked by Hyundai's firewall (HTTP ${res.status}). Try again later or from another network.`, 'HTTP');
          }
          throw new BlueLinkError(`Unexpected HTTP ${res.status} from ${endpoint}: ${text.slice(0, 200)}`, 'HTTP');
        }
        if (this.debug) {
          const safe = JSON.stringify(json).replace(/"(accessToken|refreshToken|pAuth|otpKey|otpValidationKey)":"[^"]+"/g, '"$1":"***"');
          this.debug(`${endpoint} -> ${safe.slice(0, 2000)}`);
        }
        return { json, headers: res.headers, status: res.status };
      } catch (e) {
        if (e instanceof BlueLinkError) throw e;
        lastErr = e;
        try { this.onCall?.(endpoint, 'network', null); } catch {}
        if (this.debug) this.debug(`${endpoint} network error: ${e.message}; retrying`);
        await sleep(delay);
      }
    }
    throw new BlueLinkError(`Network error talking to ${this.host}: ${lastErr && (lastErr.cause?.message || lastErr.message)}`, 'NETWORK');
  }

  /** Hyundai answered, but not with its usual success/error envelope (gateway error, rate limit…). */
  static isOddReply(json) {
    return get(json, 'responseHeader.responseCode') !== 0 && !(json && typeof json.error === 'object' && json.error && json.error.errorCode);
  }

  static mask(json) {
    return JSON.stringify(json).replace(/"(accessToken|refreshToken|pAuth|otpKey|otpValidationKey|password|pin)":"[^"]*"/g, '"$1":"***"');
  }

  /** check(), but log (masked) any reply that isn't Hyundai's usual envelope first. */
  verify(res, endpoint) {
    if (BlueLinkCA.isOddReply(res.json)) this.warn(`${endpoint}: unexpected reply (HTTP ${res.status}) ${BlueLinkCA.mask(res.json).slice(0, 500)}`);
    BlueLinkCA.check(res.json, { endpoint, status: res.status });
  }

  static check(json, { endpoint = '', status } = {}) {
    if (get(json, 'responseHeader.responseCode') === 0) return;
    const err = json && typeof json.error === 'object' && json.error ? json.error : null;
    if (err && err.errorCode) {
      const ec = String(err.errorCode);
      const desc = err.errorDesc || ERROR_HINTS[ec] || 'Hyundai reported an error';
      throw new BlueLinkError(ERROR_HINTS[ec] && err.errorDesc ? `${desc} (${ec}) — ${ERROR_HINTS[ec]}` : `${desc} (${ec})`, ec);
    }
    // Not Hyundai's usual envelope: say what we actually got.
    const desc = get(json, 'responseHeader.responseDesc') || json?.message || json?.error || json?.errorMessage;
    const http = status && status !== 200 ? `HTTP ${status}` : null;
    const what = [http, typeof desc === 'string' ? desc : null].filter(Boolean).join(': ');
    const hint = status === 429 ? ' Hyundai is rate-limiting requests; wait a few minutes.' : '';
    throw new BlueLinkError(`Hyundai gave an unexpected answer${endpoint ? ' to ' + endpoint : ''}${what ? ' (' + what + ')' : ''}.${hint}`, status === 429 ? 'RATE_LIMIT' : 'ODD_REPLY');
  }

  // ------------------------------------------------------------ auth

  /** @returns {Promise<{ok:true}|{otpRequired:true,email:string,phone:string|null}>} */
  async login() {
    delete this.session.accessToken;
    const login = await this.raw('v2/login', { body: { loginId: this.creds.username, password: this.creds.password } });
    const { json } = login;
    if (json.responseHeader?.responseCode === 1 && json.error?.errorCode === '7110') {
      const sel = await this.raw('mfa/selverifmeth', { body: { mfaApiCode: '0107', userAccount: this.creds.username } });
      this.verify(sel, 'mfa/selverifmeth');
      const r = sel.json.result || {};
      this.session.pendingMfa = {
        userInfoUuid: r.userInfoUuid,
        email: (r.emailList && r.emailList[0]) || this.creds.username,
        phone: r.userPhone || null,
      };
      this.save();
      return { otpRequired: true, email: this.session.pendingMfa.email, phone: this.session.pendingMfa.phone };
    }
    this.verify(login, 'v2/login');
    this.storeToken(json.result.token);
    return { ok: true };
  }

  /** Send the verification code. method: 'email' | 'sms'. */
  async sendOtp(method = 'email') {
    let m = this.session.pendingMfa;
    if (!m) {
      const r = await this.login();
      if (r.ok) return { alreadyLoggedIn: true };
      m = this.session.pendingMfa;
    }
    if (method === 'sms' && !m.phone) throw new BlueLinkError('No phone number on this BlueLink account; use email.', 'INPUT');
    const body = method === 'sms'
      ? { otpMethod: 'S', mfaApiCode: '0107', userAccount: m.email, userPhone: m.phone, userInfoUuid: m.userInfoUuid }
      : { otpMethod: 'E', mfaApiCode: '0107', userAccount: m.email, userPhone: '', userInfoUuid: m.userInfoUuid };
    const sent = await this.raw('mfa/sendotp', { body });
    this.verify(sent, 'mfa/sendotp');
    const { json } = sent;
    this.session.pendingOtp = { otpKey: json.result.otpKey, email: m.email, at: Date.now() };
    this.save();
    return { via: method, to: method === 'sms' ? m.phone : m.email };
  }

  async verifyOtp(code) {
    const p = this.session.pendingOtp;
    if (!p) throw new BlueLinkError('No verification code has been sent yet.', 'NO_OTP');
    const v = await this.raw('mfa/validateotp', {
      body: { otpNo: String(code).trim(), userAccount: this.creds.username, otpKey: p.otpKey, mfaApiCode: '0107' },
    });
    this.verify(v, 'mfa/validateotp');
    if (!v.json.result?.verifiedOtp) throw new BlueLinkError('That code was not accepted.', '7549');
    const t = await this.raw('mfa/genmfatkn', {
      body: { userAccount: this.creds.username, otpEmail: p.email, mfaApiCode: '0107', otpValidationKey: v.json.result.otpValidationKey, mfaYn: 'Y' },
    });
    this.verify(t, 'mfa/genmfatkn');
    delete this.session.pendingOtp;
    delete this.session.pendingMfa;
    this.storeToken(t.json.result.token);
    return { ok: true };
  }

  forgetDevice() {
    for (const k of Object.keys(this.session)) delete this.session[k];
    this.session.deviceId = Buffer.from(crypto.randomUUID().replace(/-/g, '')).toString('base64');
    this.save();
  }

  logout() {
    delete this.session.accessToken;
    delete this.session.expiresAt;
    this.save();
  }

  storeToken(tok) {
    this.session.accessToken = tok.accessToken;
    this.session.expiresAt = Date.now() + (Number(tok.expireIn) - 60) * 1000;
    this.session.signedInAt = Date.now();
    delete this.session.loginRefusals;
    this.save();
    this.warn(`signed in; Hyundai says this sign-in lasts ${Math.round(Number(tok.expireIn) / 60)} min`);
    try { this.onNote?.(`sign-in lasts ${Math.round(Number(tok.expireIn) / 60)}min`); } catch {}
  }

  /** We hold a session token. (Whether Hyundai still accepts it is only known by using it.) */
  get loggedIn() { return !!this.session.accessToken; }

  /**
   * Minutes left on the current sign-in, from what Hyundai said when it was made (expireIn).
   * Costs no request — and so nothing against Hyundai's limits. Hyundai can still end a sign-in
   * early; call() then signs in again. null = no sign-in, or no expiry on record.
   */
  signInMinutesLeft() {
    if (!this.session.accessToken || !this.session.expiresAt) return null;
    return Math.max(0, (this.session.expiresAt - Date.now()) / 60000);
  }

  async ensureAuth() {
    // Sign in only when there's no token at all. An expired or rejected token is handled in
    // call(), which signs in again only when Hyundai says so (7403/7602/7606) — far fewer
    // sign-ins than renewing on a timer, which matters behind Cloudflare's rate limiting.
    if (this.session.accessToken) return;
    await this.relogin();
  }

  /**
   * Sign in again. Never two at once: whoever asks while a sign-in is running waits for that one.
   * And except for a lock, never again within minSigninGapMs of the last one — sign-ins are what
   * Hyundai rate-limits, and a sign-in that's seconds old being "expired" means something else is
   * wrong (which another sign-in won't fix).
   */
  async relogin() {
    if (this.signingIn) return this.signingIn;
    const since = Date.now() - (this.session.signedInAt || 0);
    if (!this.isUrgent && since < this.minSigninGapMs) {
      throw new BlueLinkError(`Signed in to Hyundai ${Math.round(since / 1000)} s ago; not signing in again so soon.`, 'LOGIN_WAIT');
    }
    this.signingIn = this.doRelogin();
    try { return await this.signingIn; } finally { this.signingIn = null; }
  }

  async doRelogin() {
    // Canada has no refresh endpoint; a plain re-login with the persisted Deviceid
    // does not normally re-trigger OTP.
    const lastFail = this.session.loginFailedAt || 0;
    if (Date.now() - lastFail < this.loginRetryMs) {
      const mins = Math.ceil((this.loginRetryMs - (Date.now() - lastFail)) / 60000);
      throw new BlueLinkError(`Signing in to Hyundai failed a moment ago; trying again in about ${mins} min.`, 'LOGIN_WAIT');
    }
    let r;
    try {
      r = await this.login();
    } catch (e) {
      if (e.code !== 'RATE_LIMIT' && e.code !== 'NETWORK') { this.session.loginFailedAt = Date.now(); this.save(); }
      throw e;
    }
    delete this.session.loginFailedAt;
    this.save();
    if (r.otpRequired) {
      throw new BlueLinkError('Hyundai needs a one-time verification code for this device.', 'OTP_REQUIRED');
    }
  }

  /** Authenticated call with one automatic re-login on token-expired / IP-changed. */
  async call(endpoint, opts = {}, { allowFail = false } = {}) {
    await this.ensureAuth();
    const go = () => this.raw(endpoint, {
      ...opts,
      headers: {
        accessToken: this.session.accessToken,
        ...(this.session.vehicleId ? { vehicleId: this.session.vehicleId } : {}),
        ...(opts.headers || {}),
      },
    });
    let used = this.session.accessToken;
    let res = await go();
    if (res.json.responseHeader?.responseCode === 1 && AUTH_ERRORS.has(String(res.json.error?.errorCode))
        && this.session.accessToken && this.session.accessToken !== used) {
      // Someone else signed in while this request was out: use that sign-in, don't make another.
      used = this.session.accessToken;
      res = await go();
    }
    if (res.json.responseHeader?.responseCode === 1 && AUTH_ERRORS.has(String(res.json.error?.errorCode))) {
      const age = this.session.signedInAt ? Math.round((Date.now() - this.session.signedInAt) / 60000) : null;
      this.warn(`Hyundai ended the sign-in (${res.json.error?.errorCode})${age !== null ? ` after ${age} min` : ''}; signing in again`);
      try { this.onNote?.(`sign-in ended after ${age ?? '?'}min (${endpoint})`); } catch {}
      await this.relogin();
      res = await go();
    }
    if (BlueLinkCA.isOddReply(res.json)) {
      // Log it (masked) so `docker compose logs api` shows what Hyundai sent, then retry once.
      this.warn(`${endpoint}: unexpected reply (HTTP ${res.status}) ${BlueLinkCA.mask(res.json).slice(0, 500)}`);
      if (!allowFail) {
        await sleep(res.status === 429 ? 5000 : 2000);
        res = await go();
        if (BlueLinkCA.isOddReply(res.json)) this.warn(`${endpoint}: still unexpected after retry (HTTP ${res.status}) ${BlueLinkCA.mask(res.json).slice(0, 500)}`);
      }
    }
    if (!allowFail) BlueLinkCA.check(res.json, { endpoint, status: res.status });
    return res;
  }

  // ------------------------------------------------------------ vehicles & reads

  async vehicles() {
    const { json } = await this.call('vhcllst');
    return json.result.vehicles || [];
  }

  async selectVehicle(vin) {
    if (this.session.vehicleId && (!vin || this.session.vin === vin)) return this.vehicleInfo();
    const saved = this.session.vehicleId;
    delete this.session.vehicleId;
    const list = await this.vehicles();
    if (!list.length) throw new BlueLinkError('No vehicles on this BlueLink account.', 'INPUT');
    const v = vin ? list.find((x) => x.vin === vin) : (list.find((x) => /IONIQ\s*6/i.test(x.modelName || '')) || list[0]);
    if (!v) { this.session.vehicleId = saved; throw new BlueLinkError(`VIN ${vin} not found on this account.`, 'INPUT'); }
    Object.assign(this.session, {
      vehicleId: v.vehicleId, vin: v.vin, nickName: v.nickName, model: v.modelName,
      year: parseInt(v.modelYear, 10) || 2023, fuel: v.fuelKindCode,
    });
    this.save();
    return this.vehicleInfo();
  }

  vehicleInfo() {
    const s = this.session;
    return { vin: s.vin, model: s.model, year: s.year, nickname: s.nickName };
  }

  /** Raw vehicle status object. refresh=true wakes the car for live data. */
  async rawStatus({ refresh = false } = {}) {
    const { json } = await this.call(refresh ? 'rltmvhclsts' : 'lstvhclsts');
    return json.result.status;
  }

  /** Reads something that changes rarely, at most once per maxAgeMs. */
  async cached(key, maxAgeMs, fn) {
    const c = this.cache[key];
    if (c && Date.now() - c.at < maxAgeMs) return c.value;
    try {
      const value = await fn();
      this.cache[key] = { at: Date.now(), value };
      return value;
    } catch (e) {
      if (c) return c.value;   // stale is better than nothing for these
      throw e;
    }
  }

  async status({ refresh = false } = {}) {
    const s = await this.rawStatus({ refresh });
    const svc = await this.cached('svc', 6 * 3600e3, async () => (await this.call('nxtsvc', {}, { allowFail: true })).json.result?.maintenanceInfo || {}).catch(() => ({}));
    const soc = await this.cached('soc', 30 * 60e3, async () => (await this.call('evc/selsoc', {}, { allowFail: true })).json.result).catch(() => null);
    return summarize({ s, svc, soc }, this.session);
  }

  async location() {
    const pAuth = await this.pAuth({ fresh: true });
    const { json } = await this.call('fndmcr', {
      body: { pin: this.creds.pin },
      headers: { pAuth, from: 'SPA', Referer: this.host + '/remote/' },
    });
    const r = json.result || {};
    const lat = get(r, 'coord.lat'), lon = get(r, 'coord.lon');
    if (lat == null) throw new BlueLinkError('Hyundai returned no location.', 'HTTP');
    return { lat, lon, time: parseStamp(r.time), heading: r.head ?? null, speed: get(r, 'speed.value') ?? null, maps: `https://maps.google.com/?q=${lat},${lon}` };
  }

  async chargeLimits() {
    delete this.cache.soc;
    const { json } = await this.call('evc/selsoc');
    const list = Array.isArray(json.result) ? json.result : (json.result?.tsoc || []);
    const cur = (t) => { const x = list.filter((l) => l.plugType === t).pop(); return x ? x.level : null; };
    return { ac: cur(1), dc: cur(0) };
  }

  async trips() {
    const { json } = await this.call('alerts/maintenance/evTripDetails');
    return json.result?.tripdetails || [];
  }

  // ------------------------------------------------------------ commands

  /**
   * PIN token. Each command gets a fresh one; the status polls that follow reuse it, because
   * sending the PIN again on every poll multiplied our request count for nothing.
   */
  async pAuth({ fresh = false } = {}) {
    const c = this._pAuth;
    if (!fresh && c && Date.now() - c.at < 4 * 60_000) return c.value;
    const { json } = await this.call('vrfypin', { body: { pin: this.creds.pin } });
    if (!json.result?.pAuth) throw new BlueLinkError('PIN verification failed — check your 4-digit BlueLink PIN.', '7445');
    this._pAuth = { value: json.result.pAuth, at: Date.now() };
    return json.result.pAuth;
  }

  /**
   * Remote commands return a transactionId header; poll rmtsts until the car answers.
   * @returns {Promise<{status:'success'|'failed'|'timeout'|'sent', transactionId:string, detail?:string}>}
   */
  async command(endpoint, body, { wait = true, timeout = 120, extraHeaders = {}, onProgress } = {}) {
    const pAuth = await this.pAuth({ fresh: true });
    const { headers } = await this.call(endpoint, { body: { ...body, pin: this.creds.pin }, headers: { pAuth, ...extraHeaders } });
    const tid = headers.get('transactionId');
    if (!tid) throw new BlueLinkError('Hyundai accepted the request but returned no transaction id.', 'HTTP');
    if (!wait) return { status: 'sent', transactionId: tid };

    // Ask how it went at a gentle, slowing pace (5 s, 7, 9, then every 10 s): the car usually
    // answers within 10–30 s, and fewer requests keep us clear of Hyundai's rate limits.
    const deadline = Date.now() + timeout * 1000;
    for (let n = 0; Date.now() < deadline; n++) {
      await sleep(Math.min(this.pollMs * (1 + 0.4 * n), Math.max(this.pollMs, 10_000)));
      const poll = (p) => this.call('rmtsts', { headers: { transactionId: tid, pAuth: p } }, { allowFail: true });
      let { json } = await poll(await this.pAuth());
      if (json.responseHeader?.responseCode === 1) {
        // Maybe just our reused PIN token expiring: try once with a fresh one before calling it failed.
        ({ json } = await poll(await this.pAuth({ fresh: true })));
      }
      if (json.responseHeader?.responseCode === 1) {
        return { status: 'failed', transactionId: tid, detail: json.error?.errorDesc || json.error?.errorCode };
      }
      const tx = json.result?.transaction || {};
      if (tx.apiResult === 'C') return { status: 'success', transactionId: tid };
      if (tx.apiResult !== 'P') return { status: 'failed', transactionId: tid, detail: tx.apiStatusCode };
      if (onProgress) onProgress();
    }
    return { status: 'timeout', transactionId: tid };
  }

  get isEv() { return this.session.fuel !== 'G'; }

  lock(o) { return this.command('drlck', {}, o); }
  unlock(o) { return this.command('drulck', {}, o); }
  stopClimate(o) { return this.command(this.isEv ? 'evc/rfoff' : 'rmtstp', {}, o); }
  startCharge(o) { return this.command('evc/rcstrt', {}, o); }
  stopCharge(o) { return this.command('evc/rcstp', {}, o); }

  /**
   * @param {object} c  { temp=21, duration=10, defrost, steering, rearWindow,
   *                      seats: { driver, passenger, rearLeft, rearRight } with SEAT level names }
   */
  startClimate(c = {}, o) {
    const { endpoint, body, temp, duration } = this.climatePayload(c);
    return this.command(endpoint, body, o).then((r) => ({ ...r, temp, duration }));
  }

  /** Builds and validates the climate request (throws BlueLinkError 'INPUT' on bad values). */
  climatePayload(c = {}) {
    const temp = c.temp !== undefined ? Number(c.temp) : 21;
    const duration = c.duration !== undefined ? Number(c.duration) : 10;
    if (!(duration >= 1 && duration <= 30)) throw new BlueLinkError('Duration must be 1–30 minutes.', 'INPUT');
    const seats = c.seats || {};
    const seat = (k) => {
      if (seats[k] === undefined || seats[k] === null) return 0;
      if (!(seats[k] in SEAT)) throw new BlueLinkError(`Seat level must be one of: ${Object.keys(SEAT).join(', ')}`, 'INPUT');
      return SEAT[seats[k]];
    };
    const heating = c.steering && c.rearWindow ? 1 : c.rearWindow ? 2 : c.steering ? 3 : 0;
    const hvac = {
      airCtrl: 1,
      defrost: !!c.defrost,
      heating1: heating,
      airTemp: { value: tempToHex(temp, this.session.year || 2023), unit: 0, hvacTempType: 1 },
      igniOnDuration: duration,
      seatHeaterVentCMD: {
        drvSeatOptCmd: seat('driver'), astSeatOptCmd: seat('passenger'),
        rlSeatOptCmd: seat('rearLeft'), rrSeatOptCmd: seat('rearRight'),
      },
    };
    const body = this.isEv
      ? (/IONIQ\s*9/i.test(this.session.model || '') ? { remoteControl: hvac } : { hvacInfo: hvac })
      : { setting: { ...hvac, ims: 0, airTemp: { ...hvac.airTemp, hvacTempType: 0 } } };
    return { endpoint: this.isEv ? 'evc/rfon' : 'rmtstrt', body, temp, duration };
  }

  async setChargeLimits({ ac, dc }, o = {}) {
    const cur = (ac === undefined || dc === undefined) ? await this.chargeLimits() : {};
    ac = ac !== undefined ? Number(ac) : cur.ac;
    dc = dc !== undefined ? Number(dc) : cur.dc;
    for (const [n, v] of [['ac', ac], ['dc', dc]]) {
      if (!(v >= 50 && v <= 100 && v % 10 === 0)) throw new BlueLinkError(`${n} limit must be 50–100 in steps of 10 (got ${v}).`, 'INPUT');
    }
    delete this.cache.soc;
    const r = await this.command('evc/setsoc',
      { tsoc: [{ plugType: 0, level: dc }, { plugType: 1, level: ac }] },
      { ...o, extraHeaders: { from: 'SPA', Referer: this.host + '/remote/' } });
    return { ...r, ac, dc };
  }
}

// ---------------------------------------------------------------- status summary

function summarize({ s, svc = {}, soc }, session = {}) {
  const ev = s.evStatus || {};
  const range = get(ev, 'drvDistance.0.rangeByFuel.evModeRange') || get(ev, 'drvDistance.0.rangeByFuel.totalAvailableRange') || {};
  const limits = Array.isArray(soc?.tsoc || soc) ? (soc.tsoc || soc) : [];
  const lim = (t) => { const x = limits.filter((l) => l.plugType === t).pop(); return x ? x.level : null; };
  const seats = s.seatHeaterVentState || {};
  return {
    vehicle: `${session.year || ''} ${session.model || 'IONIQ 6'}${session.nickName ? ' "' + session.nickName + '"' : ''}`.trim(),
    vin: session.vin,
    updated: parseStamp(s.lastStatusDate),
    battery: ev.batteryStatus ?? null,
    range_km: range.value ?? null,
    plugged_in: !!ev.batteryPlugin,
    charging: !!ev.batteryCharge,
    charge_time_left_min: ev.batteryCharge ? (get(ev, 'remainTime2.atc.value') ?? null) : null,
    charge_limit_ac: lim(1),
    charge_limit_dc: lim(0),
    car_on: !!s.engine,
    locked: s.doorLock,
    doors_open: Object.entries(s.doorOpen || {}).filter(([, v]) => v).map(([k]) => k),
    trunk_open: !!s.trunkOpen,
    frunk_open: !!s.hoodOpen,
    windows_open: Object.entries(s.windowOpen || {}).filter(([, v]) => v).map(([k]) => k),
    climate_on: !!s.airCtrlOn,
    climate_set_c: s.airTemp ? hexToTemp(s.airTemp.value, session.year || 2023) : null,
    defrost: !!s.defrost,
    steering_heat: !!s.steerWheelHeat,
    rear_window_heat: !!s.sideBackWindowHeat,
    seat_driver: SEAT_NAMES[seats.flSeatHeatState] ?? null,
    seat_passenger: SEAT_NAMES[seats.frSeatHeatState] ?? null,
    aux_12v_percent: get(s, 'battery.batSoc') ?? null,
    odometer_km: svc.currentOdometer ?? null,
    tire_warning: !!get(s, 'tirePressureLamp.tirePressureLampAll'),
    washer_fluid_low: !!s.washerFluidStatus,
  };
}

module.exports = { BlueLinkCA, BlueLinkError, summarize, tempToHex, hexToTemp, parseStamp, SEAT, get };
