# Engineering notes — calibration and modelling fixes

This file exists because the report's own discipline is to report what
was measured, not what was hoped for, and that includes documenting the
mistakes found on the way. Two were found in the first real end-to-end
runs and fixed before the results in results.csv were accepted.

## 1. Node oversubscription (fixed before this file was created)

First pass used FASTPATH_MIPS=1 / FOG_PROCESSING_MIPS=800 / IDS=1200
relative to a 4000-MIPS fog node. With fog-2's 15 sensor/vehicle pairs
emitting at 20 Hz, that oversubscribed each node by roughly 150x and
pinned utilisation at 100% even with no attack present — which would
have made any later resource-aware tier-switching demonstration
meaningless, since there is no "spare" headroom to switch on. MIPS
values were rescaled by roughly two orders of magnitude so that normal
load sits at a low, non-saturated level — see item 3 below for the
corrected measurement of what that level actually is; the figure
originally written here (30-40%) was measured with a sampler later
found to be broken. Ratios were preserved; the ratio, not the literal
number, is what future retuning against Member A's real cost table
must keep.

## 2. Verdict-logging cost model (fixed 2026, first full run_all.sh pass)

Before the fix, every verdict from ids_inference was sent to the cloud
analytics module at the SAME per-tuple cost as full sampled telemetry
(CLOUD_ANALYTICS_MIPS = 4, ~1000 network units), and at selectivity 1.0
(every single tuple, not sampled). Result, across 5 seeds at 5000 ms:

| scenario       | cloud execution cost |
|----------------|----------------------|
| CLOUD_ONLY     | ~170                 |
| FOG_NO_IDS     | ~24                  |
| FOG_WITH_IDS   | ~4,607               |

FOG_WITH_IDS costing 27x more than the *centralised cloud baseline* is
indefensible for an architecture whose entire premise is keeping
processing at the edge. The cause: a verdict is a small tag and a
confidence value, not a reprocessed copy of the original sensor
payload, and a real security control plane aggregates/escalates rather
than streaming a full-cost record per classification. Fixed by giving
verdict logging its own lightweight cost (VERDICT_LOG_MIPS = 1,
VERDICT_LOG_NW_BYTES = 50) distinct from full analytics traffic.

This is exactly the kind of thing Member C's future quarantine logic
will refine further — most verdicts are "benign, ignore", and only a
sustained anomaly should escalate anything to the cloud at all. The fix
here is the minimum correction needed for the cost numbers to be
meaningful before that logic exists; it is not the final design.

See results/results.csv and results/utilization_trace.csv for the
corrected numbers, and run/run_all.sh to reproduce them.

## 3. Utilization trace was bimodal — root cause was sampler aliasing, not sensor sync (fixed)

`utilization.csv` used to show each fog node's sampled utilisation
swinging between exactly 0.0 and 1.0 on every single sample, alternating
almost perfectly. The original diagnosis in this file blamed "all
sensors fire in lockstep every 50 ms" — that's true, but it isn't the
real cause, and staggering sensor phase alone (tried first, see below)
did not fix the alternation.

**Actual root cause:** `MetricsController`'s old sampler read
`FogDevice.getLastUtilization()` once every `SAMPLE_INTERVAL_MS = 50.0`
— which is *exactly* `Topology.SENSOR_PERIOD_MS`. Sampling a periodic
signal at its own period is textbook aliasing: every sample lands at the
same relative phase in the burst/idle cycle, so the trace reads as a
suspiciously clean alternation regardless of the true duty cycle. This
is a property of *when you look*, not of *what the sensors are doing*.

**What was tried first and reverted:** staggering each sensor's
`transmissionStartDelay` across the period (spreading arrivals instead
of firing all 15 at once) fixed the aliasing appearance by coincidence
in one quick test, but a full run then showed `FOG_WITH_IDS` and
`CLOUD_ONLY` cloud execution cost inflating by roughly 100–1000x with no
clear mechanism — bisected by toggling the stagger on/off with
everything else held fixed, which confirmed the stagger, not the
sampling change, was the cause. The likely explanation is a MIPS
allocation/deallocation accounting quirk in `StreamOperatorScheduler`
under a staggered-arrival timing pattern it wasn't exercised with
before, but that wasn't confirmed by stepping through the scheduler
itself, and it wasn't worth shipping a change built on an unconfirmed
mechanism. The stagger was reverted; `Topology.java` is unchanged from
the previous commit. **Flagged for whoever next touches per-tuple
arrival timing** (most likely Member C, whose attack injectors will
need well-understood timing semantics for jitter and evasion traffic)
to investigate properly before relying on staggered or randomised
arrival patterns.

**Actual fix, kept:** `MetricsController` no longer point-samples.
It accumulates `getLastUtilization()` on a fine internal tick
(`FINE_TICK_MS = 1.0`) and writes the *mean* over each 25 ms reporting
window (`REPORT_MS`). This is robust to aliasing regardless of any
periodicity in the traffic, because it doesn't depend on picking a
sampling interval that happens not to collide with the signal's period
— it integrates instead. The resulting trace is smooth: no more 0/1
alternation, and the same fix required no changes to `Topology.java`.

**Correction to item 1's calibration claim, above:** the "~30-40%
normal-load utilisation" figure quoted when the MIPS values were
rescaled was itself measured using the old, aliased sampler and was
never real — that number was an artifact of always sampling mid-burst.
The corrected, time-averaged measurement shows true baseline utilisation
on fog-2 sits at roughly **3.5-3.9% mean** (range ~0-8%) under normal
traffic with the current MIPS settings, not 30-40%.

This is not a new problem to fix — it's arguably the *correct* baseline
for this architecture. The report's own worked example (Section 6)
frames normal operation as comfortably within budget, with the DDoS
attack specifically being what pushes a node's load up past the 60%
and 85% tier-switching thresholds. A system that idles near-empty until
attacked and only then needs to shed detection depth is the scenario
the tiering design exists for. What this correction actually means for
next steps: Member C's attack injectors (not yet built) are what should
be relied on to demonstrate the tier-switching behaviour under load —
the current MIPS settings should not be re-inflated to force a "busier"
baseline just to make idle-state utilisation look more dramatic.

See `results/results.csv` and `results/utilization.csv` for the current
numbers (headline latency/network/cost figures are unchanged by this
fix — only the utilisation trace's shape changed), and `run/run_all.sh`
to reproduce them.
