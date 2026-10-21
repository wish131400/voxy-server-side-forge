"""Audit the packaged architecture and regression results for both loaders."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

OUTPUT = "architecture-audit-20261015"
PACKAGE = "dev/xantha/vss/client/prediction/"
SHARED = (
    "PredictionGpuScene", "PredictionGpuSelectionInput", "PredictionGpuSelectionPolicy",
    "PredictionPlanPreparation", "PredictionDemandPreparation", "PredictionSchedulePreparation",
    "PredictionLoadingPreparation", "PredictionSeamPreparation", "PredictionHiZBudget",
)
REMOVED = {
    "PredictionTileManager": (
        "refreshViewPaths", "refreshViewWork", "refreshOccludedPriorities", "foregroundDebt",
        "refreshPreviewWork", "refreshLoadingProgress", "ordinaryAxis", "makeRoomForSurface",
        "buildRequest", "fullyAuthoritative", "fullyVoxyOwned", "refreshVoxyOwnership",
        "hasCoverage", "queuedHeightSamples", "previewBuildNeeded",
    ),
    "PredictionRenderer": ("publishPatches",),
    "PredictionRenderer$SeamBatch": ("prepare",),
    "PredictionGpuSelectionPolicy": ("choose", "turning", "residentMotion"),
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def methods(data):
    """Read declared method names without depending on a JDK or loader classpath."""
    offset = 8

    def take(size):
        nonlocal offset
        result = data[offset:offset + size]
        offset += size
        return result

    def u2():
        return struct.unpack(">H", take(2))[0]

    pool = {}
    count = u2()
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = take(1)[0]
        if tag == 1:
            pool[index] = take(u2()).decode("utf-8", errors="replace")
        else:
            take(sizes[tag])
        index += 2 if tag in (5, 6) else 1
    take(6)
    take(u2() * 2)

    def members():
        names = []
        for _ in range(u2()):
            take(2)
            names.append(pool[u2()])
            take(2)
            for _ in range(u2()):
                take(2)
                take(struct.unpack(">I", take(4))[0])
        return names

    members()
    return members()


def regression(root, kind):
    files = sorted((root / f"build-{OUTPUT}/test-results/architecture-audit-{kind}").glob("TEST-*.xml"))
    assert files, f"Missing {kind} regression results: {root}"
    totals = dict(suites=len(files), tests=0, failures=0, errors=0, skipped=0)
    for path in files:
        suite = ET.parse(path).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(suite.get(key, "0"))
    assert totals["tests"] > 0 and all(totals[key] == 0 for key in ("failures", "errors", "skipped")), totals
    return totals


def audit(root):
    props = dict(re.findall(r"(?m)^(\w+)=(.*)$", (root / "gradle.properties").read_text(encoding="utf-8")))
    version = props["mod_version"].strip()
    major = 65 if "neoforge" in version else 61
    jar = root / "lib" / OUTPUT / f"vss-{version}.jar"
    assert jar.is_file(), jar
    payload = jar.read_bytes()
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        required = [PACKAGE + name + ".class" for name in SHARED]
        required += [PACKAGE + "PredictionAuthoritativeCoverageView.class", PACKAGE + "PredictionRenderer$Scene.class"]
        assert all(name in names for name in required), required
        assert PACKAGE + "PredictionSubmissionCoverage.class" not in names
        assert not any(name.startswith("me/cortex/voxy/") or re.search(r"(?:Test|Benchmark)\.class$", name)
                       or "/ArchitectureLegacy" in name or "/CompletionLegacy" in name for name in names)
        method_checks = {}
        for owner, removed in REMOVED.items():
            declared = methods(archive.read(PACKAGE + owner + ".class"))
            assert not set(removed).intersection(declared), (owner, removed, declared)
            method_checks[owner] = list(removed)
        majors = sorted({struct.unpack(">H", archive.read(name)[6:8])[0] for name in names if name.endswith(".class")})
        assert majors == [major], majors
        natives = []
        resource_root = root / "src/main/resources"
        for source in sorted((resource_root / "META-INF/vss-natives").rglob("*")):
            if not source.is_file():
                continue
            name = source.relative_to(resource_root).as_posix()
            assert name in names and sha(archive.read(name)) == sha(source.read_bytes()), name
            natives.append(name)
        assert len(natives) == 5, natives
    source_hashes = {name: sha((root / "src/main/java" / PACKAGE / f"{name}.java").read_bytes()) for name in SHARED}
    result = dict(platform=version, jar=str(jar), bytes=len(payload), sha256=sha(payload),
                  classMajors=majors, nativeResources=natives, removedMethods=method_checks,
                  sharedSources=source_hashes, cpu=regression(root, "cpu"), gpu=regression(root, "gpu"),
                  validationScope="Source, packaged bytecode and isolated CPU/hidden OpenGL regressions; no game FPS measurement.")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("roots", nargs="+", type=Path)
    args = parser.parse_args()
    results = [audit(root.resolve()) for root in args.roots]
    assert all(result["sharedSources"] == results[0]["sharedSources"] for result in results), "Shared sources differ"
    for root, result in zip(args.roots, results):
        destination = root / "lib" / OUTPUT
        (destination / "validation.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (destination / "SHA256SUMS.txt").write_text(f"{result['sha256']}  {Path(result['jar']).name}\n", encoding="utf-8")
    print(json.dumps([{key: result[key] for key in ("platform", "bytes", "sha256", "cpu", "gpu")} for result in results],
                     indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
