"""Compare scalar VSS observations, rejecting mixed views and menu intervals.

This computes interval counter differences and never adds nested CPU/GPU stages.
Frame percentiles use differences of the cumulative 1 ms histogram, not averages
of the overlapping 90-frame diagnostic windows. No third-party dependencies.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import statistics


def intervals(path: Path):
    rows = []
    warnings = []
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, 1):
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                warnings.append(f"Skipped incomplete line {line_number}")
                continue
            if row.get("schema") != 1:
                raise ValueError(f"Unknown profile schema at {path}:{line_number}")
            rows.append(row)
    valid = []
    for a, b in zip(rows, rows[1:]):
        if (a["run"], a["session"], a["dimension"]) != (b["run"], b["session"], b["dimension"]):
            continue
        duration = (b["monotonicNanos"] - a["monotonicNanos"]) / 1e9
        if duration <= 0 or duration > 2.5 or a.get("menuOpen") or b.get("menuOpen") or b.get("menuDuringInterval"):
            continue
        if b.get("droppedRows", 0) != a.get("droppedRows", 0):
            continue
        frames = difference(a, b, "frameCounters", "frames")
        nanos = difference(a, b, "frameCounters", "durationNanos")
        if frames <= 0 or nanos <= 0:
            continue
        hist = [j - i for i, j in zip(a["frameCounters"]["histogramMillis"], b["frameCounters"]["histogramMillis"])]
        if len(hist) != 502 or min(hist) < 0 or sum(hist) != frames:
            continue
        if a.get("generation", {}).get("contentVersion") != b.get("generation", {}).get("contentVersion"):
            continue
        movement = math.dist(a["camera"], b["camera"])
        matrix_change = max(abs(x - y) for x, y in zip(a["modelView"], b["modelView"]))
        projection_change = max(abs(x - y) for x, y in zip(a["projection"], b["projection"]))
        if movement > 0.25:
            phase = "movement"
        elif matrix_change > 0.01:
            phase = "turn"
        elif projection_change > 0.001 or a["scoping"] != b["scoping"]:
            phase = "zoom-transition"
        else:
            phase = "scoped-static" if b["scoping"] else "normal-static"
        observation = dict(row=b, phase=phase, frameCount=frames, frameNanos=nanos, histogram=hist, seconds=duration,
                           long33=difference(a, b, "frameCounters", "over33ms"), long50=difference(a, b, "frameCounters", "over50ms"))
        observation["gpu"] = {}
        for stage, stats in b.get("timings", {}).items():
            if not isinstance(stats, dict):
                continue
            observation["gpu"][stage] = {}
            for clock in ("cpu", "gpu"):
                count = stats[clock + "Samples"] - a["timings"][stage][clock + "Samples"]
                elapsed = stats[clock + "Nanos"] - a["timings"][stage][clock + "Nanos"]
                if count > 0 and elapsed >= 0:
                    observation["gpu"][stage][clock] = (elapsed, count)
        observation["delta"] = {}
        for key in ("meshUploads", "preparedPlans", "reusedPlans", "submittedQuads"):
            value = b[key] - a[key]
            if value >= 0:
                observation["delta"][key] = value
        for key in ("built", "failed", "supersededBuilds", "visibleCpuNanos", "backgroundCpuNanos"):
            value = difference(a, b, "generation", key)
            if value >= 0:
                observation["delta"][key] = value
        valid.append(observation)
    return valid, warnings


def difference(a, b, group, key):
    return b.get(group, {}).get(key, 0) - a.get(group, {}).get(key, 0)


def percentile(hist, fraction):
    threshold = math.ceil(sum(hist) * fraction)
    count = 0
    for milliseconds, n in enumerate(hist):
        count += n
        if count >= threshold:
            return ">500" if milliseconds == 501 else milliseconds
    return None


def summarize(observations):
    if not observations:
        return {"intervals": 0}
    frames = sum(x["frameCount"] for x in observations)
    nanos = sum(x["frameNanos"] for x in observations)
    hist = [sum(x["histogram"][i] for x in observations) for i in range(502)]
    result = dict(intervals=len(observations), frames=frames, seconds=nanos/1e9, fps=frames*1e9/nanos,
                  p50MsUpper=percentile(hist, .5), p95MsUpper=percentile(hist, .95), p99MsUpper=percentile(hist, .99),
                  over33ms=sum(x["long33"] for x in observations), over50ms=sum(x["long50"] for x in observations))
    result["counters"] = {k: sum(o["delta"].get(k, 0) for o in observations) for k in observations[0]["delta"]}
    result["passes"] = {}
    for name in ("opaque", "water"):
        result["passes"][name] = {k: statistics.median(o["row"][name][k] for o in observations)
                                   for k in ("sourceQuads", "terrainQuads", "displayQuads", "selectedQuads", "noTerrainVariantTiles", "mixedOwnershipTiles", "activeMorphTiles")}
    result["residency"] = {k: statistics.median(o["row"]["gpuResidency"][k] for o in observations)
                           for k in ("liveBytes", "committedBytes", "sourceQuads", "displayQuads")}
    stages = defaultdict(lambda: defaultdict(lambda: [0, 0]))
    for observation in observations:
        for stage, clocks in observation["gpu"].items():
            for clock, (elapsed, count) in clocks.items():
                stages[stage][clock][0] += elapsed
                stages[stage][clock][1] += count
    result["timings"] = {stage: {clock: {"meanMs": elapsed/count/1e6, "samples": count}
                                for clock, (elapsed, count) in clocks.items()} for stage, clocks in stages.items()}
    return result


def same_view(a, b):
    x, y = a["row"], b["row"]
    if a["phase"] != b["phase"] or a["phase"] not in {"normal-static", "scoped-static"}:
        return False
    for key in ("dimension", "viewport", "detail", "horizonBlocks", "surfaceDistanceBlocks", "iris", "scoping", "gameVersion"):
        if x[key] != y[key]:
            return False
    return (math.dist(x["camera"], y["camera"]) < 0.5
            and max(abs(i-j) for i, j in zip(x["modelView"], y["modelView"])) < 0.005
            and max(abs(i-j) for i, j in zip(x["projection"], y["projection"])) < 0.001)


def compare(before, after):
    result = {}
    for phase in sorted({o["phase"] for o in before + after}):
        old = [o for o in before if o["phase"] == phase]
        new = [o for o in after if o["phase"] == phase]
        used = set()
        matched_old, matched_new = [], []
        for candidate in new:
            for i, previous in enumerate(old):
                if i not in used and same_view(previous, candidate):
                    used.add(i); matched_old.append(previous); matched_new.append(candidate)
                    break
        enough = sum(o["seconds"] for o in matched_old) >= 10 and sum(o["seconds"] for o in matched_new) >= 10
        result[phase] = dict(before=summarize(old), after=summarize(new),
                             matchedBefore=summarize(matched_old), matchedAfter=summarize(matched_new),
                             comparableFixedView=enough,
                             conclusion="Matched fixed view; assess frame tails, GPU time, residency and range counts together."
                             if enough else "Insufficient identical-view intervals. Workload observations only; no measured FPS gain conclusion.")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    old, old_warnings = intervals(args.before)
    new, new_warnings = intervals(args.after)
    report = dict(schema=1, before=str(args.before), after=str(args.after),
                  beforeSha256=hashlib.sha256(args.before.read_bytes()).hexdigest(),
                  afterSha256=hashlib.sha256(args.after.read_bytes()).hexdigest(),
                  warnings=old_warnings + new_warnings, phases=compare(old,new),
                  limits=["1 ms histogram buckets are upper bounds; bucket 501 represents >500 ms.",
                          "Menu intervals, dropped rows, session changes and >2.5-second gaps are excluded.",
                          "Nested GPU/CPU stages must not be summed.",
                          "Matching camera/projection does not guarantee the same world contents; check source counts and generation state.",
                          "Movement, turns and world entry require the same externally repeated route; no automatic causal FPS conclusion."])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    lines = ["# VSS 统一采样对照", "", "时间、面数和驻留分别核算；暂停／断档区间已排除。", "",
             "| 阶段 | 旧 FPS | 新 FPS | 旧 P99 上界 | 新 P99 上界 | 固定画面对齐 |",
             "| --- | ---: | ---: | ---: | ---: | --- |"]
    for phase, data in report["phases"].items():
        before, after = (data["matchedBefore"],data["matchedAfter"]) if data["comparableFixedView"] else (data["before"],data["after"])
        lines.append(f"| {phase} | {before.get('fps',0):.2f} | {after.get('fps',0):.2f} | {before.get('p99MsUpper','—')} | {after.get('p99MsUpper','—')} | {'是' if data['comparableFixedView'] else '否，仅工作量'} |")
    lines.extend(["", "详细阶段计数、GPU 样本量和驻留见同名 JSON。", "",
                  "相同相机、投影也需要核对世界内容、生成状态和素材。移动／转身／进档数据用于重复路线的核对，不自动认定 FPS 收益。"])
    args.output.with_suffix(".md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"Accepted intervals: {len(old)} before, {len(new)} after. Saved {args.output}")


if __name__ == "__main__":
    main()
