# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-19

### Added

**Core blocking**
- Blocking profiles with per-profile app selection, supporting both block-list and allow-list
  modes.
- Website domain blocking inside major Android browsers, with its own allow-list mode.
- 12 blocking strategies: Manual, NFC, NFC + Manual, NFC Timer, NFC Pause Timer, NFC Temporary
  Access (soft unblock), the five QR-code equivalents of the above, and Timer + Manual.
- Physical unlocks: register specific named NFC tags or QR codes as the only valid codes that can
  stop a profile's session.
- Accessibility-service-based foreground app detection with a usage-stats fallback, plus a
  full-screen block overlay shown on top of blocked apps.

**Sessions, schedules & flexibility**
- Session start/stop lifecycle with single-active-session semantics, persisted history, and a
  foreground service + status notification while a session is active.
- Schedules: pick days of the week and a time window; profiles start and stop themselves
  automatically, with boot-persistence for pending alarms.
- Timed breaks, with configurable duration and single-break or multiple-breaks-per-session modes.
- Soft unblock ("Open for N minutes"): a short, counted, time-boxed exception to open a blocked
  app without ending the session.
- Emergency unblock: a deliberate 3-tap "break glass" escape hatch with a limited, periodically
  resetting allowance, disableable per profile.
- Strict mode: additionally blocks Android Settings and the package installer/uninstaller while a
  session is active.

**Insights & data**
- Session history with a 4-week activity heatmap, weekly bar chart, and monthly grid chart on the
  Insights screen.
- CSV export of full session history.

**Platform integration**
- Home-screen widget to start/stop a profile without opening the app.
- Quick Settings tile with the same capability from the notification shade.
- Deep links: `foqos://profile/{id}` toggles a profile, `foqos://navigate/...` opens the app
  directly; both also resolve as `https://foqos.app/...` links.
- Guided onboarding flow covering the permission set required for blocking to work.

**Release hardening**
- Release build signing config (local keystore only, never checked into the repo or CI), with
  minification and resource shrinking enabled via R8.
- ProGuard keep rules for kotlinx.serialization, ZXing, Glance, and DataStore Preferences'
  bundled protobuf-lite runtime.
- Android 13+ themed (monochrome) launcher icon support.
- GitHub Actions CI running unit tests, lint, and a debug build on every push/PR to `main` and
  `dev`.
- 237 unit tests covering strategies, session lifecycle, schedules, insights aggregation, and CSV
  export.
