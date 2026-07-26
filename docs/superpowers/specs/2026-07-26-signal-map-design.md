# Signal Map — Design Spec

Date: 2026-07-26
Status: Approved

## Purpose

An Android app that records cellular signal strength for both SIMs while the
user walks around, and renders the results as a smooth, zoom-adaptive gradient
overlay on OpenStreetMap. Goal: find the best-signal spots in an area, at any
zoom level — from meter-scale detail up to whole-neighborhood overview.

## Decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Collection | Foreground "walk mode": user taps Record; foreground service samples while recording. No always-on background logging. |
| SIM display | Toggle layers: SIM1 or SIM2 shown one at a time (chips). |
| Metric | Signal strength in dBm (RSRP for LTE/5G NR, RSSI otherwise). Network type stored alongside. |
| Resolution | Store raw samples with GPS accuracy; finest *rendered* resolution ~2–5 m, adapting to reported accuracy. Samples with accuracy > 25 m are stored but flagged and excluded from rendering by default. |
| Data | Local SQLite (Room) only; export sessions as CSV and GeoJSON via share sheet. No server. |
| Platform | Kotlin, minSdk 29 (Android 10+), osmdroid for the map. |
| Distribution | Public GitHub repo; GitHub Actions builds APK artifacts on push and attaches APKs to Releases on `v*` tags. |

## Architecture

Single-module Android app, Kotlin, MVVM-lite:

```
app/
  data/        Room entities, DAO, aggregation queries
  collect/     RecordingService (foreground), location + telephony sampling
  render/      Grid aggregation → interpolation → gradient bitmap overlay
  ui/          MapActivity, chips/legend/record controls, sessions & export
```

### Components

**RecordingService** (foreground service, `location` type)
- FusedLocationProvider, 1 s interval, high accuracy.
- `SubscriptionManager` enumerates active SIMs; a per-subscription
  `TelephonyManager` (`createForSubscriptionId`) each.
- On each location fix: read `TelephonyManager.signalStrength` (the
  system-cached latest measurement; works on API 29+, unlike
  `TelephonyCallback` which needs 31) and write one `Sample` row per active
  SIM.
- Stops on user tap or when the app task is removed.

**Sample** (Room entity)
`id, sessionId, simSlot (0/1), timestampMs, lat, lon, accuracyM, dbm,
networkType (e.g. LTE/NR/WCDMA/GSM), flagged (accuracy > 25 m)`

Indexed on `(simSlot, lat, lon)` plus quantized web-mercator columns
`(mx, my)` at a fixed deep zoom (z22, ~4.7 m/px equator → sub-meter storage
fidelity) to make grid aggregation a pure integer `GROUP BY`.

**Aggregator** (pure Kotlin + SQL)
- Input: visible bounding box, current osmdroid zoom `z`.
- Cell size chosen so one cell ≈ 24 screen px, clamped so the cell's ground
  size never drops below 2 m (the finest honest resolution per the accuracy
  decision); implemented as bit-shifting the stored z22 integer coords down
  to the derived grid zoom.
- SQL: `SELECT max(dbm), avg(dbm), count(*) ... GROUP BY (mx >> s, my >> s)`
  filtered by bbox, simSlot, and `flagged = 0`.
- **max(dbm)** drives rendering (best-spot goal: a cell containing one great
  spot stays green when zoomed out). `avg` retained in the result for a
  possible future toggle.

**GradientRenderer** (value-based smooth heatmap)
1. Offscreen `Bitmap` at 1/8 of the MapView pixel size, plus a parallel
   float buffer pair: `valueSum` and `weightSum`.
2. For each aggregated cell, splat a radial falloff kernel (radius ≈ 2 cell
   widths) centered on the cell: `valueSum += dbm_norm * k`,
   `weightSum += k`.
3. Per pixel: `v = valueSum / weightSum` where `weightSum > ε` — a
   Shepard/IDW-style weighted-average interpolation, so color between
   samples blends naturally with no visible cell edges.
4. Colorize through a red→yellow→green LUT over a fixed dBm window
   (−120 dBm → red, −70 dBm → green, clamped); alpha ramps to 0 as
   `weightSum → ε` so no-data areas stay transparent (never "red").
5. Draw upscaled with bilinear filtering as an osmdroid `Overlay` at ~60 %
   opacity.

Runs on a background thread; pan/zoom triggers a debounced (~250 ms)
re-render; the previous bitmap is drawn transformed in the meantime so the
overlay never flickers.

Zoom-adaptivity falls out of the design: cells are fixed *screen* pixels, so
their ground size grows as you zoom out and the same pipeline yields
street-level blobs or meter-level detail without special cases.

**UI (MapActivity)**
- osmdroid `MapView`, OSM Mapnik tiles.
- Record/Stop FAB (starts/stops RecordingService; live sample count shown).
- SIM chips: `SIM1` / `SIM2` (from SubscriptionManager labels) — exactly one
  active at a time.
- Legend: horizontal dBm gradient bar with −120/−70 endpoints.
- Follow-location toggle.
- Menu → Sessions: list (date, duration, sample count), per-session export
  (CSV, GeoJSON) via `ACTION_SEND` share sheet, and delete.

### Permissions
`ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS` (service
notification), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`.
Runtime rationale screens before first record.

### Error handling
- Missing permission → explanatory dialog, deep link to settings.
- Single-SIM device → SIM2 chip hidden; everything else works.
- Telephony returns `CellInfo` unavailable / dBm = `Integer.MAX_VALUE`
  sentinel → sample skipped for that SIM.
- GPS accuracy > 25 m → stored flagged, excluded from render.
- DB writes batched per fix in a transaction; service survives activity
  death (foreground service + notification).

## Export formats
- **CSV**: one row per sample, all entity columns.
- **GeoJSON**: `FeatureCollection` of Points, properties = sim, dbm,
  networkType, accuracy, timestamp.

## Repo & CI
- Public GitHub repo (`gh repo create --public`).
- Workflow `android.yml`:
  - on push/PR: JDK 17 + Gradle cache → `./gradlew test assembleDebug` →
    upload `app-debug.apk` artifact.
  - on tag `v*`: same build, attach APK to a GitHub Release.
- Debug signing with the default debug keystore generated in CI from a
  checked-in seed (stable signature so installs update in place); no Play
  Store, no secrets required initially.

## Testing
- Unit tests (JVM): mercator quantization round-trips, grid bit-shift
  aggregation math, dBm→color LUT (known dBm → expected ARGB), interpolation
  (synthetic cells → expected pixel values at cell centers and midpoints),
  CSV/GeoJSON serialization.
- Instrumented/manual: recording, dual-SIM callbacks, and overlay visuals
  verified on a real dual-SIM device in the field.

## Out of scope (YAGNI)
Background logging, server sync, multi-device merge, throughput tests,
per-network-type layers, iOS.
