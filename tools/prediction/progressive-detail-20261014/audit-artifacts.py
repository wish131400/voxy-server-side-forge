"""Audit the selected regressions and packaged progressive-detail release."""
from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET
import zipfile


PRODUCTION = (
    "PredictionAquaticLod", "PredictionDisplayGeometry", "PredictionGpuTile",
    "PredictionPackedMesh", "PredictionRenderer", "PredictionSubmissionCoverage",
    "PredictionVegetationRanges", "PredictionSubmissionChanges",
    "PredictionTerrainDisplayRanges",
)
GROUPS = (
    "progressive-detail-cpu", "loading-benefit", "village-water-cpu",
    "progressive-detail-gpu", "loading-benefit-gpu", "village-water-gpu",
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()
    build = root / "build-progressive-detail-20261014"
    release = root / "lib/progressive-detail-20261014"
    jars = sorted(release.glob("vss-0.3.5-*.jar"))
    assert len(jars) == 1, "one platform release is required"
    jar = jars[0]
    neoforge = "neoforge" in jar.name
    required_major = 65 if neoforge else 61
    groups = {}
    unique = {"cpu": set(), "gpu": set()}
    for group in GROUPS:
        reports = sorted((build / "test-results" / group).glob("TEST*.xml"))
        assert reports, f"missing test results: {group}"
        total = dict(tests=0, skipped=0, failures=0, errors=0)
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in total:
                total[key] += int(suite.get(key, "0"))
            kind = "gpu" if group.endswith("gpu") else "cpu"
            # Parameterized methods can deliberately use the same display name.
            # Count those executions, but deduplicate the same suite across tasks.
            occurrences = Counter()
            for case in suite.findall("testcase"):
                key = (case.get("classname", suite.get("name")), case.get("name"))
                occurrence = occurrences[key]
                occurrences[key] += 1
                unique[kind].add((*key, occurrence))
        assert total["tests"] > 0 and not total["failures"] and not total["errors"], (group, total)
        groups[group] = total

    with zipfile.ZipFile(jar) as archive:
        assert archive.testzip() is None, "archive CRC failure"
        names = set(archive.namelist())
        metadata_name = "META-INF/neoforge.mods.toml" if neoforge else "META-INF/mods.toml"
        metadata = archive.read(metadata_name).decode("utf-8")
        version = "0.3.5-neoforge-1.21.1" if neoforge else "0.3.5-forge-1.20.1"
        assert f'version="{version}"' in metadata
        classes = {}
        for name in PRODUCTION:
            entry = f"dev/xantha/vss/client/prediction/{name}.class"
            payload = archive.read(entry)
            major = int.from_bytes(payload[6:8], "big")
            assert major == required_major, (name, major)
            classes[name] = {"major": major, "bytes": len(payload)}
            if neoforge:
                compiled = build / "classes/java/main" / entry
                assert payload == compiled.read_bytes(), f"stale packaged class: {name}"
        natives = sorted(name for name in names if name.startswith("META-INF/vss-natives/") and not name.endswith("/"))
        assert len(natives) == 5, "all five existing native targets must remain packaged"
        mixins = ("vss.compat.mixins.json", "vss.strict.mixins.json", "vss.lostcities.mixins.json")
        for name in mixins:
            json.loads(archive.read(name))
        if not neoforge:
            refmap = json.loads(archive.read("vss.refmap.json"))
            assert refmap.get("mappings"), "Forge remapping metadata is required"
            renderer = archive.read("dev/xantha/vss/client/prediction/PredictionRenderer.class")
            assert b"m_91087_" in renderer, "Forge Minecraft references must be reobfuscated"
        assert not any(name.startswith("progressive-detail/") for name in names), "test captures must not ship"
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        assert "Implementation-Version: 0.3.5" in manifest

    evidence = root / "tools/prediction/progressive-detail-20261014/evidence"
    replay = list(csv.DictReader((evidence / "historical-corpus.csv").open(encoding="utf-8", newline="")))
    assert len(replay) == 2019
    sums = {column: sum(int(row[column]) for row in replay) for column in
            ("source", "ground", "features", "old4096", "new4096", "old8192", "new8192",
             "mixed_old4096", "mixed_new4096", "mixed_old8192", "mixed_new8192", "old_bytes", "new_bytes")}
    corpus = {
        "meshes": len(replay),
        "shared_ranges": sum(row["shared"] == "true" for row in replay),
        "newly_admitted_terrain_proxy": sum(row["shared"] == "true" and row["old_variants"] == "false" for row in replay),
        "sums": sums,
        "mixed_case_assumption": "all terrain owned; features in handoff; not measured live scene share",
    }
    gpu_rows = list(csv.DictReader((evidence / "isolated-gpu-benefit.csv").open(encoding="utf-8", newline="")))
    gpu = []
    for mesh in sorted({int(row["mesh"]) for row in gpu_rows}):
        row = {"mesh": mesh}
        for mode, label in (("0", "before"), ("1", "after")):
            selected = [item for item in gpu_rows if int(item["mesh"]) == mesh and item["mode"] == mode]
            row[label] = {key: statistics.median(int(item[key]) for item in selected) for key in ("gpu_ns", "cpu_ns", "quads", "commands")}
        gpu.append(row)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    (release / "SHA256SUMS.txt").write_text(f"{digest}  {jar.name}\n", encoding="utf-8")
    sources = {name: hashlib.sha256((root / f"src/main/java/dev/xantha/vss/client/prediction/{name}.java").read_bytes()).hexdigest() for name in PRODUCTION}
    result = {
        "jar": jar.name, "sha256": digest, "bytes": jar.stat().st_size,
        "platform": "NeoForge 1.21.1" if neoforge else "Forge 1.20.1",
        "test_groups": groups, "unique_testcases": {key: len(value) for key, value in unique.items()},
        "class_audit": classes, "native_targets": natives, "source_sha256": sources,
        "historical_replay": corpus, "isolated_gpu_repeated_mesh": gpu,
        "live_client_long_run_measured": False,
    }
    (release / "validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("jar", "sha256", "bytes", "unique_testcases")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
