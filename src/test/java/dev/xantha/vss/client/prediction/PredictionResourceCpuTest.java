package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PredictionResourceCpuTest {
    private static volatile long consumed;
    @Test void offscreenUploadsReusePassButVisiblePayloadChangesRebuild() throws Exception {
        PredictionMeshCodecTest.bootstrap();var draws=new ArrayList<PredictionRenderer.Draw>();
        Field gpuPacked=field(PredictionGpuTile.class,"packed");
        for(int i=0;i<3072;i++) {
            var mesh=PredictionMeshCodecTest.fixture();var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,i%64,i/64,0);
            var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,
                    new PredictionDepthBound(60,80),0,i+1,mesh.cellAxis(),1);
            var gpu=new PredictionGpuTile(key);gpuPacked.set(gpu,mesh.gpuPayload());
            boolean[] allowed=new boolean[mesh.cellCount()];Arrays.fill(allowed,true);
            draws.add(new PredictionRenderer.Draw(tile,gpu,allowed,false,VssLodFaceGroup.ALL,0,
                    new AABB(tile.baseBlockX(),60,tile.baseBlockZ(),tile.baseBlockX()+tile.spanBlocks(),80,tile.baseBlockZ()+tile.spanBlocks())));
        }
        Class<?> type=Arrays.stream(PredictionRenderer.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("PreparedPass")).findFirst().orElseThrow();
        Constructor<?> constructor=type.getDeclaredConstructor(boolean.class);constructor.setAccessible(true);
        Object pass=constructor.newInstance(false);Method prepare=type.getDeclaredMethod("prepare",List.class,long.class,Vec3.class);prepare.setAccessible(true);
        Field source=field(type,"source"),revision=field(type,"revision");Vec3 camera=new Vec3(0,100,0);
        prepare.invoke(pass,draws,1L,camera);long before=revision.getLong(pass);
        prepare.invoke(pass,draws,2L,camera);assertEquals(before,revision.getLong(pass),"offscreen residency changes must preserve pass contents");
        var replacement=PredictionMeshCodecTest.fixture(32).gpuPayload();gpuPacked.set(draws.get(0).gpu(),replacement);
        prepare.invoke(pass,draws,3L,camera);assertTrue(revision.getLong(pass)>before,"a replaced visible payload must invalidate the cached pass");
        Class<?> oldType=Arrays.stream(LegacyPassFixture.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("PreparedPass")).findFirst().orElseThrow();
        Constructor<?> oldConstructor=oldType.getDeclaredConstructor(boolean.class);oldConstructor.setAccessible(true);Object oldPass=oldConstructor.newInstance(false);
        Method oldPrepare=oldType.getDeclaredMethod("prepare",List.class,long.class,Vec3.class);oldPrepare.setAccessible(true);oldPrepare.invoke(oldPass,draws,3L,camera);
        var rows=new ArrayList<String>();rows.add("round,mode,tiles,elapsed_ns");long[][] times=new long[2][41];long epoch=4;
        for(int round=-20;round<41;round++)for(int order=0;order<2;order++) {
            int mode=Math.floorMod(round+order,2);
            long start=System.nanoTime();
            if(mode==0)oldPrepare.invoke(oldPass,draws,epoch++,camera);else prepare.invoke(pass,draws,epoch++,camera);
            long elapsed=System.nanoTime()-start;
            consumed=revision.getLong(pass);
            if(round>=0){times[mode][round]=elapsed;rows.add(round+","+mode+",3072,"+elapsed);}
        }
        for(var a:times)Arrays.sort(a);write("pass-preparation.csv",rows);
        System.out.printf(Locale.ROOT,"PASS_PREP tiles=3072 global_revision_changes median_ms=%.6f->%.6f rounds=41%n",times[0][20]/1e6,times[1][20]/1e6);
    }
    @Test void revisedOffscreenDetailWaitsForVisibilityOrRequiredCoverage() {
        var now=new AtomicLong();var cache=new PredictionGpuResidency<String>(1,now::get);
        cache.uploaded("tile",1,40);cache.observe("tile",40,false,false,true,false);now.set(20_000_000_000L);
        cache.evicted(cache.retirements(40).get(0));
        assertFalse(cache.allowsUpload("tile",2,false,false));assertFalse(cache.allowsUpload("tile",3,false,false));
        assertTrue(cache.allowsUpload("tile",3,false,false,true),"missing fallback, near terrain and seams must restore without waiting for frustum entry");
        cache.uploaded("tile",3,40);cache.observe("tile",40,false,false,true,false);now.addAndGet(20_000_000_000L);
        cache.evicted(cache.retirements(40).get(0));assertTrue(cache.allowsUpload("tile",4,true,false));
    }
    @Test void measureOffscreenRefinementUploadChurnWithOriginalPolicy() throws Exception {
        var now=new AtomicLong();long mib=PredictionGpuResidency.MIB,size=4*mib;
        var old=new LegacyGpuResidency<Integer>(128*mib,now::get);var candidate=new PredictionGpuResidency<Integer>(128*mib,now::get);
        for(int i=0;i<128;i++){old.uploaded(i,1,size);candidate.uploaded(i,1,size);}
        int[] uploads=new int[2];var rows=new ArrayList<String>();rows.add("round,old_uploads,candidate_uploads,old_resident_bytes,candidate_resident_bytes");
        for(int round=0;round<64;round++) {
            now.addAndGet(11_000_000_000L);
            for(int i=0;i<128;i++){old.observe(i,size,false,false,true,false);candidate.observe(i,size,false,false,true,false);}
            old.retirements(old.residentBytes()).forEach(old::evicted);candidate.retirements(candidate.residentBytes()).forEach(candidate::evicted);
            for(int i=0;i<128;i++) {
                if(old.allowsUpload(i,round+2,false,false)){uploads[0]++;old.uploaded(i,round+2,size);}
                if(candidate.allowsUpload(i,round+2,false,false)){uploads[1]++;candidate.uploaded(i,round+2,size);}
            }
            rows.add(round+","+uploads[0]+","+uploads[1]+","+old.residentBytes()+","+candidate.residentBytes());
        }
        assertTrue(uploads[1]<uploads[0]);assertTrue(candidate.residentBytes()<old.residentBytes());write("cold-residency.csv",rows);
        System.out.printf(Locale.ROOT,"COLD_RESIDENCY synthetic_refinements=8192 uploads=%d->%d uploaded_mib=%d->%d final_resident_mib=%d->%d%n",uploads[0],uploads[1],uploads[0]*4,uploads[1]*4,old.residentBytes()/mib,candidate.residentBytes()/mib);
    }
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void write(String file,List<String> rows)throws Exception{Path directory=Path.of(System.getProperty("vss.resourceResults", "build/resource-experiment-results"));Files.createDirectories(directory);Files.write(directory.resolve(file),rows);}
}
