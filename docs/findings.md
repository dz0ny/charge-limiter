# Findings

- Token (hex): `6ce29d77` — same value in `AdvertPayload.TOKEN` and the Shelly script's `CONFIG.token`.
- Phone model / Android version: not recorded yet (no device on adb).
- Shelly firmware / scan filter support: not recorded yet.

## Decisions after the plan

- The app is Kotlin, not Java (user request, 2026-11-18). Class names and behaviour are unchanged.
- Goal stated by the user: extend battery life on phones with no built-in charge limit. The phone is carried daily, not permanently docked.
- Default thresholds are 75 / 80, not 20 / 80. With 20 / 80 a phone plugged in at 45 % after a full charge would not charge until it fell to 20 %.
- Launcher icon was generated with Codex CLI.
- Adverts are unsigned. Anyone in range who copies one can toggle the charger. Accepted for now.
