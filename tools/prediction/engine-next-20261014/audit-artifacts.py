"""Verify the release artifacts without launching a Minecraft instance."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import zipfile

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
platform = sys.argv[2] if len(sys.argv) > 2 else "neoforge"
major = 65 if platform == "neoforge" else 61
metadata = "META-INF/neoforge.mods.toml" if platform == "neoforge" else "META-INF/mods.toml"
artifacts = list((root / "lib/engine-next-20261014").glob("*.jar"))
assert len(artifacts) == 1, artifacts
jar = artifacts[0]
sha = lambda data: hashlib.sha256(data).hexdigest()
sources = {}
for name in ["SubmissionChanges", "PackedMesh", "GpuTile", "GpuSelectionInput", "Renderer",
             "IndirectBatch", "TerrainProgram", "IrisBridge"]:
    source = root / f"src/main/java/dev/xantha/vss/client/prediction/Prediction{name}.java"
    sources[source.relative_to(root).as_posix()] = sha(source.read_bytes())

with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "duplicate JAR entries"
    assert archive.testzip() is None, "invalid ZIP member"
    assert metadata in names
    assert 'version="0.3.5' in archive.read(metadata).decode().replace(" ", "")
    classes = [name for name in names if name.endswith(".class")]
    assert classes
    assert not any("/Next" in name or name.endswith("GpuTest.class") for name in classes)
    class_majors = {struct.unpack(">H", archive.read(name)[6:8])[0] for name in classes}
    assert class_majors == {major}, class_majors
    for name in sources:
        expected = name.replace("src/main/java/", "").replace(".java", ".class")
        assert expected in names, expected
        if platform == "neoforge":
            compiled = root / "build-engine-next-20261014/classes/java/main" / expected
            assert archive.read(expected) == compiled.read_bytes(), "JAR differs from validated compiled class: " + expected
    for name in ["vss.compat.mixins.json", "vss.strict.mixins.json", "vss.lostcities.mixins.json"]:
        assert archive.read(name) == (root / "src/main/resources" / name).read_bytes()
        json.loads(archive.read(name))
    natives = {}
    for file in (root / "src/main/resources/META-INF/vss-natives").rglob("*"):
        if file.is_file():
            name = file.relative_to(root / "src/main/resources").as_posix()
            data = archive.read(name)
            assert data == file.read_bytes(), name
            natives[name] = {"bytes": len(data), "sha256": sha(data)}
    assert len(natives) == 5, natives
    reobfuscated = None
    if platform == "forge":
        renderer = archive.read("dev/xantha/vss/client/prediction/PredictionRenderer.class")
        reobfuscated = b"m_91087_" in renderer and b"getInstance" not in renderer
        assert reobfuscated, "Forge Minecraft calls must use the SRG production mapping"
        assert "vss.refmap.json" in names
    report = {
        "platform": platform, "jar": str(jar), "bytes": jar.stat().st_size,
        "sha256": sha(jar.read_bytes()), "class_major": major,
        "classes": len(classes), "metadata": metadata,
        "test_classes_in_jar": False, "forge_reobfuscated": reobfuscated,
        "native_payloads": natives, "production_sources": sources,
    }

output = root / "lib/engine-next-20261014/artifact-manifest.json"
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: value for key, value in report.items()
                  if key not in ("native_payloads", "production_sources")}, ensure_ascii=False, indent=2))
