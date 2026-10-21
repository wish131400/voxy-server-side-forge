"""Compare current production display storage with the saved pre-change baseline."""
from pathlib import Path
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
 static final int STRIDE_INTS=12,FLAG_UNSHADED=1<<27,FLAG_MODEL_UV=1<<21,
 FLAG_LOD_TEXTURE_SCALE=1<<31,FLAGS_FLUID_SHIFT=22,FLAG_CUTOUT=1<<26,
 FEATURE=1<<24,DISPLAY_APPROXIMATE=1<<25,PREVIEW=1<<26,FLAGS_AXIS_SHIFT=24,
 FLAG_FINE_COORDINATES=1<<20,FLAG_FACE_POSITIVE=1<<30,XZ_SHIFT_BITS=16;
}
final class PredictionMeshCompression { static final int MAX_BYTES=64*1024*1024; }
final class VssLodSpriteTable {
 static boolean[] approx=new boolean[256],aquatic=new boolean[256],land=new boolean[256],plants=new boolean[256];
 static boolean[] displayApproximationSprites(){return approx.clone();}
 static boolean[] aquaticSprites(){return aquatic.clone();}
 static boolean[] landPlantSprites(){return land.clone();}
 static boolean[] vegetationSprites(){return plants.clone();}
}
public class BudgetReplay {
 static final com.sun.management.ThreadMXBean ALLOC=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
 static int group(int flags,boolean cross){
  if(cross&&(flags&1<<27)!=0&&(flags&1<<21)==0)return 0;
  int axis=flags>>>24&3;boolean pos=(flags&1<<30)!=0;
  return axis==1?(pos?4:3):axis==2?(pos?2:1):0;
 }
 static int count(PredictionDrawRanges[][] ranges,int[] source,int tier,int visible){
  if(tier>0&&ranges!=null&&ranges[tier][visible]!=null)return ranges[tier][visible].quads;
  int n=0;for(int g=0;g<source.length;g++)if((visible&1<<g)!=0)n+=source[g];return n;
 }
 static int[] tiers(PredictionDrawRanges[][] ranges,int[] source){
  int[] r=new int[4];for(int i=0;i<4;i++)r[i]=count(ranges,source,i,31);return r;
 }
 static int[] depths(float[] errors,PredictionDrawRanges[][] ranges,int[] source) {
  int[] depths={512,768,1024,1536,2048,3072,4096,8192,16384};int[] result=new int[depths.length];
  for(int i=0;i<depths.length;i++) {
   var lod=new PredictionDisplayLod();lod.update(errors,depths[i],1475,256,false,2);
   result[i]=count(ranges,source,lod.tier(),31);
  }return result;
 }
 static long bytes(int[] words,int terrain,int water) {
  var compact=PredictionGpuEncoding.encode(words);long result=0;int total=words.length/12;
  for(boolean wet:new boolean[]{false,true}) {
   var pass=PredictionGpuEncoding.selectPass(compact.words(),compact.paletteBaseTexel(),total,terrain,water,wet,true);
   result+=PredictionGpuEncoding.rectangles(pass,wet?water:total-water).words().length*4L;
  }return result;
 }
 public static void main(String[] args)throws Exception {
  var in=new DataInputStream(new BufferedInputStream(System.in,1048576));var out=new PrintWriter(System.out);
  for(int i=0;i<256;i++) {VssLodSpriteTable.approx[i]=in.readBoolean();VssLodSpriteTable.aquatic[i]=in.readBoolean();
   VssLodSpriteTable.land[i]=in.readBoolean();VssLodSpriteTable.plants[i]=in.readBoolean();}
  while(true){
   int id;try{id=in.readInt();}catch(EOFException e){break;}
   int length=in.readInt(),terrain=in.readInt(),water=in.readInt(),axis=in.readInt(),spacing=in.readInt();
   int[] words=new int[length];for(int i=0;i<length;i++)words[i]=in.readInt();
   boolean cross=true;for(int q=1;q<terrain;q++)if(group(words[q*12+6],true)<group(words[(q-1)*12+6],true)){cross=false;break;}
   int[] first=new int[5],count=new int[5];for(int q=0;q<terrain;q++)count[group(words[q*12+6],cross)]++;
   int at=0;for(int g=0;g<5;g++){first[g]=count[g]==0?0:at;at+=count[g];}
   for(int g=0;g<5;g++)for(int q=first[g];q<first[g]+count[g];q++)if(group(words[q*12+6],cross)!=g)throw new AssertionError("groups "+id);
   var merged=PredictionScanMerge.merge(words,first,count,axis,spacing,true,16);
   int total=Arrays.stream(merged.count()).sum();
   var retained=PredictionSpatialOrder.arrange(merged.words(),merged.first(),merged.count(),VssLodSpriteTable.plants);
   boolean[] aquatic=VssLodSpriteTable.aquaticSprites(),vegetation=VssLodSpriteTable.vegetationSprites();
   PredictionVegetationRanges.arrange(merged.words(),merged.first(),merged.count(),vegetation,aquatic);
   long threadId=Thread.currentThread().getId(),oldAllocationStart=ALLOC.getThreadAllocatedBytes(threadId);
   long start=System.nanoTime();
   var old=MergeBudgetLegacyDisplayGeometry.build(merged.words(),merged.first(),merged.count(),retained,axis);
   long oldNs=System.nanoTime()-start,oldAlloc=ALLOC.getThreadAllocatedBytes(threadId)-oldAllocationStart;
   long newAllocationStart=ALLOC.getThreadAllocatedBytes(threadId);start=System.nanoTime();
   var next=PredictionDisplayGeometry.build(merged.words(),merged.first(),merged.count(),retained,axis);
   long newNs=System.nanoTime()-start,newAlloc=ALLOC.getThreadAllocatedBytes(threadId)-newAllocationStart;
   if(!Arrays.equals(old.words(),next.words()) || !Arrays.equals(old.errors(),next.errors()))throw new AssertionError("exact result "+id);
   if(!Arrays.equals(merged.words(),Arrays.copyOf(old.words(),merged.words().length))
    ||!Arrays.equals(merged.words(),Arrays.copyOf(next.words(),merged.words().length)))throw new AssertionError("source "+id);
   if(next.words().length>merged.words().length+total/2*12)throw new AssertionError("residency "+id);
   int regressions=0;
   for(int depth:new int[]{300,512,768,1024,1536,2048,3072,4096,8192,16384,65536})
    for(double pixels:new double[]{500,1475,4000,12000})for(double error:new double[]{1,1.5,2,3}) {
     var a=new PredictionDisplayLod();var b=new PredictionDisplayLod();
     a.update(old.errors(),depth,pixels,256,false,error);b.update(next.errors(),depth,pixels,256,false,error);
     for(int visible=1;visible<32;visible++)if(count(next.ranges(),merged.count(),b.tier(),visible)>count(old.ranges(),merged.count(),a.tier(),visible))regressions++;
   }
   out.println("{\"id\":"+id+",\"source\":"+total+",\"oldTiers\":"+Arrays.toString(tiers(old.ranges(),merged.count()))
    +",\"newTiers\":"+Arrays.toString(tiers(next.ranges(),merged.count()))+",\"oldDepths\":"+Arrays.toString(depths(old.errors(),old.ranges(),merged.count()))
    +",\"newDepths\":"+Arrays.toString(depths(next.errors(),next.ranges(),merged.count()))+",\"oldExtra\":"+(old.words().length-merged.words().length)/12
    +",\"newExtra\":"+(next.words().length-merged.words().length)/12+",\"oldGpuBytes\":"+bytes(old.words(),total,water)
    +",\"newGpuBytes\":"+bytes(next.words(),total,water)+",\"oldNs\":"+oldNs+",\"newNs\":"+newNs
    +",\"oldAlloc\":"+oldAlloc+",\"newAlloc\":"+newAlloc
    +",\"regressions\":"+regressions+"}");out.flush();
  }
 }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--limit', type=int, default=0)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    args.output.mkdir(parents=True, exist_ok=True)
    src=args.output/'src';src.mkdir(exist_ok=True)
    classes=args.output/'classes';classes.mkdir(exist_ok=True)
    prod=repo/'src/main/java/dev/xantha/vss/client/prediction'
    names=['PredictionDisplayGeometry','PredictionDisplayLod','PredictionScanMerge',
           'PredictionScanMesher2D','PredictionSpatialOrder','PredictionDrawRanges','PredictionVegetationRanges','PredictionGpuEncoding']
    hashes={}
    for name in names:
        source=(prod/f'{name}.java').read_bytes();(src/f'{name}.java').write_bytes(source)
        hashes[name]=hashlib.sha256(source).hexdigest()
    legacy=repo/'tools/prediction/merge-budget-experiments/java/dev/xantha/vss/client/prediction/MergeBudgetLegacyDisplayGeometry.java'
    (src/legacy.name).write_bytes(legacy.read_bytes())
    (src/'BudgetReplay.java').write_text(HARNESS,encoding='utf-8')
    jdk=Path('C:/Program Files/Java/jdk-21/bin')
    subprocess.run([str(jdk/'javac.exe'),'--release','17','-d',str(classes),*map(str,src.glob('*.java'))],check=True)
    data=json.loads((args.capture/'geometry.json').read_text(encoding='utf-8'))
    draws={d['meshId']:d for d in data['draws'] if d['pass']=='opaquePass' and not d.get('seam')}
    records=[r for r in data['meshes'] if r['id'] in draws]
    if args.limit:records=records[:args.limit]
    materials={m['sprite']:m for m in data['materials']}
    aq={'seagrass','tall_seagrass_bottom','tall_seagrass_top','kelp','kelp_plant'}
    land={'short_grass','grass','tall_grass_bottom','tall_grass_top','fern','large_fern_bottom','large_fern_top'}
    rows=[];start=time.monotonic()
    stderr=args.output/'stderr.txt'
    with stderr.open('wb') as errors:
        process=subprocess.Popen([str(jdk/'java.exe'),'-Xmx3G','-cp',str(classes),'dev.xantha.vss.client.prediction.BudgetReplay'],
                                 stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=errors)
        try:
            for i in range(256):
                m=materials.get(i,{});name=(m.get('name') or '').removeprefix('minecraft:block/')
                process.stdin.write(struct.pack('>4?',bool(m.get('leafClass')) or name in {'snow','snow_block'},name in aq,name in land,name in aq|land))
            for index,record in enumerate(records):
                raw=(args.capture/f"mesh-{record['id']}.bin").read_bytes()
                if record['codec']=='zstd':raw=zstd.decompress(raw)
                stored=np.frombuffer(raw,dtype='<u4');full=record['wordCount']//12;base=record['paletteBaseTexel']
                if base:
                    compact=stored[:full*8].reshape(-1,8);palette=stored[base*4:].reshape(-1,4)
                    canonical=np.zeros((full,12),dtype=np.uint32);canonical[:,:7]=compact[:,:7];canonical[:,8]=compact[:,7]&65535
                    canonical[:,[7,9,10,11]]=palette[compact[:,7]>>16]
                else:canonical=stored.reshape(-1,12)
                words=canonical.ravel()[:record['sourceWordCount']];draw=draws[record['id']]
                process.stdin.write(struct.pack('>6i',record['id'],len(words),record['terrainQuadCount'],record['waterQuadCount'],draw['span']//draw['spacing'],draw['spacing']))
                process.stdin.write(words.astype('>u4').tobytes());process.stdin.flush()
                line=process.stdout.readline()
                if not line:raise RuntimeError(stderr.read_text(encoding='utf-8',errors='replace'))
                row=json.loads(line);row['spacing']=draw['spacing'];rows.append(row)
                if (index+1)%250==0:print(f'Replayed {index+1}/{len(records)}, {time.monotonic()-start:.1f}s',flush=True)
            process.stdin.close();assert process.wait(timeout=30)==0
        finally:
            if process.poll() is None:process.kill();process.wait()
    summary={k:sum(r[k] for r in rows) for k in ['source','oldExtra','newExtra','oldGpuBytes','newGpuBytes','oldNs','newNs','oldAlloc','newAlloc','regressions']}
    for k in ['oldTiers','newTiers','oldDepths','newDepths']:summary[k]=[sum(r[k][i] for r in rows) for i in range(len(rows[0][k]))]
    result=dict(capture=str(args.capture),cameraMissing=True,runtimeFPSMeasured=False,materialAdapter='Historical leafClass and vanilla snow/plant names; no full tag snapshot.',
                sourceSha256=hashes,baselineSha256=hashlib.sha256(legacy.read_bytes()).hexdigest(),depths=[512,768,1024,1536,2048,3072,4096,8192,16384],
                selectionCheck='Fresh selectors, 11 distances x 4 projection scales x 4 error settings x 31 face masks per mesh.',
                summary=summary,rows=rows,elapsedSeconds=time.monotonic()-start)
    (args.output/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,indent=2),flush=True)


if __name__=='__main__':main()
