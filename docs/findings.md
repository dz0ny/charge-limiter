# Findings

- Token (hex): `6ce29d77` — same value in `AdvertPayload.TOKEN` and the Shelly script's `CONFIG.token`.
- Phone: POCO X8 Pro (2511FPC34G), Android 16, HyperOS. USB install was blocked; installed from the Download folder.
- 2026-11-19 on the phone: service in foreground, BLE advertising running at interval 160 (100 ms), TX power -7 dBm. Battery read 41.2 °C on USB power, so the heat guard was active.
- Shelly firmware / scan filter support: not recorded yet.

## Decisions after the plan

- The app is Kotlin, not Java (user request, 2026-11-18). Class names and behaviour are unchanged.
- Goal stated by the user: extend battery life on phones with no built-in charge limit. The phone is carried daily, not permanently docked.
- Default thresholds are 75 / 80, not 20 / 80. With 20 / 80 a phone plugged in at 45 % after a full charge would not charge until it fell to 20 %.
- Launcher icon was generated with Codex CLI.
- 2026-11-19: the repo is public and the app updates itself from GitHub Releases. It now has the INTERNET and install-packages permissions, which the original design ruled out.
- 2026-11-19 on the emulator: a release-signed 0.0.1 found v0.1.0, downloaded and installed it. Stopping needs a 2-second hold plus a confirmation.
- Release signing key: outside the repo, in the maintainer's home config folder. Losing it breaks updates.
- Adverts are unsigned. Anyone in range who copies one can toggle the charger. Accepted for now.
