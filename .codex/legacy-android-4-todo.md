# Android 4.0.4 Legacy TODO

Date: 2026-05-07

## 1. Main screen poster cards are incomplete

- Symptom: On Android 4.0.4 emulator, recent-play cards render text but cover images do not appear.
- Symptom: Cards cannot be selected/opened reliably for playback.
- Evidence: `E:\安卓4.0\logs\oriontv-latest-main.png`
- Likely areas:
  - `legacy-android-ics/app/src/main/java/com/oriontv/legacy/ui/PosterGridAdapter.java`
  - `legacy-android-ics/app/src/main/java/com/oriontv/legacy/MainActivity.java`
  - Image proxy / Picasso / HTTPS image loading path.
- Acceptance:
  - Poster cover placeholders or real covers are visibly rendered on API 15.
  - DPAD focus is visible on cards.
  - Pressing OK/Enter on a focused card opens detail/play path.

## 2. SSL/HTTPS must work end to end on Android 4.0.4

- Symptom: The service can be used via `http://tvv.t2t.cc.cd`, but user reports SSL problems.
- Constraint: Playback URLs and other upstream APIs may still be HTTPS, so avoiding HTTPS only for the server root is not enough.
- Scope:
  - App API client HTTPS.
  - Image loading/proxy HTTPS.
  - Video detail and playback URL retrieval.
  - MediaPlayer playback of HTTPS streams.
  - Third-party source APIs that return HTTPS assets or m3u8 URLs.
- Likely areas:
  - `legacy-android-ics/app/src/main/java/com/oriontv/legacy/api/OrionApiClient.java`
  - `legacy-android-ics/app/src/main/java/com/oriontv/legacy/media/M3u8Inspector.java`
  - `legacy-android-ics/app/src/main/java/com/oriontv/legacy/PlayerActivity.java`
  - `legacy-android-ics/app/src/main/java/com/oriontv/legacy/LiveActivity.java`
- Acceptance:
  - `https://tvv.t2t.cc.cd` login and API calls work on API 15, or the app provides a deliberate compatibility transport/proxy path.
  - HTTPS poster/image loading works or is proxied through a compatible endpoint.
  - HTTPS playback URLs either play directly on API 15 or are resolved/proxied to a compatible URL.
  - Failures are visible in UI with actionable messages, not silent loading states.
