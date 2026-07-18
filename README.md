# Foqos for Android

A full-featured Android port of [Foqos](https://github.com/awaseem/foqos) — the open-source
focus app that blocks distracting apps until you scan an NFC tag or QR code.

> Work in progress. A signed APK will be published in
> [GitHub Releases](https://github.com/Elias02345/Foqos-Android/releases) with v1.0.0.

## Features (v1.0 target)

- Blocking profiles with per-profile app selection (block list or allow list)
- 12 blocking strategies: Manual, NFC, NFC + Manual, NFC Timer, NFC Pause Timer,
  NFC Temporary Access, the five QR equivalents, and Timer + Manual
- Physical Unlocks: register named NFC tags / QR codes as the only valid stop codes
- Schedules (days of week + time window) with automatic session start/stop
- Timed breaks, strict mode, soft unblock ("Open for N minutes"), emergency unblock
- Session history and insights: 4-week heatmap, weekly and monthly charts
- Website blocking in major browsers, CSV data export
- Home-screen widget, quick settings tile, deep links (`foqos://profile/{id}`)
- No internet permission — everything stays on your device

## License

MIT — see [LICENSE](LICENSE). Based on the original iOS app by
[Ali Waseem](https://github.com/awaseem).
