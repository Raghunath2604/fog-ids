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
load sits at 30-40% utilisation on a fog node, comfortably inside the
report's "full tier" band (<60%) with real headroom for an attack surge
to consume. Ratios were preserved; the ratio, not the literal number,
is what future retuning against Member A's real cost table must keep.

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

## 3. Utilization trace is bimodal, not smooth (observed, not yet fixed)

`utilization.csv` shows each fog node's sampled utilisation swinging
between 0.0 and 1.0 rather than settling near a steady value, even
though the time-averaged mean (~0.485 for a fog node in FOG_WITH_IDS)
is a sensible, usable figure. Cause: all sensors on a node use the same
DeterministicDistribution period with no phase offset, so all 15 (or 6)
sensor/vehicle pairs on a node fire in lockstep every 50 ms — the node
processes a synchronised burst, then goes idle, and our 50 ms sample
interval happens to be sampling almost exactly on that cycle.

This is not fixed here for two reasons: first, it is a genuine argument
for the EWMA smoothing the security-control-plane design already commits
to (docs from that track state "the monitor acts on a short moving
average of headroom, not a single instantaneous sample" — this trace is
the empirical case for why that matters, not merely a defensive
assumption); second, real V2X beacon traffic is not perfectly
synchronised either, so staggering sensor phase is a real-world realism
improvement worth making together with Member C's jitter/timing work on
attack traffic, rather than patched in isolation here. Tracked as a
known refinement, not hidden.
