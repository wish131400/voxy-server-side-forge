"""Summarize saved A/B data and verify the two built jars, without launching Minecraft."""
import collections
import csv
import hashlib
import json
import math
import pathlib
import re
import statistics
import struct
import xml.etree.ElementTree as ET
import zipfile

here = pathlib.Path(__file__).resolve().parent
desktop = here.parents[3]
repos = {"neoforge": desktop / "voxyserverside-neoforge-1.21.1",
         "forge": desktop / "voxyserverside"}
report = {"date": "2026-10-12", "decision": "retain measured improvements",
          "spark": json.loads((here / "profile/profile-analysis.json").read_text(encoding="utf-8")),
          "method": {"gpu": "RTX 4070 Ti SUPER, NVIDIA 572.70, OpenGL 4.6; actual arena/MDI/shader",
                     "target_pixels": [256, 256], "gpu_warmups": 24, "gpu_rounds": 81,
                     "gpu_repetitions": 64, "cpu_warmups": 8, "cpu_rounds": 31,
                     "order": "paired alternating baseline/current",
                     "cpu_clock": "System.nanoTime; prepare/submit excluding glFinish and query readback",
                     "gpu_clock": "GL_TIME_ELAPSED around prepare/submit; not world FPS"},
          "results": {}, "tests": {}, "jars": {}}

def summarize_csv(path):
    groups = collections.defaultdict(lambda: collections.defaultdict(list))
    with path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            key = (row.get("content", "coverage"), row["scenario"], row["mode"])
            for field in ("gpu_ns", "cpu_ns", "mdi_quads", "mdi_commands",
                          "display_tier", "vegetation_tier", "rendered_cells"):
                if field in row:
                    groups[key][field].append(int(row[field]))
    output = []
    for (content, scenario, mode), values in groups.items():
        result = {"content": content, "scenario": scenario, "mode": int(mode)}
        for field, samples in values.items():
            if field.endswith("_ns"):
                name = field.removesuffix("_ns") + "_ms"
                result[name] = {"median": statistics.median(samples) / 1e6,
                                "p95": sorted(samples)[math.ceil(len(samples) * .95) - 1] / 1e6,
                                "n": len(samples)}
            else:
                result[field] = sorted(set(samples))
        output.append(result)
    return output

for kind, repo in repos.items():
    archive = here / "results" / kind
    report["results"][kind] = {
        "submission": summarize_csv(archive / "zoom-return-submission.csv"),
        "coverage": summarize_csv(archive / "zoom-return-coverage.csv")}
    tests = {}
    for task in ("zoom-lod-regression", "zoom-lod-cpu", "zoom-lod-gpu", "zoom-lod-production-gpu"):
        suites = [ET.parse(file).getroot() for file in (archive / "junit" / task).glob("TEST-*.xml")]
        totals = {field: sum(int(suite.get(field, "0")) for suite in suites)
                  for field in ("tests", "failures", "errors", "skipped")}
        totals["evidence"] = "archived JUnit XML"
        if not suites and kind == "neoforge" and task == "zoom-lod-production-gpu":
            log = (archive / "production-gpu.log").read_text(encoding="utf-8", errors="replace")
            assert "BUILD SUCCESSFUL" in log
            passed = re.findall(r"^Prediction\w+GpuTest > .+ PASSED$", log, re.M)
            assert len(passed) == 11
            totals.update(tests=len(passed), evidence="saved successful Gradle log; later empty filter replaced live XML")
        assert totals["tests"] > 0 and not any(totals[field] for field in ("failures", "errors", "skipped"))
        tests[task] = totals
    report["tests"][kind] = tests
    jar = next((repo / "lib/zoom-return-20261012").glob("*.jar"))
    baseline = next((repo / "lib/zoom-lod-20261012").glob("*.jar"))
    with zipfile.ZipFile(jar) as new, zipfile.ZipFile(baseline) as old:
        assert new.testzip() is None
        classes = [name for name in new.namelist() if name.startswith("dev/xantha/vss/") and name.endswith(".class")]
        majors = collections.Counter(struct.unpack(">H", new.read(name)[6:8])[0] for name in classes)
        assert set(majors) == ({65} if kind == "neoforge" else {61})
        unwanted = [name for name in new.namelist() if name.startswith("org/junit/")
                    or name.endswith("Test.class") or "ZoomReturnBaseline" in name
                    or name.startswith("tools/prediction/")]
        assert not unwanted, unwanted
        natives = {name: hashlib.sha256(new.read(name)).hexdigest()
                   for name in new.namelist() if name.endswith((".dll", ".dylib", ".so"))}
        assert len(natives) == 5
        assert all(new.read(name) == old.read(name) for name in natives)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    jar.with_suffix(".jar.sha256").write_text(digest + "  " + jar.name + "\n", encoding="utf-8")
    report["jars"][kind] = {"path": str(jar), "bytes": jar.stat().st_size,
                           "sha256": digest, "crc": "pass", "vss_class_major_versions": dict(majors),
                           "experiment_classes_packaged": False, "natives_match_previous_jar": True,
                           "native_sha256": natives}

target = repos["neoforge"] / "docs/vss-zoom-return-profile-20261012.json"
target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"tests": report["tests"], "jars": {key: {field: value[field] for field in ("bytes", "sha256")}
                  for key, value in report["jars"].items()}}, ensure_ascii=False, indent=2))
