"""Summarize completed, serial experiments; do not treat them as live FPS."""
from pathlib import Path
from collections import defaultdict
import csv, hashlib, json, shutil, statistics, xml.etree.ElementTree as ET

repos = {
    'neoforge': Path('C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1'),
    'forge': Path('C:/Users/Administrator/Desktop/voxyserverside'),
}

def summarize_csv(path, keys, fields):
    groups = defaultdict(list)
    for row in csv.DictReader(path.open(encoding='utf-8')):
        groups[tuple(row[key] for key in keys)].append(row)
    result = []
    for key, rows in groups.items():
        out = dict(zip(keys,key),samples=len(rows))
        for field in fields:
            values = [float(row[field]) for row in rows]
            out[field+'_median'] = statistics.median(values)
            if field.endswith('_ns'):
                out[field+'_p95'] = sorted(values)[int(.95*(len(values)-1))]
                out[field+'_p99'] = sorted(values)[int(.99*(len(values)-1))]
        if 'frames' in rows[0]: out['ns_per_pass_median'] = statistics.median(float(row['elapsed_ns'])/int(row['frames']) for row in rows)
        result.append(out)
    return result

result = dict(date='2026-10-14',liveClientSampled=False,liveFPSMeasured=False,
              productionChanged=False,releaseJarBuilt=False,
              gpu='NVIDIA GeForce RTX 4070 Ti SUPER',driver='572.70',api='OpenGL 4.6',
              finalGpuRunsSerial=True,experimentalInitialConcurrentRunsDiscarded=True,
              references={
                  'Unity DOTS Instancing':'https://docs.unity3d.com/6000.0/Documentation/Manual/dots-instancing-shaders.html',
                  'UE Mesh Drawing Pipeline':'https://dev.epicgames.com/documentation/en-us/unreal-engine/mesh-drawing-pipeline-in-unreal-engine',
                  'UE HLOD':'https://dev.epicgames.com/documentation/en-us/unreal-engine/world-partition---hierarchical-level-of-detail-in-unreal-engine',
                  'UE Nanite Foliage Experimental':'https://dev.epicgames.com/documentation/en-us/unreal-engine/nanite-foliage',
              },platforms={})

for platform, root in repos.items():
    build=root/'build-engine-render-20261014'; data=build/'results'
    platform_result = dict(
        input=summarize_csv(data/'engine-input.csv',['tiles','changed','mode'],['cpu_ns','allocated_bytes','rebuilt_records']),
        prepare=summarize_csv(data/'engine-prepare.csv',['tiles','changed','mode'],['cpu_ns','allocated_bytes','range_builds']),
        maintenance=summarize_csv(data/'engine-maintenance-throughput.csv',['tiles','case','mode'],['elapsed_ns']),
        forest=summarize_csv(data/'engine-forest.csv',['mesh','distance','hiz','mode'],['gpu_ns','cpu_ns','mdi_quads','mdi_commands']),
        retainedControl=summarize_csv(data/'retained-gpu-selection-throughput.csv',['tiles','case','mode'],['elapsed_ns']),
        tests=[],sourceSha256={},artifactSha256={},quality=[])
    quality=list(csv.DictReader((data/'engine-forest-quality.csv').open()))
    for mode in ['1','2']:
        rows=[r for r in quality if r['mode']==mode and r['proxy_eligible']=='true']
        platform_result['quality'].append(dict(mode=int(mode),eligibleViews=len(rows),
            iouMedian=statistics.median(float(r['iou']) for r in rows),
            addedFractionMedian=statistics.median(float(r['added_fraction']) for r in rows),
            lostFractionMedian=statistics.median(float(r['lost_fraction']) for r in rows),
            sourcePixelsMin=min(int(r['source_pixels']) for r in rows),sourcePixelsMax=max(int(r['source_pixels']) for r in rows)))
    for xml in build.glob('test-results/*/TEST-*.xml'):
        suite=ET.parse(xml).getroot();row=dict(task=xml.parent.name,**suite.attrib)
        assert row['failures']=='0' and row['errors']=='0' and row['skipped']=='0',str(xml)
        platform_result['tests'].append(row)
    assert sum(int(r['tests']) for r in platform_result['tests'])==6
    for name in ['PredictionGpuSelectionInput','PredictionIndirectBatch','PredictionDisplayGeometry','PredictionRenderer','PredictionVegetation']:
        path=root/f'src/main/java/dev/xantha/vss/client/prediction/{name}.java'
        platform_result['sourceSha256'][name]=hashlib.sha256(path.read_bytes()).hexdigest()
    for csv_file in data.glob('*.csv'):
        platform_result['artifactSha256'][csv_file.name]=hashlib.sha256(csv_file.read_bytes()).hexdigest()
    result['platforms'][platform]=platform_result

result['forestInputs']=json.loads((repos['neoforge']/'tools/prediction/engine-render-20261014/inputs/manifest.json').read_text())
result['interpretation']={
    'resident':'Stable cached normalized records reduce downstream preparation on sparse updates and unrelated global epoch changes; scans and extra cache memory remain. Full replacement regresses. Prototype not enabled.',
    'forest':'Surface-derived binary canopy proxies reduce GPU work but increase foliage coverage and fill holes. This is not Nanite virtualized geometry or its volumetric shading. Prototype not enabled.',
    'bounds':'512x512 standalone contexts and prescribed scenarios; no Minecraft frame time or full-game CPU/GPU utilization inference. Tiny distant masks make IoU sensitive to individual pixels.',
    'notTested':'Production Iris integration, real client FPS, resource-pack leaf textures, dynamic whole-world proxy handoff, temporal occlusion changes, repeated-part instancing, RDG, complete Nanite.'}

def row(rows,**values):
    return next(r for r in rows if all(r[key]==value for key,value in values.items()))

lines = ['**Unity／虚幻渲染思路的 VSS 原型对照（2026-10-14）**', '',
    '测试已完成。常驻候选记录的局部复用在指定更新压力下有明确 CPU 与完整提交收益；粗树冠代理有 GPU 收益，但没有证明能保留当前树叶孔隙和覆盖密度。两项都保留为独立实验，没有进入生产源码或发布 JAR。', '',
    '这是借鉴引擎设计思路的 VSS 原型与当前 VSS 对照，没有运行 Unity／Unreal 引擎，也没有移植完整 Nanite。两个平台按相同输入分别编译，最终 GPU 测量串行执行；初次并行启动的 GPU 数据废弃。NeoForge 使用 JDK 21，Forge 使用 JDK 17。显卡为 RTX 4070 Ti SUPER，OpenGL 4.6，NVIDIA 572.70。没有启动、退出、采样或替换 Minecraft 客户端，没有提交／推送 GitHub。', '',
    '**常驻记录复用：实际减少了什么**', '',
    '原型沿用现有 Scene、PreparedPass、MDI、arena 与当前帧 Hi-Z，只复用未变的候选记录：以原始 Draw 身份、packed 身份、局部 mesh/GPU revision、相机位置和 horizon 校验。全球版本变化时仍扫描记录，但不反复创建相同的 normalized Draw；下游 PreparedPass 因身份稳定可复用原条目。删除和换世界清空引用。它还没有实现稳定 GPU Scene ID、脏瓦片队列或零扫描的常驻场景表。', '',
    '输入维护与正式 PreparedPass 一起测量，双方使用相同场景快照；不包含生成、快照生产、OpenGL 提交及 GPU 等待。81 次记录前预热 48 次，逐轮交替顺序。下表单位 ms/次，中位数。', '',
    '| 平台 | 候选数 | 每轮替换记录 | 当前路径 | 复用原型 | 耗时变化 |',
    '| --- | ---: | ---: | ---: | ---: | ---: |']
for platform in repos:
    data=result['platforms'][platform]['prepare']
    for count,changed in [('4096','1'),('8192','1'),('4096','4096'),('8192','8192')]:
        a=row(data,tiles=count,changed=changed,mode='0')['cpu_ns_median']/1e6;b=row(data,tiles=count,changed=changed,mode='1')['cpu_ns_median']/1e6
        lines.append(f'| {platform} | {count} | {changed} | {a:.4f} | {b:.4f} | {(b/a-1)*100:+.1f}% |')
lines += ['', '负值表示减少。局部替换时分配也显著减少；完整替换时新增版本检查／缓存记录使时间和分配增加，所以不能直接全场景启用。CPU 微基准的 fixture 共享 packed 输入、没有 GPU 分配，不作为 GPU 选择实际启用的证据。', '',
    '**包含 GPU 完成的提交对照**', '',
    '另一个独立测试使用上传后的 MODEL_UV 合成地形、生产 PreparedPass、OpaqueBatches、MDI 和当前帧 Hi-Z。对照为当前生产 IndirectBatch；候选为同一源码仅改用复用输入的实验副本。每帧让一个不属于当前集合、但已 trackSubmission 的 mesh 改变全局提交 epoch；单帧检查断言 epoch 确实增加。相机原地转向；两条路径按各自条件执行真实 GPU 命令选择，并断言有选择帧。', '',
    '这刻意模拟高频无关资源更新，不表示所有游戏帧都发生该更新。计时每批 32 帧，8 批预热后测 21 批，批边界等待 GPU 完成，交替 A/B 顺序。下表为总墙钟时间除以 32 的中位数，不能把它换算成实际游戏 FPS。', '',
    '| 平台 | 地形瓦片 | 当前 ms/次 | 复用 ms/次 | 减少 |', '| --- | ---: | ---: | ---: | ---: |']
for platform in repos:
    data=result['platforms'][platform]['maintenance']
    for count in ['3072','4096']:
        a=row(data,tiles=count,case='1',mode='0')['ns_per_pass_median']/1e6;b=row(data,tiles=count,case='1',mode='1')['ns_per_pass_median']/1e6
        lines.append(f'| {platform} | {count} | {a:.4f} | {b:.4f} | {(1-b/a)*100:.1f}% |')
lines += ['', '每平台 4 个场景 × 3 个检查帧，12 对颜色及 12 对深度全数一致。CPU 局部替换、移动、horizon、payload 撤销、引用释放的检查也通过。静止／禁用选择是控制场景，没有稳定的大幅收益承诺。Iris、水面及实际客户端下的交接未接入该原型。', '',
    '**树冠 HLOD／粗体素代理**', '',
    '从历史捕获的 3,890 个网格中找出 189 个满足筛选条件的森林瓦片，选择 6 个叶面较多且涵盖不同叶材质的样本。选取不是随机抽样，不能外推为全世界收益。使用同一 1.21.1 原版客户端 JAR 提取的真实树叶 alpha 纹理作为两平台共同测试图集；它不是用户当前资源包，也不是各平台实时图集。其他材质在原型和基线中保持相同。', '',
    '原型从叶面附近重建 2／4 方块二值占据格，保留边缘 8 方块原几何，树干、地形和水体源记录不被替换。这个试验没有完整树木体积快照、覆盖率／法线分布或 Nanite 体素光栅化；用的是现有生产顶点／片元 shader 和 MDI。它属于“简单二值树冠代理”的验证，不能证明完整引擎方案的质量和收益。', '',
    '六个样本源不透明面数 191,776。代理源记录分别为 162,925／136,657 面，减少约 15.0%／28.7%；随后两条路径各自执行已有地形显示 LOD。性能测试为 512×512、每个样本一次提交，预热 24 次、交替 81 次，单独比较无 Hi-Z 和完整当前帧 Hi-Z。以下是六个独立用例 GPU 时间中位数之和，单位 ms，不是同一游戏帧。', '',
    '| 平台 | 距离 | Hi-Z | 当前 | 2 方块代理 | 4 方块代理 |', '| --- | ---: | --- | ---: | ---: | ---: |']
for platform in repos:
    data=result['platforms'][platform]['forest']
    for distance in ['1024','4096']:
        sums=[sum(r['gpu_ns_median']/1e6 for r in data if r['distance']==distance and r['hiz']=='true' and r['mode']==str(mode)) for mode in range(3)]
        lines.append(f'| {platform} | {distance} | 开 | {sums[0]:.6f} | {sums[1]:.6f} | {sums[2]:.6f} |')
lines += ['', '4 方块代理在 1,024 方块处超出本试验的 2 px 位移预算，数字只反映潜在工作量，不计为可启用收益。代理与原几何没有要求跨 LOD 像素一致；每个平台对 6 样本 × 3 距离 × 2 朝向 × 3 表示的直绘／MDI 做了 108 对颜色和 108 对深度一致检查，通过。', '',
    '另外把树叶单独绘制，统计同视角覆盖掩码交并比和新增／丢失覆盖像素。满足距离保护的 2 方块代理有 24 个视角，IoU 中位数约 0.815；4 方块代理有 12 个视角，约 0.651。新增覆盖占原覆盖的中位数约 22%／54%，丢失覆盖中位数为零，显示主要问题是变实和孔隙填充。4,096 方块处原覆盖只有 5～23 像素，IoU 对单像素很敏感；图像也保留了 1,024 方块处 61～439 像素的对照。', '',
    '![同视角树冠代理及放大对照](engine-render-20261014-forest-comparison.png)', '',
    '近处、望远镜、接缝、morph、部分归属条件只检查了实验选择门槛；没有将代理接入 Renderer 的真实父子／Voxy 交接。因此不能据此声称完成了动态交接验证。下一版需要同源体积、覆盖率及轮廓约束，并在有完整回退的版本事务内发布。', '',
    '**保留和后续顺序**', '',
    '先推进稳定记录和细粒度版本失效，但上线前需要解决完整替换退步、限制额外缓存引用、测试实际资源更新比例和 Iris 路径。森林继续研究带覆盖率的树冠表示，先通过多视角质量对照，再接真实归属和接缝；当前二值代理保留为反例。已有常驻 GPU 选择控制也重跑完成，但属于已有机制的复测，不能算作本轮新收益。', '',
    '没有测试完整 Unity／UE 引擎、重复部件实例化、时序遮挡改动或 Render Graph；没有把此前无净收益的深层级或 Mesh Shader 重新启用。', '',
    '**复现与证据**', '',
    '两个仓库的 tools/prediction/engine-render-20261014 保存相同原型、输入、各平台原始 CSV、最终日志和 JUnit XML；同名 JSON 包含分组中位数、样本数、计时分位、源码／输入 SHA256 和限制。每个平台 6 个测试，失败／错误／跳过均为零。', '',
    '```powershell', '# NeoForge（JDK 21）', './gradlew.bat --offline --no-daemon -I tools/prediction/engine-render-tests.gradle engineInputTest engineForestGpuTest engineResidentGpuTest engineMaintenanceGpuTest --console=plain', '',
    '# Forge（JDK 17）；两平台 GPU 测试须串行', "./gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/engine-render-tests.gradle engineInputTest engineForestGpuTest engineResidentGpuTest engineMaintenanceGpuTest --console=plain", '```', '',
    '官方参考：[Unity DOTS Instancing](https://docs.unity3d.com/6000.0/Documentation/Manual/dots-instancing-shaders.html)、[虚幻 Mesh Drawing Pipeline](https://dev.epicgames.com/documentation/en-us/unreal-engine/mesh-drawing-pipeline-in-unreal-engine)、[HLOD](https://dev.epicgames.com/documentation/en-us/unreal-engine/world-partition---hierarchical-level-of-detail-in-unreal-engine)、[Nanite Foliage（Experimental）](https://dev.epicgames.com/documentation/en-us/unreal-engine/nanite-foliage)。', '']

forest_manifest=result['forestInputs']['fixtures']
actual_totals=[sum(f['variants'][mode] for f in forest_manifest) for mode in range(3)]
assert actual_totals==[191776,162925,136657],actual_totals

for root in repos.values():
    docs=root/'docs';docs.mkdir(exist_ok=True)
    (docs/'vss-engine-render-tests-20261014.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    (docs/'vss-engine-render-tests-20261014.md').write_text('\n'.join(lines),encoding='utf-8')
    shutil.copy2(repos['neoforge']/'build-engine-render-20261014/results/forest-proxy-comparison.png',docs/'engine-render-20261014-forest-comparison.png')
    for platform, source_root in repos.items():
        destination=root/'tools/prediction/engine-render-20261014/results'/platform;destination.mkdir(parents=True,exist_ok=True)
        for path in (source_root/'build-engine-render-20261014/results').glob('*.csv'): shutil.copy2(path,destination/path.name)
        for path in (source_root/'build-engine-render-20261014').glob('run-*.log'): shutil.copy2(path,destination/path.name)
        for path in (source_root/'build-engine-render-20261014/test-results').glob('*/TEST-*.xml'):
            shutil.copy2(path,destination/(path.parent.name+'-'+path.name))
print(json.dumps({key:sum(int(r['tests']) for r in data['tests']) for key,data in result['platforms'].items()}))
print('Saved reports, source hashes, raw CSV, logs, XML and comparison image to both repositories')
