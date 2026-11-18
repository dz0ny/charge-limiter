# BLE charge limiter — design

Date: 2026-11-18

## Intent

Keep an Android device's battery inside a chosen charge range by switching its
charger with a Shelly 1 Gen4 relay. Everything is local: no cloud, no Wi-Fi
dependency at runtime, no app store. The Shelly keeps its stock firmware.

**Assumption (mine, not confirmed by the user):** the battery being managed is
the battery of the Android device that runs the app, and that device stays
plugged into the charger wired through the Shelly.

## Goals

- The app broadcasts battery level and the wanted charger state over BLE.
- A script on the Shelly hears the broadcast and sets the relay to match.
- If the app dies or the phone leaves range, the charger ends up **on**.
- The app survives screen-off and reboot without user action.
- Low and high thresholds are adjustable in the app (defaults 20 % / 80 %).

## Non-goals

- ESPHome or any custom firmware.
- A GATT connection, pairing, or any reply channel from the Shelly.
- Managing more than one phone or more than one relay.
- Power metering (the Shelly 1 Gen4 has none).
- Play Store distribution, Kotlin, Compose.
- Manual "charge to 100 % once" override, schedules, statistics.

## Anti-goals

- A state where the relay stays off with nobody watching, so the battery drains.
- An app that silently stops broadcasting because the OS killed it.
- Relay chatter (rapid toggling) from a noisy battery reading or missed adverts.

## Constraints

- Shelly 1 Gen4, stock firmware, script written in Shelly Script (mJS-style
  JavaScript, no ES6 niceties). Relay is dry-contact: the charger's live wire
  must be wired through it by the user.
- Shelly scan results are deduplicated for 3 s, and the radio is shared with
  Wi-Fi, so individual adverts will be missed.
- Android randomises the advertising MAC address. The phone must be identified
  by payload, never by address.
- Legacy advertising payload limit is 31 bytes.
- Java, Gradle, installed with `adb install`. Toolchains come from mise
  (Temurin 21, Gradle 8.14.5 already present; Android SDK via mise's
  `android-sdk` plugin).

## Approaches

| | A. Broadcast + script (chosen) | B. GATT commands | C. ESPHome |
|---|---|---|---|
| How | App advertises; Shelly script scans and switches | App connects to Shelly's BLE RPC service, sends `Switch.Set` | Flash Shelly, custom BLE server |
| For | No connection to maintain; failsafe is natural; smallest app | Real acknowledgement; no script | Full control on device |
| Against | One-way; spoofable by anyone in range | Reconnect/chunking logic is the fragile part of any BLE app | Opening a mains device, serial flashing |

The user picked A. C was dropped by the user earlier ("no esphome just java
app"). **B is the fallback** if milestone 1 shows A cannot work.

## Design

### Advert format

Manufacturer-specific data, company ID `0xFFFF` (reserved for testing).
Payload after the company ID, 8 bytes:

| Byte | Meaning |
|---|---|
| 0–3 | Shared token: 4 random bytes fixed at project creation, same constant in app and script |
| 4 | Format version = 1 |
| 5 | Battery level 0–100 |
| 6 | Flags: bit 0 = charger wanted on, bit 1 = phone reports it is charging |
| 7 | Sequence counter, +1 on every payload change |

Total advert is about 15 bytes, well under 31. Not connectable, not scannable,
no device name. The token is identification, not security; spoofing is an
accepted risk (worst case: someone toggles a charger).

### Android app (Java)

- `MainActivity`: shows battery %, wanted state, whether advertising is
  running; two number fields for low/high thresholds; start/stop button;
  requests permissions and the battery-optimisation exemption.
- `ChargeService`: foreground service, type `connectedDevice`, with a
  persistent notification. Registers for `ACTION_BATTERY_CHANGED`, runs the
  threshold logic, owns the BLE advertiser.
- `BootReceiver`: starts the service on `BOOT_COMPLETED` if it was running
  before reboot.
- `ChargePolicy`: pure Java, no Android imports. Input: level, previous wanted
  state, low, high. Output: wanted state. Rule: on at `level <= low`, off at
  `level >= high`, otherwise unchanged; initial state is on. Unit-tested.
- `AdvertPayload`: pure Java, builds the 8 bytes. Unit-tested.
- Advertising uses the `AdvertisingSet` API so the payload can be replaced
  without stopping the advert (`setAdvertisingData`). Interval: low-latency
  preset (about 100 ms) so that 3 s dedup and missed packets still leave many
  chances per window. This is my call; it can be relaxed after measuring.
- Settings in `SharedPreferences`: low, high, service-enabled flag.
- Reboot with a locked screen (my call): the service and boot receiver are
  direct-boot aware, listen for `LOCKED_BOOT_COMPLETED` as well, and keep
  settings in device-protected storage, so broadcasting resumes before the
  first unlock.
- minSdk 26 (needed for `AdvertisingSet`), target the current SDK. Permissions:
  `BLUETOOTH_ADVERTISE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
  `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; legacy
  `BLUETOOTH`/`BLUETOOTH_ADMIN` capped at SDK 30.
- If the device reports no advertiser (`getBluetoothLeAdvertiser() == null`)
  or Bluetooth is off, the UI says so plainly and the service does not pretend
  to run.

### Shelly script

- Starts a passive, infinite scan filtered on manufacturer data; ignores any
  result whose company ID, token or version do not match.
- On a valid advert, records the time and the wanted state.
- Relay off is always issued as `Switch.Set {id:0, on:false, toggle_after:T}`
  so the firmware itself turns the charger back on after `T` seconds. While
  valid "off" adverts keep arriving the script re-issues it every `T/2`.
  `T` defaults to 600 s. This is the failsafe if the script crashes.
- Relay on is a plain `Switch.Set {id:0, on:true}`.
- Silence timeout: no valid advert for 120 s → relay on.
- Minimum 30 s between relay state changes.
- Device configuration applied once, by hand or by a setup step: script
  enabled on boot, switch `initial_state: "on"`, Bluetooth enabled.
- Constants (token, `T`, timeouts) sit in one config block at the top.

### What the user sees

```
┌──────────────────────────────┐
│ Battery        63 %  charging│
│ Charger wanted ON            │
│ Broadcasting   yes           │
│                              │
│ Charge below   [ 20 ] %      │
│ Stop at        [ 80 ] %      │
│                              │
│        [ Stop service ]      │
└──────────────────────────────┘
```

## Milestones

1. **Proof of approach.** App advertises a fixed payload; script logs every
   match. Confirms phone can advertise in the background and the Shelly
   script sees manufacturer data. If this fails, stop and switch to B.
2. Script relay logic with firmware failsafe; confirm that re-issuing
   `toggle_after` while already off re-arms the timer.
3. App policy, service, boot start, UI.
4. Soak: full charge/discharge cycle, kill the app, reboot the phone, walk out
   of range, power-cycle the Shelly.

## Unverified facts the plan must prove

- Stock Gen4 firmware exposes `manufacturer_data` to scripts for a phone's
  legacy advert (docs say yes; not tried on this model).
- The user's phone supports BLE peripheral mode.
- `Switch.Set` with `toggle_after` on an already-off switch re-arms the timer.
- Whether scan `filters` (firmware 2.0.0+) are available on the installed
  firmware; if not, filter in the callback.

## Open questions for the user

- Is the managed battery the phone that runs the app, and does it stay
  plugged in? (Decides the failsafe direction and the soak test.)
- Phone model and Android version (decides whether advertising is supported,
  which permission prompts appear, and how hard the OS kills background work).
- Is the Shelly already wired, on Wi-Fi and reachable, and on which firmware?
  (Decides whether the on-device steps are run from this Mac or handed over
  as instructions.)

## Left to the builder

- Package name, Gradle layout (single `app` module), exact SDK versions.
- Notification wording and icon.
- Script upload method (device web UI or the `shelly` CLI).
- Whether the token is generated by a script or typed in once.
