"""Record the validation inputs, then verify the two loader-specific release artifacts.

Run from the repository root. Use --snapshot before validation and no arguments
after editedTerrainRegressionTest, editedTerrainGpuTest, editedTerrainBenchmark and jar.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path.cwd()
TASK = ROOT / "tools/prediction/edited-terrain-20261016"
BUILD = ROOT / "build-edited-terrain-20261016"
RELEASE = ROOT / "lib/edited-terrain-20261016"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inputs() -> dict[str, str]:
    paths = sorted((ROOT / "src/main").rglob("*"))
    paths += [ROOT / "build.gradle", ROOT / "gradle.properties", ROOT / "tools/prediction/edited-terrain-tests.gradle"]
    return {p.relative_to(ROOT).as_posix(): sha256(p.read_bytes()) for p in paths if p.is_file()}


def save(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if "--snapshot" in sys.argv:
    save(TASK / "validation-inputs.json", inputs())
    print("Recorded production inputs.")
    sys.exit(0)

expected = json.loads((TASK / "validation-inputs.json").read_text(encoding="utf-8"))
current = inputs()
assert current == expected, "Production inputs changed during validation; inspect and validate the changes."


def tests(name: str) -> dict:
    files = sorted((BUILD / "test-results" / name).glob("TEST-*.xml"))
    assert files, f"No results for {name}"
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    skips = []
    suites = {}
    for path in files:
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, "0"))
        suites[suite.get("name")] = int(suite.get("tests", "0"))
        for case in suite.findall("testcase"):
            skipped = case.find("skipped")
            if skipped is not None:
                skips.append(dict(test=case.get("classname") + "." + case.get("name"), reason=skipped.get("message", "")))
    assert counts["failures"] == counts["errors"] == 0, counts
    return dict(**counts, skippedCases=skips, suites=suites)


cpu = tests("edited-terrain-cpu")
gpu = tests("edited-terrain-gpu")
assert cpu["suites"].get("dev.xantha.vss.client.prediction.PredictionEditedTerrainTest", 0) >= 12
assert cpu["suites"].get("dev.xantha.vss.client.prediction.PredictionLocalCapturesTest", 0) >= 3
assert gpu["tests"] > gpu["skipped"], "No OpenGL tests executed"

props = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
             if "=" in line and not line.startswith("#"))
version = props["mod_version"]
assert version.startswith("0.3.5-"), version
jar = RELEASE / f"vss-{version}.jar"
assert jar.is_file(), jar
with zipfile.ZipFile(jar) as archive:
    entries = set(archive.namelist())
    prefix = "dev/xantha/vss/client/prediction/"
    required = [prefix + "PredictionCapturedTerrain.class", prefix + "PredictionCapturedTerrain$Summary.class",
                prefix + "PredictionCapturedTerrain$Node.class", prefix + "PredictionLocalCaptures.class",
                prefix + "PredictionLocalCaptures$Request.class",
                "dev/xantha/vss/mixin/client/LevelChunkPredictionCaptureMixin.class"]
    assert all(path in entries for path in required), "New production classes absent"
    assert b"beforeUnload" in archive.read(prefix + "PredictionLocalCaptures.class"), "Startup/unload capture guard absent"
    assert not any("EditedTerrainTest" in p or "EditedTerrainBenchmark" in p or "LegacyCaptureExtractor" in p for p in entries)
    mixins = json.loads(archive.read("vss.compat.mixins.json"))
    assert "client.LevelChunkPredictionCaptureMixin" in mixins["client"]
    assert "client.LevelChunkPredictionCaptureMixin" not in mixins["mixins"]
    natives = {}
    for source in sorted((ROOT / "src/main/resources/META-INF/vss-natives").rglob("*")):
        if source.is_file():
            path = source.relative_to(ROOT / "src/main/resources").as_posix()
            assert source.read_bytes() == archive.read(path), f"Native library differs: {path}"
            natives[path] = sha256(archive.read(path))
    assert len(natives) >= 5
    if "neoforge" in version:
        metadata = "META-INF/neoforge.mods.toml"
        for path in required:
            assert archive.read(path) == (BUILD / "classes/java/main" / path).read_bytes(), path
    else:
        metadata = "META-INF/mods.toml"
        refmap = archive.read("vss.refmap.json").decode("utf-8")
        assert "LevelChunkPredictionCaptureMixin" in refmap, "New Forge mixin is not mapped"
        assert "setBlockState" in refmap
        log_bytes = (TASK / "gpu-build.log").read_bytes()
        build_log = log_bytes.decode("utf-16" if log_bytes.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8", errors="replace")
        assert ":reobfJar" in build_log, "Forge reobfuscation was not recorded"
    assert version in archive.read(metadata).decode("utf-8"), "Wrong loader version in mod metadata"

benchmark = json.loads((BUILD / "results/edited-terrain-benchmark.json").read_text(encoding="utf-8"))
digest = sha256(jar.read_bytes())
(RELEASE / "SHA256SUMS.txt").write_text(f"{digest}  {jar.name}\n", encoding="ascii")
result = dict(version=version, jar=jar.name, sha256=digest, bytes=jar.stat().st_size,
              cpu=cpu, gpu=gpu, benchmark=benchmark, nativeLibraries=natives,
              productionInputManifestSha256=sha256((TASK / "validation-inputs.json").read_bytes()),
              liveClientTested=False)
save(TASK / "results.json", result)
save(RELEASE / "validation.json", result)
print(json.dumps({k: result[k] for k in ("version", "sha256", "bytes")}, ensure_ascii=False))
print(json.dumps(dict(cpu={k: cpu[k] for k in ("tests", "failures", "errors", "skipped")},
                      gpu={k: gpu[k] for k in ("tests", "failures", "errors", "skipped")}), ensure_ascii=False))
