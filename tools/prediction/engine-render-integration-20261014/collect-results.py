"""Collect final production integration evidence without touching prior experiment results."""
from pathlib import Path
import csv, hashlib, json, shutil, statistics, struct, zipfile
import xml.etree.ElementTree as ET

ROOTS = {
    'neoforge': Path('C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1'),
    'forge': Path('C:/Users/Administrator/Desktop/voxyserverside'),
}
REL = Path('tools/prediction/engine-render-integration-20261014')
SUITES = ['resident-record','loading-benefit','resident-maintenance-gpu','loading-benefit-gpu',
          'gpu-selection-fallback','canopy-surface','canopy-material-gpu','resident-coverage']
CSV_FILES = ['resident-input.csv','resident-prepare.csv','resident-maintenance.csv',
             'resident-maintenance-throughput.csv','canopy-surface-census.csv',
             'canopy-material.csv','canopy-material-quality.csv']

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path):
    with path.open(encoding='utf-8-sig',newline='') as f: return list(csv.DictReader(f))
def grouped(rows, keys, fields):
    groups = {}
    for row in rows: groups.setdefault(tuple(row[k] for k in keys), []).append(row)
    result = []
    for key, values in sorted(groups.items()):
        item = dict(zip(keys,key)); item['samples'] = len(values)
        for field in fields:
            nums = sorted(float(v[field]) for v in values)
            item[field] = dict(median=statistics.median(nums),p10=nums[int((len(nums)-1)*.1)],p90=nums[int((len(nums)-1)*.9)])
        result.append(item)
    return result

summary = dict(date='2026-10-14',liveClientProfile=False,productionChange='PredictionGpuSelectionInput',
    canopyProxyEnabled=False,platforms={},limits=[
        'Input and PreparedPass CPU comparisons exclude scene/world generation and GL submission.',
        'Whole submissions deliberately change a tracked unrelated mesh epoch every frame; not game FPS.',
        'Static cases do not exercise resident selection; differences are control-run variation.',
        'Foliage GPU inputs use the same alpha-tested flags and vanilla atlas for source and proxies.',
        'Captured forest tiles are six selected historical samples, not a random full-world sample.',
        'The exact planar foliage experiment failed residency/savings admission and is not packaged.',
        'No stable GPU Scene ID, dirty-scene queue, Nanite, Mesh Shader or general zero-scan pipeline is claimed.',
        'GPU tests were run serially across platforms on the same RTX 4070 Ti SUPER.',
    ])
for platform, root in ROOTS.items():
    build=root/'build-engine-integration-20261014'
    out=ROOTS['neoforge']/REL/'results'/platform;out.mkdir(parents=True,exist_ok=True)
    data={}
    for name in CSV_FILES:
        path=build/'results'/name;assert path.exists(), path
        shutil.copy2(path,out/name);data[name]=read(path)
    tests={};unique=set()
    for suite in SUITES:
        paths=list((build/'test-results'/suite).glob('TEST-*.xml'));assert paths,suite
        total=dict(tests=0,failures=0,errors=0,skipped=0)
        target=out/'junit'/suite;target.mkdir(parents=True,exist_ok=True)
        for path in paths:
            xml=ET.parse(path).getroot()
            for key in total:total[key]+=int(xml.get(key,0))
            for case in xml.findall('testcase'):unique.add((case.get('classname'),case.get('name')))
            shutil.copy2(path,target/path.name)
        assert total['failures']==total['errors']==total['skipped']==0,(platform,suite,total)
        tests[suite]=total
    for name in ['build-engine-integration-cpu.log','build-engine-integration-final.log',
                 'build-engine-integration-quality.log','build-engine-integration-canopy-gpu.log','build-engine-integration-coverage.log']:
        path=root/name
        if path.exists():shutil.copy2(path,out/name)
    jars=list((root/'lib/engine-integration-20261014').glob('*.jar'));assert len(jars)==1,jars
    jar=jars[0]
    with zipfile.ZipFile(jar) as z:
        names=z.namelist();raw=z.read('dev/xantha/vss/client/prediction/PredictionGpuSelectionInput.class')
        assert struct.unpack('>H',raw[6:8])[0]==(65 if platform=='neoforge' else 61)
        assert b'collectDirect' in raw and b'submissionRevision' in raw
        assert not any(any(k in n for k in ['EngineBefore','ResidentRecord','CoverageCanopy','CanopyMaterialQuality','CanopySurfaceIntegration']) for n in names)
        natives=[n for n in names if 'vss-natives/' in n and not n.endswith('/')]
        assert any(n.endswith('windows-x86_64/vss_native_core.dll') for n in natives)
        resource='META-INF/neoforge.mods.toml' if platform=='neoforge' else 'META-INF/mods.toml'
        assert '0.3.5' in z.read(resource).decode()
        if platform=='forge':assert b'f_82479_' in raw,'Forge JAR must be reobfuscated'
    quality=[r for r in data['canopy-material-quality.csv'] if r['proxy_eligible']=='true' and r['mode']!='0']
    summary['platforms'][platform]=dict(tests=tests,uniqueTests=len(unique),
        sourceSha256=sha(root/'src/main/java/dev/xantha/vss/client/prediction/PredictionGpuSelectionInput.java'),
        baselineSha256=json.loads((root/REL/'baseline-sha256.json').read_text()),
        jar=dict(path=str(jar),sha256=sha(jar),bytes=jar.stat().st_size,classMajor=65 if platform=='neoforge' else 61,natives=natives),
        input=grouped(data['resident-input.csv'],['tiles','changed','mode'],['cpu_ns','allocated_bytes']),
        prepared=grouped(data['resident-prepare.csv'],['tiles','changed','mode'],['cpu_ns','allocated_bytes','range_builds']),
        submission=grouped(data['resident-maintenance-throughput.csv'],['tiles','case','mode'],['elapsed_ns','selected_frames']),
        alphaCanopyQuality=grouped(quality,['mode'],['iou','added_fraction','lost_fraction','rgb_mae']),
        surfaceCanopy=data['canopy-surface-census.csv'],
        colorDepthPairs=dict(resident=12,alphaCanopy=216))
    assert all(r['accepted']=='false' for r in data['canopy-surface-census.csv'])

assert len({p['sourceSha256'] for p in summary['platforms'].values()})==1
# Keep a single corrected image comparison from NeoForge; raw frames stay in build/results.
try:
    from PIL import Image, ImageDraw, ImageFont
    res=ROOTS['neoforge']/'build-engine-integration-20261014/results'
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    canvas=Image.new('RGB',(1230,560),'#20252d');draw=ImageDraw.Draw(canvas)
    draw.text((15,8),'统一透明树叶材质的离线对照：两种粗树冠均未启用',font=font,fill='white')
    for row,mesh in enumerate([1511,1476]):
        images=[Image.open(res/f'canopy-material-{mesh}-1024-45-{mode}.png').convert('RGBA') for mode in range(3)]
        boxes=[im.getchannel('A').getbbox() for im in images];boxes=[b for b in boxes if b]
        box=(max(0,min(b[0] for b in boxes)-3),max(0,min(b[1] for b in boxes)-3),min(512,max(b[2] for b in boxes)+3),min(512,max(b[3] for b in boxes)+3))
        for col,(image,label) in enumerate(zip(images,['当前几何','2 方块粗树冠（实验）','4 方块粗树冠（实验）'])):
            x=10+col*410;y=48+row*255
            draw.text((x,y),f'{label}  #{mesh}',font=font,fill='white')
            tile=image.crop(box);tile.thumbnail((395,212),Image.Resampling.NEAREST)
            # Integer nearest scaling makes the same pixel coverage easy to compare.
            scale=max(1,min(395//tile.width,212//tile.height));tile=tile.resize((tile.width*scale,tile.height*scale),Image.Resampling.NEAREST)
            canvas.paste(tile,(x+(395-tile.width)//2,y+32+(212-tile.height)//2),tile)
    for root in ROOTS.values():canvas.save(root/'docs/engine-integration-20261014-canopy-alpha.png')
except ImportError:
    pass
for root in ROOTS.values():
    (root/'docs/vss-engine-render-integration-20261014.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    other=root/REL/'results';other.mkdir(parents=True,exist_ok=True)
    for platform in ROOTS:
        src=ROOTS['neoforge']/REL/'results'/platform;dest=other/platform
        if src.resolve()!=dest.resolve():shutil.copytree(src,dest,dirs_exist_ok=True)
print(json.dumps({k:dict(uniqueTests=v['uniqueTests'],tests=v['tests'],jar=v['jar'],quality=v['alphaCanopyQuality']) for k,v in summary['platforms'].items()},ensure_ascii=False,indent=2))
