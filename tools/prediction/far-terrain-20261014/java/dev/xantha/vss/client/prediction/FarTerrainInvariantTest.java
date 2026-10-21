package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FarTerrainInvariantTest {
    @Test void historicalSourceWaterExistingVariantsAndAllSelectionMasksStayValid() throws Exception {
        var fixtures=new FarTerrainFixture(); int newlyAvailable=0;
        for(var mesh:fixtures.meshes) {
            var old=fixtures.before(mesh); var next=fixtures.after(mesh);
            assertArrayEquals(mesh.words(),Arrays.copyOf(next.words(),mesh.words().length));
            assertTrue(next.words().length<=mesh.words().length+Arrays.stream(mesh.count()).sum()/2*12);
            if(old.ranges()!=null) {
                assertArrayEquals(old.words(),next.words(),"existing mesh "+mesh.id());
                assertArrayEquals(old.errors(),next.errors());
            } else if(next.ranges()!=null) newlyAvailable++;
            for(int distance:new int[]{300,512,768,1024,1536,2048,3072,4096,8192,16384,65536})
                for(double pixels:new double[]{450,900,1475,4000,12000})
                    for(double error:new double[]{1,1.5,2,3}) {
                        var a=new PredictionDisplayLod();var b=new PredictionDisplayLod();
                        a.update(old.errors(),distance,pixels,512,false,error);b.update(next.errors(),distance,pixels,512,false,error);
                        for(int mask=1;mask<32;mask++) {
                            var ar=FarTerrainFixture.range(mesh,old.ranges(),a.tier(),mask);
                            var br=FarTerrainFixture.range(mesh,next.ranges(),b.tier(),mask);
                            assertTrue(br.quads<=ar.quads,"selection regression "+mesh.id());
                            for(int i=0;i<br.first.length;i++) {
                                assertTrue(br.first[i]>=0 && br.first[i]+br.count[i]<=next.words().length/12);
                                if(b.tier()>0)assertTrue(br.first[i]>=mesh.words().length/12,"far range must address the appended opaque copy");
                            }
                        }
                    }
        }
        assertTrue(newlyAvailable>=4,"the captured problem must exercise the fallback");
    }

    @Test void workerConstructionCostAndMemoryAreReportedSeparatelyFromRendering() throws Exception {
        var fixtures=new FarTerrainFixture(); var rows=new ArrayList<String>();
        rows.add("mesh,round,mode,build_ns,allocated_bytes,source_quads,extra_quads");
        var alloc=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long thread=Thread.currentThread().getId();
        for(var mesh:fixtures.meshes) {
            for(int round=-8;round<25;round++)for(int order=0;order<2;order++) {
                int mode=Math.floorMod(round+order,2);long bytes=alloc.getThreadAllocatedBytes(thread),start=System.nanoTime();
                int n;
                if(mode==0){var r=fixtures.before(mesh);n=r.words().length;}else{var r=fixtures.after(mesh);n=r.words().length;}
                long elapsed=System.nanoTime()-start,used=alloc.getThreadAllocatedBytes(thread)-bytes;
                if(round>=0)rows.add(mesh.id()+","+round+","+mode+","+elapsed+","+used+","+Arrays.stream(mesh.count()).sum()+","+(n-mesh.words().length)/12);
            }
        }
        CompletionGpuFixture.save("far-terrain-worker.csv",rows);
    }
}
