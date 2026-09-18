# security-control/ — Member C: fast path, resource monitor, quarantine, attacks

Not yet started in this repo. See the individual final report for the full
scope and week-by-week plan, and `docs/interfaces.md` for what `simulation/`
already exposes for this track to build against:

1. `FogDevice.getLastUtilization()` — the node telemetry interface
2. The `AppGraph` fast-path/slow-path structure — `fastpath_filter` fans out
   to `fog_processing` (blocking) and `ids_inference` (non-blocking); real
   fast-path logic (HMAC, rate limit, schema) replaces the current stub cost
3. Open question flagged, not yet solved: no proxy-rerouting primitive exists
   in the topology yet — see `docs/interfaces.md`, section 4

Branches: `feature/security-control-plane`, `feature/attack-injection`
