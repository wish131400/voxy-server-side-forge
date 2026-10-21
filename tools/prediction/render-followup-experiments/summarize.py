"""Recompute component A/B results and verify the two delivery JARs. Python 3 stdlib only."""
import csv
import hashlib
import json
import statistics
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

EXPERIMENTS = Path(__file__).resolve().parent
REPOSITORY = EXPERIMENTS.parents[2]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ab_csv(path, fields, group=None):
    rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))
    groups = sorted({row[group] for row in rows}) if group else [None]
    output = []
    for key in groups:
        selected = [row for row in rows if not group or row[group] == key]
        modes = [[row for row in selected if row["mode"] == str(i)] for i in range(2)]
        assert len(modes[0]) == len(modes[1]) and modes[0]
        summary = {"samples_per_mode": len(modes[0])}
        if group:
            summary[group] = key
        for label, columns in fields.items():
            values = [[sum(int(row[column]) for column in columns) for row in mode] for mode in modes]
            medians = [statistics.median(mode) / 1e6 for mode in values]
            summary[label + "_median_ms"] = medians
            summary[label + "_median_reduction_pct"] = 100 * (1 - medians[1] / medians[0])
            summary[label + "_p95_ms"] = [sorted(mode)[int(len(mode) * .95)] / 1e6 for mode in values]
        output.append(summary)
    return {"file": str(path), "sha256": sha256(path), "comparisons": output}


def tests(root, suite):
    files = list((root / "build-render-followup-20261012/test-results" / suite).glob("TEST-*.xml"))
    assert files, (root, suite)
    return {key: sum(int(ET.parse(path).getroot().get(key, 0)) for path in files)
            for key in ["tests", "failures", "errors", "skipped"]}


def artifact(root, platform):
    jars = list((root / "lib/render-followup-20261012").glob("*.jar"))
    assert len(jars) == 1
    path = jars[0]
    expected_major = 65 if platform == "neoforge" else 61
    with zipfile.ZipFile(path) as jar:
        assert jar.testzip() is None
        names = jar.namelist()
        assert not [name for name in names if name.endswith("Test.class") or any(
            part in name for part in ["FollowupLegacy", "SeamIndexBenchmark", "PredictionReadbackBuffer", "FollowupEarlyFace"])]
        for name in ["PredictionRenderer", "PredictionRenderResidency", "PredictionSeamIndex"]:
            data = jar.read("dev/xantha/vss/client/prediction/" + name + ".class")
            assert int.from_bytes(data[6:8], "big") == expected_major
        native = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in names
                  if name.startswith("META-INF/vss-natives/") and not name.endswith("/")}
        for name, digest in native.items():
            assert sha256(root / "src/main/resources" / name) == digest
        metadata_name = "META-INF/neoforge.mods.toml" if platform == "neoforge" else "META-INF/mods.toml"
        metadata = jar.read(metadata_name).decode("utf-8")
        version = "0.3.5-neoforge-1.21.1" if platform == "neoforge" else "0.3.5-forge-1.20.1"
        assert 'version="' + version + '"' in metadata
        if platform == "forge":
            import re
            assert re.search(rb"m_\d+_", jar.read("dev/xantha/vss/client/prediction/PredictionRenderer.class"))
            assert "META-INF/jarjar/lz4-java-1.8.0.jar" in names
    digest = sha256(path)
    (path.parent / "SHA256SUMS.txt").write_text(digest + "  " + path.name + "\n", encoding="utf-8")
    return {"path": str(path), "sha256": digest, "bytes": path.stat().st_size,
            "version": version, "class_major": expected_major, "native_sha256": native}


def main():
    roots = {"neoforge": REPOSITORY if "neoforge" in REPOSITORY.name else REPOSITORY.parent / "voxyserverside-neoforge-1.21.1",
             "forge": REPOSITORY if REPOSITORY.name == "voxyserverside" else REPOSITORY.parent / "voxyserverside"}
    result = {"date": "2026-10-12", "basis_spark": "https://spark.lucko.me/lNvcOAWHJs", "platforms": {}}
    for platform, root in roots.items():
        data = EXPERIMENTS / "results" / platform
        measurements = [ab_csv(data / filename, {"query": ["cpu_ns"], "including_local_update": ["cpu_ns", "update_ns"]})
                        for filename in ["seam-candidates.csv", "seam-candidates-confirmation.csv"]]
        suites = {suite: tests(root, suite) for suite in ["followup-regression", "followup-gpu", "followup-cpu-benchmark"]}
        assert all(value["failures"] == value["errors"] == value["skipped"] == 0 for value in suites.values())
        production = root / "src/main/java/dev/xantha/vss/client/prediction"
        result["platforms"][platform] = {"seam_candidate_ab": measurements, "tests": suites, "artifact": artifact(root, platform),
            "source_sha256": {name: sha256(production / name) for name in ["PredictionRenderer.java", "PredictionRenderResidency.java", "PredictionSeamIndex.java", "PredictionRefinementOcclusion.java", "PredictionTerrainProgram.java"]}}
    rejected = EXPERIMENTS / "rejected"
    result["rejected"] = {name: ab_csv(rejected / name / "neoforge-visibility-readback.csv",
        {"submit_cpu": ["submit_cpu_ns"], "poll_cpu": ["poll_cpu_ns"], "total_cpu": ["submit_cpu_ns", "poll_cpu_ns"], "gpu": ["gpu_ns"]})
        for name in ["mapped-copy", "direct-mapped"]}
    result["rejected"]["early-face"] = ab_csv(rejected / "early-face/neoforge-early-face.csv",
        {"cpu": ["cpu_ns"], "gpu": ["gpu_ns"]}, "view")
    result["limits"] = ["Component timings are not game FPS or frame-time A/B.",
        "The 6306 seam coordinates are captured; heights are reconstructed as [-64,512].",
        "Exact visits include warmup; quadtree traversal CPU is included in query timing.",
        "The GL retirement fixture holds 134144 live bytes in one 64 MiB arena page; this is not a world VRAM saving estimate.",
        "The Forge shader convention regression reads shader text from the installed NeoForge Voxy JAR; it does not load that mod."]
    result["retained"] = ["Local seam patch quadtree with actual subtree bounds", "View-dependent seam GPU protection and cold admission with complete ordinary parent fallback"]
    result["not_changed"] = ["GPU readback implementation", "Production vertex shader", "Adaptive HiZ comparison", "Screen-space parent ownership selection", "GPU retirement throttle"]
    output = REPOSITORY / "docs/vss-render-followup-implementation-20261012.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)
    for name, value in result["platforms"].items():
        print(name, json.dumps(value["tests"]), value["artifact"]["sha256"])
        for run in value["seam_candidate_ab"]:
            print(Path(run["file"]).name, json.dumps(run["comparisons"][0]))


if __name__ == "__main__":
    main()
