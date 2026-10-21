"""Archive already completed A/B runs; never launch Minecraft or a benchmark."""

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


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def text_file(path):
    return path.read_text(encoding="utf-8-sig")


def describe_csv(path, keys):
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    groups = {}
    for row in rows:
        groups.setdefault(tuple(row[key] for key in keys), []).append(row)
    result = []
    metrics = [key for key in rows[0] if key not in keys and key != "round"]
    for key, samples in groups.items():
        entry = dict(zip(keys, key))
        entry["samples"] = len(samples)
        entry["metrics"] = {}
        for metric in metrics:
            values = sorted(float(sample[metric]) for sample in samples)
            entry["metrics"][metric] = {
                "median": statistics.median(values),
                "p95": values[math.ceil(len(values) * .95) - 1],
                "min": values[0],
                "max": values[-1],
            }
        result.append(entry)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--neoforge-root", required=True, type=Path)
    parser.add_argument("--forge-root", required=True, type=Path)
    args = parser.parse_args()
    roots = {"neoforge": args.neoforge_root.resolve(), "forge": args.forge_root.resolve()}
    experiments = Path("tools/prediction/design-benefit-experiments")
    baseline_root = roots["neoforge"] / experiments / "baseline"
    report = {
        "date": "2026-10-12",
        "scope": "Voxy/Sodium design candidates; synthetic component A/B, not live game FPS",
        "gpu": "NVIDIA GeForce RTX 4070 Ti SUPER; NVIDIA 572.70; OpenGL 4.6",
        "method": {
            "gpu_warmups": 24, "gpu_measured_rounds": 81,
            "order": "alternating paired baseline/candidate", "target_pixels": [256, 256],
            "cpu_time": "prepare and submit, excluding glFinish and synchronous query readback",
            "gpu_time": "GL_TIME_ELAPSED surrounding production prepare/submission interval",
            "direction_repetitions": 64, "representation_repetitions": 128,
            "water_draws": 1024, "worker_warmups": 5, "worker_measured_rounds": 21,
            "prepare_warmups": 400, "prepare_measured_rounds": 301,
            "baseline": "exact beginning-of-turn dirty worktree snapshots; not Git HEAD",
        },
        "decisions": [
            {"candidate": "direct-mode conservative meshlet direction ranges", "decision": "retain",
             "guard": "keep seams, morph, unknown and double-sided; reject <12.5% quad reduction or >max(4,2*input_ranges) fragments"},
            {"candidate": "water sorting triggered by X/Z only", "decision": "retain",
             "guard": "ownership, GPU residency and geometry revisions still invalidate; horizontal ordering unchanged"},
            {"candidate": "2x2 local terrain LOD with exact joining rings", "decision": "reject",
             "reason": "tested implementation increased quads, commands, storage, build and GPU cost"},
            {"candidate": "2-block occupied canopy proxy", "decision": "reject",
             "reason": "GPU faster but filled layered gaps, added occupancy and lost minority materials"},
        ],
        "limitations": [
            "No whole-game FPS, P95 or P99 comparison was performed.",
            "Repeating synthetic geometry amplifies component cost; it is not a captured current scene.",
            "Local terrain selection uses assumed projection scale/depth, not the full live perspective selector.",
            "Canopy worker values are single diagnostic samples, not repeated worker A/B.",
            "Latest NeoForge candidate GPU XML contains only the water rerun; earlier direction/representation CSVs remain.",
            "Conditional upload estimation, page relocation and bamboo caching were not implemented without hotspot evidence.",
        ],
        "repositories": {},
    }
    patches = {}
    for flavor, root in roots.items():
        prior = baseline_root / flavor / "prediction"
        assert prior.is_dir(), prior
        current = root / "src/main/java/dev/xantha/vss/client/prediction"
        manifest = {path.name: sha256(path.read_bytes()) for path in sorted(prior.glob("*.java"))}
        changed = [name for name in manifest if text_file(prior / name) != text_file(current / name)]
        assert sorted(changed) == ["PredictionMeshletBounds.java", "PredictionPackedMesh.java", "PredictionRenderer.java"], changed
        added = sorted(path.name for path in current.glob("*.java") if not (prior / path.name).exists())
        assert added == ["PredictionCommandDirections.java"], added
        for name in ["PredictionDisplayGeometry.java", "PredictionVegetation.java"]:
            assert (prior / name).read_bytes() == (current / name).read_bytes(), name
        patch = []
        for name in changed + added:
            original = text_file(prior / name).splitlines(True) if (prior / name).exists() else []
            patch.extend(difflib.unified_diff(original, text_file(current / name).splitlines(True),
                         fromfile="baseline/" + name, tofile="current/" + name))
        patches[flavor] = "".join(patch)
        suites = {}
        test_root = root / "build-design-benefit-20261012/test-results"
        for group in sorted(test_root.iterdir()):
            files = sorted(group.glob("TEST-*.xml")) if group.is_dir() else []
            if not files:
                continue
            documents = [ET.parse(path).getroot() for path in files]
            suites[group.name] = {
                "classes": [document.attrib["name"] for document in documents],
                **{attr: sum(int(document.attrib.get(attr, "0")) for document in documents)
                   for attr in ["tests", "failures", "errors", "skipped"]},
            }
            assert suites[group.name]["failures"] == suites[group.name]["errors"] == suites[group.name]["skipped"] == 0
        result_root = root / "build-design-benefit-20261012/results"
        metrics = {
            name: describe_csv(result_root / name, keys) for name, keys in [
                ("benefit-direction.csv", ["scenario", "mode"]),
                ("benefit-water-full.csv", ["path", "mode"]),
                ("benefit-local-build.csv", ["scenario"]),
                ("benefit-representations.csv", ["candidate", "case", "mode"]),
                ("benefit-prepare.csv", ["water", "path", "mode"]),
            ]
        }
        with (result_root / "benefit-canopy-quality.csv").open(encoding="utf-8", newline="") as stream:
            quality = list(csv.DictReader(stream))
        jars = list((root / "lib/design-benefit-20261012").glob("*.jar"))
        assert len(jars) == 1, jars
        jar = jars[0]
        with zipfile.ZipFile(jar) as package:
            assert package.testzip() is None
            names = package.namelist()
            assert "dev/xantha/vss/client/prediction/PredictionCommandDirections.class" in names
            assert not [name for name in names if name.endswith("Test.class") or name.startswith("org/junit/")
                        or name.startswith("dev/xantha/vss/client/prediction/Benefit")
                        or name.startswith("dev/xantha/vss/client/prediction/Completion")]
            native = {name: sha256(package.read(name)) for name in names if name.endswith((".dll", ".so", ".dylib"))}
            previous = next((root / "lib/vegetation-lod-20261012").glob("*.jar"))
            with zipfile.ZipFile(previous) as old_package:
                assert native == {name: sha256(old_package.read(name)) for name in old_package.namelist()
                                  if name.endswith((".dll", ".so", ".dylib"))}
        report["repositories"][flavor] = {
            "root": root.as_posix(), "changed_production_files": changed, "new_production_files": added,
            "baseline_source_sha256": manifest,
            "final_changed_source_sha256": {name: sha256((current / name).read_bytes()) for name in changed + added},
            "tests": suites, "csv_metrics": metrics, "canopy_quality": quality,
            "raw_csv_sha256": {path.name: sha256(path.read_bytes()) for path in sorted(result_root.glob("*.csv"))},
            "jar": {"path": jar.as_posix(), "bytes": jar.stat().st_size,
                    "sha256": sha256(jar.read_bytes()), "native_sha256": native,
                    "zip_integrity": "passed", "experimental_classes": [], "natives_equal_previous": True},
        }
    # All source, evidence and package checks finish before writing archives.
    archive_relative = experiments / "results"
    for root in roots.values():
        for flavor, source_root in roots.items():
            archive = root / archive_relative / flavor
            archive.mkdir(parents=True, exist_ok=True)
            for csv_path in (source_root / "build-design-benefit-20261012/results").glob("*.csv"):
                shutil.copy2(csv_path, archive / csv_path.name)
            for group in (source_root / "build-design-benefit-20261012/test-results").iterdir():
                xmls = list(group.glob("TEST-*.xml")) if group.is_dir() else []
                for path in xmls:
                    target = archive / "test-results" / group.name
                    target.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, target / path.name)
            (archive / "production.patch").write_text(patches[flavor], encoding="utf-8")
        destination = root / "docs/vss-design-benefit-tests-20261012.json"
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        jar = Path(report["repositories"]["neoforge" if root == roots["neoforge"] else "forge"]["jar"]["path"])
        (jar.parent / (jar.name + ".sha256")).write_text(sha256(jar.read_bytes()) + "  " + jar.name + "\n", encoding="ascii")
    forge_baseline = roots["forge"] / experiments / "baseline"
    if not forge_baseline.exists():
        shutil.copytree(baseline_root, forge_baseline)
    print(json.dumps({key: {"tests": value["tests"], "jar": value["jar"]["sha256"]}
                      for key, value in report["repositories"].items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
