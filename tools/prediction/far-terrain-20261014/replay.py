"""Replay immutable captured meshes in a separate JVM; never changes the game."""
from pathlib import Path
from compression import zstd
import argparse
import json
import struct
import subprocess
import time
import numpy as np

ROOT = Path(__file__).resolve().parents[3]

def canonical(capture, record):
    raw = (capture / f"mesh-{record['id']}.bin").read_bytes()
    if record['codec'] == 'zstd':
        raw = zstd.decompress(raw)
    elif record['codec'] != 'raw':
        raise ValueError(record['codec'])
    storage = np.frombuffer(raw, dtype='<u4')
    n = record['wordCount'] // 12
    if record['paletteBaseTexel']:
        compact = storage[:n*8].reshape(-1, 8)
        palette = storage[record['paletteBaseTexel']*4:].reshape(-1, 4)
        words = np.zeros((n, 12), dtype=np.uint32)
        words[:, :7] = compact[:, :7]
        words[:, 8] = compact[:, 7] & 65535
        words[:, [7, 9, 10, 11]] = palette[compact[:, 7] >> 16]
    else:
        words = storage.reshape(-1, 12).copy()
    return words.ravel()[:record['sourceWordCount']]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=Path, default=Path(r'C:\Users\Administrator\.codex\tmp\vss-distance-render-20261010\geometry'))
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--main', default='DisplayProbe')
    parser.add_argument('--limit', type=int, default=128)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    classes=args.output/'classes';classes.mkdir(exist_ok=True)
    jdk=Path('C:/Program Files/Java/jdk-21/bin')
    subprocess.run([str(jdk/'javac.exe'),'--release','17','-d',str(classes),*map(str,args.source.glob('*.java'))],check=True)
    data=json.loads((args.capture/'geometry.json').read_text())
    draws={d['meshId']:d for d in data['draws'] if not d.get('seam')}
    records=sorted([r for r in data['meshes'] if r['id'] in draws],key=lambda r:r['id'])
    if args.limit:
        records=[records[i*len(records)//min(args.limit,len(records))] for i in range(min(args.limit,len(records)))]
    process=subprocess.Popen([str(jdk/'java.exe'),'-Xmx3G','-cp',str(classes),'dev.xantha.vss.client.prediction.'+args.main],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(args.output/'stderr.txt').open('wb'))
    materials={m['sprite']:m for m in data['materials']}
    for i in range(256):
        m=materials.get(i,{});name=m.get('name') or '';leaf=bool(m.get('leafClass'))
        aquatic=name in {'minecraft:block/seagrass','minecraft:block/tall_seagrass_bottom','minecraft:block/tall_seagrass_top','minecraft:block/kelp','minecraft:block/kelp_plant'}
        land=name in {'minecraft:block/short_grass','minecraft:block/grass','minecraft:block/tall_grass_bottom','minecraft:block/tall_grass_top','minecraft:block/fern','minecraft:block/large_fern_bottom','minecraft:block/large_fern_top'}
        process.stdin.write(struct.pack('>4?',leaf or name=='minecraft:block/snow',aquatic,land,leaf or aquatic or land))
    rows=[];start=time.monotonic()
    try:
        for record in records:
            words=canonical(args.capture,record);draw=draws[record['id']]
            process.stdin.write(struct.pack('>6i',record['id'],len(words),record['terrainQuadCount'],record['waterQuadCount'],draw['span']//draw['spacing'],draw['spacing']))
            process.stdin.write(words.astype('>u4').tobytes());process.stdin.flush()
            line=process.stdout.readline()
            if not line:raise RuntimeError((args.output/'stderr.txt').read_text())
            row=json.loads(line);row['spacing']=draw['spacing'];rows.append(row)
            if len(rows)%128==0:print(f'Replayed {len(rows)}/{len(records)} in {time.monotonic()-start:.1f}s',flush=True)
        process.stdin.close()
        if process.wait()!=0:raise RuntimeError((args.output/'stderr.txt').read_text())
    finally:
        if process.poll() is None:process.kill()
    (args.output/'rows.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
    print(json.dumps({'meshes':len(rows),'elapsedSeconds':time.monotonic()-start,'totals':{k:sum(r[k] for r in rows) for k in ('source','extra') if all(k in r for r in rows)},'rejections':[sum(r['reasons'][i] for r in rows) for i in range(6)] if rows and 'reasons' in rows[0] else None}),flush=True)

if __name__=='__main__':main()
