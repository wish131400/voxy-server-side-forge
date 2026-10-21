"""Read captured immutable meshes; census and exact water-order replay, not an FPS benchmark."""
from pathlib import Path
from compression import zstd
from collections import Counter
import argparse
import hashlib
import json
import math

import numpy as np


def decode(record, capture):
    raw = (capture / f"mesh-{record['id']}.bin").read_bytes()
    if record['codec'] == 'zstd':
        raw = zstd.decompress(raw)
    assert len(raw) == record['rawBytes']
    assert record['wordCount'] % 12 == 0
    stored = np.frombuffer(raw, dtype='<u4')
    total = record['wordCount'] // 12
    base = record['paletteBaseTexel']
    if base < 0:
        raise ValueError('This historical census expects 48/32-byte captured records')
    if base == 0:
        return stored.reshape(-1, 12)
    compact = stored[:total * 8].reshape(-1, 8)
    palette = stored[base * 4:].reshape(-1, 4)
    result = np.zeros((total, 12), dtype=np.uint32)
    result[:, :7] = compact[:, :7]
    result[:, 8] = compact[:, 7] & 65535
    result[:, [7, 9, 10, 11]] = palette[compact[:, 7] >> 16]
    return result


def directions(words):
    """Matches PredictionMeshletBounds.direction, including conservative unknown bit."""
    flags = words[:, 6]
    axis = flags >> 24 & 3
    result = np.full(len(words), 64, dtype=np.uint32)
    eligible = ((flags >> 22 & 3) == 0) & (((flags & (1 << 27)) == 0) | ((flags & (1 << 21)) != 0))
    for a, offset, dimension in [(0, 4, 1), (1, 0, 0), (2, 2, 2)]:
        pair = words[:, offset]
        flat = (pair & 65535) == (pair >> 16)
        flat &= words[:, offset + 1] == pair
        positive = (flags & (1 << 29)) == 0 if a == 0 else (flags & (1 << 30)) != 0
        use = eligible & (axis == a) & flat
        result[use] = np.left_shift(np.uint32(1), dimension * 2 + positive[use].astype(np.uint32))
    return result


def add_counts(counts, words, dirs, material_classes):
    flags = words[:, 6]
    sprite = flags & 65535
    counts['quads'] += len(words)
    counts['axis_up'] += int(np.count_nonzero(dirs == 8))
    counts['axis_down'] += int(np.count_nonzero(dirs == 4))
    counts['other_axis'] += int(np.count_nonzero((dirs != 64) & (dirs != 4) & (dirs != 8)))
    counts['conservative_unknown_or_double_sided'] += int(np.count_nonzero(dirs == 64))
    counts['model_uv'] += int(np.count_nonzero(flags & (1 << 21)))
    counts['cutout'] += int(np.count_nonzero(flags & (1 << 26)))
    counts['feature'] += int(np.count_nonzero(words[:, 10] & (1 << 24)))
    counts['preview'] += int(np.count_nonzero(words[:, 10] & (1 << 26)))
    counts['slope_y_axis'] += int(np.count_nonzero(((flags >> 24 & 3) == 0) & (dirs == 64) & ((flags & (1 << 27)) == 0)))
    for name, rows in material_classes.items():
        counts[name] += int(np.count_nonzero(np.isin(sprite, rows)))


def water_replay(draws):
    """Checks unchanged order exactly. Does not quantize distance or delay legitimate changes."""
    items = [(i, d['x'] + d['span'] * .5, d['z'] + d['span'] * .5) for i, d in enumerate(draws)]
    if len(items) < 2:
        return {}
    origin = (float(np.median([i[1] for i in items])), float(np.median([i[2] for i in items])))
    frames = 600
    paths = {
        'vertical_only': [(origin[0], origin[1]) for _ in range(frames)],
        'horizontal_72_blocks': [(origin[0] + i * .12, origin[1]) for i in range(frames)],
        'turning_64_block_radius': [(origin[0] + 64 * math.cos(i * .002), origin[1] + 64 * math.sin(i * .002)) for i in range(frames)],
    }
    result = {}
    for name, path in paths.items():
        def key(item, x, z):
            dx, dz = item[1] - x, item[2] - z
            return dx * dx + dz * dz
        old = sorted(items, key=lambda item: key(item, *path[0]))
        new = old.copy()
        sorts = changed = 0
        for x, z in path:
            before = [i[0] for i in old]
            old.sort(key=lambda item: key(item, x, z))
            changed += before != [i[0] for i in old]
            distances = [key(item, x, z) for item in new]
            if any(a > b for a, b in zip(distances, distances[1:])):
                new.sort(key=lambda item: key(item, x, z))
                sorts += 1
            assert [i[0] for i in old] == [i[0] for i in new], name
        result[name] = dict(frames=frames, current_sort_and_revision_events=frames,
                            actual_order_changes=changed, exact_validation_full_sorts=sorts,
                            camera_y_changes=name == 'vertical_only',
                            identical_order_every_frame=True)
    return dict(items=len(items), assumed_origin_xz=origin, synthetic_paths=True,
                camera_missing_in_capture=True, cpu_timing_measured=False, paths=result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = args.capture / 'geometry.json'
    data = json.loads(manifest.read_text(encoding='utf-8'))
    opaque = {d['meshId']: d for d in data['draws'] if d['pass'] == 'opaquePass' and not d.get('seam')}
    materials = data['materials']
    material_classes = {
        'leaf_material': [m['sprite'] for m in materials if m.get('leafClass')],
        'known_vanilla_aquatic_material': [m['sprite'] for m in materials if m.get('name') in {
            'minecraft:block/seagrass', 'minecraft:block/tall_seagrass_top', 'minecraft:block/tall_seagrass_bottom',
            'minecraft:block/kelp', 'minecraft:block/kelp_plant'}],
        'known_vanilla_land_plant_material': [m['sprite'] for m in materials if m.get('name') in {
            'minecraft:block/short_grass', 'minecraft:block/grass', 'minecraft:block/tall_grass_top',
            'minecraft:block/tall_grass_bottom', 'minecraft:block/fern', 'minecraft:block/large_fern_top',
            'minecraft:block/large_fern_bottom'}],
    }
    source, selected, selected_commands = Counter(), Counter(), Counter()
    selected_materials = Counter()
    meshes = 0
    for record in data['meshes']:
        draw = opaque.get(record['id'])
        if draw is None:
            continue
        words = decode(record, args.capture)
        dirs = directions(words)
        terrain = record['terrainQuadCount']
        assert terrain * 12 <= record['sourceWordCount']
        add_counts(source, words[:terrain], dirs[:terrain], material_classes)
        assert len(draw['first']) == len(draw['count'])
        assert sum(draw['count']) == draw['selectedQuads']
        selected_mask = np.zeros(len(words), dtype=bool)
        meshlet_masks = np.bitwise_or.reduceat(dirs, np.arange(0, len(dirs), 256))
        for first, count in zip(draw['first'], draw['count']):
            assert count > 0 and first >= 0 and first + count <= len(words)
            assert not np.any(selected_mask[first:first + count]), 'Overlapping ranges are not counted twice'
            selected_mask[first:first + count] = True
            # Commands follow packed payload's 256-quad boundaries. Bounds/masks refer
            # to the WHOLE meshlet, including unselected records in a partial range.
            at = first
            while at < first + count:
                end = min(first + count, (at // 256 + 1) * 256)
                mask = int(meshlet_masks[at // 256])
                kind = 'unknown_or_double_sided' if mask & 64 else 'single_direction' if mask.bit_count() == 1 else 'mixed_known_directions'
                selected_commands[kind] += 1
                selected_commands['quads_' + kind] += end - at
                selected_commands['all'] += 1
                at = end
        selected_words = words[selected_mask]
        add_counts(selected, selected_words, dirs[selected_mask], material_classes)
        selected_materials.update(dict(enumerate(np.bincount((selected_words[:, 6] & 65535).astype(np.int32)).tolist())))
        meshes += 1
        if meshes % 500 == 0:
            print(f'Counted {meshes} historical meshes', flush=True)
    names = {m['sprite']: m.get('name') for m in materials}
    result = dict(capture=str(args.capture.resolve()), manifest_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
                  captured_epoch_ms=data['epochMs'], meshes=meshes, source=dict(source), captured_opaque_selection=dict(selected),
                  hypothetical_segmented_commands=dict(selected_commands),
                  top_selected_materials=[dict(sprite=row, name=names.get(row), quads=int(n)) for row, n in selected_materials.most_common(16)],
                  water_order_replay=water_replay([d for d in data['draws'] if d['pass'] == 'waterPass']),
                  limits=['Historical pre-20261012 meshes and draw ranges; not current client or current display LOD.',
                          'No camera, no measured FPS/GPU times; quad directions are potential selectivity, not cull savings.',
                          'Material classes may overlap other flags; feature/cutout/model counts are not mutually exclusive.',
                          'Registry tags and shared material users are unavailable; vanilla material names do not classify every modded plant.',
                          'Command census assumes segmentation; does not reproduce adaptive direct mode, command caps or page grouping.'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
