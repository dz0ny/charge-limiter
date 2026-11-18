# BLE Charge Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Java Android app broadcasts battery level and wanted charger state over BLE; a script on a stock-firmware Shelly 1 Gen4 switches the charger relay to match.

**Architecture:** One-way BLE advertising. The app (foreground service) owns the threshold policy and puts an 8-byte payload in manufacturer data. The Shelly script scans passively, validates the payload, drives the relay, and fails safe to "charger on" through both a silence timeout and the firmware's `toggle_after` timer.

**Tech Stack:** Java 21 (Temurin via mise), Gradle 8.14.5 (mise), Android Gradle Plugin 8.13.x, Android SDK 36 (mise `android-sdk`), JUnit 4, Shelly Script, Node 24 (script unit tests only).

**Spec:** `docs/superpowers/specs/2026-11-18-ble-charge-limiter-design.md`

## Global Constraints

- Toolchains are installed with mise only, pinned in a project `mise.toml`. No Homebrew, no Android Studio.
- Java only. No Kotlin, no Compose, no AndroidX beyond what AGP forces. Single Gradle module `app`.
- Package `app.chargelimit`. minSdk 26, compileSdk/targetSdk 36.
- No GATT connection, no pairing, no network permission in the app.
- Advert: manufacturer data, company ID `0xFFFF`, 8-byte payload: bytes 0–3 token, 4 version = `1`, 5 battery 0–100, 6 flags (bit 0 wanted-on, bit 1 phone-charging), 7 sequence counter.
- Token: 4 bytes, generated once in Task 1 with `openssl rand -hex 4`, the same constant in `AdvertPayload.TOKEN` and the script's `CONFIG.token`.
- Policy defaults: low 20, high 80; initial wanted state on.
- Script timings: `toggle_after` T = 600 s, renew every 300 s, silence timeout 120 s, minimum 30 s between relay changes.
- Shelly Script dialect: `var` only, no arrow functions, no template strings, no destructuring.
- The phone is identified by payload only, never by MAC address.
- Steps marked **[HARDWARE]** need the user's phone on `adb` and/or the Shelly reachable. If they are not available, stop that step, record it as not verified, and carry on with steps that do not depend on it.

## Review Focus

1. **Thresholds entered wrong** (low ≥ high, empty, > 100, negative): the app rejects the input and keeps the previous values. Test in Task 2 (`ChargePolicy.validRange`).
2. **Foreign BLE adverts** (other company ID, short payload, right company ID with wrong token or version, malformed EIR lengths): the script ignores them and never throws. Test in Task 3.
3. **Bluetooth switched off or no advertiser** while the service runs: the service reports "not broadcasting", does not crash, and resumes when Bluetooth comes back. Covered in Task 4 step 5 (manual) and the `ACTION_STATE_CHANGED` handling step.
4. **Battery level jitter around a threshold** (79→80→79→80): one relay change, not four. Test in Task 2 (hysteresis) and Task 3 (30 s minimum gap).
5. **Shelly power-cycle or script restart with the phone absent**: relay ends up on. Test in Task 3 (startup state) and Task 6 soak.

---

### Task 1: Toolchain, skeleton, proof of approach

Proves the approach before anything else is built. If step 8 fails, stop and report: the spec's fallback (GATT commands) needs a new plan.

**Files:**
- Create: `mise.toml`, `.gitignore`, `settings.gradle`, `build.gradle`, `gradle.properties`, `app/build.gradle`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/app/chargelimit/AdvertPayload.java`
- Create: `app/src/main/java/app/chargelimit/MainActivity.java`
- Create: `shelly/probe.js`
- Create: `docs/findings.md`

**Interfaces:**
- Produces: `AdvertPayload.COMPANY_ID` (`int`, `0xFFFF`), `AdvertPayload.TOKEN` (`byte[4]`), a buildable `./gradlew assembleDebug`, and `docs/findings.md` recording phone model, Android version, Shelly firmware version, and whether `filters` are supported.

- [ ] **Step 1: Initialise git and pin toolchains**

`git init`. `mise.toml` pins `java = "temurin-21"`, `gradle = "8.14.5"`, `node = "24"`, `android-sdk = "latest"`. Run `mise install`, then with the plugin's `sdkmanager` install `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, accepting licences. Set `ANDROID_HOME` in `mise.toml` `[env]` to the plugin's install path.

Verify: `mise exec -- java -version` prints 21; `mise exec -- sdkmanager --list_installed` lists the three packages.

- [ ] **Step 2: Gradle skeleton**

Single module `app`, namespace and applicationId `app.chargelimit`, Java source/target 21, minSdk 26, compile/target 36, `testImplementation 'junit:junit:4.13.2'`. Generate the wrapper with `gradle wrapper --gradle-version 8.14.5`.

Verify: `./gradlew help` succeeds.

- [ ] **Step 3: Generate the token and write `AdvertPayload` constants**

Run `openssl rand -hex 4`; put the four bytes in `AdvertPayload.TOKEN` and note the hex in `docs/findings.md`. Only the two constants exist in this task.

- [ ] **Step 4: Proof `MainActivity`**

One screen with a status `TextView`. On launch: request `BLUETOOTH_ADVERTISE` (API 31+), then start legacy advertising through `BluetoothLeAdvertiser.startAdvertisingSet` with `AdvertisingSetParameters` (legacy mode, not connectable, not scannable, `INTERVAL_LOW` (the short, about 100 ms interval), `TX_POWER_MEDIUM`) and manufacturer data `COMPANY_ID` → `TOKEN + {1, 50, 1, 0}`. Show "advertising", or the failure status code, or "this phone cannot advertise" when `getBluetoothLeAdvertiser()` is null. Manifest declares `BLUETOOTH_ADVERTISE` and legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` with `maxSdkVersion="30"`.

- [ ] **Step 5: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Probe script**

`shelly/probe.js`: `BLE.Scanner.start({duration_ms: BLE.Scanner.INFINITE_SCAN, active: false})`, subscribe, and for every `SCAN_RESULT` whose `advData` contains an EIR block of type `0xFF` starting with bytes `FF FF`, `print` the address, RSSI, hex of `advData`, and `JSON.stringify(result.manufacturer_data)`. Print one line at start with `Shelly.getDeviceInfo().fw_id`.

- [ ] **Step 7 [HARDWARE]: Install and run on the phone**

Run: `adb devices`, `adb shell getprop ro.product.model`, `adb shell getprop ro.build.version.release`, `adb install -r app/build/outputs/apk/debug/app-debug.apk`, launch it.
Expected: screen shows "advertising". Record model and Android version in `docs/findings.md`.

- [ ] **Step 8 [HARDWARE]: Run the probe on the Shelly**

Use the `shelly-scripting` skill to upload and start `shelly/probe.js`, with Bluetooth enabled on the device. Watch the script log for 60 s with the phone within 2 m, then with the phone's screen off for 5 minutes.
Expected: log lines carrying the token at least every 10 s in both cases. Record firmware version, the observed `manufacturer_data` shape, and the longest gap in `docs/findings.md`.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat: project skeleton and BLE broadcast proof"
```

---

### Task 2: Payload and policy (pure Java)

**Files:**
- Modify: `app/src/main/java/app/chargelimit/AdvertPayload.java`
- Create: `app/src/main/java/app/chargelimit/ChargePolicy.java`
- Test: `app/src/test/java/app/chargelimit/AdvertPayloadTest.java`
- Test: `app/src/test/java/app/chargelimit/ChargePolicyTest.java`

**Interfaces:**
- Produces:
  - `static byte[] AdvertPayload.build(int level, boolean wantOn, boolean charging, int seq)` → 8 bytes
  - `static boolean ChargePolicy.next(int level, boolean previousWantOn, int low, int high)`
  - `static boolean ChargePolicy.validRange(int low, int high)`
  - `ChargePolicy.DEFAULT_LOW = 20`, `ChargePolicy.DEFAULT_HIGH = 80`

- [ ] **Step 1: Write failing tests**

```java
// AdvertPayloadTest
@Test public void layout() {
    byte[] p = AdvertPayload.build(63, true, false, 7);
    assertEquals(8, p.length);
    assertArrayEquals(AdvertPayload.TOKEN, Arrays.copyOfRange(p, 0, 4));
    assertEquals(1, p[4]); assertEquals(63, p[5]); assertEquals(0b01, p[6]); assertEquals(7, p[7]);
}
@Test public void flagsBothSet() { assertEquals(0b11, AdvertPayload.build(10, true, true, 0)[6]); }
@Test public void flagsNoneSet() { assertEquals(0, AdvertPayload.build(90, false, false, 0)[6]); }
@Test public void levelClamped() {
    assertEquals(0, AdvertPayload.build(-5, true, false, 0)[5]);
    assertEquals(100, AdvertPayload.build(250, true, false, 0)[5]);
}
@Test public void seqWraps() { assertEquals(0, AdvertPayload.build(50, true, false, 256)[7]); }

// ChargePolicyTest
@Test public void turnsOnAtLow()   { assertTrue(ChargePolicy.next(20, false, 20, 80)); }
@Test public void turnsOffAtHigh() { assertFalse(ChargePolicy.next(80, true, 20, 80)); }
@Test public void holdsBetween() {
    assertTrue(ChargePolicy.next(50, true, 20, 80));
    assertFalse(ChargePolicy.next(50, false, 20, 80));
}
@Test public void jitterAtHighGivesOneChange() {
    boolean w = true; int changes = 0;
    for (int l : new int[]{79, 80, 79, 80, 79}) { boolean n = ChargePolicy.next(l, w, 20, 80); if (n != w) changes++; w = n; }
    assertEquals(1, changes);
}
@Test public void rangeValidation() {
    assertTrue(ChargePolicy.validRange(20, 80));
    assertFalse(ChargePolicy.validRange(80, 80));
    assertFalse(ChargePolicy.validRange(90, 80));
    assertFalse(ChargePolicy.validRange(-1, 80));
    assertFalse(ChargePolicy.validRange(20, 101));
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest`
Expected: compilation failure, `build`/`ChargePolicy` not found.

- [ ] **Step 3: Implement the three methods**

`validRange` is true only for `0 <= low < high <= 100`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew testDebugUnitTest`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: advert payload and charge policy"
```

---

### Task 3: Shelly script

**Files:**
- Create: `shelly/charge-limiter.js`
- Create: `shelly/test/harness.js` (stubs `BLE`, `Shelly`, `Timer`, `print` with a controllable clock, loads the script with `node:vm`)
- Test: `shelly/test/charge-limiter.test.js` (run with `node --test`)

**Interfaces:**
- Consumes: token hex and `manufacturer_data` findings from `docs/findings.md`.
- Produces: a single self-contained script. Top-of-file block:

```js
var CONFIG = {
  token: [0x00, 0x00, 0x00, 0x00],   // from docs/findings.md
  companyId: 0xFFFF,
  version: 1,
  switchId: 0,
  toggleAfterS: 600,
  renewS: 300,
  silenceS: 120,
  minGapS: 30
};
```

Functions the tests call by name: `parseAdvert(advData)` → `{level, wantOn, charging, seq}` or `null`; `onScan(event, result)`; `tick()` (called by a 10 s repeating timer).

Behaviour the signatures do not determine:
- `parseAdvert` walks the raw `advData` byte string as EIR blocks (`len, type, data…`) itself, using `charCodeAt`; it does not depend on `result.manufacturer_data`. It returns `null` for anything that is not type `0xFF`, company `0xFFFF` little-endian, exactly 8 payload bytes, matching token and version, level ≤ 100.
- Relay off is `Shelly.call("Switch.Set", {id: 0, on: false, toggle_after: 600})`; relay on is `{id: 0, on: true}`.
- On start the script commands relay on and treats that as the last change time.
- `tick()`: if no valid advert for `silenceS`, command on. If the wanted state is off and the last off command is older than `renewS`, re-issue off.
- A wanted state that differs from the commanded state is applied only when `minGapS` has passed since the last change; otherwise it is applied by a later `tick()`.

- [ ] **Step 1: Write the harness and failing tests**

Test names and assertions (`calls` is the recorded list of `Shelly.call` arguments; `adv(...)` builds a raw advert string; `clock.advance(s)` moves time and fires due timers):

```js
test('startup commands relay on', () => { load(); assert.deepEqual(calls[0], ['Switch.Set', {id: 0, on: true}]); });
test('valid off advert switches off with toggle_after', () => {
  load(); clock.advance(31); scan(adv({level: 80, wantOn: false}));
  assert.deepEqual(last(), ['Switch.Set', {id: 0, on: false, toggle_after: 600}]);
});
test('off is renewed after 300 s of continued off adverts', () => {
  load(); clock.advance(31); scan(adv({wantOn: false}));
  const n = offCalls(); keepScanning({wantOn: false}, 310);
  assert.equal(offCalls(), n + 1);
});
test('silence for 120 s switches on', () => {
  load(); clock.advance(31); scan(adv({wantOn: false})); clock.advance(130);
  assert.deepEqual(last(), ['Switch.Set', {id: 0, on: true}]);
});
test('changes are at least 30 s apart', () => {
  load(); clock.advance(31); scan(adv({wantOn: false})); const n = calls.length;
  clock.advance(5); scan(adv({wantOn: true})); assert.equal(calls.length, n);
  clock.advance(30); assert.deepEqual(last(), ['Switch.Set', {id: 0, on: true}]);
});
test('same wanted state does not repeat the on command', () => {
  load(); const n = calls.length; keepScanning({wantOn: true}, 200); assert.equal(calls.length, n);
});
for (const [name, data] of [
  ['other company id', adv({company: 0x004C})],
  ['wrong token', adv({token: [1, 2, 3, 4]})],
  ['wrong version', adv({version: 2})],
  ['short payload', adv({truncate: 5})],
  ['level over 100', adv({level: 150})],
  ['EIR length past end', '\x1f\xff\xff\xff'],
  ['empty', ''],
]) test('ignores ' + name, () => { load(); assert.equal(ctx.parseAdvert(data), null); });
test('non-result scan events are ignored', () => { load(); const n = calls.length; ctx.onScan(0, null); assert.equal(calls.length, n); });
```

- [ ] **Step 2: Run to verify failure**

Run: `node --test shelly/test/`
Expected: FAIL, script file missing.

- [ ] **Step 3: Implement `shelly/charge-limiter.js`**

- [ ] **Step 4: Run to verify pass**

Run: `node --test shelly/test/`
Expected: all tests pass.

- [ ] **Step 5 [HARDWARE]: Deploy and check the firmware failsafe**

With the `shelly-scripting` skill: upload, enable run-on-boot, set `Switch.SetConfig {id:0, config:{initial_state:"on"}}`. Temporarily set `toggleAfterS: 60, renewS: 30`. From the proof app (still advertising wanted-on = 1) confirm relay on. Then stop the script by hand right after issuing an off via `Switch.Set {id:0,on:false,toggle_after:60}` twice 30 s apart.
Expected: relay returns on about 60 s after the **second** call, proving re-issue re-arms the timer. If it returns 60 s after the first, change the renew logic to issue `on:false` only once and record that in `docs/findings.md`. Restore 600/300.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: Shelly relay script with failsafe"
```

---

### Task 4: Foreground service and boot start

**Files:**
- Create: `app/src/main/java/app/chargelimit/Settings.java`
- Create: `app/src/main/java/app/chargelimit/ChargeService.java`
- Create: `app/src/main/java/app/chargelimit/BootReceiver.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AdvertPayload.build`, `ChargePolicy.next`, `ChargePolicy.validRange`, defaults.
- Produces:
  - `Settings(Context)` backed by device-protected `SharedPreferences` (`createDeviceProtectedStorageContext()`), with `int low()`, `int high()`, `boolean setRange(int low, int high)` (false and unchanged when `validRange` fails), `boolean enabled()`, `void setEnabled(boolean)`, `boolean wantOn()`, `void setWantOn(boolean)`.
  - `ChargeService.start(Context)`, `ChargeService.stop(Context)`.
  - Local broadcast action `app.chargelimit.STATUS` (package-restricted) with extras `level` (int), `charging` (boolean), `wantOn` (boolean), `broadcasting` (boolean), `error` (String or absent).

- [ ] **Step 1: `Settings`**

- [ ] **Step 2: `ChargeService`**

Foreground service, `foregroundServiceType="connectedDevice"`, `directBootAware="true"`, `START_STICKY`, ongoing low-importance notification showing level and wanted state. Registers `ACTION_BATTERY_CHANGED` (level from `EXTRA_LEVEL`/`EXTRA_SCALE`, charging from `EXTRA_PLUGGED != 0`) and `BluetoothAdapter.ACTION_STATE_CHANGED`. On each battery change: `wantOn = ChargePolicy.next(level, settings.wantOn(), low, high)`, persist, and if level, wantOn or charging changed, increment seq and call `AdvertisingSet.setAdvertisingData`. Advertising parameters as in Task 1. When Bluetooth is off or the advertiser is null: no advertising set, status `broadcasting=false` with an `error` string; when Bluetooth turns on, start advertising again. `onDestroy` stops the advertising set. Sends the `STATUS` broadcast after every change.

- [ ] **Step 3: `BootReceiver`**

`directBootAware="true"`, handles `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`; calls `ChargeService.start` only when `settings.enabled()`.

- [ ] **Step 4: Manifest**

Add permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; declare the service and receiver.

- [ ] **Step 5 [HARDWARE]: Verify**

Run: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`, then `adb shell am start-foreground-service app.chargelimit/.ChargeService` after granting permissions.
Expected: notification present; Shelly script log shows the real battery level; `adb shell dumpsys battery set level 85` turns the relay off within 40 s and `set level 15` turns it on; `adb shell dumpsys battery reset` afterwards. Toggle Bluetooth off and on: no crash in `adb logcat`, broadcasting resumes.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: foreground charge service and boot start"
```

---

### Task 5: Main screen

**Files:**
- Modify: `app/src/main/java/app/chargelimit/MainActivity.java`
- Create: `app/src/main/res/layout/main.xml`, `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Settings`, `ChargeService.start/stop`, the `STATUS` broadcast.

- [ ] **Step 1: Layout and behaviour**

Replace the proof screen with the spec's layout: rows "Battery" (`63 %  charging` / `not charging`), "Charger wanted" (`ON`/`OFF`), "Broadcasting" (`yes`/`no` plus the error text), number fields "Charge below" and "Stop at", and one button reading "Start service" or "Stop service".

- Start: request `BLUETOOTH_ADVERTISE` and `POST_NOTIFICATIONS` where the API level needs them; if not exempt, open `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; then `settings.setEnabled(true)` and `ChargeService.start`.
- Stop: `settings.setEnabled(false)` and `ChargeService.stop`.
- Threshold fields save on focus loss through `settings.setRange`; on `false` restore the previous values and show the toast "Charge below must be less than Stop at (0–100)".
- Permission denied: show "Bluetooth permission is needed to broadcast" in the Broadcasting row; do not start the service.

- [ ] **Step 2 [HARDWARE]: Verify**

Install, start the service from the button, change thresholds to 30/60, and confirm with `adb shell dumpsys battery set level 65` that the relay turns off. Enter 90/80 and confirm the toast and that 30/60 remain. Reset the battery override.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: main screen with thresholds and service control"
```

---

### Task 6: Soak and handover

**Files:**
- Create: `README.md`
- Modify: `docs/findings.md`

- [ ] **Step 1: README**

Sections: what it does (three sentences); wiring warning (dry-contact relay, mains work is the user's responsibility); build and install (`mise install`, `./gradlew assembleDebug`, `adb install`); Shelly setup (enable Bluetooth, upload `shelly/charge-limiter.js`, enable on boot, `initial_state: on`); changing the token; the failsafe behaviour in the four timings from Global Constraints.

- [ ] **Step 2 [HARDWARE]: Soak checklist**

Record pass/fail for each in `docs/findings.md`:
1. Discharge to low and charge to high once with real battery levels: relay follows.
2. Force-stop the app while the relay is off: relay on within 120 s.
3. Reboot the phone and leave it locked: broadcasting resumes, relay follows.
4. Carry the phone out of range while the relay is off: relay on within 120 s; back in range: relay follows again.
5. Power-cycle the Shelly with the phone absent: relay on.
6. Stop the script while the relay is off: relay on within 600 s.
7. Leave overnight: no more than one relay change per threshold crossing in the script log.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: README and soak results"
```
