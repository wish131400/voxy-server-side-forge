"""A/B display allocation on captured canonical meshes; no live FPS claim."""
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
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--candidate', type=Path)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
args.output.mkdir(parents=True, exist_ok=True)
source = args.output / 'display-src'; source.mkdir(exist_ok=True)
classes = args.output / 'display-classes'; classes.mkdir(exist_ok=True)
for name in ['PredictionDisplayGeometry', 'PredictionDisplayLod', 'PredictionAquaticLod', 'PredictionScanMerge',
             'PredictionScanMesher2D', 'PredictionSpatialOrder', 'PredictionDrawRanges', 'PredictionVegetationRanges']:
    (source / (name + '.java')).write_bytes((repo / 'src/main/java/dev/xantha/vss/client/prediction' / (name + '.java')).read_bytes())
if args.candidate:
    (source / 'PredictionDisplayGeometry.java').write_bytes(args.candidate.read_bytes())
(source / 'ArchitectureLegacyDisplayGeometry.java').write_text(
    args.baseline.read_text(encoding='utf-8-sig').replace('PredictionDisplayGeometry', 'ArchitectureLegacyDisplayGeometry'), encoding='utf-8')
adapter = (repo / 'tools/prediction/replay-voxy-scan.py').read_text(encoding='utf-8')
adapter = adapter.split("write_text(r'''", 1)[1].split('public class ScanReplay', 1)[0]
adapter = adapter.replace('static boolean[] landPlantSprites(){return new boolean[256];}',
    'static boolean[] landPlantSprites(){return land.clone();} static boolean[] vegetationSprites(){boolean[] v=leaf.clone();for(int i=0;i<v.length;i++)v[i]|=plants[i]||land[i];return v;}')
adapter = adapter.replace('static boolean[] approx=new boolean[256], plants=new boolean[256], leaf=new boolean[256];',
    'static boolean[] approx=new boolean[256], plants=new boolean[256], leaf=new boolean[256], land=new boolean[256];')
(source / 'DisplayReplay.java').write_text(adapter + r'''
public class DisplayReplay {
 static boolean crossHorizontal;
 static final double[] DEPTHS={512,768,1024,1536,2048,3072,4096,6144,8192};
 static int group(int flags) {
  if(crossHorizontal&&(flags&1<<27)!=0&&(flags&1<<21)==0)return 0;
  int axis=flags>>>24&3;boolean pos=(flags&1<<30)!=0;
  return axis==1?(pos?4:3):axis==2?(pos?2:1):0;
 }
 static int[] tiers(PredictionDrawRanges[][] ranges,int count){
  int[] result={count,count,count,count};
  if(ranges!=null)for(int tier=1;tier<=3;tier++)if(ranges[tier][31]!=null)result[tier]=ranges[tier][31].quads;
  return result;
 }
 public static void main(String[] args)throws Exception {
  var in=new java.io.DataInputStream(new java.io.BufferedInputStream(System.in,1048576));
  var out=new java.io.PrintWriter(System.out);
  for(int i=0;i<256;i++){
   VssLodSpriteTable.leaf[i]=in.readBoolean();VssLodSpriteTable.approx[i]=in.readBoolean();
   VssLodSpriteTable.plants[i]=in.readBoolean();VssLodSpriteTable.land[i]=in.readBoolean();
  }
  while(true){
   int id;try{id=in.readInt();}catch(java.io.EOFException eof){break;}
   int n=in.readInt(),terrain=in.readInt(),axis=in.readInt(),spacing=in.readInt();boolean scoped=in.readBoolean();
   int[] words=new int[n];for(int i=0;i<n;i++)words[i]=in.readInt();
   crossHorizontal=true;for(int q=1;q<terrain;q++)if(group(words[q*12+6])<group(words[(q-1)*12+6])){crossHorizontal=false;break;}
   int[] count=new int[5],first=new int[5];
   for(int q=0;q<terrain;q++)count[group(words[q*12+6])]++;
   int at=0;for(int g=0;g<5;g++){first[g]=count[g]==0?0:at;at+=count[g];}
   for(int g=0;g<5;g++)for(int q=first[g];q<first[g]+count[g];q++)if(group(words[q*12+6])!=g)throw new AssertionError("face groups "+id);
   var merged=PredictionScanMerge.merge(words,first,count,axis,spacing,true,16);
   int total=java.util.Arrays.stream(merged.count()).sum();
   boolean[] allPlants=VssLodSpriteTable.plants.clone();for(int i=0;i<allPlants.length;i++)allPlants[i]|=VssLodSpriteTable.land[i];
   int[][] retained=PredictionSpatialOrder.arrange(merged.words(),merged.first(),merged.count(),allPlants);
   long t=System.nanoTime();
   var old=ArchitectureLegacyDisplayGeometry.build(merged.words(),merged.first(),merged.count(),retained,axis);
   long before=System.nanoTime()-t;t=System.nanoTime();
   int[] candidate=merged.words().clone();
   var plants=PredictionVegetationRanges.arrange(candidate,merged.first(),merged.count(),
       VssLodSpriteTable.vegetationSprites(),VssLodSpriteTable.aquaticSprites());
   var next=PredictionDisplayGeometry.build(candidate,merged.first(),merged.count(),retained,axis);
   long after=System.nanoTime()-t;
   if(!java.util.Arrays.equals(java.util.Arrays.copyOf(old.words(),merged.words().length),merged.words())
     ||!java.util.Arrays.equals(java.util.Arrays.copyOf(next.words(),candidate.length),candidate))throw new AssertionError("source "+id);
   if(!java.util.Arrays.equals(old.errors(),next.errors()))throw new AssertionError("plant-independent terrain error "+id);
   if(next.words().length>merged.words().length+total/2*12)throw new AssertionError("residency "+id);
   int[] oldT=tiers(old.ranges(),total),newT=tiers(next.ranges(),total);
   // Match the previous water/land-plant distance selector in both modes.
   int[] oldS=new int[DEPTHS.length],fixedS=new int[DEPTHS.length],newS=new int[DEPTHS.length];double[] depths=DEPTHS;
   for(int depth=0;depth<depths.length;depth++){
    var aquatic=new PredictionAquaticLod();aquatic.update(depths[depth],738,scoped);
    var plantLod=new PredictionDisplayLod();plantLod.update(PredictionVegetationRanges.ERRORS,depths[depth],1475,512,scoped);
    for(int mode=0;mode<3;mode++){
     float[] errors=mode==0?old.errors():next.errors();var lod=new PredictionDisplayLod();
     lod.update(errors,depths[depth],1475,512,scoped,mode==2?PredictionDisplayLod.terrainPixelError("normal"):1.5);
     PredictionDrawRanges range=null;
     if(lod.tier()>0){if(mode==0&&old.ranges()!=null)range=old.range(lod.tier(),aquatic.tier(),31);
                      if(mode>0&&next.ranges()!=null)range=next.range(lod.tier(),aquatic.tier(),31,
                          scoped?0:Math.max(aquatic.tier(),plantLod.tier()));}
     if(range==null)range=mode==0?new PredictionDrawRanges(merged.first(),retained[aquatic.tier()],31)
         :plants.range(31,scoped?0:Math.max(aquatic.tier(),plantLod.tier()),aquatic.tier());
     if(mode==0)oldS[depth]=range.quads;else if(mode==1)fixedS[depth]=range.quads;else newS[depth]=range.quads;
    }
   }
   out.println("{\"id\":"+id+",\"source\":"+total+",\"oldTiers\":"+java.util.Arrays.toString(oldT)
    +",\"newTiers\":"+java.util.Arrays.toString(newT)+",\"oldSelected\":"+java.util.Arrays.toString(oldS)
    +",\"newFixedSelected\":"+java.util.Arrays.toString(fixedS)+",\"newSelected\":"+java.util.Arrays.toString(newS)+",\"beforeBytes\":"+old.words().length*4L
    +",\"afterBytes\":"+next.words().length*4L+",\"beforeNs\":"+before+",\"afterNs\":"+after+"}");out.flush();
  }
 }
}
''', encoding='utf-8')
jdk = Path('C:/Program Files/Java/jdk-21/bin')
subprocess.run([str(jdk / 'javac.exe'), '--release', '17', '-d', str(classes), *map(str, source.glob('*.java'))], check=True)
data = json.loads((args.capture / 'geometry.json').read_text(encoding='utf-8'))
process = subprocess.Popen([str(jdk / 'java.exe'), '-Xmx2G', '-cp', str(classes), 'dev.xantha.vss.client.prediction.DisplayReplay'],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=(args.output / 'stderr.txt').open('wb'))
materials = {m['sprite']: m for m in data['materials']}
for i in range(256):
    m = materials.get(i, {})
    leaf = bool(m.get('leafClass'))
    name = m.get('name') or ''
    aquatic = name in {'minecraft:block/seagrass','minecraft:block/tall_seagrass_bottom','minecraft:block/tall_seagrass_top','minecraft:block/kelp','minecraft:block/kelp_plant'}
    land = name in {'minecraft:block/short_grass','minecraft:block/grass','minecraft:block/tall_grass_bottom','minecraft:block/tall_grass_top','minecraft:block/fern','minecraft:block/large_fern_bottom','minecraft:block/large_fern_top'}
    process.stdin.write(struct.pack('>4?', leaf, leaf or name == 'minecraft:block/snow', aquatic, land))
draws = {d['meshId']: d for d in data['draws'] if not d.get('seam')}
start = time.monotonic(); rows = []
try:
    for record in data['meshes']:
        draw = draws.get(record['id'])
        if not draw: continue
        raw = (args.capture / f"mesh-{record['id']}.bin").read_bytes()
        if record['codec'] == 'zstd': raw = zstd.decompress(raw)
        storage = np.frombuffer(raw, dtype='<u4'); full = record['wordCount'] // 12
        if record['paletteBaseTexel']:
            compact = storage[:full*8].reshape(-1, 8); palette = storage[record['paletteBaseTexel']*4:].reshape(-1, 4)
            canonical = np.zeros((full, 12), dtype=np.uint32)
            canonical[:, :7] = compact[:, :7]; canonical[:, 8] = compact[:, 7] & 65535
            canonical[:, [7, 9, 10, 11]] = palette[compact[:, 7] >> 16]
        else: canonical = storage.reshape(-1, 12).copy()
        words = canonical.ravel()[:record['sourceWordCount']]
        process.stdin.write(struct.pack('>5i?', record['id'], len(words), record['terrainQuadCount'], draw['span']//draw['spacing'],
                                       draw['spacing'], record.get('hasMorph', False)))
        process.stdin.write(words.astype('>u4').tobytes()); process.stdin.flush()
        line = process.stdout.readline()
        if not line: raise RuntimeError((args.output / 'stderr.txt').read_text(encoding='utf-8', errors='replace'))
        row = json.loads(line); rows.append(row)
        if len(rows) % 250 == 0: print(f'Replayed {len(rows)} meshes, {time.monotonic()-start:.1f}s', flush=True)
    process.stdin.close(); assert process.wait() == 0
finally:
    if process.poll() is None: process.kill()
result = dict(capture=str(args.capture), liveFPSMeasured=False, depths=[768,1024,2048,4096,8192],
              projection='1475 pixels per world unit / eye depth, fullDistance=512, aquaticDistance=738; all meshes placed at each hypothetical eye depth, complete ownership; morph stays exact. Historical capture camera is unavailable.',
              source=sum(r['source'] for r in rows), meshes=len(rows), rows=rows)
result['depths'] = [512,768,1024,1536,2048,3072,4096,6144,8192]
result['modes'] = ['baseline 1.5 px', 'independent plants, unchanged 1.5 px terrain', 'independent plants, normal 2 px terrain']
result['materialLimits'] = 'Captured registry tags and shared-row mappings are unavailable; only known vanilla plant sprite names and captured leafClass are classified. New tagged modded vegetation benefits are not estimated.'
for key in ['beforeBytes', 'afterBytes', 'beforeNs', 'afterNs']:
    result[key] = sum(r[key] for r in rows)
for key in ['oldTiers', 'newTiers', 'oldSelected', 'newFixedSelected', 'newSelected']:
    result[key] = [sum(r[key][i] for r in rows) for i in range(len(rows[0][key]))]
(args.output / 'display-results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='rows'}, indent=2), flush=True)
