"""Offline HLOD prototype from captured faces; never edits source/cached geometry."""
from pathlib import Path
from compression import zstd
from collections import Counter, defaultdict
import argparse, hashlib, json, math, struct, time, zipfile
import numpy as np
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--capture', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--minecraft', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
manifest = json.loads((args.capture / 'geometry.json').read_text())
leaves = {m['sprite'] for m in manifest['materials'] if m.get('leafClass')}
draws = {d['meshId']: d for d in manifest['draws'] if not d.get('seam')}

def decode(record):
    raw = (args.capture / f"mesh-{record['id']}.bin").read_bytes()
    if record['codec'] == 'zstd': raw = zstd.decompress(raw)
    words = np.frombuffer(raw, dtype='<u4')
    full = record['wordCount'] // 12
    if record['paletteBaseTexel']:
        assert record['paletteBaseTexel'] > 0, 'unsupported negative encoding'
        records = words[:full*8].reshape(-1, 8)
        palette = words[record['paletteBaseTexel']*4:].reshape(-1, 4)
        result = np.zeros((full, 12), dtype=np.uint32)
        result[:, :7] = records[:, :7]; result[:, 8] = records[:, 7] & 65535
        result[:, [7, 9, 10, 11]] = palette[records[:, 7] >> 16]
    else: result = words.reshape(-1, 12).copy()
    return result[:record['terrainQuadCount']].copy()

def coordinates(words):
    f = words[:, 6]
    fine = (f & (1 << 20)) != 0
    horizontal = np.where(fine, 1/16, (1 << ((f >> 16) & 15)).astype(float))
    vertical = np.where(fine, 1/16, .25)
    c = np.empty((len(words), 4, 3), dtype=float)
    for corner in range(4):
        shift = (corner & 1)*16
        c[:, corner, 0] = ((words[:, corner//2] >> shift) & 65535)*horizontal
        c[:, corner, 2] = ((words[:, 2+corner//2] >> shift) & 65535)*horizontal
        c[:, corner, 1] = (((words[:, 4+corner//2] >> shift) & 65535).astype(np.int32)-32768)*vertical
    return c

def proxy(words, span, spacing, step):
    c = coordinates(words); flags = words[:, 6]
    selected = np.isin(flags & 65535, list(leaves)) & ((flags & (1 << 21)) == 0)
    selected &= (c[:, :, 0].min(1) >= 8) & (c[:, :, 2].min(1) >= 8)
    selected &= (c[:, :, 0].max(1) <= span-8) & (c[:, :, 2].max(1) <= span-8)
    votes = defaultdict(Counter); templates = {}; removed = []
    for q in np.flatnonzero(selected):
        axis_class = int((flags[q] >> 24) & 3); axis = {0: 1, 1: 0, 2: 2}.get(axis_class)
        if axis is None or np.ptp(c[q, :, axis]) > .0001: continue
        normal = -1 if axis == 1 and flags[q] & (1 << 29) else 1 if axis == 1 or flags[q] & (1 << 30) else -1
        lo, hi = c[q].min(0), c[q].max(0)
        plane = float(lo[axis])-.001*normal
        ranges = []
        for dim in range(3):
            if dim == axis: ranges.append([math.floor(plane/step)])
            else: ranges.append(range(math.floor(lo[dim]/step), math.ceil(hi[dim]/step)))
        material = int(flags[q] & 65535); templates.setdefault(material, words[q])
        for x in ranges[0]:
            for y in ranges[1]:
                for z in ranges[2]: votes[x, y, z][material] += 1
        removed.append(q)
    occupied = {key: count.most_common(1)[0][0] for key, count in votes.items()}
    out = []
    for (gx, gy, gz), sprite in sorted(occupied.items()):
        x0, y0, z0 = gx*step, gy*step, gz*step; x1, y1, z1 = x0+step, y0+step, z0+step
        for axis, sign, delta, corners in [
            (0,1,(1,0,0),[(x1,y0,z0),(x1,y1,z0),(x1,y1,z1),(x1,y0,z1)]),
            (0,-1,(-1,0,0),[(x0,y0,z1),(x0,y1,z1),(x0,y1,z0),(x0,y0,z0)]),
            (1,1,(0,1,0),[(x0,y1,z0),(x0,y1,z1),(x1,y1,z1),(x1,y1,z0)]),
            (1,-1,(0,-1,0),[(x0,y0,z1),(x0,y0,z0),(x1,y0,z0),(x1,y0,z1)]),
            (2,1,(0,0,1),[(x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]),
            (2,-1,(0,0,-1),[(x1,y0,z0),(x0,y0,z0),(x0,y1,z0),(x1,y1,z0)])]:
            neighbor = gx+delta[0], gy+delta[1], gz+delta[2]
            if neighbor in occupied: continue
            row = np.zeros(12, dtype=np.uint32)
            for corner, xyz in enumerate(corners):
                for dim, offset in [(0,0),(1,4),(2,2)]:
                    value = round(xyz[dim]*16)+(32768 if dim == 1 else 0)
                    assert 0 <= value <= 65535
                    row[offset+corner//2] |= np.uint32(value << ((corner & 1)*16))
            row[6] = sprite | (1 << 20) | (1 << 26) | (1 << 31) | ({0:1,1:0,2:2}[axis] << 24)
            if axis != 1 and sign > 0: row[6] |= np.uint32(1 << 30)
            if axis == 1 and sign < 0: row[6] |= np.uint32(1 << 29)
            template = templates[sprite]
            for index in [7,9,10,11]: row[index] = template[index] & np.uint32(0xF0FFFFFF)
            row[9] &= np.uint32(~(1 << 24) & 0xFFFFFFFF)
            row[10] |= np.uint32(1 << 24)
            axis_cells = span//spacing
            row[8] = min(axis_cells-1,max(0,gz*step//spacing))*axis_cells + min(axis_cells-1,max(0,gx*step//spacing))
            out.append(row)
    kept = np.ones(len(words), dtype=bool); kept[removed] = False
    exact = words[kept].copy()
    coarse = np.asarray(out,dtype=np.uint32).reshape(-1,12)
    return np.concatenate((exact,coarse)), dict(step=step,replacedFaces=len(removed),proxyFaces=len(coarse),occupiedCells=len(occupied),maxDisplacement=step*math.sqrt(3))

start = time.monotonic(); candidates = []
for record in manifest['meshes']:
    draw = draws.get(record['id'])
    if not draw or record.get('hasMorph') or draw['spacing'] > 4 or record['terrainQuadCount'] > 100000: continue
    words = decode(record)
    count = int(np.isin(words[:, 6] & 65535,list(leaves)).sum())
    if count >= 800 and draw['span'] <= 256: candidates.append((count,record,draw))
print('Eligible captured forest tiles:',len(candidates),flush=True)
# Cover several dominant leaf types rather than picking only the largest one.
chosen = []; seen = Counter()
for count, record, draw in sorted(candidates,key=lambda item:item[0],reverse=True):
    words = decode(record); leaf_words = words[np.isin(words[:,6]&65535,list(leaves))]
    dominant = Counter(map(int,leaf_words[:,6]&65535)).most_common(1)[0][0]
    if seen[dominant] >= 2: continue
    chosen.append((count,record,draw,words)); seen[dominant] += 1
    if len(chosen) == 6: break

summary = []
with (args.output/'forest-fixtures.bin').open('wb') as f:
    f.write(struct.pack('>i',len(chosen)))
    for count,record,draw,words in chosen:
        variants = [words]; metrics = []
        for step in [2,4]:
            variant, metric = proxy(words,draw['span'],draw['spacing'],step); variants.append(variant); metrics.append(metric)
        c = coordinates(words); leaf_c = c[np.isin(words[:,6]&65535,list(leaves))]
        bounds = (float(leaf_c[:,:,1].min()),float(leaf_c[:,:,1].max()))
        f.write(struct.pack('>3i2f',record['id'],draw['span'],draw['spacing'],*bounds))
        for variant in variants:
            f.write(struct.pack('>i',variant.size)); f.write(variant.astype('>u4').tobytes())
        summary.append(dict(id=record['id'],span=draw['span'],spacing=draw['spacing'],sourceFaces=len(words),leafFaces=count,
                            variants=[len(w) for w in variants],metrics=metrics,sourceSha256=hashlib.sha256(words.tobytes()).hexdigest()))
        print('Prepared',summary[-1]['id'],'faces',summary[-1]['variants'],flush=True)

# All tested leaves use real vanilla alpha textures. Other unavailable materials
# use a visible checker fallback and are unchanged between modes.
atlas = Image.new('RGBA',(256,256),(255,255,255,255)); table = np.zeros((2,256,4),dtype='>f4')
texture_sources = []
with zipfile.ZipFile(args.minecraft) as minecraft:
    for material in manifest['materials']:
        sprite = material['sprite']; name = material.get('name'); x=sprite%16*16; y=sprite//16*16
        if not name or sprite == 0: continue
        namespace,path = name.split(':',1); resource=f'assets/{namespace}/textures/{path}.png'
        if resource in minecraft.namelist():
            from io import BytesIO
            image = Image.open(BytesIO(minecraft.read(resource))).convert('RGBA').crop((0,0,16,16))
            texture_sources.append(dict(sprite=sprite,resource=resource))
        else:
            image=Image.new('RGBA',(16,16),(200,200,200,255))
        atlas.paste(image,(x,y)); rgba=np.asarray(image)/255
        table[0,sprite]=[x/256,y/256,(x+16)/256,(y+16)/256];table[1,sprite]=rgba.reshape(-1,4).mean(0)
atlas.save(args.output/'atlas.png');(args.output/'atlas.rgba').write_bytes(atlas.tobytes());(args.output/'sprite-table.bin').write_bytes(table.tobytes())
(args.output/'manifest.json').write_text(json.dumps(dict(capture=str(args.capture),captureSha256=hashlib.sha256((args.capture/'geometry.json').read_bytes()).hexdigest(),
    eligibleTiles=len(candidates),fixtures=summary,textures=texture_sources,textureJar=str(args.minecraft),
    textureJarSha256=hashlib.sha256(args.minecraft.read_bytes()).hexdigest(),productionIntegration=False,preparationSeconds=time.monotonic()-start),indent=2),encoding='utf-8')
assert all(any(t['sprite']==sprite for t in texture_sources) for sprite in leaves)
print('Forest preparation complete',round(time.monotonic()-start,2),'s',flush=True)
