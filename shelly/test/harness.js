// Runs shelly/charge-limiter.js inside a Node vm with stand-ins for the Shelly script API
// and a clock the tests control.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SCRIPT = path.join(__dirname, '..', 'charge-limiter.js');
const TOKEN = [0x6c, 0xe2, 0x9d, 0x77];

function load(options) {
  options = options || {};
  const h = { calls: [], prints: [], nowMs: 0, timers: [], scanCallback: null, scanOptions: null };

  const context = {
    print: function () { h.prints.push(Array.prototype.slice.call(arguments).join(' ')); },
    Shelly: {
      call: function (method, params, cb) { h.calls.push([method, params]); if (cb) cb({}, 0, ''); },
      getUptimeMs: function () { return h.nowMs; },
      getComponentConfig: function () { return { enable: options.bleEnabled !== false }; },
    },
    Timer: {
      set: function (ms, repeat, cb) {
        const t = { ms: ms, repeat: repeat, cb: cb, due: h.nowMs + ms };
        h.timers.push(t);
        return t;
      },
      clear: function (t) { h.timers = h.timers.filter(function (x) { return x !== t; }); },
    },
    BLE: {
      Scanner: {
        SCAN_START: 0, SCAN_STOP: 1, SCAN_RESULT: 2, INFINITE_SCAN: -1,
        subscribe: function (cb) { h.scanCallback = cb; },
        start: function (opts) { h.scanOptions = opts; return opts; },
        isRunning: function () { return h.scanOptions !== null; },
      },
    },
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(SCRIPT, 'utf8'), context, { filename: SCRIPT });
  h.ctx = context;

  h.advance = function (seconds) {
    const end = h.nowMs + seconds * 1000;
    for (;;) {
      const next = h.timers.filter(function (t) { return t.due <= end; })
        .sort(function (a, b) { return a.due - b.due; })[0];
      if (!next) break;
      h.nowMs = next.due;
      if (next.repeat) next.due += next.ms; else context.Timer.clear(next);
      next.cb();
    }
    h.nowMs = end;
  };
  h.scan = function (advData) { h.scanCallback(2, { addr: 'aa:bb:cc:dd:ee:ff', rssi: -50, advData: advData }); };
  // A matching advert every 5 s for the given number of seconds.
  h.keepScanning = function (fields, seconds) {
    for (let s = 0; s < seconds; s += 5) { h.scan(adv(fields)); h.advance(5); }
  };
  h.last = function () { return h.calls[h.calls.length - 1]; };
  h.offCalls = function () { return h.calls.filter(function (c) { return c[1].on === false; }).length; };
  return h;
}

// Builds raw advertising data: a flags block followed by a manufacturer-data block.
function adv(fields) {
  const f = Object.assign({ company: 0xffff, token: TOKEN, version: 1, level: 50, wantOn: true, charging: false, seq: 0 }, fields);
  let payload = f.token.concat([f.version, f.level, (f.wantOn ? 1 : 0) | (f.charging ? 2 : 0), f.seq]);
  if (f.truncate !== undefined) payload = payload.slice(0, f.truncate);
  const mfr = [0xff, f.company & 0xff, f.company >> 8].concat(payload);
  const bytes = [2, 0x01, 0x06, mfr.length].concat(mfr);
  return String.fromCharCode.apply(null, bytes);
}

module.exports = { load: load, adv: adv, TOKEN: TOKEN };
