"""Archive the LIVE implementation validation and audit both packaged JARs.

This script only writes reports/evidence below the two repository tool directories.
It never installs a JAR, changes production code, or launches Minecraft. Run after
the CPU, GPU, seam benchmark, and jar tasks have completed on both platforms.
"""
from __future__ import annotations

import argparse
import collections
import csv
import difflib
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


EXPERIMENT = Path("tools/prediction/meridian-implementation-20261016")
BUILD = Path("build-meridian-implementation-20261016")
BASELINE_BUILD = Path("build-meridian-implementation-baseline-20261016")
MAIN = Path("src/main/java/dev/xantha/vss/client/prediction")
FROZEN = Path("tools/prediction/meridian-algorithms-20261016/production-src")
PRODUCTION = [
    "PredictionLodSeams", "PredictionRealBoundarySeams", "PredictionSeamPreparation",
    "PredictionSeamMesh", "PredictionSeamWalls", "PredictionMeshBuilder",
    "PredictionMeshCodec", "PredictionMemoryBudget", "PredictionTileManager", "PredictionRenderer",
]
REPORT = Path("docs/meridian-018-implementation-validation-20261016.md")
CURRENT_RESULT_FILES = (
    "implemented-seam-version-cost.csv",
    "seam-before-after.csv",
    "water-production-replay.csv",
)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def stats(values) -> dict:
    values = sorted(float(v) for v in values)
    rank = (len(values) - 1) * .95
    a, b = math.floor(rank), math.ceil(rank)
    return dict(n=len(values), median=statistics.median(values),
                p95=values[a] + (values[b] - values[a]) * (rank - a),
                minimum=values[0], maximum=values[-1])


def read_csv(path: Path) -> list[dict]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def grouped(rows, keys, columns) -> list[dict]:
    groups = collections.defaultdict(list)
    for row in rows:
        groups[tuple(row[k] for k in keys)].append(row)
    return [dict(zip(keys, key), **{col: stats(row[col] for row in data) for col in columns})
            for key, data in sorted(groups.items())]


def counts(folder: Path, expected: int | None = None) -> dict:
    files = sorted(folder.glob("TEST-*.xml"))
    assert files, f"Missing test XML: {folder}"
    result = {key: sum(int(ET.parse(p).getroot().get(key, "0")) for p in files)
              for key in ["tests", "failures", "errors", "skipped"]}
    if expected is not None:
        assert result == dict(tests=expected, failures=0, errors=0, skipped=0), (folder, result)
    return result


def patch(before: Path, after: Path) -> str:
    return "\n".join(line for line in difflib.unified_diff(
        before.read_text(encoding="utf-8").splitlines(), after.read_text(encoding="utf-8").splitlines(), n=0)
        if not line.startswith(("---", "+++", "@@")))


def shader_constants(data: bytes) -> list[bytes]:
    """Terrain GLSL lives in Java text blocks, not separate resource files."""
    count = struct.unpack_from(">H", data, 8)[0]
    at, index, found = 10, 1, []
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
              12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[at]
        at += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, at)[0]
            at += 2
            value = data[at:at + length]
            if len(value) > 500 and b"#version" in value and b"void main" in value:
                found.append(value)
            at += length
        else:
            at += widths[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    return sorted(found)


def audit_jar(root: Path, platform: str, output: Path) -> dict:
    jars = list((root / "lib/meridian-implementation-20261016").glob("*.jar"))
    assert len(jars) == 1, jars
    jar = jars[0]
    java = Path("C:/Program Files/Java") / ("jdk-21" if platform == "neoforge" else "jdk-17")
    markers = {
        "PredictionLodSeams": ["sameInputs", "inputReuses"],
        "PredictionRealBoundarySeams": ["rebind", "indexSurface"],
        "PredictionSeamPreparation$Builder": ["rebind"],
        "PredictionMemoryBudget": ["buildCosts={", "tryReserveRestore"],
        "PredictionRenderer$PreparedPass": ["waterOrderDirty", "waterSortIndex"],
        "PredictionTileManager": ["costDiagnostics", "completeBuild"],
    }
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert archive.testzip() is None
        classes = [name for name in names if name.endswith(".class")]
        test_classes = [name for name in classes if "/prediction/Implementation" in name
                        or "/prediction/Algorithm" in name or "/testutil/" in name or name.endswith("Test.class")]
        assert not test_classes, test_classes
        majors = sorted({struct.unpack(">H", archive.read(name)[6:8])[0] for name in classes})
        assert majors == ([65] if platform == "neoforge" else [61]), majors
        for name, required in markers.items():
            data = archive.read("dev/xantha/vss/client/prediction/" + name + ".class")
            assert all(text.encode() in data for text in required), (platform, name)
        compiled = root / BUILD / "classes/java/main"
        assert all((compiled / name).is_file() for name in classes)
        identical = sum(archive.read(name) == (compiled / name).read_bytes() for name in classes)
        if platform == "neoforge":
            assert identical == len(classes), "NeoForge JAR must contain the tested live classes"
        metadata = "META-INF/neoforge.mods.toml" if platform == "neoforge" else "META-INF/mods.toml"
        meta = archive.read(metadata).decode("utf-8")
        version = "0.3.5-neoforge-1.21.1" if platform == "neoforge" else "0.3.5-forge-1.20.1"
        assert f'version="{version}"' in meta
        assert f'modId="{platform}"' in meta
        natives = [name for name in names if name.endswith(".dll")]
        shaders = [p for p in (root / "src/main/resources").rglob("*")
                   if p.is_file() and p.suffix in {".vert", ".frag", ".glsl", ".comp", ".vsh", ".fsh"}]
        for source in shaders:
            name = source.relative_to(root / "src/main/resources").as_posix()
            assert archive.read(name) == source.read_bytes(), name
        terrain_class = "dev/xantha/vss/client/prediction/PredictionTerrainProgram.class"
        embedded_shaders = shader_constants(archive.read(terrain_class))
        assert len(embedded_shaders) == 2
        assert embedded_shaders == shader_constants((compiled / terrain_class).read_bytes())
        for name in natives:
            assert archive.read(name) == (root / "src/main/resources" / name).read_bytes()
        info = dict(path=jar.relative_to(root).as_posix(), bytes=jar.stat().st_size, sha256=sha(jar),
                    production_class_count=len(classes), class_major_versions=majors,
                    identical_to_tested_unmapped_classes=identical,
                    forge_reobfuscation_expected=platform == "forge", experimental_class_count=0,
                    production_markers=markers, verified_shader_files=len(shaders),
                    verified_terrain_shader_constants=[hashlib.sha256(data).hexdigest() for data in embedded_shaders],
                    metadata_path=metadata, version=version,
                    natives={name: hashlib.sha256(archive.read(name)).hexdigest() for name in natives})
        (output / "jar-metadata.toml").write_text(meta, encoding="utf-8")
    inspected = subprocess.run([str(java / "bin/javap.exe"), "-private", "-constants", "-classpath", str(jar),
                                "dev.xantha.vss.client.prediction.PredictionMeshCodec"],
                               capture_output=True, text=True, timeout=30, check=True)
    assert "VERSION = 14;" in inspected.stdout
    (output / "jar-mesh-codec-javap.txt").write_text(inspected.stdout, encoding="utf-8")
    return info


def collect(root: Path, platform: str) -> dict:
    output = root / EXPERIMENT / "results" / platform
    output.mkdir(parents=True, exist_ok=True)
    tests = {}
    for suite, expected in [("implementation-cpu", 214), ("implementation-gpu", 10), ("seam-benchmark", 1)]:
        source = root / BUILD / "test-results" / suite
        tests[suite] = counts(source, expected)
        shutil.copytree(source, output / "junit" / suite, dirs_exist_ok=True)
    tests["baseline-seam-benchmark"] = counts(root / BASELINE_BUILD / "test-results/seam-benchmark", 1)
    shutil.copytree(root / BASELINE_BUILD / "test-results/seam-benchmark",
                    output / "junit/baseline-seam-benchmark", dirs_exist_ok=True)
    # Only archive result files produced by the current source set. The old
    # attribute/demand CSVs remain historical evidence and must not look like
    # active validation output.
    for filename in CURRENT_RESULT_FILES:
        source = root / BUILD / "results" / filename
        if source.is_file():
            shutil.copy2(source, output / source.name)
    shutil.copy2(root / BASELINE_BUILD / "results/seam-before-after.csv", output / "seam-baseline.csv")
    for name in [f"build-meridian-final-{platform}.log", f"build-meridian-package-{platform}.log",
                 "build-meridian-seam-baseline.log", "build-meridian-seam-current.log",
                 "build-meridian-baseline-control.log"]:
        source = root / name
        if source.is_file():
            shutil.copy2(source, output / name)

    baseline = {p.name: sha(p) for p in sorted((root / EXPERIMENT / "baseline").glob("*.java"))}
    assert len(baseline) == 11
    write_json(root / EXPERIMENT / "baseline-sha256.json", baseline)
    expected_changed = {f"dev/xantha/vss/client/prediction/{name}.java" for name in PRODUCTION}
    changed = set()
    for old in (root / FROZEN).rglob("*.java"):
        relative = old.relative_to(root / FROZEN)
        new = root / "src/main/java" / relative
        assert new.is_file(), new
        if old.read_text(encoding="utf-8") != new.read_text(encoding="utf-8"):
            changed.add(relative.as_posix())
    assert changed == expected_changed, sorted(changed ^ expected_changed)
    changes = []
    for name in PRODUCTION:
        before = root / EXPERIMENT / "baseline" / f"{name}.java"
        after = root / MAIN / f"{name}.java"
        changes.extend(difflib.unified_diff(before.read_text(encoding="utf-8").splitlines(keepends=True),
                       after.read_text(encoding="utf-8").splitlines(keepends=True),
                       fromfile="baseline/" + before.name, tofile=(MAIN / after.name).as_posix()))
    (output / "production-changes.patch").write_text("".join(changes), encoding="utf-8")

    result = dict(tests=tests, baseline_files=baseline, changed_production_files=sorted(changed),
                  jar=audit_jar(root, platform, output))
    for filename, keys, columns in [
        ("seam-baseline.csv", ["scenario"], ["cpu_ns", "allocated_bytes", "updated_surfaces", "patch_updates"]),
        ("seam-before-after.csv", ["scenario"], ["cpu_ns", "allocated_bytes", "updated_surfaces", "patch_updates"]),
        ("water-production-replay.csv", ["scenario", "mode"], ["cpu_ns", "encoded_items", "replay_frames", "revision_changes"]),
    ]:
        result[filename] = grouped(read_csv(output / filename), keys, columns)
    result["historical_only_evidence"] = [
        "tools/prediction/meridian-algorithms-20261016/results/attribute-full-shader.csv",
        "tools/prediction/meridian-algorithms-20261016/results/demand-dynamic.csv",
        "tools/prediction/meridian-algorithms-20261016/results/junit/retired-candidates",
    ]
    result["water_frames_per_measured_interval"] = 64
    return result


def manifest(root: Path) -> dict:
    paths = list((root / "src/main/java").rglob("*.java")) + list((root / "src/main/resources").rglob("*"))
    paths += list((root / EXPERIMENT).rglob("*"))
    paths += list((root / "tools/prediction/meridian-algorithms-20261016/java").rglob("*.java"))
    # Capture all test Java to include imported helpers without claiming they
    # were all executed. The selected classes are recorded in the task and XML.
    paths += list((root / "src/test/java").rglob("*.java"))
    paths += [root / REPORT, root / "tools/prediction/meridian-implementation-tests.gradle",
              root / "build.gradle", root / "gradle.properties"]
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(set(paths))
            if p.is_file() and p.name not in {"summary.json", "validation-manifests.json"}
            and "__pycache__" not in p.parts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    desktop = Path(__file__).resolve().parents[4]
    parser.add_argument("--neoforge", type=Path, default=desktop / "voxyserverside-neoforge-1.21.1")
    parser.add_argument("--forge", type=Path, default=desktop / "voxyserverside")
    args = parser.parse_args()
    roots = dict(neoforge=args.neoforge.resolve(), forge=args.forge.resolve())
    summary = {
        "date": "2026-10-16", "source": "live production; frozen baseline only under explicit -PvssMeridianBaseline",
        "units": {"cpu_ns": "host elapsed interval, not thread CPU time",
                  "allocated_bytes": "cumulative Java allocation, not retained or peak memory",
                  "water_cpu_ns": "64-frame interval; divide by 64 for per-frame value",
                  "gpu_ns": "GL_TIME_ELAPSED; not whole Minecraft frame time",
                  "dynamic_keys": "simulated resident tile keys, not measured VRAM"},
        "decisions": {"border_and_seam_reuse": "production", "existing_budget_cost_feedback": "production",
                      "water_order_revision": "production", "attribute_dictionary": "rejected: slower full shaders",
                      "pressure_demand": "removed prototype; historical results only",
                      "box_occlusion": "see raster-boxes-20261016 live full-shader comparison", "binary_canopy": "rejected on quality",
                      "native_batch_expansion": "not justified by previous test"},
        "platforms": {platform: collect(root, platform) for platform, root in roots.items()},
    }
    assert summary["platforms"]["neoforge"]["jar"]["natives"] == summary["platforms"]["forge"]["jar"]["natives"]
    for name in PRODUCTION:
        assert patch(roots["neoforge"] / EXPERIMENT / "baseline" / f"{name}.java", roots["neoforge"] / MAIN / f"{name}.java") == patch(
            roots["forge"] / EXPERIMENT / "baseline" / f"{name}.java", roots["forge"] / MAIN / f"{name}.java"), name
    for platform, root in roots.items():
        for other, destination in roots.items():
            if other != platform:
                shutil.copytree(root / EXPERIMENT / "results" / platform,
                                destination / EXPERIMENT / "results" / platform, dirs_exist_ok=True)
    for root in roots.values():
        write_json(root / EXPERIMENT / "summary.json", summary)
    manifests = {platform: manifest(root) for platform, root in roots.items()}
    for root in roots.values():
        write_json(root / EXPERIMENT / "validation-manifests.json", manifests)
    for platform, result in summary["platforms"].items():
        print(platform, result["tests"], result["jar"]["path"], result["jar"]["sha256"])


if __name__ == "__main__":
    main()
