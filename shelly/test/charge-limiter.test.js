const test = require('node:test');
const assert = require('node:assert/strict');
const { load, adv } = require('./harness');

const ON = ['Switch.Set', { id: 0, on: true }];
const OFF = ['Switch.Set', { id: 0, on: false, toggle_after: 600 }];
const same = (actual, expected) => assert.equal(JSON.stringify(actual), JSON.stringify(expected));

test('startup commands relay on', () => {
  const h = load();
  same(h.calls[0], ON);
});

test('startup starts a passive, endless scan', () => {
  const h = load();
  assert.equal(h.scanOptions.duration_ms, -1);
  assert.equal(h.scanOptions.active, false);
});

test('valid off advert switches off with toggle_after', () => {
  const h = load();
  h.advance(31);
  h.scan(adv({ level: 80, wantOn: false }));
  same(h.last(), OFF);
});

test('off is renewed after 300 s of continued off adverts', () => {
  const h = load();
  h.advance(31);
  h.scan(adv({ wantOn: false }));
  const n = h.offCalls();
  h.keepScanning({ wantOn: false }, 310);
  assert.equal(h.offCalls(), n + 1);
});

test('silence for 120 s switches on', () => {
  const h = load();
  h.advance(31);
  h.scan(adv({ wantOn: false }));
  h.advance(130);
  same(h.last(), ON);
});

test('silence right after startup leaves the relay on without repeating the command', () => {
  const h = load();
  h.advance(600);
  assert.equal(h.calls.length, 1);
});

test('changes are at least 30 s apart', () => {
  const h = load();
  h.advance(31);
  h.scan(adv({ wantOn: false }));
  const n = h.calls.length;
  h.advance(5);
  h.scan(adv({ wantOn: true }));
  assert.equal(h.calls.length, n);
  // The 10 s tick applies the change after the 30 s gap ends.
  h.advance(35);
  same(h.last(), ON);
});

test('an off advert in the first 30 s after startup is applied once the gap has passed', () => {
  const h = load();
  h.advance(5);
  h.scan(adv({ wantOn: false }));
  assert.equal(h.calls.length, 1);
  h.keepScanning({ wantOn: false }, 40);
  same(h.last(), OFF);
});

test('same wanted state does not repeat the on command', () => {
  const h = load();
  const n = h.calls.length;
  h.keepScanning({ wantOn: true }, 200);
  assert.equal(h.calls.length, n);
});

test('jitter between on and off adverts gives one change per 30 s at most', () => {
  const h = load();
  h.advance(31);
  const n = h.calls.length;
  for (let i = 0; i < 6; i++) {
    h.scan(adv({ wantOn: i % 2 === 1 }));
    h.advance(4);
  }
  assert.equal(h.calls.length, n + 1);
});

test('parses a valid advert', () => {
  const h = load();
  same(h.ctx.parseAdvert(adv({ level: 63, wantOn: false, charging: true, seq: 9 })),
    { level: 63, wantOn: false, charging: true, seq: 9 });
});

for (const [name, data] of [
  ['other company id', adv({ company: 0x004c })],
  ['wrong token', adv({ token: [1, 2, 3, 4] })],
  ['wrong version', adv({ version: 2 })],
  ['short payload', adv({ truncate: 5 })],
  ['level over 100', adv({ level: 150 })],
  ['EIR length past end', '\x1f\xff\xff\xff'],
  ['empty', ''],
  ['missing advData', undefined],
]) {
  test('ignores ' + name, () => {
    const h = load();
    assert.equal(h.ctx.parseAdvert(data), null);
    const n = h.calls.length;
    h.advance(31);
    h.scanCallback(2, { advData: data });
    assert.equal(h.calls.length, n);
  });
}

test('non-result scan events are ignored', () => {
  const h = load();
  const n = h.calls.length;
  h.ctx.onScan(0, null);
  h.ctx.onScan(1, null);
  assert.equal(h.calls.length, n);
});

test('says so when Bluetooth is disabled on the device', () => {
  const h = load({ bleEnabled: false });
  assert.ok(h.prints.some((p) => /Bluetooth is disabled/.test(p)));
  assert.equal(h.scanOptions, null);
  same(h.calls[0], ON);
});
