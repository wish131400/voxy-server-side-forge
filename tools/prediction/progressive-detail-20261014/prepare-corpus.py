"""Convert an existing local geometry capture; never attaches to a client."""
from pathlib import Path
from compression import zstd
import argparse
import gzip
import json
import struct
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('--capture', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
data = json.loads((a.capture / 'geometry.json').read_text(encoding='utf-8'))
draws = {d['meshId']: d for d in data['draws'] if not d.get('seam')}
meshes = [m for m in data['meshes'] if m['id'] in draws]
materials = {m['sprite']: m for m in data['materials']}
a.output.parent.mkdir(parents=True, exist_ok=True)
with gzip.open(a.output, 'wb', compresslevel=1) as out:
    out.write(struct.pack('>i', 0x56535350))
    for i in range(256):
        m = materials.get(i, {})
        name = m.get('name') or ''
        land = any(k in name for k in ['short_grass', 'tall_grass', 'fern'])
        water = any(k in name for k in ['seagrass', 'kelp'])
        out.write(struct.pack('>3?', bool(m.get('leafClass')) or name == 'minecraft:block/snow', land or water, water))
    out.write(struct.pack('>i', len(meshes)))
    for idx, m in enumerate(meshes):
        raw = (a.capture / f"mesh-{m['id']}.bin").read_bytes()
        if m['codec'] == 'zstd': raw = zstd.decompress(raw)
        storage = np.frombuffer(raw, dtype='<u4')
        full = m['wordCount'] // 12
        if m['paletteBaseTexel']:
            compact = storage[:full * 8].reshape(-1, 8)
            palette = storage[m['paletteBaseTexel'] * 4:].reshape(-1, 4)
            canonical = np.zeros((full, 12), dtype=np.uint32)
            canonical[:, :7] = compact[:, :7]
            canonical[:, 8] = compact[:, 7] & 65535
            canonical[:, [7, 9, 10, 11]] = palette[compact[:, 7] >> 16]
        else:
            canonical = storage.reshape(-1, 12)
        words = canonical.ravel()[:m['sourceWordCount']]
        d = draws[m['id']]
        out.write(struct.pack('>5i', m['id'], d['span'] // d['spacing'], d['spacing'], len(words), m['terrainQuadCount']))
        out.write(words.astype('>u4').tobytes())
        if (idx + 1) % 250 == 0: print(f'Prepared {idx + 1}/{len(meshes)} real meshes', flush=True)
print(f'Prepared {len(meshes)} meshes, {a.output.stat().st_size} compressed bytes', flush=True)
