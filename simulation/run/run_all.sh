#!/usr/bin/env bash
# Runs all three comparison scenarios (Section "Simulation plan" in the
# architecture report) across a fixed set of seeds, and records a manifest
# per run: config, seed, git commit, timestamp. One JVM invocation per run
# (see RunScenario.java for why). Results accumulate in results/results.csv
# and results/utilization.csv; re-running this script from a clean results/
# directory reproduces both byte-for-byte given the same source tree.
#
# Usage: ./run_all.sh [maxSimTimeMs]

set -euo pipefail
cd "$(dirname "$0")/.."

MAX_SIM_TIME="${1:-5000}"
RESULTS_DIR="results"
CLASSES="target/classes"
LIBS=$(find lib -name "*.jar" | tr '\n' ':')
CP="${CLASSES}:${LIBS}"

SCENARIOS=(CLOUD_ONLY FOG_NO_IDS FOG_WITH_IDS)
SEEDS=(1 2 3 4 5)

mkdir -p "$RESULTS_DIR" "$RESULTS_DIR/manifests"

GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "no-git")

for scenario in "${SCENARIOS[@]}"; do
  for seed in "${SEEDS[@]}"; do
    run_label="${scenario}_seed${seed}"
    manifest="${RESULTS_DIR}/manifests/${run_label}.json"
    echo "=== Running ${run_label} (max sim time ${MAX_SIM_TIME} ms) ==="

    started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    java -cp "$CP" fogids.sim.RunScenario "$scenario" "$seed" \
        "${RESULTS_DIR}/results.csv" "${RESULTS_DIR}/utilization.csv" "$MAX_SIM_TIME"
    finished_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

    cat > "$manifest" <<EOF
{
  "run_label": "${run_label}",
  "scenario": "${scenario}",
  "seed": ${seed},
  "max_sim_time_ms": ${MAX_SIM_TIME},
  "git_commit": "${GIT_COMMIT}",
  "started_at": "${started_at}",
  "finished_at": "${finished_at}"
}
EOF
  done
done

echo "All runs complete. Results: ${RESULTS_DIR}/results.csv, ${RESULTS_DIR}/utilization.csv"
