package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

class BenefitLocalTerrainTest {
    static int[] fixture(int scenario) {
        int axis=128;int[] source=new int[axis*axis*12];
        for(int z=0;z<axis;z++)for(int x=0;x<axis;x++) {
            int q=z*axis+x,p=q*12;PredictionSpatialOrderTest.quad(source,q,x,z,80,PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE|1);
            int y=32768+320+((x+z)&1);if(scenario==1&&x<64&&z<64)y+=((x*7+z*3)%16);
            source[p+4]=source[p+5]=y|y<<16;
            for(int i:new int[]{7,9,10,11})source[p+i]=0x668844;
        }
        return source;
    }
    @Test void compareMeasuredErrorSelectionStorageAndWorkerCost() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var csv=new ArrayList<String>();csv.add("scenario,round,whole_ns,local_ns,whole_words,local_words");
        for(int scenario=0;scenario<2;scenario++) {
            int[] source=fixture(scenario);long[][] times=new long[2][21];
            PredictionDisplayGeometry.Result whole=null;BenefitLocalTerrain.Result local=null;
            for(int round=-5;round<21;round++)for(int slot=0;slot<2;slot++) {
                int mode=Math.floorMod(round+slot,2);long start=System.nanoTime();
                if(mode==0)whole=PredictionDisplayGeometry.build(source,new int[5],new int[]{16384,0,0,0,0},new int[4][5],128,new boolean[256]);
                else local=BenefitLocalTerrain.build(source,128);
                if(round>=0)times[mode][round]=System.nanoTime()-start;
            }
            for(int round=0;round<21;round++)csv.add(scenario+","+round+","+times[0][round]+","+times[1][round]+","+whole.words().length+","+local.words().length);
            assertEquals(16384,local.sourceQuads());assertTrue(local.words().length<=source.length*1.5);
            for(var patch:local.patches())for(int tier=1;tier<4;tier++)if(patch.ranges()[tier][31]!=null) {
                var ranges=patch.ranges()[tier][31];
                assertTrue(Float.isFinite(patch.errors()[tier]));
                // Exact source ring is the watertight join. Test all four internal edges.
                var originals=new HashSet<String>();
                for(int q=patch.start();q<patch.start()+patch.count();q++)if(onRing(local.words(),q*12,patch))originals.add(record(local.words(),q*12));
                var kept=new HashSet<String>();for(int i=0;i<ranges.first.length;i++)for(int q=ranges.first[i];q<ranges.first[i]+ranges.count[i];q++)
                    if(onRing(local.words(),q*12,patch))kept.add(record(local.words(),q*12));
                assertTrue(kept.containsAll(originals),"local LOD changed an exact joining ring; lost="+originals.stream().filter(s->!kept.contains(s)).limit(2).toList());
            }
            System.out.printf(Locale.ROOT,"BENEFIT_LOCAL_BUILD scenario=%d words=%d->%d worker_ms=%.6f->%.6f wholeErrors=%s localErrors=%s%n",scenario,
                    whole.words().length,local.words().length,CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                    Arrays.toString(whole.errors()),local.patches().stream().map(p->Arrays.toString(p.errors())).toList());
            for(int distance:new int[]{256,400,600,900,1400,2400,6000}) {
                var camera=new Vec3(64,130,-distance);var lod=new PredictionDisplayLod();lod.update(whole.errors(),distance,1475,256,false,2);
                int wholeCount=lod.tier()==0?16384:whole.range(lod.tier(),0,31,0).quads;
                var regional=BenefitLocalTerrain.select(local,camera,1475,2,false);
                assertEquals(16384,BenefitLocalTerrain.select(local,camera,1475,2,true).quads);
                System.out.printf("BENEFIT_LOCAL_SELECT scenario=%d distance=%d tier=%d quads=%d->%d commands=%d%n",scenario,distance,lod.tier(),wholeCount,regional.quads,regional.first.length);
            }
        }
        CompletionGpuFixture.save("benefit-local-build.csv",csv);
    }
    private static boolean onRing(int[] words,int p,BenefitLocalTerrain.Patch b) {
        int x0=words[p]&65535,x1=words[p+1]&65535,z0=words[p+2]&65535,z1=words[p+3]&65535;
        return x0<=b.x()||z0<=b.z()||x1>=b.x()+b.span()||z1>=b.z()+b.span();
    }
    private static String record(int[] words,int p) { return Arrays.toString(Arrays.copyOfRange(words,p,p+6)); }
}
