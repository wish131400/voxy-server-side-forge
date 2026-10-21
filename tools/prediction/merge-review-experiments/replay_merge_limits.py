"""Compare production scan meshing with two isolated larger-rectangle candidates.

The historical capture has no usable camera. This measures complete ordinary
meshes and uniform display tiers, not frame selection, memory usage or FPS.
Requires Python 3.14 (compression.zstd), NumPy and JDK 21. No game is launched.
"""
from pathlib import Path
from collections import Counter
from compression import zstd
import argparse
import hashlib
import json
import struct
import subprocess
import time

import numpy as np


HARNESS = r'''package dev.xantha.vss.client.prediction;
import java.io.*; import java.util.*;
final class PredictionPackedMesh {
 static final int STRIDE_INTS=12, FLAG_UNSHADED=1<<27, FLAG_MODEL_UV=1<<21,
 FLAG_LOD_TEXTURE_SCALE=1<<31, FLAGS_FLUID_SHIFT=22, FLAG_CUTOUT=1<<26,
 FEATURE=1<<24, DISPLAY_APPROXIMATE=1<<25, PREVIEW=1<<26, FLAGS_AXIS_SHIFT=24,
 FLAG_FINE_COORDINATES=1<<20, FLAG_FACE_POSITIVE=1<<30, XZ_SHIFT_BITS=16;
}
final class VssLodSpriteTable {
 static boolean[] approx=new boolean[256], aquatic=new boolean[256], land=new boolean[256], plants=new boolean[256];
 static boolean[] displayApproximationSprites(){return approx.clone();}
 static boolean[] aquaticSprites(){return aquatic.clone();}
 static boolean[] landPlantSprites(){return land.clone();}
 static boolean[] vegetationSprites(){return plants.clone();}
}
public class MergeLimitReplay {
 static boolean crossHorizontal;
 static int group(int flags) {
  if(crossHorizontal && (flags & 1<<27)!=0 && (flags & 1<<21)==0)return 0;
  int axis=flags>>>24&3; boolean pos=(flags & 1<<30)!=0;
  return axis==1?(pos?4:3):axis==2?(pos?2:1):0;
 }
 static Map<List<Integer>,Long> areas(int[] words,int count) {
  var result=new HashMap<List<Integer>,Long>();
  for(int p=0;p<count*12;p+=12) {
   int n=PredictionScanMerge.normal(words[p+6]),u=(n+1)%3,v=(n+2)%3;
   long area=0;
   for(int c=0;c<4;c++) area+=(long)PredictionScanMerge.coordinate(words,p,c,u)*PredictionScanMerge.coordinate(words,p,(c+1)%4,v)
        -(long)PredictionScanMerge.coordinate(words,p,(c+1)%4,u)*PredictionScanMerge.coordinate(words,p,c,v);
   var key=List.of(words[p+6],words[p+7],words[p+9]&~(1<<24),words[p+10],words[p+11],PredictionScanMerge.coordinate(words,p,0,n));
   result.merge(key,area,Long::sum);
  }
  return result;
 }
 static int[] tiers(PredictionDrawRanges[][] ranges,int source) {
  int[] result={source,source,source,source};
  if(ranges!=null) for(int tier=1;tier<=3;tier++) if(ranges[tier][31]!=null)result[tier]=ranges[tier][31].quads;
  return result;
 }
 public static void main(String[] args)throws Exception {
  var in=new DataInputStream(new BufferedInputStream(System.in,1048576)); var out=new PrintWriter(System.out);
  for(int i=0;i<256;i++) {
   VssLodSpriteTable.approx[i]=in.readBoolean(); VssLodSpriteTable.aquatic[i]=in.readBoolean();
   VssLodSpriteTable.land[i]=in.readBoolean(); VssLodSpriteTable.plants[i]=in.readBoolean();
  }
  while(true) {
   int id;try{id=in.readInt();}catch(EOFException e){break;}
   int length=in.readInt(),terrain=in.readInt(),water=in.readInt(),axis=in.readInt(),spacing=in.readInt();
   int[] words=new int[length];for(int i=0;i<length;i++)words[i]=in.readInt();
   crossHorizontal=true;
   for(int q=1;q<terrain;q++)if(group(words[q*12+6])<group(words[(q-1)*12+6])){crossHorizontal=false;break;}
   int[] counts=new int[5],first=new int[5];for(int p=6;p<terrain*12;p+=12)counts[group(words[p])]++;
   int cursor=0;for(int g=0;g<5;g++){first[g]=counts[g]==0?0:cursor;cursor+=counts[g];}
   for(int g=0;g<5;g++)for(int q=first[g];q<first[g]+counts[g];q++)if(group(words[q*12+6])!=g)throw new AssertionError("groups "+id);
   var a=PredictionScanMerge.merge(words,first,counts,axis,spacing,true,16);
   var b=PredictionScanMerge32.merge(words,first,counts,axis,spacing,true,16);
   var c=PredictionScanMergeAdjacent.merge(a.words(),a.first(),a.count(),axis,spacing,true,16);
   int an=Arrays.stream(a.count()).sum(),bn=Arrays.stream(b.count()).sum(),cn=Arrays.stream(c.count()).sum();
   var originalArea=areas(words,terrain);
   if(!originalArea.equals(areas(a.words(),an))||!originalArea.equals(areas(b.words(),bn))||!originalArea.equals(areas(c.words(),cn)))throw new AssertionError("material/light/plane/signed area "+id);
   var tail=Arrays.copyOfRange(words,terrain*12,length);
   if(!Arrays.equals(tail,Arrays.copyOfRange(a.words(),an*12,a.words().length))||!Arrays.equals(tail,Arrays.copyOfRange(b.words(),bn*12,b.words().length))||!Arrays.equals(tail,Arrays.copyOfRange(c.words(),cn*12,c.words().length)))throw new AssertionError("water "+id);
   // arrange mutates, so keep the A/B input arrays independent.
   int[] aw=a.words().clone(),bw=b.words().clone(),cw=c.words().clone();
   boolean[] plants=VssLodSpriteTable.plants.clone();
   var ar=PredictionSpatialOrder.arrange(aw,a.first(),a.count(),plants);
   var br=PredictionSpatialOrder.arrange(bw,b.first(),b.count(),plants);
   var cr=PredictionSpatialOrder.arrange(cw,c.first(),c.count(),plants);
   var ad=PredictionDisplayGeometry.build(aw,a.first(),a.count(),ar,axis);
   var bd=PredictionDisplayGeometry32.build(bw,b.first(),b.count(),br,axis);
   var cd=PredictionDisplayGeometryAdjacent.build(cw,c.first(),c.count(),cr,axis);
   int[] at=tiers(ad.ranges(),an),bt=tiers(bd.ranges(),bn),ct=tiers(cd.ranges(),cn);
   out.println("{\"id\":"+id+",\"before\":"+terrain+",\"cap16\":"+an+",\"cap32\":"+bn
       +",\"adjacent\":"+cn+",\"tiers16\":"+Arrays.toString(at)+",\"tiers32\":"+Arrays.toString(bt)+",\"tiersAdjacent\":"+Arrays.toString(ct)
       +",\"extraQuads16\":"+(ad.words().length-aw.length)/12+",\"extraQuads32\":"+(bd.words().length-bw.length)/12
       +",\"extraQuadsAdjacent\":"+(cd.words().length-cw.length)/12
       +",\"hasDisplay16\":"+(ad.ranges()!=null)+",\"hasDisplay32\":"+(bd.ranges()!=null)+",\"hasDisplayAdjacent\":"+(cd.ranges()!=null)
       +",\"attempted16\":"+Arrays.toString(PredictionDisplayGeometry.attempted)
       +",\"extrasBefore16\":"+Arrays.toString(PredictionDisplayGeometry.extrasBefore)+"}");out.flush();
  }
 }
}
'''


def decode(record, capture):
    raw = (capture / f"mesh-{record['id']}.bin").read_bytes()
    if record['codec'] == 'zstd':
        raw = zstd.decompress(raw)
    assert len(raw) == record['rawBytes']
    stored = np.frombuffer(raw, dtype='<u4')
    full = record['wordCount'] // 12
    base = record['paletteBaseTexel']
    if base < 0:
        raise ValueError('Expected historical 48/32-byte capture')
    if not base:
        return stored.reshape(-1, 12)
    compact = stored[:full * 8].reshape(-1, 8)
    palette = stored[base * 4:].reshape(-1, 4)
    words = np.zeros((full, 12), dtype=np.uint32)
    words[:, :7] = compact[:, :7]
    words[:, 8] = compact[:, 7] & 65535
    words[:, [7, 9, 10, 11]] = palette[compact[:, 7] >> 16]
    return words


def aggregate(rows):
    keys = ['before', 'cap16', 'cap32', 'adjacent', 'extraQuads16', 'extraQuads32', 'extraQuadsAdjacent']
    result = {k: sum(r[k] for r in rows) for k in keys}
    result.update(meshes=len(rows), cap32ImprovedMeshes=sum(r['cap32'] < r['cap16'] for r in rows),
                  cap32WorseMeshes=sum(r['cap32'] > r['cap16'] for r in rows),
                  adjacentImprovedMeshes=sum(r['adjacent'] < r['cap16'] for r in rows),
                  adjacentWorseMeshes=sum(r['adjacent'] > r['cap16'] for r in rows),
                  hasDisplay16=sum(r['hasDisplay16'] for r in rows), hasDisplay32=sum(r['hasDisplay32'] for r in rows),
                  hasDisplayAdjacent=sum(r['hasDisplayAdjacent'] for r in rows))
    for cap in [16, 32, 'Adjacent']:
        result[f'tiers{cap}'] = [sum(r[f'tiers{cap}'][i] for r in rows) for i in range(4)]
    return result


def instrument_display(source):
    """Record the pre-admission counts in a temporary class; no algorithm changes."""
    source = source.replace('final class PredictionDisplayGeometry {',
                            'final class PredictionDisplayGeometry {\n'
                            ' static final int[] attempted = new int[4], extrasBefore = new int[4];')
    entry = 'static Result build(int[] source, int[] first, int[] count, int[][] retained, int axis) {'
    assert source.count(entry) == 1
    source = source.replace(entry, entry + '\n Arrays.fill(attempted, -1); Arrays.fill(extrasBefore, 0);')
    gate = 'if (total - n < 64 || n > total * .65 || extras + n > total / 2) continue;'
    assert source.count(gate) == 1
    return source.replace(gate, 'attempted[tier] = n; extrasBefore[tier] = extras; ' + gate)


# Independent experiment inspired by DH's sort/adjacency strategy. It does not
# reproduce DH's column downsampling, vertical reduction, lighting or LOD tree.
ADJACENT_BUILD = r'''
    private static final class Rect {
        int u0,u1,v0,v1;
        Rect(int a,int b,int c,int d){u0=a;u1=b;v0=c;v1=d;}
    }
    private static int build(int[] source, Plane plane, int latticeCap, int budget) {
        if(plane.size>budget)return 0;
        int n=normal(plane.key.flags),u=(n+1)%3,v=(n+2)%3;
        ArrayList<Rect> rectangles=new ArrayList<>();
        for(int i=0;i<plane.size;i++) {
            int p=plane.offsets[i],a=coordinate(source,p,0,u),b=coordinate(source,p,2,u);
            int c=coordinate(source,p,0,v),d=coordinate(source,p,2,v);
            rectangles.add(new Rect(Math.min(a,b),Math.max(a,b),Math.min(c,d),Math.max(c,d)));
        }
        for(int pass=0;pass<2;pass++) {
            final boolean horizontal=pass==0;
            rectangles.sort(java.util.Comparator.comparingInt((Rect r)->horizontal?r.v0:r.u0)
                    .thenComparingInt(r->horizontal?r.v1:r.u1).thenComparingInt(r->horizontal?r.u0:r.v0));
            ArrayList<Rect> merged=new ArrayList<>();
            for(Rect r:rectangles) {
                Rect last=merged.isEmpty()?null:merged.get(merged.size()-1);
                if(last!=null && (horizontal
                        ? last.v0==r.v0&&last.v1==r.v1&&last.u1==r.u0&&r.u1-last.u0<=2048*16
                        : last.u0==r.u0&&last.u1==r.u1&&last.v1==r.v0&&r.v1-last.v0<=2048*16)) {
                    if(horizontal)last.u1=r.u1;else last.v1=r.v1;
                } else merged.add(r);
            }
            rectangles=merged;
        }
        if(rectangles.size()>=plane.size)return plane.size;
        int[] output=new int[rectangles.size()*STRIDE];int at=0;
        for(Rect r:rectangles) {
            System.arraycopy(source,plane.offsets[0],output,at,STRIDE);
            output[at+9]=plane.key.color|plane.key.tags1;
            for(int corner=0;corner<4;corner++) {
                int layout=plane.key.layout>>>(corner*2);
                setCoordinate(output,at,corner,u,(layout&1)==0?r.u0:r.u1);
                setCoordinate(output,at,corner,v,(layout&2)==0?r.v0:r.v1);
            }
            at+=STRIDE;
        }
        plane.replacement=output;return plane.size;
    }
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Java/jdk-21'))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    args.output.mkdir(parents=True, exist_ok=True)
    source_dir = args.output / 'replay-src'; source_dir.mkdir(exist_ok=True)
    classes = args.output / 'replay-classes'; classes.mkdir(exist_ok=True)
    production = repo / 'src/main/java/dev/xantha/vss/client/prediction'
    source_hashes = {}
    for name in ['PredictionScanMerge', 'PredictionScanMesher2D', 'PredictionDisplayGeometry',
                 'PredictionSpatialOrder', 'PredictionDrawRanges', 'PredictionVegetationRanges']:
        source = (production / f'{name}.java').read_bytes()
        source_hashes[name] = hashlib.sha256(source).hexdigest()
        (source_dir / f'{name}.java').write_bytes(source)
        if name == 'PredictionScanMerge':
            candidate = source.decode('utf-8').replace('PredictionScanMerge', 'PredictionScanMerge32')
            assert candidate.count('Math.min(16, MAX_SPAN /') == 2
            candidate = candidate.replace('Math.min(16, MAX_SPAN /', 'Math.min(32, MAX_SPAN /')
            (source_dir / 'PredictionScanMerge32.java').write_text(candidate, encoding='utf-8')
            original = source.decode('utf-8')
            start = original.index('    private static int build(')
            end = original.index('    static int normal(', start)
            adjacent = (original[:start] + ADJACENT_BUILD + '\n' + original[end:]).replace(
                'PredictionScanMerge', 'PredictionScanMergeAdjacent')
            (source_dir / 'PredictionScanMergeAdjacent.java').write_text(adjacent, encoding='utf-8')
        if name == 'PredictionDisplayGeometry':
            candidate = source.decode('utf-8').replace('PredictionDisplayGeometry', 'PredictionDisplayGeometry32')
            candidate = candidate.replace('PredictionScanMerge.', 'PredictionScanMerge32.')
            (source_dir / 'PredictionDisplayGeometry32.java').write_text(candidate, encoding='utf-8')
            adjacent = source.decode('utf-8').replace('PredictionDisplayGeometry', 'PredictionDisplayGeometryAdjacent')
            merge = 'var merged = PredictionScanMerge.merge(variant, starts, counts, axis, 0, false, spacing);'
            assert adjacent.count(merge) == 1
            adjacent = adjacent.replace(merge, merge + '\n'
                'merged = PredictionScanMergeAdjacentAdapter.merge(merged, axis, spacing);')
            (source_dir / 'PredictionDisplayGeometryAdjacent.java').write_text(adjacent, encoding='utf-8')
            (source_dir / f'{name}.java').write_text(instrument_display(source.decode('utf-8')), encoding='utf-8')
    (source_dir / 'MergeLimitReplay.java').write_text(HARNESS, encoding='utf-8')
    (source_dir / 'PredictionScanMergeAdjacentAdapter.java').write_text('''package dev.xantha.vss.client.prediction;
final class PredictionScanMergeAdjacentAdapter {
 static PredictionScanMerge.Result merge(PredictionScanMerge.Result base,int axis,int spacing) {
  var result=PredictionScanMergeAdjacent.merge(base.words(),base.first(),base.count(),axis,0,false,spacing);
  return new PredictionScanMerge.Result(result.words(),result.first(),result.count());
 }
}
''', encoding='utf-8')
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '--release', '17', '-d', str(classes),
                    *map(str, source_dir.glob('*.java'))], check=True)
    manifest = args.capture / 'geometry.json'
    data = json.loads(manifest.read_text(encoding='utf-8'))
    draws = {d['meshId']: d for d in data['draws'] if d['pass'] == 'opaquePass' and not d.get('seam')}
    records = [r for r in data['meshes'] if r['id'] in draws]
    materials = {m['sprite']: m for m in data['materials']}
    aquatic = {'seagrass', 'tall_seagrass_bottom', 'tall_seagrass_top', 'kelp', 'kelp_plant'}
    land = {'short_grass', 'grass', 'tall_grass_bottom', 'tall_grass_top', 'fern', 'large_fern_bottom', 'large_fern_top'}
    stderr = args.output / 'stderr.txt'
    rows, census = [], Counter()
    start = time.monotonic()
    with stderr.open('wb') as errors:
        process = subprocess.Popen([str(args.jdk / 'bin/java.exe'), '-Xmx3G', '-cp', str(classes),
                                    'dev.xantha.vss.client.prediction.MergeLimitReplay'],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors)
        try:
            for i in range(256):
                m = materials.get(i, {})
                name = (m.get('name') or '').removeprefix('minecraft:block/')
                approx = bool(m.get('leafClass')) or name in {'snow', 'snow_block'}
                process.stdin.write(struct.pack('>4?', approx, name in aquatic, name in land, name in aquatic | land))
            for index, record in enumerate(records):
                words = decode(record, args.capture)[:record['sourceWordCount'] // 12].copy()
                opaque = words[:record['terrainQuadCount']]
                flags = opaque[:, 6]
                features = (opaque[:, 10] & (1 << 24)) != 0
                census['opaqueQuads'] += len(opaque)
                census['featureQuads'] += int(features.sum())
                census['baseTerrainQuads'] += int((~features).sum())
                census['modelUvQuads'] += int(np.count_nonzero(flags & (1 << 21)))
                census['cutoutQuads'] += int(np.count_nonzero(flags & (1 << 26)))
                census['unshadedQuads'] += int(np.count_nonzero(flags & (1 << 27)))
                census['nonUniformCornerColorLight'] += int(np.count_nonzero(
                    (opaque[:, 7] != (opaque[:, 9] & 0xf0ffffff)) | (opaque[:, 7] != (opaque[:, 10] & 0xf0ffffff))
                    | (opaque[:, 7] != opaque[:, 11])))
                draw = draws[record['id']]
                axis = draw['span'] // draw['spacing']
                process.stdin.write(struct.pack('>6i', record['id'], words.size, record['terrainQuadCount'],
                                                record['waterQuadCount'], axis, draw['spacing']))
                process.stdin.write(words.astype('>u4').tobytes()); process.stdin.flush()
                line = process.stdout.readline()
                if not line:
                    raise RuntimeError(stderr.read_text(encoding='utf-8', errors='replace'))
                row = json.loads(line)
                row.update(spacing=draw['spacing'], axis=axis, hasMorph=record['hasMorph'])
                row['featureBefore'] = int(features.sum())
                row['baseTerrainBefore'] = int((~features).sum())
                row['gate16'] = ('tooLarge' if row['cap16'] > 131072 else 'tooSmall' if row['cap16'] < 128
                                 else 'hasDisplay' if row['hasDisplay16'] else 'noAcceptedVariant')
                rows.append(row)
                if (index + 1) % 250 == 0:
                    print(f'Replayed {index + 1}/{len(records)} ordinary meshes, {time.monotonic() - start:.1f}s', flush=True)
            process.stdin.close()
            assert process.wait(timeout=30) == 0
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
    result = dict(capture=str(args.capture), epochMs=data['epochMs'], captureManifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
                  sourceSha256=source_hashes, runtimeFPSMeasured=False, cameraMissing=True,
                  scope='Unique ordinary opaque source meshes, followed by uniform display tiers. No current-frame culling/selection replay.',
                  adapter='Historical leafClass and vanilla snow/plant sprite names; full block registry/tag snapshot unavailable.',
                  candidates=['Scan rectangle caps 16 -> 32 raster cells; page size, keys, ownership and 256-block cap unchanged.',
                              'Production scan followed by an independent exact-attribute, two-axis sort/adjacency pass up to 2048 blocks, crossing scan pages. No DH column/downsampling code.'],
                  verifiedMaterialLightPlaneSignedArea=True, verifiedWaterExact=True,
                  summary=aggregate(rows), sourceCensus=dict(census),
                  spacingBuckets={str(s): aggregate([r for r in rows if r['spacing'] == s]) for s in sorted({r['spacing'] for r in rows})},
                  displayGates={g: aggregate([r for r in rows if r['gate16'] == g]) for g in sorted({r['gate16'] for r in rows})},
                  rows=rows, elapsedSeconds=time.monotonic() - start)
    for tier in [1, 2, 3]:
        rejection = Counter()
        for row in rows:
            n = row['attempted16'][tier]
            total = row['cap16']
            if n < 0:
                rejection['earlySizeGate'] += 1
            elif total - n < 64:
                rejection['savedLessThan64'] += 1
            elif n > total * .65:
                rejection['reducedLessThan35Percent'] += 1
            elif row['extrasBefore16'][tier] + n > total // 2:
                rejection['extraResidencyBudget'] += 1
            else:
                rejection['accepted'] += 1
        result.setdefault('tierAdmission', {})[str(tier)] = dict(rejection)
    rejected = [r for r in rows if r['gate16'] == 'noAcceptedVariant']
    result['rejectedBestAttempt'] = dict(
        source=sum(r['cap16'] for r in rejected),
        attempted=sum(min(r['attempted16'][1:]) for r in rejected),
        featureBefore=sum(r['featureBefore'] for r in rejected),
        baseTerrainBefore=sum(r['baseTerrainBefore'] for r in rejected),
        onlyResidencyRejectedMeshes=sum(any(r['cap16'] - n >= 64 and n <= r['cap16'] * .65
                                           for n in r['attempted16'][1:]) for r in rejected))
    (args.output / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: result[k] for k in ['summary', 'sourceCensus', 'displayGates', 'tierAdmission',
                                           'rejectedBestAttempt', 'elapsedSeconds']}, indent=2), flush=True)


if __name__ == '__main__':
    main()
