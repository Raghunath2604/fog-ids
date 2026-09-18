"""
Regenerates the scenario-comparison figure from results/results.csv.
Run with no arguments: python3 charts/make_charts.py
Writes charts/scenario_comparison.png.

Nothing in this script hand-edits or estimates a value — every number
plotted is read from the committed CSV, which is itself produced by
run/run_all.sh. Re-run that script to refresh the underlying data before
re-running this one.
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results" / "results.csv"
OUT = ROOT.parent / "charts" if (ROOT.parent / "charts").exists() else ROOT / "charts"
OUT = ROOT / "charts"
OUT.mkdir(exist_ok=True)

SCENARIOS = ["CLOUD_ONLY", "FOG_NO_IDS", "FOG_WITH_IDS"]
LABELS = {"CLOUD_ONLY": "Cloud-only", "FOG_NO_IDS": "Fog, no IDS", "FOG_WITH_IDS": "Fog + IDS"}
COLORS = {"CLOUD_ONLY": "#94A3B8", "FOG_NO_IDS": "#2E4A9E", "FOG_WITH_IDS": "#7C3A66"}


def load():
    rows = list(csv.DictReader(open(RESULTS)))
    by_scenario = defaultdict(lambda: defaultdict(list))
    for r in rows:
        scen = r["scenario"]
        by_scenario[scen]["network"].append(float(r["network_usage"]))
        by_scenario[scen]["cost"].append(float(r["cloud_execution_cost"]))
        # loop_latencies is a "modA->modB=1.23 | modC->modD=4.56" string; pull the
        # ECU decision loop specifically (the one the report's 100ms budget is about).
        for part in r["loop_latencies"].split(" | "):
            if "ECU_ACTION" in part:
                by_scenario[scen]["ecu_latency"].append(float(part.split("=")[1]))
    return by_scenario


def mean(xs):
    return statistics.mean(xs) if xs else float("nan")


def stdev(xs):
    return statistics.stdev(xs) if len(xs) > 1 else 0.0


def main():
    data = load()
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.2))
    metrics = [
        ("ecu_latency", "ECU decision loop latency (ms)", axes[0]),
        ("network", "Network usage (arbitrary units)", axes[1]),
        ("cost", "Cloud execution cost (arbitrary units)", axes[2]),
    ]
    for key, title, ax in metrics:
        means = [mean(data[s][key]) for s in SCENARIOS]
        errs = [stdev(data[s][key]) for s in SCENARIOS]
        colors = [COLORS[s] for s in SCENARIOS]
        bars = ax.bar([LABELS[s] for s in SCENARIOS], means, yerr=errs, capsize=4, color=colors)
        ax.set_title(title, fontsize=11)
        ax.tick_params(axis="x", labelrotation=15)
        for b, m in zip(bars, means):
            ax.annotate(f"{m:,.1f}", (b.get_x() + b.get_width() / 2, b.get_height()),
                        ha="center", va="bottom", fontsize=8.5)
    fig.suptitle("Scenario comparison — mean across 5 seeds, error bars = 1 std dev", fontsize=12)
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    out_path = OUT / "scenario_comparison.png"
    fig.savefig(out_path, dpi=160)
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
