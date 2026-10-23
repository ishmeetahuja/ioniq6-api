// Mock of mybluelink.ca /tods/api for testing ioniq6.js
const http = require('http');
const log = [];
let devicesKnown = new Set();
let tokenN = 0, validTokens = new Set(), pendingTx = {};
let soc = [{ plugType: 0, level: 80 }, { plugType: 1, level: 90 }];
let loginExpire = 86400; let expireNext = false; let odd = { left: 0, status: 503 }; let oddLogin = 0; let cf429 = 0; let cmd429 = 0;
const car = { engine: false, doorLock: false, doorOpen: { frontLeft: 0, frontRight: 0, backLeft: 0, backRight: 0 }, trunkOpen: false };
const notes = []; const calls = {}; const failTx = new Set();
const ok = (result, headers = {}) => ({ code: 200, body: { responseHeader: { responseCode: 0, responseDesc: 'Success' }, result }, headers });
const err = (c, d) => ({ code: 200, body: { responseHeader: { responseCode: 1 }, error: { errorCode: c, errorDesc: d } } });

function handle(ep, h, b) {
  const dev = h.deviceid;
  const authed = () => validTokens.has(h.accesstoken);
  switch (ep) {
    case 'v2/login':
      if (b.password !== 'pw') return err('7404', 'Invalid credentials');
      if (!devicesKnown.has(dev)) return err('7110', 'OTP required');
      validTokens.add('T' + ++tokenN); return ok({ token: { accessToken: 'T' + tokenN, refreshToken: 'R', expireIn: loginExpire } });
    case 'mfa/selverifmeth': return ok({ userInfoUuid: 'U1', emailList: ['me@x.ca'], userPhone: '416-555-0100' });
    case 'mfa/sendotp': return ok({ otpKey: 'K-' + b.otpMethod });
    case 'mfa/validateotp': return b.otpNo === '123456' ? ok({ verifiedOtp: true, otpValidationKey: 'VK' }) : err('7549', 'bad otp');
    case 'mfa/genmfatkn': devicesKnown.add(dev); validTokens.add('T' + ++tokenN); return ok({ token: { accessToken: 'T' + tokenN, refreshToken: 'R', expireIn: 86400 } });
  }
  if (!authed()) return err('7403', 'expired');
  if (expireNext) { expireNext = false; validTokens.clear(); return err('7606', 'IP changed'); }
  switch (ep) {
    case 'vhcllst': return ok({ vehicles: [{ vehicleId: 'V1', nickName: 'Sparky', modelName: 'IONIQ 6', modelYear: '2024', vin: 'KMHM00000000', fuelKindCode: 'E', dtcCount: 0 }] });
    case 'vrfypin': return b.pin === '1234' ? ok({ pAuth: 'PA' }) : err('7445', 'Invalid PIN');
    case 'lstvhclsts': case 'rltmvhclsts': return ok({ status: { lastStatusDate: '20261013131500', engine: car.engine, doorLock: car.doorLock, doorOpen: car.doorOpen, trunkOpen: car.trunkOpen, hoodOpen: false, airCtrlOn: true, airTemp: { value: '0EH', unit: 0 }, defrost: false, steerWheelHeat: 1, sideBackWindowHeat: 0, seatHeaterVentState: { flSeatHeatState: 8, frSeatHeatState: 2 }, battery: { batSoc: 88 }, evStatus: { batteryStatus: 74, batteryCharge: true, batteryPlugin: 2, remainTime2: { atc: { value: 95 } }, drvDistance: [{ rangeByFuel: { evModeRange: { value: 381, unit: 1 } } }] } } });
    case 'nxtsvc': return ok({ maintenanceInfo: { currentOdometer: 18234, currentOdometerUnit: 1 } });
    case 'evc/selsoc': return ok(soc);
    case 'fndmcr': return h.from === 'SPA' && h.referer.endsWith('/remote/') && h.pauth ? ok({ coord: { lat: 43.65, lon: -79.38 }, time: '20261013131000', head: 90 }) : err('6459', 'loc fail');
    case 'evc/setsoc': soc = b.tsoc;
    // fallthrough
    case 'drlck': case 'drulck': case 'evc/rfon': case 'evc/rfoff': case 'evc/rcstrt': case 'evc/rcstp': {
      if (!h.pauth || b.pin !== '1234') return err('7445', 'no pAuth');
      const tid = 'TX' + Math.random().toString(36).slice(2, 8); pendingTx[tid] = 0;
      const anyOpen = Object.values(car.doorOpen).some(Boolean) || car.trunkOpen;
      if (ep === 'drlck' && anyOpen) failTx.add(tid); else if (ep === 'drlck') car.doorLock = true;
      if (ep === 'drulck') car.doorLock = false;
      return ok({}, { transactionId: tid });
    }
    case 'rmtsts': pendingTx[h.transactionid]++; if (pendingTx[h.transactionid] >= 2 && failTx.has(h.transactionid)) return ok({ transaction: { apiResult: 'F', apiStatusCode: 'door open' } }); return ok({ transaction: { apiResult: pendingTx[h.transactionid] >= 2 ? 'C' : 'P', apiStatusCode: 'null' } });
    case 'alerts/maintenance/evTripDetails': return ok({ tripdetails: [{ startdate: '2026-10-12 08:01:00', distance: 23, totalused: 3900, drivetrain: 3100, climate: 500, accessories: 200, batterycare: 100, regen: 900 }] });
  }
  return err('9999', 'unknown ' + ep);
}

http.createServer((req, res) => {
  let data = ''; req.on('data', (d) => (data += d)); req.on('end', () => {
    const ep = req.url.replace('/tods/api/', '');
    let b = {}; try { b = data ? JSON.parse(data) : {}; } catch {}
    if (ep === '__expirein') { loginExpire = b.sec; res.end('ok'); return; }
    if (ep === '__expire') { expireNext = true; res.end('ok'); return; }
    if (req.url === '/') { const n = JSON.parse(data); notes.push({ topic: n.topic, title: n.title, prio: n.priority, actions: n.actions, body: n.message }); res.end('{}'); return; }
    if (ep === '__cmd429') { cmd429 = b.left; res.end('ok'); return; }
    if (ep === '__cf429') { cf429 = b.left; validTokens.clear(); res.end('ok'); return; }
    if (ep === '__oddlogin') { oddLogin = b.left; validTokens.clear(); res.end('ok'); return; }
    if (ep === '__odd') { odd = b; res.end('ok'); return; }
    if (ep === '__car') { Object.assign(car, b); res.end(JSON.stringify(car)); return; }
    if (ep === '__notes') { res.end(JSON.stringify(notes.splice(0))); return; }
    if (ep === '__calls') { res.end(JSON.stringify(calls)); return; }
    if (ep === '__log') { res.end(JSON.stringify({ log, lastBody: global.last })); return; }
    let r = handle(ep, req.headers, b);
    if (ep === 'drlck' && cmd429 > 0) { cmd429--; r = { code: 429, body: { title: 'Error 1015: You are being rate limited', status: 429, error_code: 1015, cloudflare_error: true, retry_after: 1 } }; }
    if (ep === 'v2/login' && cf429 > 0) { cf429--; r = { code: 429, body: { title: 'Error 1015: You are being rate limited', status: 429, error_code: 1015, cloudflare_error: true, retry_after: 2 } }; }
    if (ep === 'v2/login' && oddLogin > 0) { oddLogin--; r = { code: 403, body: { code: 'blocked', message: 'Request blocked' } }; }
    if (ep === 'lstvhclsts' && odd.left > 0) { odd.left--; r = { code: odd.status, body: { message: odd.status === 429 ? 'Too Many Requests' : 'Service Unavailable' } }; }
    log.push(ep); calls[ep] = (calls[ep] || 0) + 1; global.last = { ep, b, h: { from: req.headers.from, offset: req.headers.offset, deviceid: req.headers.deviceid } };
    res.writeHead(r.code, { 'content-type': 'application/json', ...(r.headers || {}) });
    res.end(JSON.stringify(r.body));
  });
}).listen(18080, () => console.log('mock up'));
