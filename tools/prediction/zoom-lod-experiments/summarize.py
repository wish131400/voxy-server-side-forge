"""Archive completed pixel/zoom LOD tests; never launch a client or GPU job."""
import argparse
import csv
import difflib
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import xml.etree.ElementTree as ET
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--neoforge-root", required=True, type=Path)
    parser.add_argument("--forge-root", required=True, type=Path)
    args = parser.parse_args()
    roots = {"neoforge": args.neoforge_root.resolve(), "forge": args.forge_root.resolve()}
    relative = Path("tools/prediction/zoom-lod-experiments")
    report = {
        "date": "2026-10-12", "decision": "retain",
        "behavior": ["rendered projection drives visible refinement", "scoped display uses bounded pixel LOD",
                     "zoom-in selects fine ranges; zoom-out reuses existing coarse ranges",
                     "disabled spyglass loading keeps ordinary generation scale"],
        "method": {"gpu": "RTX 4070 Ti SUPER, NVIDIA 572.70, OpenGL 4.6", "target": [256, 256],
                   "warmups": 24, "rounds": 81, "order": "alternating paired A/B", "repetitions": 64,
                   "cpu": "actual PreparedPass plus static replay/submission; excludes glFinish and query readback",
                   "gpu": "GL_TIME_ELAPSED around prepare and submission",
                   "baseline": "exact preceding dirty worktree PreparedPass, test-only ZoomBaselinePass",
                   "projection_degrees": [70, 8.75], "zoom_near_camera": [64, 1040, 1344],
                   "zoom_far_camera": [64, 7280, 9664], "live_fps_measured": False},
        "limitations": ["not a live game scene or game FPS A/B", "whole-tile errors and existing variant availability remain",
                        "plant error is an importance proxy, not a bound on the position of every deleted stalk",
                        "water, seams, morph and mixed ownership retain their protections",
                        "other zoom mods require their actual world projection to reach the render event; not all zoom mods tested"],
        "repositories": {},
    }
    for flavor, root in roots.items():
        base = root / relative / "baseline" / flavor
        main_sources = root / "src/main/java/dev/xantha/vss/client/prediction"
        changed = [file.name for file in base.glob("*.java")
                   if file.read_text(encoding="utf-8-sig") != (main_sources / file.name).read_text(encoding="utf-8-sig")]
        assert sorted(changed) == ["PredictionRenderer.java", "VssLodProjection.java"], changed
        patch = "".join(line for name in changed for line in difflib.unified_diff(
            (base / name).read_text(encoding="utf-8-sig").splitlines(True),
            (main_sources / name).read_text(encoding="utf-8-sig").splitlines(True),
            fromfile="baseline/" + name, tofile="current/" + name))
        groups = {}
        build = root / "build-zoom-lod-20261012"
        with (build / "results/zoom-submission.csv").open(encoding="utf-8", newline="") as stream:
            for row in csv.DictReader(stream):
                groups.setdefault((row["content"], row["scenario"], row["mode"]), []).append(row)
        metrics = []
        for key, rows in groups.items():
            item = dict(zip(["content", "scenario", "mode"], key), samples=len(rows), metrics={})
            assert len(rows) == 81
            for field in ["gpu_ns", "cpu_ns", "mdi_quads", "mdi_commands", "display_tier", "vegetation_tier"]:
                values = sorted(int(row[field]) for row in rows)
                item["metrics"][field] = {"median": statistics.median(values), "p95": values[math.ceil(.95 * len(values)) - 1]}
            metrics.append(item)
        tests = {}
        for name in ["zoom-lod-regression", "zoom-lod-gpu", "zoom-lod-production-gpu"]:
            suites = [ET.parse(path).getroot() for path in (build / "test-results" / name).glob("TEST-*.xml")]
            assert suites, name
            tests[name] = {field: sum(int(suite.attrib.get(field, 0)) for suite in suites)
                           for field in ["tests", "failures", "skipped", "errors"]}
            assert tests[name]["failures"] == tests[name]["skipped"] == tests[name]["errors"] == 0
        jar = next((root / "lib/zoom-lod-20261012").glob("*.jar"))
        with zipfile.ZipFile(jar) as package:
            assert package.testzip() is None
            assert b"refinementScale" in package.read("dev/xantha/vss/client/prediction/VssLodProjection.class")
            assert not [name for name in package.namelist() if name.endswith("Test.class")
                        or name.startswith("dev/xantha/vss/client/prediction/Zoom") or name.startswith("org/junit/")]
            natives = {name: digest(package.read(name)) for name in package.namelist() if name.endswith((".dll", ".so", ".dylib"))}
            with zipfile.ZipFile(next((root / "lib/design-benefit-20261012").glob("*.jar"))) as previous:
                assert natives == {name: digest(previous.read(name)) for name in previous.namelist() if name.endswith((".dll", ".so", ".dylib"))}
        report["repositories"][flavor] = {
            "changed_production_files": changed,
            "baseline_hashes": {path.name: digest(path.read_bytes()) for path in base.glob("*.java")},
            "production_hashes": {name: digest((main_sources / name).read_bytes()) for name in changed},
            "metrics": metrics, "tests": tests,
            "jar": {"path": jar.as_posix(), "size": jar.stat().st_size, "sha256": digest(jar.read_bytes()),
                    "zip_integrity": "passed", "native_hashes": natives, "test_classes": []},
            "screen_error_checks": {"terrain": {"projected": 1.593359, "limit": 2},
                                    "plants_importance_proxy": {"projected": 1.125419, "limit": 1.5}},
        }
        for destination_root in roots.values():
            destination = destination_root / relative / "results" / flavor
            destination.mkdir(parents=True, exist_ok=True)
            for path in (build / "results").glob("*.csv"):
                shutil.copy2(path, destination / path.name)
            for path in (build / "test-results").rglob("TEST-*.xml"):
                target = destination / "test-results" / path.relative_to(build / "test-results")
                target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, target)
            (destination / "production.patch").write_text(patch, encoding="utf-8")
            target = destination_root / relative / "baseline" / flavor
            if target != base and not target.exists():
                shutil.copytree(base, target)
        (jar.parent / (jar.name + ".sha256")).write_text(digest(jar.read_bytes()) + "  " + jar.name + "\n", encoding="ascii")
    for root in roots.values():
        (root / "docs/vss-zoom-pixel-lod-20261012.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: {"tests": value["tests"], "jar": value["jar"]["sha256"]}
                      for key, value in report["repositories"].items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
