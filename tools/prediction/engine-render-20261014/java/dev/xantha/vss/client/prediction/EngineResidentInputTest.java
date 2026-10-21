package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

class EngineResidentInputTest {
    private static volatile long sink;

    @Test void unchangedRecordsRemainExactAcrossReplacementMotionWithdrawalAndRelease() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var mesh = PredictionPreparedPassLocalTest.mesh();
        var resident = fixture(32, mesh);
        var old = new EngineBaselineSelectionInput(); var next = new EngineCachedSelectionInput();
        var camera = new Vec3(-10,80,-10);
        for (int frame = 0; frame < 90; frame++) {
            if (frame % 3 == 0) resident.set(frame % resident.size(), replacement(resident.get(frame % resident.size())));
            var view = frame < 30 ? camera : frame < 60 ? new Vec3(100000,80,100000) : camera;
            var snapshot = List.copyOf(resident); old.update(snapshot,view,4096); next.update(snapshot,view,4096);
            equivalent(old,next); assertEquals(resident.size(),next.retainedRecords());
        }
        var previous = next.candidates(); long builds = next.rebuiltRecords();
        next.update(List.copyOf(resident),camera,4096);
        assertSame(previous,next.candidates()); assertEquals(builds,next.rebuiltRecords());
        // A global epoch changing does not allow stale per-mesh metadata.
        mesh.morph(new float[]{0},0,0);
        var snapshot = List.copyOf(resident); old.update(snapshot,camera,4096); next.update(snapshot,camera,4096); equivalent(old,next);
        assertTrue(next.rebuiltRecords()>builds);
        PredictionPreparedPassLocalTest.payload(resident.get(0).gpu(),null);
        old.update(List.copyOf(resident),camera,4096); next.update(List.copyOf(resident),camera,4096); equivalent(old,next);
        resident.remove(0); old.update(List.copyOf(resident),camera,4096); next.update(List.copyOf(resident),camera,4096); equivalent(old,next);
        assertEquals(31,next.retainedRecords()); next.clear(); assertEquals(0,next.retainedRecords()); assertTrue(next.candidates().isEmpty());
    }

    @Test void measureRecordMaintenanceIncludingPublicationAndAllocation() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var rows = new ArrayList<String>(); rows.add("tiles,changed,round,mode,cpu_ns,allocated_bytes,rebuilt_records");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        for (int count : new int[]{512,4096,8192}) for (int changes : new int[]{0,1,32,count}) {
            var resident = fixture(count,PredictionPreparedPassLocalTest.mesh());
            var old = new EngineBaselineSelectionInput(); var next = new EngineCachedSelectionInput();
            var camera = new Vec3(3000,80,3000); var snapshot = List.copyOf(resident);
            old.update(snapshot,camera,16384); next.update(snapshot,camera,16384);
            long[][] times = new long[2][161], bytes = new long[2][161];
            for (int round = -80; round < 161; round++) {
                for (int c = 0; c < changes; c++) {
                    int index = Math.floorMod(round*37+c,count); resident.set(index,replacement(resident.get(index)));
                }
                // Both paths receive the same prepared scene snapshot; snapshot
                // production and world generation are outside this method benchmark.
                if (changes != 0) snapshot = List.copyOf(resident);
                for (int order = 0; order < 2; order++) {
                    int mode = Math.floorMod(round+order,2);
                    long before = bean.getThreadAllocatedBytes(thread), rebuilt = next.rebuiltRecords(), start = System.nanoTime();
                    if (mode == 0) { old.update(snapshot,camera,16384); sink = old.quads()+old.candidates().size(); }
                    else { next.update(snapshot,camera,16384); sink = next.quads()+next.candidates().size(); }
                    long time = System.nanoTime()-start, allocated = bean.getThreadAllocatedBytes(thread)-before;
                    if (round >= 0) {
                        times[mode][round] = time; bytes[mode][round] = allocated;
                        rows.add(count+","+changes+","+round+","+mode+","+time+","+allocated+","+(mode==0?changes==0?0:count:next.rebuiltRecords()-rebuilt));
                    }
                }
                if (round == 0 || round == 80 || round == 160) equivalent(old,next);
            }
            System.out.printf(Locale.ROOT,"ENGINE_INPUT tiles=%d changed=%d CPU_ms=%.6f->%.6f allocation=%d->%d%n",
                    count,changes,CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                    CompletionGpuFixture.median(bytes[0]),CompletionGpuFixture.median(bytes[1]));
            old.clear(); next.clear();
        }
        CompletionGpuFixture.save("engine-input.csv",rows);
    }

    @Test void measureInputAndProductionPreparedPassTogether() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var rows = new ArrayList<String>(); rows.add("tiles,changed,round,mode,cpu_ns,allocated_bytes,range_builds");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true); long thread = Thread.currentThread().getId();
        for (int count : new int[]{512,4096,8192}) for (int changes : new int[]{1,32,count}) {
            var resident = fixture(count,PredictionPreparedPassLocalTest.mesh());
            var old = new EngineBaselineSelectionInput(); var next = new EngineCachedSelectionInput();
            var passes = List.of(new PredictionPreparedPassLocalTest.Pass(false),new PredictionPreparedPassLocalTest.Pass(false));
            var camera = new Vec3(3000,80,3000);
            long[][] times = new long[2][81], bytes = new long[2][81];
            for (int round = -48; round < 81; round++) {
                for (int c = 0; c < changes; c++) {
                    int index = Math.floorMod(round*37+c,count); resident.set(index,replacement(resident.get(index)));
                }
                var snapshot = List.copyOf(resident);
                for (int order = 0; order < 2; order++) {
                    int mode = Math.floorMod(round+order,2); var pass = passes.get(mode);
                    long before = bean.getThreadAllocatedBytes(thread), builds = pass.counter("rangeBuilds"), start = System.nanoTime();
                    if (mode == 0) { old.update(snapshot,camera,16384); pass.prepare(old.candidates(),1,camera); }
                    else { next.update(snapshot,camera,16384); pass.prepare(next.candidates(),1,camera); }
                    sink = pass.counter("selectedCandidates");
                    long time = System.nanoTime()-start, allocated = bean.getThreadAllocatedBytes(thread)-before;
                    if (round >= 0) {
                        times[mode][round] = time; bytes[mode][round] = allocated;
                        rows.add(count+","+changes+","+round+","+mode+","+time+","+allocated+","+(pass.counter("rangeBuilds")-builds));
                    }
                }
                if (round == 0 || round == 40 || round == 80) {
                    equivalent(old,next);
                    assertEquals(passes.get(0).counter("selectedCandidates"),passes.get(1).counter("selectedCandidates"));
                    for (int i = 0; i < old.candidates().size(); i++) {
                        var a = passes.get(0).ranges(old.candidates().get(i)); var b = passes.get(1).ranges(next.candidates().get(i));
                        assertEquals(a == null,b == null); if (a != null) { assertArrayEquals(a.first,b.first); assertArrayEquals(a.count,b.count); }
                    }
                }
            }
            System.out.printf(Locale.ROOT,"ENGINE_PREPARE tiles=%d changed=%d CPU_ms=%.6f->%.6f allocation=%d->%d%n",
                    count,changes,CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                    CompletionGpuFixture.median(bytes[0]),CompletionGpuFixture.median(bytes[1]));
            passes.get(0).clear.invoke(passes.get(0).value); passes.get(1).clear.invoke(passes.get(1).value);
            old.clear(); next.clear();
        }
        CompletionGpuFixture.save("engine-prepare.csv",rows);
    }

    static void equivalent(EngineBaselineSelectionInput old, EngineCachedSelectionInput next) {
        assertEquals(old.quads(),next.quads()); assertEquals(old.terrainTiles(),next.terrainTiles());
        assertEquals(old.seamTiles(),next.seamTiles()); assertEquals(old.allBatched(),next.allBatched());
        assertEquals(old.candidates(),next.candidates());
    }
    static PredictionRenderer.Draw replacement(PredictionRenderer.Draw draw) {
        return new PredictionRenderer.Draw(draw.tile(),draw.gpu(),draw.allowed(),draw.features(),draw.seam(),
                draw.faces(),draw.morph(),draw.bounds(),draw.completeOwnership());
    }
    static ArrayList<PredictionRenderer.Draw> fixture(int count, PredictionPackedMesh packed) throws Exception {
        var result = new ArrayList<PredictionRenderer.Draw>();
        var mesh = PredictionMesh.restored(1024*6,0,packed,null);
        boolean[] allowed = new boolean[4096]; Arrays.fill(allowed,true);
        for (int i = 0; i < count; i++) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,i%128,i/128,0);
            var tile = new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    mesh,new PredictionDepthBound(64,96),0,1,64,1);
            var gpu = new PredictionGpuTile(key); PredictionPreparedPassLocalTest.payload(gpu,packed);
            result.add(new PredictionRenderer.Draw(tile,gpu,allowed,allowed,false,31,0,
                    new AABB(tile.baseBlockX(),64,tile.baseBlockZ(),tile.baseBlockX()+64,96,tile.baseBlockZ()+64),true));
        }
        return result;
    }
}
