"""Audit the isolated Voxy-inspired experiment and summarize paired GL runs."""
from pathlib import Path
from collections import defaultdict
import csv
import hashlib
import json
import statistics
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
REPOS = {"neoforge": REPO.parent / "voxyserverside-neoforge-1.21.1", "forge": REPO.parent / "voxyserverside"}
BASELINE = Path("C:/Users/Administrator/AppData/Local/Temp/vss-gpu-traversal-20261014-baseline")
BUILD = "build-gpu-traversal-20261014"
EXPERIMENT = "tools/prediction/gpu-traversal-experiments"
REPORT = "prediction-gpu-traversal-benefit-20261014"
MAIN = "src/main/java/dev/xantha/vss/client/prediction"
PRODUCTION_FILES = ["PredictionIndirectBatch", "PredictionOcclusionCuller", "PredictionVisiblePlan", "PredictionRenderer"]
MODE_NAMES = ["production-cpu-selection", "gpu-flat-compact", "gpu-coarse-compact", "gpu-queued-traversal"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stats(values):
    values = sorted(values)
    return {"medianNs": statistics.median(values), "p95Ns": values[(len(values) * 95 + 99) // 100 - 1], "samples": len(values)}


def verify_platform(name, repo):
    log = repo / "build-gpu-traversal-experiment-final.log"
    assert "BUILD SUCCESSFUL" in log.read_text(errors="replace")
    xml_file = repo / BUILD / "test-results/gpu-traversal-experiment/TEST-dev.xantha.vss.client.prediction.PredictionGpuTraversalExperimentTest.xml"
    suite = ET.parse(xml_file).getroot()
    assert all(int(suite.get(k)) == 0 for k in ("failures", "errors", "skipped"))
    assert int(suite.get("tests")) == 1
    production = {}
    baseline_hashes = json.loads((repo / EXPERIMENT / "baseline-hashes.json").read_text())[repo.name]
    for stem in PRODUCTION_FILES:
        source = repo / MAIN / (stem + ".java")
        baseline = BASELINE / repo.name / (stem + ".java")
        if baseline.exists():
            assert source.read_bytes() == baseline.read_bytes(), source
        assert sha(source) == baseline_hashes[stem], source
        production[stem] = sha(source)
    assert not (repo / MAIN / "PredictionGpuCommandSelector.java").exists()
    assert not list((repo / BUILD / "classes/java/main").rglob("Traversal*.class"))
    prior = json.loads((repo / "docs/prediction-loading-benefit-20261014.validation.json").read_text())
    original = next(p for p in prior["platforms"] if Path(p["repository"]).name == repo.name)
    for relative, digest in original["sourcesSha256"].items():
        assert sha(repo / relative) == digest, (repo / relative)
    assert sha(Path(original["jar"])) == original["jarSha256"]
    grouped = defaultdict(list)
    pipeline = defaultdict(list)
    raw_hashes = {}
    results = repo / EXPERIMENT / "results" / name
    results.mkdir(parents=True, exist_ok=True)
    for filename in ("production-gpu-traversal.csv", "production-gpu-traversal-throughput.csv"):
        source = repo / BUILD / "results" / filename
        (results / filename).write_bytes(source.read_bytes())
        raw_hashes[filename] = sha(source)
        rows = list(csv.DictReader(source.open(newline="")))
        assert len(rows) == (1476 if filename.endswith("traversal.csv") else 756)
        for row in rows:
            key = tuple(int(row[k]) for k in ("tiles", "case", "mode"))
            (grouped if filename.endswith("traversal.csv") else pipeline)[key].append(row)
    (results / "benchmark.log").write_bytes(log.read_bytes())
    (results / "benchmark.junit.xml").write_bytes(xml_file.read_bytes())
    cases = []
    for tiles in (128, 1024, 1025):
        for scenario in range(3):
            item = {"tiles": tiles, "fixture": "mixed-terrain-seams" if tiles == 1025 else "terrain-grid",
                    "sourceQuads": 393920 if tiles == 1025 else tiles * 1024,
                    "scenario": ["stationary-no-hiz", "turning-no-hiz", "turning-current-frame-hiz"][scenario], "modes": []}
            for mode in range(4):
                rows = grouped[(tiles, scenario, mode)]
                blocks = pipeline[(tiles, scenario, mode)]
                assert len(rows) == 41 and len(blocks) == 21
                assert {int(r["round"]) for r in rows} == set(range(41))
                assert {int(r["round"]) for r in blocks} == set(range(21))
                assert all(int(r["frames"]) == 32 for r in blocks)
                item["modes"].append({"mode": MODE_NAMES[mode],
                        "cpuSubmissionWall": stats([int(r["cpu_ns"]) for r in rows]),
                        "gpuQueryElapsed": stats([int(r["gpu_ns"]) for r in rows]),
                        "pipelinedWallPerPass": stats([int(r["elapsed_ns"]) / 32 for r in blocks]),
                        "medianEncodedItems": statistics.median(int(r["encoded_items"]) for r in rows),
                        "medianUploadedBytes": statistics.median(int(r["uploaded_bytes"]) for r in rows)})
            baseline_time = item["modes"][0]["pipelinedWallPerPass"]["medianNs"]
            for mode in item["modes"]:
                mode["pipelinedTimeReductionPercent"] = (1 - mode["pipelinedWallPerPass"]["medianNs"] / baseline_time) * 100
            cases.append(item)
    sources = {str(path.relative_to(repo)).replace("\\", "/"): sha(path)
               for path in sorted((repo / EXPERIMENT / "candidate-src").rglob("*.java"))}
    sources["src/test/java/dev/xantha/vss/client/prediction/PredictionGpuTraversalExperimentTest.java"] = sha(repo / "src/test/java/dev/xantha/vss/client/prediction/PredictionGpuTraversalExperimentTest.java")
    sources["tools/prediction/gpu-traversal-tests.gradle"] = sha(repo / "tools/prediction/gpu-traversal-tests.gradle")
    return {"platform": name, "repository": str(repo), "tests": {"tests": 1, "failures": 0, "errors": 0, "skipped": 0,
            "pixelComparisonPairs": 81, "depthComparisonPairs": 81}, "productionRestored": True,
            "productionSha256": production, "previousDeliveryVerified": original["jar"],
            "previousDeliverySha256": original["jarSha256"], "experimentalSourceSha256": sources,
            "rawResultSha256": raw_hashes, "cases": cases}


platforms = [verify_platform(name, repo) for name, repo in REPOS.items()]
assert platforms[0]["experimentalSourceSha256"] == platforms[1]["experimentalSourceSha256"]
result = {"date": "2026-10-14", "liveClientProfiled": False, "clientLaunched": False,
          "installedInstanceModified": False, "githubPushed": False, "newJarProduced": False,
          "decision": "retain experiment and evidence; restore all production changes",
          "device": "NVIDIA GeForce RTX 4070 Ti SUPER, OpenGL 4.6, NVIDIA 572.70",
          "measurement": {"isolated": "20 warmups, 41 alternating samples; GPU GL_TIME_ELAPSED",
              "pipelined": "8 warmup blocks, 21 alternating blocks, 32 frames per block; finish at block boundaries",
              "viewport": [512, 512], "isGameFps": False}, "platforms": platforms}
lines = ["**Voxy GPU 遍历与命令生成适配实验（2026-10-14）**", "",
    "已经完成两个平台的独立 OpenGL 对照。简单 GPU 命令选择在部分转向场景有局部收益，但固定视角、较小集合与部分混合场景有退步；增加队列式层级遍历没有稳定胜过简单选择。最终恢复本轮全部生产改动，保留可复现的实验和数据，未启用新的正式默认路径。", "",
    "这是已准备好的不透明提交数据实验，不是完整的 Voxy 多级 GPU 选 LOD 树，也不是当前 Minecraft 客户端 FPS 采样。", "",
    "**上游机制与适配范围**", "",
    "检查上游 voxy-263-git2 提交 b0207f6fcc0a55fa6b33f5c916932e8560a71845 的 traversal_dev.comp、queue.glsl、node.glsl、HierarchicalOcclusionTraverser 和 MDICSectionRenderer。Voxy 维护常驻节点/网格，GPU 依据视锥、当前 Hi-Z 和屏幕尺寸决定下降或提交节点，再生成绘制命令和数量；GPU dispatch 和 indirect-count 避免 CPU 等待可见性读回。其实现仍有 CPU 节点维护以及多个串行 GPU 阶段。", "",
    "VSS 原型保留原有 GPU arena、材质/遮罩、morph bounds、PreparedPass、显示档位和归属范围。输入为整个测试驻留集合，避免转向时重新发布不透明输入；GPU 在常驻命令上做视锥/方向/可选当前帧 Hi-Z 判断，使用组内前缀和、每组一次 atomicAdd 生成紧凑命令与数量。没有复制上游 shader 源码。", "",
    "三种实验模式：①平铺 GPU 选择并压缩命令；②先检查每 64 条命令的粗包围盒，再处理叶命令；③粗节点通过后写 GPU 队列，由 glDispatchComputeIndirect 遍历入队的叶组。三种模式最终都采用 indirect-count，计数没有进入 CPU 热路径。", "",
    "这没有把显示 LOD 档位、预测父子归属、接缝构建或真实世界读取移到 GPU。常驻集合在真实客户端如何维护、转向/停止切换、资源撤销、Iris 与望远镜仍需另外进行完整集成验证。", "",
    "**连续提交结果**", "",
    "下表单位为毫秒，是每 32 帧批次总墙钟时间除以 32 后的中位数，包含 CPU 准备、提交和 GPU 完成等待；只在批次边界同步。减少耗时为正数，增加耗时为负数。不是游戏帧时间或 FPS。", "",
    "| 平台 | 输入集合 | 状态 | 现有路径 | GPU 平铺选择 | 粗节点选择 | 队列遍历 | 平铺耗时减少 |",
    "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |"]
for platform in platforms:
    for case in platform["cases"]:
        names = {"stationary-no-hiz": "固定，无 Hi-Z", "turning-no-hiz": "转向，无 Hi-Z", "turning-current-frame-hiz": "转向，当前帧 Hi-Z"}
        values = [m["pipelinedWallPerPass"]["medianNs"] / 1e6 for m in case["modes"]]
        label = "1025，含 683 个接缝" if case["tiles"] == 1025 else str(case["tiles"])
        lines.append(f"| {platform['platform']} | {label} | {names[case['scenario']]} | " + " | ".join(f"{v:.4f}" for v in values) + f" | {case['modes'][1]['pipelinedTimeReductionPercent']:+.1f}% |")
lines += ["", "**如何解读收益**", "",
    "大集合转向时，稳定输入和 GPU 选择确实可以减少客户端准备与编码。部分场景的连续提交也有净收益。但 CPU 单项变快不等于 GPU 路径变快：粗测试、前缀扫描、计数清空、屏障和额外 dispatch 都需要时间；原有固定视角缓存只需要很少工作，新路径持续付出 GPU 选择成本。", "",
    "没有用 CPU 与 GPU 两列相加估算一帧，也没有将局部提交耗时减少比例当成 FPS 提升比例。队列实验只是一层粗节点到叶组的有界队列，不能据此否定完整空间树在更大/遮挡更复杂场景中的价值。", "",
    "**测试边界与正确性**", "",
    "设备 RTX 4070 Ti SUPER / OpenGL 4.6 / NVIDIA 572.70，512×512 隐藏窗口。128 和 1024 个瓦片分别为 131072 / 1048576 个合成地形四边形；1025 集合包含 342 个地形瓦片和 683 个接缝，共 393920 面。所有使用当前生产 arena、GPU 编码、terrain program、PreparedPass、OpaqueBatches；CPU 基准调用当前平台生产 VisiblePlan 和 IndirectBatch。接缝使用 ensureSeams 的当前共享 arena 路径。", "",
    "单帧隔离计时每项预热 20 次，41 轮交错顺序；CPU 列是准备与提交的墙钟窗口，可能包含驱动调用成本。GPU 列是 GL_TIME_ELAPSED 区间，不等价于专属 GPU 核心利用时间。连续提交每项预热 8 个批次，21 个交错批次、每批 32 帧，不逐帧 glFinish。GPU 时钟/系统背景活动和 JIT 会影响小差异，未固定硬件频率。", "",
    "每个平台 9 个场景、每场景 3 个检查帧，对 3 个候选分别进行颜色和深度逐元素比较，共 81 对颜色、81 对深度。固定/转向无 Hi-Z 的基准检查还要求实际存在可见像素，避免空画面通过。所有计时帧检查 GL_NO_ERROR；两版测试无失败、错误、跳过。Hi-Z 场景以本帧深度纹理的均匀初始深度及随后近景绘制作为证明，不是实际城市/森林的复杂遮挡。", "",
    "没有验证真实树叶/草花贴图、光影、GPU 内存压力、完整显示 LOD 切换、世界传送与覆盖撤销，也没有采样客户端。本轮不能承诺任何实际游戏 FPS 提升。", "",
    "**最终交付与回退**", "",
    "两个仓库的 PredictionRenderer、PredictionVisiblePlan、PredictionIndirectBatch、PredictionOcclusionCuller 已与本轮开始时的备份逐字节比对一致；上一轮 loading-benefit 报告列出的生产源码哈希和 0.3.5 JAR 哈希也再次核对通过。GPU 选择类已从 main 删除，实验类仅在候选测试源集，不进入正式 JAR。", "",
    "实验类、测试与构建脚本在两个仓库内容一致，完整结果与 SHA256 写入同名 validation.json。没有构建实验 JAR、修改整合包、启动 Minecraft 或推送 GitHub。继续使用 loading-benefit-20261014 的 0.3.5 包即可。", "",
    "后续有证据支持的方向是：先维护可独立失效的常驻候选记录，把动态可见性与显示档位/归属选择分开；在实际转向 CPU 成本足够高时试简单 GPU 选择，并保留静态/小集合路径。更深的 GPU 层级应在粗空间节点能够明显减少叶测试时再引入。", "",
    "复现：NeoForge 使用 JDK 21、Forge 使用 JDK 17，在对应仓库执行 gradlew.bat --offline --no-daemon -I tools/prediction/gpu-traversal-tests.gradle gpuTraversalExperimentTest。生产源码不需要改写。", ""]
json_text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
report_text = "\n".join(lines)
for repo in REPOS.values():
    (repo / "docs" / f"{REPORT}.validation.json").write_text(json_text, encoding="utf-8")
    (repo / "docs" / f"{REPORT}.md").write_text(report_text, encoding="utf-8")
print(json.dumps({"productionRestored": True, "platforms": [{"platform": p["platform"], "tests": p["tests"], "previousDeliveryVerified": True} for p in platforms]}, ensure_ascii=False))
