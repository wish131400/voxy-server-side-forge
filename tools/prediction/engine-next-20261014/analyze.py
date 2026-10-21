"""Summarize complete A/B samples; keep distributions and rejected controls."""
import csv, json, math, statistics, sys
from collections import defaultdict
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else "build-engine-next-20261014/results")
report = {}
for path in sorted(root.glob("next-*.csv")):
    rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))
    if not rows or "mode" not in rows[0]:
        continue
    keys = [key for key in ("tiles", "mesh", "distance", "case") if key in rows[0]]
    groups = defaultdict(list)
    for row in rows:
        groups[tuple(row[key] for key in keys) + (row["mode"],)].append(row)
    result = []
    for group, samples in sorted(groups.items()):
        record = dict(zip(keys + ["mode"], group))
        record["samples"] = len(samples)
        for field in samples[0]:
            if field in keys + ["mode", "round", "yaw"]:
                continue
            try:
                values = [float(row[field]) for row in samples]
            except ValueError:
                continue
            if field == "elapsed_ns":
                values = [float(row[field]) / float(row["frames"]) for row in samples]
                field = "frame_ns"
            values.sort()
            record[field] = {"p50": statistics.median(values), "p95": values[max(0, math.ceil(len(values) * .95) - 1)], "min": values[0], "max": values[-1]}
        result.append(record)
    report[path.stem] = result
root.joinpath("next-summary.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

for category in ("next-iris-throughput", "next-submission-throughput", "next-variant-throughput"):
    grouped = defaultdict(dict)
    for row in report.get(category, []):
        label = "/".join(str(row[k]) for k in ("tiles", "mesh", "distance", "case") if k in row)
        grouped[label][row["mode"]] = row["frame_ns"]["p50"] / 1e6
    print(category)
    for label, modes in grouped.items():
        print(label, " / ".join(f"{mode}: {value:.6f} ms" for mode, value in sorted(modes.items())))
