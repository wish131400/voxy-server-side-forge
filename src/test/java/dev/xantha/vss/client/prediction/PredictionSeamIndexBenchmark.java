package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Captured tile coordinates, reconstructed conservative heights; not a game FPS replay. */
class PredictionSeamIndexBenchmark {
    @Test void compareFullCandidateScanWithProductionPatchIndex() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var rows = Files.readAllLines(Path.of(System.getProperty("vss.followupFixture")));
        var boxes = new LinkedHashMap<PredictionTileKey,AABB>();
        for (String row : rows.subList(1,rows.size())) {
            var fields = row.split(","); if (Integer.parseInt(fields[5]) == 0) continue;
            int x=Integer.parseInt(fields[0]), z=Integer.parseInt(fields[1]), lod=Integer.parseInt(fields[2]);
            double span=64<<lod;
            boxes.put(new PredictionTileKey(Level.OVERWORLD,x,z,lod),
                    new AABB(x*span-2,-64,z*span-2,(x+1)*span+2,512,(z+1)*span+2));
        }
        var index = new PredictionSeamIndex<PredictionTileKey>();
        var old = new PredictionVisiblePlan<PredictionTileKey,AABB>(box->box);
        var next = new PredictionVisiblePlan<PredictionTileKey,AABB>(box->box);
        boxes.forEach((key,box)->{ index.put(key,key,box); old.put(key,box); next.put(key,box); });
        var all = Set.copyOf(boxes.keySet()); var candidates = new HashSet<PredictionTileKey>();
        var keys = List.copyOf(boxes.keySet());
        var projection = new Matrix4f().perspective((float)Math.toRadians(70),1.6F,.1F,20000);
        var csv = new ArrayList<String>(); csv.add("round,heading,mode,cpu_ns,candidates,visited,update_ns");
        long[][] times = new long[2][121]; long[] updates = new long[121];
        for (int round=-80;round<121;round++) {
            double heading=Math.toRadians(round*1.7);
            var view = new Matrix4f().rotateY((float)heading);
            var camera = new Vec3(642+round*.125,93,245);
            var frustum = new Frustum(view,projection);frustum.prepare(camera.x,camera.y,camera.z);
            var key=keys.get(Math.floorMod(round,keys.size()));var box=boxes.get(key).inflate((round&1)==0?.125:-.125);
            old.put(key,box);next.put(key,box);
            long updateStart=System.nanoTime();index.put(key,key,box);long updateNs=System.nanoTime()-updateStart;
            List<AABB> a=null,b=null;
            for (int order=0;order<2;order++) {
                int mode=Math.floorMod(round+order,2);long visits=(mode==0?old:next).visited();
                long start=System.nanoTime();
                if(mode==0) a=old.selectStable(camera,view,projection,frustum,all);
                else { candidates.clear();index.intersectFrustum(frustum,candidates::add);
                    b=next.selectStable(camera,view,projection,frustum,candidates); }
                long ns=System.nanoTime()-start;
                if(round>=0){times[mode][round]=ns;updates[round]=updateNs;
                    csv.add(round+","+heading+","+mode+","+ns+","+(mode==0?all.size():candidates.size())+","+
                            ((mode==0?old:next).visited()-visits)+","+(mode==0?0:updateNs));}
            }
            assertEquals(a,b,"indexed/full exact visible members and stable order differ");
        }
        long before=median(times[0]),after=median(times[1]),update=median(updates);
        System.out.printf(Locale.ROOT,"FOLLOWUP_SEAMS entries=%d CPU_ms=%.6f->%.6f localUpdate_ms=%.6f exactVisits=%d->%d%n",
                boxes.size(),before/1e6,after/1e6,update/1e6,old.visited(),next.visited());
        Path output=Path.of(System.getProperty("vss.followupResults"));Files.createDirectories(output);
        Files.write(output.resolve("seam-candidates.csv"),csv);
        assertTrue(next.visited()<old.visited(),"same visible membership must use fewer exact tests");
    }
    private static long median(long[] values){var copy=values.clone();Arrays.sort(copy);return copy[copy.length/2];}
}
