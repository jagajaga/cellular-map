# Research: smart connection-speed measurement per map point

Status: superseded — see "What shipped" at the bottom. The adaptive chunked
design below was implemented in v0.7.0 and then replaced in v0.10.0 by
continuous streaming, which measures throughput *at* each position instead of
around it.

## The problem

Naive speed tests download tens of MB per run. At a sample every 5 s that
would burn hundreds of MB per walk and skew the network itself. We want a
throughput *estimate* per area, cheap enough to run repeatedly on mobile data.

## Options considered

### 1. Passive observation (0 extra bytes)
`NetworkStatsManager` / `TrafficStats` deltas: watch bytes actually
transferred by the phone during normal use, divide by active time.
- + Free, no data cost, no network distortion.
- − Only produces data when the user happens to transfer something; walking
  with an idle phone yields nothing. Rate is demand-limited, not
  capacity-limited, so it underestimates.
- Verdict: good as an opportunistic *floor* estimate, useless alone.

### 2. Latency-derived estimate (~KBs)
Estimate bandwidth from RTT + RTT variance under a few parallel small
requests (bufferbloat correlates with congestion).
- + Nearly free; we already measure RTT via the ping probe.
- − Physics says RTT alone cannot give throughput; correlation is weak
  (an empty 2G cell has fine RTT and terrible bandwidth).
- Verdict: keep RTT as its own layer (done), don't pretend it's speed.

### 3. Adaptive chunked download — RECOMMENDED (~100 KB–3 MB, self-limiting)
Fetch from a CDN endpoint that serves arbitrary byte counts, growing the
request until the measurement stabilizes, then stop:

1. Warm up: fetch 64 KB (fills TCP slow-start, discard timing).
2. Fetch 256 KB, measure rate r1.
3. Keep doubling (512 KB, 1 MB, 2 MB…) while: elapsed < 1.5 s total per
   chunk AND rates keep changing by > 20 % (still slow-start limited).
4. Stop at the first chunk whose rate is within 20 % of the previous chunk
   (stable), or at a hard budget cap (default 3 MB / 4 s).
5. Report the last stable rate as Mbps.

On fast LTE this converges around 2–3 MB in ~2 s; on EDGE the 1.5 s time cap
stops it after ~30 KB — the *worse the network, the cheaper the test*, which
is exactly the right shape for a coverage-mapping app.

Endpoints (all free, no key):
- `https://speed.cloudflare.com/__down?bytes=N` — global anycast CDN,
  designed for this; also has `__up` for upload.
- Fallback: any large static file on a CDN with HTTP Range requests
  (`Range: bytes=0-N`).

Accuracy notes:
- Measure application-layer goodput; subtract the warm-up chunk.
- Single TCP connection underestimates on high-BDP links; 2–3 parallel
  connections (like fast.com does) fixes it at 2–3× the data cost. For a
  relative "better here / worse there" map, one connection is enough.

### 4. Full-fat test libraries (10–100 MB)
Ookla-style multi-stream saturation tests.
- Verdict: rejected — accuracy we don't need at a data cost we can't afford
  per-point.

## Proposed integration (when we implement)

- New nullable `speedKbps` column on samples (same migration pattern as
  `pingMs`), new "Speed" map mode with MAX-per-cell aggregation.
- Do NOT run per 5 s sample. Trigger policy:
  - every Nth sample (e.g. once per 60 s), AND
  - immediately when the grid cell under the phone has no speed value yet
    (first visit to an area), AND
  - manual "test here" button for spot checks.
- Budget guard: configurable daily cap (default e.g. 100 MB), skip when the
  cap is hit or when on a metered-and-roaming connection.
- Data-SIM only (same constraint as ping/YT probes).

## Cost model (recommended design)

| Network | Converges after | Data per test | Tests per walk-hour (60 s cadence) | Data per hour |
|---|---|---|---|---|
| EDGE | time cap | ~30 KB | 60 | ~2 MB |
| 3G | ~1 MB | ~1 MB | 60 | ~60 MB → cap throttles to ~30 |
| LTE | ~2–3 MB | ~3 MB | 60 | cap limits to ~33 tests |

The daily budget cap is the real limiter on fast networks; on slow networks
(the interesting areas!) tests are nearly free.

## What shipped (v0.10.0): continuous streaming

The discrete design has a flaw that matters for a *map*: a test occupies a
window of time, so on the move it starts in one cell and finishes in another.
Shrinking the window to fit the movement (v0.7.0) fixed the smearing but
capped accuracy — and still left most positions with no measurement at all.

Continuous streaming inverts it:

- One long-lived download (`speed.cloudflare.com/__down?bytes=5GB`) is drained
  to nowhere while recording, reconnecting automatically when it ends or fails.
- Throughput is tracked over a rolling 1 s window with EWMA smoothing
  (α = 0.4), exposed as `SpeedStream.currentKbps`.
- Every GPS fix reads the *instantaneous* rate, so the value belongs to the
  position where it was measured. Every sample gets a speed, not every 30th.
- Failures are data, not gaps: a stalled or failed stream records 0 kbps —
  "no usable throughput here" is exactly what a coverage map should show.

Trade-off accepted: this saturates the link continuously, so it is strictly
opt-in (⬇ toggle, off by default) with a live "X MB used" counter in the UI.
The old bounded-cost design remains the right choice if a data budget ever
becomes a hard requirement.
