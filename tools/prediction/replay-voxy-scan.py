"""Replay captured canonical meshes through the production scan merger.

Uses a small material-table adapter instead of launching Minecraft. All meshing,
display selection metadata and GPU encoding algorithms are production sources.
This measures geometry/preparation/storage, not live client FPS.
"""
from pathlib import Path
from compression import zstd
import argparse
import json
import struct
import subprocess
import time
import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument('--capture', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Java/jdk-21'))
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
args.output.mkdir(parents=True, exist_ok=True)
data = json.loads((args.capture / 'geometry.json').read_text(encoding='utf-8'))
source_dir = args.output / 'replay-src'; source_dir.mkdir(exist_ok=True)
classes = args.output / 'replay-classes'; classes.mkdir(exist_ok=True)
files = ['PredictionScanMesher2D', 'PredictionScanMerge', 'PredictionDisplayGeometry', 'PredictionSpatialOrder',
         'PredictionDrawRanges', 'PredictionGpuEncoding']
for file in files:
    (source_dir / f'{file}.java').write_bytes((repo / f'src/main/java/dev/xantha/vss/client/prediction/{file}.java').read_bytes())
(source_dir / 'ScanReplay.java').write_text(r'''package dev.xantha.vss.client.prediction;
import java.io.*; import java.util.*;
final class PredictionPackedMesh {
 static final int STRIDE_INTS=12, FLAG_UNSHADED=1<<27, FLAG_MODEL_UV=1<<21,
 FLAG_LOD_TEXTURE_SCALE=1<<31, FLAGS_FLUID_SHIFT=22, FLAG_CUTOUT=1<<26,
 FEATURE=1<<24, DISPLAY_APPROXIMATE=1<<25, PREVIEW=1<<26, FLAGS_AXIS_SHIFT=24,
 FLAG_FINE_COORDINATES=1<<20, FLAG_FACE_POSITIVE=1<<30, XZ_SHIFT_BITS=16;
}
final class PredictionMeshCompression { static final int MAX_BYTES=64*1024*1024; }
final class VssLodSpriteTable {
 static boolean[] approx=new boolean[256], plants=new boolean[256], leaf=new boolean[256];
 static boolean[] displayApproximationSprites(){return approx.clone();}
 static boolean[] aquaticSprites(){return plants.clone();}
 static boolean[] landPlantSprites(){return new boolean[256];}
}
public class ScanReplay {
 static boolean crossHorizontal;
 static int group(int flags) {
  if(crossHorizontal && (flags & 1<<27)!=0 && (flags & 1<<21)==0)return 0; // Source builder; feature-preserving repacks instead use the encoded axis.
  int axis=flags>>>24&3; boolean pos=(flags & 1<<30)!=0;
  return axis==1?(pos?4:3):axis==2?(pos?2:1):0;
 }
 static long bytes(int[] words,int terrain,int water) {
  var compact=PredictionGpuEncoding.encode(words); long result=0; int total=words.length/12;
  for(boolean wet:new boolean[]{false,true}) {
   var pass=PredictionGpuEncoding.selectPass(compact.words(),compact.paletteBaseTexel(),total,terrain,water,wet,true);
   result+=PredictionGpuEncoding.rectangles(pass,wet?water:total-water).words().length*4L;
  }
  return result;
 }
 static int[] tiers(PredictionDisplayGeometry.Result result,int count) {
  int[] totals={count,count,count,count};
  if(result.ranges()!=null) for(int tier=1;tier<=3;tier++)
   if(result.ranges()[tier][31]!=null) totals[tier]=result.ranges()[tier][31].quads;
  return totals;
 }
 static long leaf(int[] words,int count) {
  long n=0; for(int p=0;p<count*12;p+=12) {int sprite=words[p+6]&65535;if(sprite<256&&VssLodSpriteTable.leaf[sprite])n++;} return n;
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
 public static void main(String[] args)throws Exception {
  var in=new DataInputStream(new BufferedInputStream(System.in,1048576)); var out=new PrintWriter(System.out);
  for(int i=0;i<256;i++) {
   VssLodSpriteTable.leaf[i]=in.readBoolean(); VssLodSpriteTable.approx[i]=in.readBoolean(); VssLodSpriteTable.plants[i]=in.readBoolean();
  }
  while(true) {
   int id;try{id=in.readInt();}catch(EOFException e){break;}
   int length=in.readInt(),terrain=in.readInt(),water=in.readInt(),axis=in.readInt(),spacing=in.readInt();
   boolean display=in.readBoolean();
   int[] words=new int[length];for(int i=0;i<length;i++)words[i]=in.readInt();
   crossHorizontal=true;
   for(int q=1;q<terrain;q++)if(group(words[q*12+6])<group(words[(q-1)*12+6])){crossHorizontal=false;break;}
   int[] counts=new int[5],first=new int[5];for(int p=6;p<terrain*12;p+=12)counts[group(words[p])]++;
   int cursor=0;for(int g=0;g<5;g++){first[g]=counts[g]==0?0:cursor;cursor+=counts[g];}
   // Captured records already have face-group order; verify the inferred ranges.
   for(int g=0;g<5;g++)for(int q=first[g];q<first[g]+counts[g];q++)if(group(words[q*12+6])!=g)throw new AssertionError("groups "+id);
   System.setProperty("vss.disableVoxyScanMerge","true"); long t=System.nanoTime();
   int[] old=words.clone();var retained=PredictionSpatialOrder.arrange(old,first,counts,VssLodSpriteTable.plants);
   var oldDisplay=display?PredictionDisplayGeometry.build(old,first,counts,retained,axis):new PredictionDisplayGeometry.Result(old,null);
   long beforeNanos=System.nanoTime()-t;
   System.clearProperty("vss.disableVoxyScanMerge"); t=System.nanoTime();
   var merged=PredictionScanMerge.merge(words,first,counts,axis,spacing,true,16);
   int current=Arrays.stream(merged.count()).sum();
   var newRetained=PredictionSpatialOrder.arrange(merged.words(),merged.first(),merged.count(),VssLodSpriteTable.plants);
   var newDisplay=display?PredictionDisplayGeometry.build(merged.words(),merged.first(),merged.count(),newRetained,axis):new PredictionDisplayGeometry.Result(merged.words(),null);
   long afterNanos=System.nanoTime()-t;
   if(!areas(words,terrain).equals(areas(merged.words(),current)))throw new AssertionError("material/light/plane/signed area "+id);
   if(!Arrays.equals(Arrays.copyOfRange(words,terrain*12,length),Arrays.copyOfRange(merged.words(),current*12,merged.words().length)))throw new AssertionError("water "+id);
   long oldBytes=bytes(oldDisplay.words(),terrain,water),newBytes=bytes(newDisplay.words(),current,water);
   out.println("{\"id\":"+id+",\"before\":"+terrain+",\"after\":"+current+",\"leafBefore\":"+leaf(words,terrain)
       +",\"leafAfter\":"+leaf(merged.words(),current)+",\"oldTiers\":"+Arrays.toString(tiers(oldDisplay,terrain))
       +",\"newTiers\":"+Arrays.toString(tiers(newDisplay,current))+",\"beforeGpuBytes\":"+oldBytes+",\"afterGpuBytes\":"+newBytes
       +",\"beforeNanos\":"+beforeNanos+",\"afterNanos\":"+afterNanos+"}");out.flush();
  }
 }
}
''', encoding='utf-8')
subprocess.run([str(args.jdk / 'bin/javac.exe'), '--release', '17', '-d', str(classes),
                *map(str, source_dir.glob('*.java'))], check=True)
process = subprocess.Popen([str(args.jdk / 'bin/java.exe'), '-Xmx2G', '-cp', str(classes),
                            'dev.xantha.vss.client.prediction.ScanReplay'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=(args.output / 'replay-stderr.txt').open('wb'))
materials = {m['sprite']: m for m in data['materials']}
leaf_ids = {m['sprite'] for m in data['materials'] if m.get('leafClass')}
for i in range(256):
    material = materials.get(i, {})
    leaf = i in leaf_ids
    snow = material.get('name') == 'minecraft:block/snow'
    plants = material.get('name') in {'minecraft:block/seagrass', 'minecraft:block/tall_seagrass_bottom',
        'minecraft:block/tall_seagrass_top', 'minecraft:block/kelp', 'minecraft:block/kelp_plant',
        'minecraft:block/short_grass', 'minecraft:block/grass', 'minecraft:block/tall_grass_bottom',
        'minecraft:block/tall_grass_top', 'minecraft:block/fern', 'minecraft:block/large_fern_bottom', 'minecraft:block/large_fern_top'}
    process.stdin.write(struct.pack('>3?', leaf, leaf or snow, plants))
draws = {d['meshId']: d for d in data['draws'] if not d.get('seam')}
start = time.monotonic(); rows = []
try:
    for index, record in enumerate(data['meshes']):
        raw = (args.capture / f"mesh-{record['id']}.bin").read_bytes()
        if record['codec'] == 'zstd': raw = zstd.decompress(raw)
        storage = np.frombuffer(raw, dtype='<u4')
        full = record['wordCount'] // 12
        if record['paletteBaseTexel']:
            compact = storage[:full * 8].reshape(-1, 8)
            palette = storage[record['paletteBaseTexel'] * 4:].reshape(-1, 4)
            canonical = np.zeros((full, 12), dtype=np.uint32)
            canonical[:, :7] = compact[:, :7]; canonical[:, 8] = compact[:, 7] & 65535
            canonical[:, [7, 9, 10, 11]] = palette[compact[:, 7] >> 16]
        else: canonical = storage.reshape(-1, 12).copy()
        words = canonical.ravel()[:record['sourceWordCount']]
        draw = draws.get(record['id'])
        axis = draw['span'] // draw['spacing'] if draw else 64
        spacing = draw['spacing'] if draw else 0
        process.stdin.write(struct.pack('>6i?', record['id'], len(words), record['terrainQuadCount'], record['waterQuadCount'],
                                        axis, spacing, bool(draw)))
        process.stdin.write(words.astype('>u4').tobytes()); process.stdin.flush()
        line = process.stdout.readline()
        if not line: raise RuntimeError((args.output / 'replay-stderr.txt').read_text(encoding='utf-8', errors='replace'))
        row = json.loads(line); row['spacing'] = spacing; row['hasMorph'] = record.get('hasMorph', False); row['ordinary'] = bool(draw)
        rows.append(row)
        if (index + 1) % 250 == 0: print(f"Replayed {index + 1}/{len(data['meshes'])}, {time.monotonic() - start:.1f}s", flush=True)
    process.stdin.close(); assert process.wait() == 0
finally:
    if process.poll() is None: process.kill()
keys = ['before', 'after', 'leafBefore', 'leafAfter', 'beforeGpuBytes', 'afterGpuBytes', 'beforeNanos', 'afterNanos']
def aggregate(records):
    result = {k: sum(row[k] for row in records) for k in keys}
    result['meshes'] = len(records)
    result['improvedMeshes'] = sum(row['after'] < row['before'] for row in records)
    result['oldTiers'] = [sum(row['oldTiers'][i] for row in records) for i in range(4)]
    result['newTiers'] = [sum(row['newTiers'][i] for row in records) for i in range(4)]
    return result
ordinary = [row for row in rows if row['ordinary']]
result = dict(capture=str(args.capture), epochMs=data['epochMs'], runtimeFPSMeasured=False,
              verifiedMaterialLightPlaneSignedArea=True, verifiedWaterExact=True,
              materialApproximationAdapter='Captured leafClass and vanilla snow; no registry tag snapshot available.',
              scope='Complete resident canonical meshes; draw ownership/visible selection is not replayed.',
              summary=aggregate(rows), ordinary=aggregate(ordinary), noMorph=aggregate([r for r in ordinary if not r['hasMorph']]),
              spacingBuckets={str(s): aggregate([r for r in ordinary if r['spacing'] == s]) for s in sorted({r['spacing'] for r in ordinary})},
              rows=rows, elapsedSeconds=time.monotonic() - start)
(args.output / 'replay-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: result[k] for k in ['summary', 'ordinary', 'noMorph', 'elapsedSeconds']}, indent=2), flush=True)
