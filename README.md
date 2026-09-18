# fog-ids — Resource-Aware Intrusion Detection for Fog Nodes

Three-person internship project. One repo, one folder per track, so merges
almost never touch the same file across tracks.

```
ml/                 Member A — datasets, feature engineering, the three IDS tiers
simulation/          Member B — iFogSim2/CloudSim topology, scenarios, metrics
security-control/    Member C — fast path, resource monitor, quarantine, attacks
docs/                Shared: interfaces.md, threat-coverage.md, this project's contracts
```

See `docs/git-workflow.md` for branch naming, PR rules, and how the three
tracks avoid stepping on each other. See each folder's own README for what's
built, what's stubbed, and how to run it — start with `simulation/README.md`,
which is the only track with working end-to-end runs as of this commit.

## Status at a glance

| Track | Status |
|---|---|
| `simulation/` | Three scenarios running end to end, results checked in |
| `ml/` | Not started in this repo yet |
| `security-control/` | Not started in this repo yet |

## Quick start

```
git clone <repo-url>
cd fog-ids/simulation
bash run/run_all.sh 5000
```
