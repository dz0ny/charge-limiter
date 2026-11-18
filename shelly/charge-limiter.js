// This script switches a charger relay from the BLE adverts of the Android app.

// The app sends manufacturer data with company ID 0xFFFF and 8 bytes.
// Bytes 0-3 are the token, byte 4 the version, byte 5 the battery percent.
// Byte 6 has the flags: bit 0 = charger wanted on, bit 1 = phone is charging.
// Byte 7 is a sequence counter.
// Keep this layout the same as AdvertPayload.java in the app.

// Failsafe: the charger goes ON when this script cannot hear the phone.
// No valid advert for CONFIG.silenceS seconds sets the relay on.
// Each "off" command has a toggle_after timer.
// The firmware sets the relay on again if this script stops.

var CONFIG = {
  // Use the same bytes as AdvertPayload.TOKEN.
  token: [0x6c, 0xe2, 0x9d, 0x77],
  companyId: 0xffff,
  version: 1,
  switchId: 0,
  // The firmware sets the relay on this many seconds after the last "off".
  toggleAfterS: 600,
  // The script sends "off" again after this many seconds.
  renewS: 300,
  // The relay goes on after this many seconds without an advert.
  silenceS: 120,
  // Minimum seconds between two relay changes.
  minGapS: 30,
  tickS: 10
};

var PREFIX = "charge-limiter:";
var PAYLOAD_LEN = 8;
var EIR_MANUFACTURER = 0xff;

// All times are uptime seconds.
var state = {
  // Time of the last valid advert.
  lastSeen: 0,
  // The last state that the phone asked for.
  wantOn: true,
  // The last state that this script sent to the relay.
  commandedOn: null,
  // Time of the last relay change.
  lastChange: 0,
  // Time of the last "off" command.
  lastOff: 0,
  level: -1
};

// Use uptime. The wall clock can jump when the device syncs its time.
function now() {
  return Shelly.getUptimeMs() / 1000;
}

// Reads the raw advert data as blocks of length, type and data.
// Returns {level, wantOn, charging, seq} for our block.
// Returns null for all other data.
function parseAdvert(advData) {
  if (typeof advData !== "string") return null;
  var i = 0;
  while (i + 1 < advData.length) {
    var len = advData.charCodeAt(i);
    if (len === 0) return null;
    if (i + 1 + len > advData.length) return null;
    if (advData.charCodeAt(i + 1) === EIR_MANUFACTURER) {
      var p = i + 2;
      if (len - 1 !== 2 + PAYLOAD_LEN) return null;
      var company = advData.charCodeAt(p) | (advData.charCodeAt(p + 1) << 8);
      if (company !== CONFIG.companyId) return null;
      p += 2;
      for (var t = 0; t < 4; t++) {
        if (advData.charCodeAt(p + t) !== CONFIG.token[t]) return null;
      }
      if (advData.charCodeAt(p + 4) !== CONFIG.version) return null;
      var level = advData.charCodeAt(p + 5);
      if (level > 100) return null;
      var flags = advData.charCodeAt(p + 6);
      return {
        level: level,
        wantOn: (flags & 1) !== 0,
        charging: (flags & 2) !== 0,
        seq: advData.charCodeAt(p + 7)
      };
    }
    i += len + 1;
  }
  return null;
}

function setRelay(on, reason) {
  var t = now();
  var params = { id: CONFIG.switchId, on: on };
  if (!on) {
    params.toggle_after = CONFIG.toggleAfterS;
    state.lastOff = t;
  }
  if (state.commandedOn !== on) {
    state.lastChange = t;
    print(PREFIX, "relay", on ? "ON" : "OFF", "-", reason);
  }
  state.commandedOn = on;
  Shelly.call("Switch.Set", params, function (result, errorCode, errorMessage) {
    if (errorCode) print(PREFIX, "Switch.Set failed:", errorCode, errorMessage);
  });
}

// Sets the relay to the state that the phone wants.
// Sets the relay on when the phone is silent.
function reconcile() {
  var t = now();
  var silent = t - state.lastSeen > CONFIG.silenceS;
  var target = silent ? true : state.wantOn;
  if (target !== state.commandedOn) {
    if (t - state.lastChange >= CONFIG.minGapS) {
      setRelay(target, silent ? "no advert for " + CONFIG.silenceS + " s" : "battery " + state.level + " %");
    }
    return;
  }
  // Send "off" again before the firmware timer sets the relay on.
  if (!target && t - state.lastOff >= CONFIG.renewS) {
    setRelay(false, "renew");
  }
}

function onScan(event, result) {
  if (event !== BLE.Scanner.SCAN_RESULT || !result) return;
  var advert = parseAdvert(result.advData);
  if (advert === null) return;
  state.lastSeen = now();
  state.wantOn = advert.wantOn;
  state.level = advert.level;
  reconcile();
}

function tick() {
  reconcile();
}

function startScan() {
  var ble = Shelly.getComponentConfig("ble");
  if (!ble || !ble.enable) {
    print(PREFIX, "Bluetooth is disabled on this device. The relay stays on.");
    return;
  }
  BLE.Scanner.subscribe(onScan);
  // A different script can have a scan in progress. This script then shares its results.
  if (BLE.Scanner.isRunning()) return;
  var started = BLE.Scanner.start({ duration_ms: BLE.Scanner.INFINITE_SCAN, active: false });
  if (!started) print(PREFIX, "The BLE scan did not start. The relay stays on.");
}

// Start with the relay on. Count the silence from this moment.
state.lastSeen = now();
setRelay(true, "startup");
startScan();
Timer.set(CONFIG.tickS * 1000, true, tick);
