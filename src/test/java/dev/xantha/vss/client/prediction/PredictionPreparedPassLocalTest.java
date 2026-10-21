package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;

class PredictionPreparedPassLocalTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    static final class Pass {
        final Object value;
        final Class<?> type;
        final Method prepare, ranges, clear;
        Pass(boolean water) throws Exception {
            type = Class.forName(PredictionRenderer.class.getName()+"$PreparedPass");
            var constructor = type.getDeclaredConstructor(boolean.class); constructor.setAccessible(true);
            value = constructor.newInstance(water);
            prepare = method("prepare", List.class, long.class, Vec3.class);
            ranges = method("ranges", PredictionRenderer.Draw.class); clear = method("clear");
        }
        Method method(String name, Class<?>... args) throws Exception {
            var method = type.getDeclaredMethod(name,args); method.setAccessible(true); return method;
        }
        void prepare(List<PredictionRenderer.Draw> draws,long revision,Vec3 camera) throws Exception { prepare.invoke(value,draws,revision,camera); }
        PredictionDrawRanges ranges(PredictionRenderer.Draw draw) throws Exception { return (PredictionDrawRanges)ranges.invoke(value,draw); }
        long counter(String name) throws Exception { var field=type.getDeclaredField(name);field.setAccessible(true);return field.getLong(value); }
        @SuppressWarnings("unchecked") List<Object> entries() throws Exception { return (List<Object>)method("entries").invoke(value); }
    }
    static void payload(PredictionGpuTile gpu,PredictionPackedMesh mesh) throws Exception {
        var field=PredictionGpuTile.class.getDeclaredField("packed");field.setAccessible(true);field.set(gpu,mesh);
    }
    static PredictionRenderer.Draw draw(int x,int z,PredictionPackedMesh mesh) throws Exception {
        var tile=PredictionLodSeamsTest.tile(x,z,1,64);var gpu=new PredictionGpuTile(tile.key());payload(gpu,mesh);
        boolean[] cells=new boolean[4096];Arrays.fill(cells,true);
        return new PredictionRenderer.Draw(tile,gpu,cells,false,VssLodFaceGroup.ALL,0,
                new AABB(tile.baseBlockX(),0,tile.baseBlockZ(),tile.baseBlockX()+64,100,tile.baseBlockZ()+64));
    }
    static PredictionPackedMesh mesh() { return PredictionPackedMesh.terrainRecords(PredictionDisplayGeometryTest.grid(0),64); }
    @Test void replacementMemberChangesAndUnrelatedRevisionOnlyRebuildAffectedEntries() throws Exception {
        var pass=new Pass(false);var draws=new ArrayList<PredictionRenderer.Draw>();
        for(int i=0;i<8;i++)draws.add(draw(i,0,mesh()));
        var camera=new Vec3(32,80,32);pass.prepare(draws,1,camera);
        long built=pass.counter("rangeBuilds"),work=pass.counter("workloadRevision");
        pass.prepare(new ArrayList<>(draws),2,camera);
        assertEquals(built,pass.counter("rangeBuilds"));assertEquals(work,pass.counter("workloadRevision"));
        payload(draws.get(3).gpu(),mesh());pass.prepare(draws,3,camera);
        assertEquals(built+1,pass.counter("rangeBuilds"));assertEquals(work+1,pass.counter("workloadRevision"));
        var removed=draws.remove(0);pass.prepare(new ArrayList<>(draws),4,camera);
        assertNull(pass.ranges(removed));assertEquals(built+1,pass.counter("rangeBuilds"));
        payload(draws.get(0).gpu(),null);pass.prepare(draws,5,camera);assertNull(pass.ranges(draws.get(0)));
        pass.clear.invoke(pass.value);assertTrue(pass.entries().isEmpty());assertNull(pass.ranges(draws.get(1)));
    }

    @Test void residentCoverageChangesCannotDeleteGeometryOrInvalidateStaticCommands() throws Exception {
        var local = draw(0,0,PredictionAuthoritativeCoverageViewTest.mesh(0));
        var far = draw(100,0,mesh()); var pass=new Pass(false); var draws=List.of(local,far);
        var camera=new Vec3(4,68,4);
        pass.prepare(draws,1,camera); assertEquals(1,pass.ranges(local).quads);
        long built=pass.counter("rangeBuilds");
        for (int frame=0;frame<120;frame++) {
            dev.xantha.vss.compat.StrictLodVisibility.updateRenderWindow(Level.OVERWORLD,frame*16,0,32);
            pass.prepare(draws,1,camera);
            assertEquals(1,pass.ranges(local).quads);
            assertEquals(built,pass.counter("rangeBuilds"),"Voxy scheduling metadata is not a draw version");
        }
        local.gpu().packed().morph(new float[]{0},-64,1000);
        pass.prepare(draws,1,camera); assertEquals(1,pass.ranges(local).quads);
        assertEquals(built+1,pass.counter("rangeBuilds"));
        dev.xantha.vss.compat.StrictLodVisibility.reset();
    }

    @Test void waterMovesWithoutRebuildingRangesAndKeepsExactDistanceOrder() throws Exception {
        var draws=List.of(draw(0,0,PredictionMeshCodecTest.fixture().gpuPayload()),
                draw(10,0,PredictionMeshCodecTest.fixture().gpuPayload()));
        var pass=new Pass(true);pass.prepare(draws,1,Vec3.ZERO);long built=pass.counter("rangeBuilds");
        var method=pass.entries().get(0).getClass().getDeclaredMethod("draw");method.setAccessible(true);
        assertSame(draws.get(0),method.invoke(pass.entries().get(0)));
        pass.prepare(draws,1,new Vec3(1000,0,0));
        assertSame(draws.get(1),method.invoke(pass.entries().get(0)));
        assertEquals(built,pass.counter("rangeBuilds"));
    }

    @Test void distanceBandsRefreshWhenMovingWithoutRebuildingSubmissionRanges() throws Exception {
        var draw=draw(0,0,PredictionPackedMesh.seamRecords(PredictionDisplayGeometryTest.grid(0),64));
        var draws=List.of(draw);var pass=new Pass(false);
        pass.prepare(draws,1,Vec3.ZERO);
        var field=pass.type.getDeclaredField("distanceSource");field.setAccessible(true);
        assertArrayEquals(new long[]{1024,0,0,0},(long[])field.get(pass.value));
        long built=pass.counter("rangeBuilds"),work=pass.counter("workloadRevision");
        var refresh=pass.type.getDeclaredField("nextDistanceRefresh");refresh.setAccessible(true);refresh.setLong(pass.value,0);
        pass.prepare(draws,1,new Vec3(1500,0,0));
        assertArrayEquals(new long[]{0,0,0,1024},(long[])field.get(pass.value));
        assertEquals(built,pass.counter("rangeBuilds"));assertEquals(work,pass.counter("workloadRevision"));
    }

    @Test void changingDetailAtTheSameCameraUpdatesTerrainSelectionAndDiagnosticBands() throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        String previous = config.predictionDetail;
        int previousFullDistance = config.predictionSurfaceDistanceBlocks;
        var scale = PredictionRenderer.class.getDeclaredField("selectionScale"); scale.setAccessible(true);
        double previousScale = scale.getDouble(null);
        try {
            var packed = mesh();
            java.util.Arrays.fill(packed.displayErrors(), 2F); packed.displayErrors()[0] = Float.NaN;
            var draw = draw(0, 0, packed); var draws = List.of(draw); var pass = new Pass(false);
            scale.setDouble(null, 1475); config.predictionDetail = "high";
            var camera = new Vec3(2000, 80, 0); pass.prepare(draws, 1, camera);
            assertEquals(1024, pass.ranges(draw).quads);
            config.predictionDetail = "normal"; pass.prepare(draws, 1, camera);
            assertTrue(pass.ranges(draw).quads < 1024);
            var field = pass.type.getDeclaredField("distanceTerrain"); field.setAccessible(true);
            assertArrayEquals(new long[]{0, 0, 0, pass.ranges(draw).quads}, (long[])field.get(pass.value));
            config.predictionDetail = "extreme"; pass.prepare(draws, 1, camera);
            assertEquals(1024, pass.ranges(draw).quads);
            java.util.Arrays.fill(packed.displayErrors(), .1F); packed.displayErrors()[0] = Float.NaN;
            config.predictionDetail = "normal"; config.predictionSurfaceDistanceBlocks = 128;
            camera = new Vec3(600, 80, 0); pass.prepare(draws, 1, camera);
            assertTrue(pass.ranges(draw).quads < 1024);
            config.predictionSurfaceDistanceBlocks = 640; pass.prepare(draws, 1, camera);
            assertEquals(1024, pass.ranges(draw).quads,
                    "full-detail distance must invalidate selection even when the aquatic distance is unchanged");
        } finally {
            config.predictionDetail = previous; config.predictionSurfaceDistanceBlocks = previousFullDistance;
            scale.setDouble(null, previousScale);
        }
    }
}
