"""Verify the delivered artifact and record the final production test results."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

repo = Path(sys.argv[1]).resolve()
forge = repo.name == "voxyserverside"
suffix = "forge-1.20.1" if forge else "neoforge-1.21.1"
major = 61 if forge else 65
directory = repo / "lib/refinement-latency-20261021"
artifact = directory / f"vss-0.3.5-{suffix}.jar"
baseline = repo / "lib/voxel-hierarchy-20261020" / artifact.name
build_id = "refinement-latency-20261021"

with zipfile.ZipFile(artifact) as current, zipfile.ZipFile(baseline) as previous:
    names = set(current.namelist())
    manifest = current.read("META-INF/MANIFEST.MF").decode().replace("\r\n", "\n").replace("\n ", "")
    assert f"Implementation-Version: 0.3.5-{suffix}\n" in manifest
    assert "VSS-Protocol: 53\n" in manifest
    assert f"VSS-Build-Id: {build_id}\n" in manifest
    assert not any(n.endswith("Test.class") or "/AlgorithmSupport" in n
                   or "/RefinementLatencyAgent" in n for n in names)
    project_classes = [n for n in names if n.startswith("dev/xantha/vss/") and n.endswith(".class")]
    for name in project_classes:
        assert struct.unpack(">I", current.read(name)[:4])[0] == 0xCAFEBABE
        assert struct.unpack(">H", current.read(name)[6:8])[0] == major, name
    critical = ["PredictionTileManager", "PredictionMesh", "PredictionPackedMesh",
                "PredictionSchedulePreparation", "PredictionUploadSelection", "PredictionRenderer"]
    for name in critical:
        entry = f"dev/xantha/vss/client/prediction/{name}.class"
        assert entry in names
        assert current.read(entry) != previous.read(entry), f"Unchanged production class: {name}"
        if not forge:
            assert current.read(entry) == (repo / "build-refinement-latency-20261021/classes/java/main" / entry).read_bytes()
    natives = sorted(n for n in names if n.startswith("META-INF/vss-natives/") and not n.endswith("/"))
    assert len(natives) == 5
    for name in natives:
        assert current.read(name) == previous.read(name), name
    if forge:
        assert "META-INF/jarjar/lz4-java-1.8.0.jar" in names
        assert "META-INF/mods.toml" in names
        mapped = current.read("dev/xantha/vss/client/prediction/PredictionRenderer.class")
        assert b"m_91087_" in mapped, "Forge output must be reobfuscated"
    else:
        assert "META-INF/neoforge.mods.toml" in names

tasks = {}
task_names = ["refinementLatencyTest", "refinementLatencyRegressionTest", "refinementLatencyGpuTest",
              "architectureTest", "moduleRegressionTest", "columnFilterTest"]
if (repo / "build-refinement-latency-20261021/test-results/refinementLatencyAsyncFixtureTest").exists():
    task_names.append("refinementLatencyAsyncFixtureTest")
for task in task_names:
    files = sorted((repo / "build-refinement-latency-20261021/test-results" / task).glob("TEST-*.xml"))
    assert files, f"No results for {task}"
    results = {"tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    for path in files:
        suite = ET.parse(path).getroot()
        for key in results:
            results[key] += int(suite.get(key, 0))
    assert results["failures"] == results["errors"] == 0, (task, results)
    tasks[task] = results

digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
(directory / "SHA256SUMS.txt").write_text(f"{digest}  {artifact.name}\n", encoding="utf-8")
report = {"artifact": artifact.name, "buildId": build_id, "protocol": 53,
          "sha256": digest, "bytes": artifact.stat().st_size, "classMajor": major,
          "projectClasses": len(project_classes), "nativeResources": natives,
          "nativeResourcesUnchanged": True, "reobfuscated": forge,
          "productionClassesUpdated": critical, "testResults": tasks,
          "deployedToClient": False, "pushedToGitHub": False,
          "measurementLimit": "Worker fixture and hidden GL regressions; no post-fix in-game latency or FPS measurement."}
(directory / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
