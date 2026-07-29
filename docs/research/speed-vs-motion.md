# Why throughput drops when you move — and what the map should do about it

## The observation

Measured downlink is lower when travelling fast, even at identical signal
strength. This is not measurement noise; it is a stack of real effects.

## Causes, strongest first

**1. Handover interruptions (dominant).** Crossing a cell boundary costs an
interruption: ~30–50 ms for an intra-frequency X2/Xn handover, 100–200 ms for
S1-based, and up to seconds for inter-RAT (LTE→3G) or a failed handover with
re-establishment. The interruption itself is small — the *TCP consequence* is
not. Packet loss collapses the congestion window; recovery takes several RTTs,
and on a 50 ms-RTT link a cwnd rebuild costs seconds. At 60 km/h in a dense
urban grid you may hand over every 20–40 s, so a meaningful fraction of the
drive is spent in post-handover recovery rather than at line rate.

**2. CQI aging / stale channel estimates.** The UE measures channel quality,
reports it, and the base station schedules ~5–10 ms later using that report.
Doppler at 1.8 GHz and 100 km/h is ≈ 167 Hz, giving a coherence time of
roughly 0.4/f_D ≈ 2.4 ms. The channel has already changed by the time the
grant is issued, so the chosen MCS is wrong: too aggressive → block errors and
HARQ retransmissions, too conservative → wasted capacity. Either way, goodput
falls at unchanged average SINR.

**3. MIMO rank collapse.** Spatial multiplexing needs a stable, well-conditioned
channel matrix. Under high Doppler the network falls back from 4×4/2×2 spatial
multiplexing to transmit diversity — a direct halving or quartering of peak
rate that has nothing to do with signal strength.

**4. Loss of multi-user diversity.** Proportional-fair schedulers gain
throughput by serving each user near their channel peaks. That gain depends on
accurate short-term CQI, so it evaporates at speed — a systematic loss even in
an uncongested cell.

**5. Beam management (5G, especially mmWave).** Beam tracking and refinement
lag a fast-moving UE; brief misalignment causes severe drops and beam-failure
recovery events.

**6. Fast fading traversal.** Moving sweeps the UE through deep fades
continuously. Averaged over a second, throughput at a given mean SINR is lower
than for a stationary UE in a favourable fade.

Published measurement studies commonly find LTE throughput at highway speeds
at roughly 30–60 % of stationary throughput under otherwise similar radio
conditions; mmWave 5G degrades far more sharply.

## The measurement-side problem (ours, not the network's)

Independent of physics, fast movement breaks *spatial attribution*:

- Our rolling window is 1 s and the EWMA (α = 0.4) has an effective time
  constant of ~2.5 s. At 100 km/h that smears one reading across ~70 m —
  dozens of cells at the finest zoom.
- So at speed, a "slow spot" on the map may simply be where a handover
  happened to land, and the value is spread over the road behind it.

## The tempting mistake: normalisation

It is tempting to divide out the effect — estimate "what this spot would give
if you stood still". I recommend **not** doing this silently:

- The correction factor depends on band, cell radius, vendor scheduler, MIMO
  configuration, handover topology and RTT. There is no defensible universal
  coefficient.
- Learning it from the user's own data is confounded: fast movement happens on
  highways served by different cells than slow movement in town. A naive
  regression of throughput on motion would attribute genuine geographic
  differences to speed.
- A paired estimator (only cells visited both fast and slow) is statistically
  sound but data-starved in practice.

A map that silently multiplies measurements by an invented constant looks
precise and is wrong. Worse, the error is invisible to the user.

## What we do instead: record the confound, expose it, let the user filter

1. **Store movement speed with every sample** (`speedMps`, from the GPS fix).
   Without it no analysis is possible; with it every later option stays open.
2. **Keep MAX-per-cell aggregation for throughput.** This is a natural,
   assumption-free partial correction: a cell's best observation is usually
   its slowest, most stable visit, so degraded fast-moving samples simply
   never win the maximum.
3. **Offer a "slow samples only" filter** (< 18 km/h) so like is compared with
   like when the user wants a clean picture. Off by default; honesty first.
4. **Show motion context in the spot dialog** — `12.3 Mbps at 45 km/h` tells
   the user immediately how much to trust the number. This is the single most
   valuable change: it converts a hidden bias into visible information.
5. **Adapt smoothing to motion.** Heavy smoothing is right when stationary
   (accuracy) and wrong when moving (spatial smear). α scales from 0.4 at rest
   to 1.0 (raw 1 s window) at ~15 m/s — the standard bias/variance trade,
   resolved in favour of correct spatial attribution when moving.

Signal strength (dBm) needs none of this: RSRP is an averaged power
measurement and is only mildly motion-sensitive. Latency is somewhat affected
(handover spikes), throughput most of all — which is exactly where the filter
and the disclosure are applied.
