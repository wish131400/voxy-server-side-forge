"""Summarize the retained experiment and audit both delivery JARs.

Run after the full replay and both platforms' CPU/GPU/packaging tasks.
This reads local artifacts only; it never attaches to or changes a game.
"""
from pathlib import Path
from statistics import median
import csv
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
NEO = ROOT if ROOT.name.endswith("neoforge-1.21.1") else ROOT.parent / "voxyserverside-neoforge-1.21.1"
FORGE = NEO.parent / "voxyserverside"
PRODUCTION = Path("src/main/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometry.java")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def csv_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def construction(path):
    data = csv_rows(path)
    result = []
    for mesh in dict.fromkeys(row["mesh"] for row in data):
        modes = []
        for mode in ("0", "1"):
            selected = [r for r in data if r["mesh"] == mesh and r["mode"] == mode]
            modes.append({
                "medianBuildNs": median(int(r["build_ns"]) for r in selected),
                "medianAllocatedBytes": median(int(r["allocated_bytes"]) for r in selected),
                "extraQuads": int(selected[0]["extra_quads"]),
            })
        result.append({"mesh": int(mesh), "before": modes[0], "after": modes[1]})
    return {"rounds": 25, "warmupRounds": 8, "interleaved": True, "meshes": result}


def submission(path):
    data = csv_rows(path)
    result = {}
    for view in dict.fromkeys(row["view"] for row in data):
        meshes = []
        for mesh in dict.fromkeys(row["mesh"] for row in data):
            modes = []
            for mode in ("0", "1"):
                selected = [r for r in data if r["mesh"] == mesh and r["view"] == view and r["mode"] == mode]
                assert len(selected) == 81
                modes.append({
                    "gpuMedianNs": median(int(r["gpu_ns"]) for r in selected),
                    "cpuMedianNs": median(int(r["cpu_ns"]) for r in selected),
                    "submittedQuads": int(selected[0]["quads"]),
                    "commands": int(selected[0]["commands"]),
                    "storedBytes": int(selected[0]["stored_bytes"]),
                })
            assert modes[1]["submittedQuads"] <= modes[0]["submittedQuads"]
            assert modes[0]["commands"] == modes[1]["commands"]
            meshes.append({"mesh": int(mesh), "before": modes[0], "after": modes[1]})
        before = sum(m["before"]["gpuMedianNs"] for m in meshes)
        after = sum(m["after"]["gpuMedianNs"] for m in meshes)
        result[view] = {
            "sumBeforeGpuMedianNs": before,
            "sumAfterGpuMedianNs": after,
            "gpuReductionPercent": 100 * (before - after) / before,
            "submittedBeforeQuads": sum(m["before"]["submittedQuads"] for m in meshes),
            "submittedAfterQuads": sum(m["after"]["submittedQuads"] for m in meshes),
            "storedBeforeBytes": sum(m["before"]["storedBytes"] for m in meshes),
            "storedAfterBytes": sum(m["after"]["storedBytes"] for m in meshes),
            "meshes": meshes,
        }
    return {"rounds": 81, "warmupRounds": 24, "repetitionsPerMesh": 64,
            "projectionPixels": 900, "pixelError": 2, "views": result,
            "limitation": "Sum of independent component medians with a replacement atlas and a fixed diagnostic camera, not frame time or game FPS."}


def test_results(directory):
    result = {}
    for name in ("loading-benefit", "far-terrain-cpu", "loading-benefit-gpu", "far-terrain-gpu"):
        suites = [ET.parse(p).getroot() for p in (directory / name).glob("TEST-*.xml")]
        assert suites, f"Missing {directory / name}"
        result[name] = {k: sum(int(s.get(k, 0)) for s in suites) for k in ("tests", "failures", "errors", "skipped")}
        assert all(result[name][k] == 0 for k in ("failures", "errors", "skipped")), result[name]
    return result


def artifact(root, platform, major):
    jar = root / f"lib/far-terrain-20261014/vss-0.3.5-{platform}.jar"
    old = root / f"lib/gpu-selection-retained-20261014/vss-0.3.5-{platform}.jar"
    target = "dev/xantha/vss/client/prediction/PredictionDisplayGeometry.class"
    with zipfile.ZipFile(jar) as archive, zipfile.ZipFile(old) as previous:
        names = archive.namelist()
        assert archive.testzip() is None
        assert not any("FarTerrain" in n or "FarBaseline" in n or "historical-source" in n
                       or "/testutil/" in n or n.startswith("org/junit/") for n in names)
        code = archive.read(target)
        assert struct.unpack(">H", code[6:8])[0] == major
        assert b"vss.disablePredictionHeightProtection" in code
        natives = {n: digest(archive.read(n)) for n in names if n.startswith("META-INF/vss-natives/") and not n.endswith("/")}
        original_natives = {n: digest(previous.read(n)) for n in previous.namelist()
                            if n.startswith("META-INF/vss-natives/") and not n.endswith("/")}
        assert natives and natives == original_natives
        metadata_name = "META-INF/neoforge.mods.toml" if major == 65 else "META-INF/mods.toml"
        assert ("0.3.5-" + platform) in archive.read(metadata_name).decode("utf-8")
        class_changes = sorted(n for n in names if n.endswith(".class")
                               and (n not in previous.namelist() or archive.read(n) != previous.read(n)))
    sha = digest(jar.read_bytes())
    (jar.parent / "checksums.sha256").write_text(f"{sha}  {jar.name}\n", encoding="ascii")
    return {"path": str(jar), "bytes": jar.stat().st_size, "sha256": sha,
            "javaClassMajor": major, "nativesUnchanged": natives,
            "experimentalClassesExcluded": True, "changedClassesAgainstPreviousDelivery": class_changes}


def main():
    rows = json.loads((HERE / "final-results/rows.json").read_text(encoding="utf-8"))
    assert len(rows) == 2019 and sum(r["regressions"] for r in rows) == 0
    before_extra = sum(r["oldExtra"] for r in rows)
    after_extra = sum(r["extra"] for r in rows)
    selections = []
    for i, distance in enumerate((512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192)):
        a = sum(r["oldSelected"][i] for r in rows)
        b = sum(r["newSelected"][i] for r in rows)
        selections.append({"distance": distance, "before": a, "after": b, "reductionPercent": 100 * (a - b) / a})
    source = (NEO / PRODUCTION).read_bytes()
    assert source == (FORGE / PRODUCTION).read_bytes()
    assert source == (HERE / "comparison-src/PredictionDisplayGeometry.java").read_bytes()
    # These rejected trial edits must not survive in the NeoForge production tree.
    for name in ("PredictionPackedMesh.java", "PredictionVegetationRanges.java", "PredictionRenderer.java"):
        assert (NEO / PRODUCTION.parent / name).read_bytes() == (HERE / "baseline" / name).read_bytes()
    report = {
        "date": "2026-10-14", "version": "0.3.5", "liveClientProfiled": False,
        "clientLaunched": False, "installedInstanceModified": False, "githubPushed": False,
        "gpu": "NVIDIA GeForce RTX 4070 Ti SUPER / OpenGL 4.6 / 572.70",
        "retained": "Height-aware water/model protection fallback only after the existing display representation policy rejects all tiers.",
        "rolledBack": ["Terrain-only append with shared feature ranges", "Replacing already useful variants with height-aware variants", "Finer fallback lattice"],
        "productionSourceSha256": digest(source),
        "corpus": {
            "origin": "Historical October 10 immutable geometry capture, not the October 14 coastal screenshot",
            "meshes": len(rows), "sourceOpaqueQuads": sum(r["source"] for r in rows),
            "beforeVariantMeshes": sum(r["oldExtra"] > 0 for r in rows),
            "afterVariantMeshes": sum(r["extra"] > 0 for r in rows),
            "beforeExtraQuads": before_extra, "afterExtraQuads": after_extra,
            "incrementalCanonicalBytes": (after_extra - before_extra) * 48,
            "selectionRegressions": 0, "existingRepresentationsPreserved": True,
            "projectionPixels": 900, "pixelError": 2, "fullDistance": 512,
            "selections": selections,
            "limitation": "All meshes evaluated at each common distance; these are candidate counts, not an actual world view or FPS. Material classification comes from retained capture metadata and a minimal replay adapter.",
        },
        "platforms": [],
    }
    for platform, root, major in (("neoforge-1.21.1", NEO, 65), ("forge-1.20.1", FORGE, 61)):
        build = root / "build-far-terrain-20261014"
        report["platforms"].append({
            "platform": platform, "repository": str(root),
            "tests": test_results(build / "test-results"),
            "worker": construction(build / "results/far-terrain-worker.csv"),
            "gpu": submission(build / "results/far-terrain-submission.csv"),
            "jar": artifact(root, platform, major),
        })
    for root in (NEO, FORGE):
        target = root / "docs/prediction-far-terrain-20261014.validation.json"
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"corpus": report["corpus"], "platforms": [
        {"platform": p["platform"], "tests": p["tests"], "jar": p["jar"],
         "gpu": {v: {k: value for k, value in data.items() if k != "meshes"} for v, data in p["gpu"]["views"].items()}}
        for p in report["platforms"]]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
