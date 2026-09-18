# fog-ids / simulation — reproduction guide

Member B's track: the iFogSim2/CloudSim environment, topology, and the
three comparison scenarios from the architecture report.

## What's real right now

- Full iFogSim2 (445 source files) vendored and compiling clean under JDK 21.
- A three-fog-node corridor topology matching the report's Section 6 worked
  example (fog-2 carries 15 sensor/vehicle pairs; fog-1 and fog-3 carry 6 each).
- An application graph with a genuinely non-blocking fast-path/slow-path split
  (see `docs/interfaces.md` for why the graph structure itself, not a runtime
  flag, is what guarantees non-blocking).
- All three comparison scenarios (`CLOUD_ONLY`, `FOG_NO_IDS`, `FOG_WITH_IDS`)
  running end to end and producing real CloudSim-computed latency, network
  usage, energy, and execution-cost numbers — see `results/results.csv` and
  `results/NOTES.md` for the corrected, currently-checked-in numbers.
- A per-device utilisation trace sampled every 50 ms
  (`results/utilization.csv`) — the concrete implementation of the "node
  telemetry interface" the resource monitor will read from.

## What's stubbed, on purpose

- `ids_inference` runs at one fixed cost representing a placeholder
  "reduced tier" — Member A's real three-tier cost table isn't wired in yet.
- No tier switching, no quarantine, no attack injection — those are Member
  C's track. The topology and application graph are built so that work can
  plug in without restructuring either (see `docs/interfaces.md`).

## Build

Maven Central isn't reachable in every environment this might run in, so
this project can be built two ways:

**Reference build (a dev machine with internet access):**
```
cd simulation
mvn compile
```
`pom.xml` declares iFogSim2's jars as system-scope dependencies against the
vendored copies in `lib/`, so this works offline too as long as your local
Maven plugin cache is already populated.

**Direct build (guaranteed to work with no Maven plugin resolution at all):**
```
cd simulation
find src/main/java -name "*.java" > /tmp/sources.txt
javac -d target/classes -cp "$(find lib -name '*.jar' | tr '\n' ':')" @/tmp/sources.txt
```

## Run

```
cd simulation
bash run/run_all.sh 5000       # 5000 = max simulated time in ms
```

Runs all three scenarios across five fixed seeds (15 runs total). Each run
is one JVM invocation (see the comment in `RunScenario.java` for why — the
base `Controller` class calls `System.exit(0)` on simulation stop). Results
accumulate in `results/results.csv` (one row per metric per run — long/tidy
format, so scenarios and seeds concatenate cleanly) and
`results/utilization.csv` (one row per device per sample). A manifest per
run — scenario, seed, git commit, timestamps — lands in
`results/manifests/`.

To run one scenario directly:
```
java -cp "target/classes:$(find lib -name '*.jar' | tr '\n' ':')" \
    fogids.sim.RunScenario FOG_WITH_IDS 1 results/results.csv results/utilization.csv 5000
```

## Known limitations (see results/NOTES.md for full detail)

1. Sensor emission is currently deterministic and unjittered, so seeds
   don't yet change output — that only starts to matter once Member C's
   attack injectors introduce randomised timing or targets. The `--seed`
   argument is already threaded through everything so nothing about the
   run harness needs to change when that lands.
2. Per-sample utilisation is bimodal (0% or 100%) due to synchronised
   sensor bursts; the time-averaged mean is meaningful, the instantaneous
   sample is not. Any future policy reading this signal must smooth it.
3. MIPS values throughout are simulation-relative units, not literal
   hardware MIPS — see the calibration note in `AppGraph.java`.
