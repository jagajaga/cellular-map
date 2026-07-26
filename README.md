# Signal Map

Walk around, record cellular signal strength for both SIMs, and see a smooth
red→green gradient of the best-signal spots on OpenStreetMap — at any zoom.

## Install

1. Go to [Releases](https://github.com/jagajaga/cellular-map/releases) and
   download `signalmap.apk` (or grab the `signalmap-apk` artifact from any
   [Actions](https://github.com/jagajaga/cellular-map/actions) run).
2. Open it on your phone (allow "install unknown apps" for your browser/file
   manager the first time).
3. **Updates install in place** — every APK is signed with the same key and
   has an increasing version, so you never need to uninstall first.

## Use

- Tap ▶ to record (grant location + phone permissions). Walk around.
- SIM 1 / SIM 2 buttons switch which SIM's map you see.
- Legend: red = −120 dBm, green = −70 dBm. No color = no data.
- Zoom in for meter-scale spots; zoom out for area overview (cells aggregate
  by max, so a good spot stays green).
- Sessions button (top right): export any session as CSV/GeoJSON or delete it.

## Notes

- GPS accuracy is ~3–5 m; samples worse than 25 m are excluded from the map.
- The signing keystore is committed (password `signalmap`) purely for
  install-continuity of a hobby app — do not reuse it for anything serious.
- Requires Android 10+.

## Build

CI builds on every push (`.github/workflows/android.yml`). Locally:
`./gradlew assembleRelease` with JDK 17 and an Android SDK.
