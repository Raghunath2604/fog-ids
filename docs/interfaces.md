# Interfaces — what Member B exposes to the other two tracks

Concrete, as-built, matching the code in `simulation/src/main/java/fogids/sim/`.
This supersedes the earlier planning-stage version of this document: numbers
and method names below exist and run today, not just as a design intent.

## 1. Node telemetry interface (for Member C's resource monitor)

`FogDevice.getLastUtilization()` — a small patch to the vendored iFogSim2
source (see the PROJECT PATCH comment in
`src/main/java/org/fog/entities/FogDevice.java`) exposing the library's
internal utilisation tracking, which was previously `protected` with no
accessor.

- Returns a fraction in `[0, 1]`: MIPS currently allocated to running
  modules divided by the device's total MIPS.
- Updated on every processing pass, not on a fixed clock — read it
  when you need it, don't assume a tick rate.
- **Empirically bimodal at the raw sample level** (see `results/NOTES.md`,
  item 3): synchronised sensor bursts mean a fog node reads either near-0%
  or 100% far more often than a middle value. Any policy reading this
  signal (tier switching, quarantine confidence) MUST smooth it — an EWMA
  over several samples, not the instantaneous value — or it will be reacting
  to sampling artifacts, not real load.
- `MetricsController` (see below) already samples this every 50 ms into
  `results/utilization.csv` as a reference implementation of a telemetry
  reader.

## 2. Tuple-processing hook (for Member C's fast path / slow path)

Implemented as the application graph itself, in `AppGraph.java`, not as a
separate callback API — iFogSim2's module model doesn't have a
call-site hook in the way an imperative framework would.

- `fastpath_filter` is the module every sensor tuple hits first. It fans
  out to two children: `fog_processing` (the blocking path — its result
  reaches the vehicle actuator) and `ids_inference` (the non-blocking
  path — its result only ever reaches `cloud_analytics`, never gates the
  actuator edge). This structurally guarantees the non-blocking property:
  there is no edge from `ids_inference` back into the path that reaches
  `ECU_ACTION`.
- Member C's fast-path checks (HMAC, rate limit, schema) become the
  actual processing logic of `fastpath_filter` once implemented; today
  it runs at a fixed trivial cost (`AppGraph.FASTPATH_MIPS = 1`) as a stub
  that proves the wiring.
- Tier switching (which cost `ids_inference` should run at) is not yet
  wired to `getLastUtilization()` — the module currently runs at one
  fixed stub cost (`AppGraph.IDS_INFERENCE_MIPS_STUB`). Wiring this
  requires either (a) a custom AppModule/scheduler subclass that varies
  per-tuple MIPS cost based on live device state, or (b) three separate
  module variants with a placement/routing decision between them made
  periodically by a monitor entity. Neither is implemented yet; this is
  the next integration milestone once Member A's real per-tier cost
  table exists (see `AppGraph.java`'s STUB comments).

## 3. Inference cost table (from Member A, consumed here)

Not yet received. `AppGraph.IDS_INFERENCE_MIPS_STUB` is a placeholder
standing in for the "reduced tier" until `ml/metrics/tier_costs.csv`
exists. When it lands: convert Member A's measured milliseconds into
MIPS-equivalent units at the same relative scale as `FOG_PROCESSING_MIPS`
(see the calibration note in `AppGraph.java` — absolute MIPS values here
are simulation units, not real hardware MIPS; what must be preserved is
the *ratio* of cost between tiers and against normal processing cost).

## 4. Proxy rerouting call (for Member C's quarantine/failover)

Not yet implemented. The topology (`Topology.java`) already gives every
fog node a `proxy` parent with connectivity to its two peers via that
shared parent, which is the physical precondition for rerouting. What's
missing is the actual mechanism: iFogSim2 doesn't have a live "reroute
this device's traffic to that device" primitive, so this will need
either dynamic sensor `gatewayDeviceId` reassignment at simulation
runtime (moving a sensor's parent fog node mid-run) or a proxy-level
routing module that Member C's quarantine state machine calls into.
Flagged as an open design question for the week-5/6 integration session,
not a solved contract yet.

## 5. Attack label schema (from Member C, consumed by Member A)

Not yet received; nothing in the simulation depends on it today. Once
Member C's injectors exist, they'll need a way to alter sensor emission
(rate, timing, payload) at runtime — `Rng.java` already threads a `--seed`
argument through every run for exactly this, even though nothing
consumes randomness yet.
