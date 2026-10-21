"""Check the packaged ground-cover renderer and the completed regression reports."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
platform = sys.argv[2] if len(sys.argv) > 2 else "neoforge"
build = root / "build-ground-cover-release-20261015"
folder = root / "lib/ground-cover-20261015"
jars = list(folder.glob("*.jar"))
assert len(jars) == 1, jars
jar = jars[0]
major = 65 if platform == "neoforge" else 61
metadata = "META-INF/neoforge.mods.toml" if platform == "neoforge" else "META-INF/mods.toml"
sha = lambda data: hashlib.sha256(data).hexdigest()

tests = {}
for task in ("ground-cover-cpu", "ground-cover-gpu"):
    files = list((build / "test-results" / task).glob("TEST-*.xml"))
    assert files, task
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
    assert counts["failures"] == counts["errors"] == 0, (task, counts)
    tests[task] = counts
    expected = "PredictionGroundCoverGpuTest" if task.endswith("gpu") else "PredictionGroundCoverTest"
    assert any(file.name.endswith(expected + ".xml") for file in files), expected

sources = {}
for name in ("GroundCoverLod", "VegetationRanges", "PackedMesh", "DisplayGeometry", "TerrainDisplayRanges",
             "Renderer", "MeshletBounds", "GpuLodGraph", "GpuScene", "GpuCommandSelector", "IndirectBatch"):
    file = root / f"src/main/java/dev/xantha/vss/client/prediction/Prediction{name}.java"
    sources[file.relative_to(root).as_posix()] = sha(file.read_bytes())
file = root / "src/main/java/dev/xantha/vss/client/prediction/VssLodSpriteTable.java"
sources[file.relative_to(root).as_posix()] = sha(file.read_bytes())

with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "duplicate entries"
    assert archive.testzip() is None, "invalid ZIP member"
    assert 'version="0.3.5' in archive.read(metadata).decode().replace(" ", "")
    classes = [name for name in names if name.endswith(".class")]
    assert {struct.unpack(">H", archive.read(name)[6:8])[0] for name in classes} == {major}
    assert not any(name.endswith("Test.class") or "/NextBefore" in name for name in classes)
    for name in sources:
        compiled_name = name.removeprefix("src/main/java/").replace(".java", ".class")
        assert compiled_name in names, compiled_name
    if platform == "neoforge":
        with zipfile.ZipFile(root / ".gradle/vss-ground-cover-20261015/production-runtime.jar") as runtime:
            runtime_classes = {name for name in runtime.namelist() if name.endswith(".class")}
            assert set(classes) == runtime_classes, "packaged class set differs from tested runtime"
            for name in classes:
                assert archive.read(name) == runtime.read(name), name
    else:
        renderer = archive.read("dev/xantha/vss/client/prediction/PredictionRenderer.class")
        assert b"m_91087_" in renderer and b"getInstance" not in renderer, "Forge JAR needs reobfJar"
        assert "vss.refmap.json" in names
    for name in ("vss.compat.mixins.json", "vss.strict.mixins.json", "vss.lostcities.mixins.json"):
        assert archive.read(name) == (root / "src/main/resources" / name).read_bytes()
        json.loads(archive.read(name))
    natives = {}
    for file in (root / "src/main/resources/META-INF/vss-natives").rglob("*"):
        if not file.is_file():
            continue
        name = file.relative_to(root / "src/main/resources").as_posix()
        data = archive.read(name)
        assert data == file.read_bytes(), name
        natives[name] = dict(bytes=len(data), sha256=sha(data))
    assert len(natives) == 5, natives

report = dict(platform=platform, jar=str(jar), bytes=jar.stat().st_size,
              sha256=sha(jar.read_bytes()), class_major=major, classes=len(classes),
              forge_reobfuscated=platform == "forge", tests=tests,
              native_payloads=natives, production_sources=sources)
(folder / "artifact-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: value for key, value in report.items()
                  if key not in ("native_payloads", "production_sources")}, ensure_ascii=False, indent=2))
