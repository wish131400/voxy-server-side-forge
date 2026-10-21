package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionRefinementLatencyTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void missingCoverageComesFirstButCoveredNearDetailDoesNotWaitForFarCoarseUpgrades() {
        var layout = VssLodLayout.of(16384, 9, true, false);
        var fallback = PredictionSubCellCoverageTest.tile(0, 0, 6, 8, 512, 64, false);
        var near = PredictionSubCellCoverageTest.tile(2, 0, 0, 64, 1, 68, false);
        var far = PredictionSubCellCoverageTest.tile(6, 0, 3, 64, 8, 68, false);
        var missingParent = PredictionSubCellCoverageTest.tile(10, 0, 4, 8, 128, 64, false);
        var missingChild = PredictionSubCellCoverageTest.tile(160, 0, 0, 8, 8, 64, false);
        var tiles = new HashMap<PredictionTileKey, PredictionTile>();
        for (var tile : List.of(fallback, near, far, missingParent, missingChild)) tiles.put(tile.key(), tile);
        var residency = new PredictionRenderResidency();
        residency.retain(new PredictionTileManager.RenderSnapshot(net.minecraft.world.level.Level.OVERWORLD,
                layout, Map.copyOf(tiles), Map.of()));
        residency.uploaded(fallback);
        var selected = PredictionUploadSelection.select(List.of(far, near, missingChild, missingParent),
                tile -> true, PredictionUploadSelection.order(residency, layout, 0, 0, null), 4);
        assertEquals(List.of(missingParent, missingChild, near, far), selected);
    }

    @Test void visibleSchedulingMustAllowLocalCompletionBeforeTheWholeHorizonIsRefined() throws Exception {
        var profile = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 123L);
        var sample = PredictionSimpleVegetationTest.sample(64);
        var sampler = new ClientTerrainSampler(42L, profile) {
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample; }
        };
        try (var manager = new PredictionTileManager(profile.levelKey(), sampler,
                new PredictionMemoryBudget(1024L * PredictionMemoryBudget.MIB, 0,
                        () -> Long.MAX_VALUE, System::nanoTime, 1), null, PredictionCpuBudget.fixed(1))) {
            var near = new PredictionTileKey(profile.levelKey(), 4, 0, 0);
            var far = new PredictionTileKey(profile.levelKey(), 8, 0, 2);
            var ready = keysToTiles(manager);
            ready.put(near, tile(near, 32, sample)); ready.put(far, tile(far, 8, sample));
            ((Set<PredictionTileKey>) get(manager, "desiredKeys")).addAll(List.of(near, far));
            ((Set<PredictionTileKey>) get(manager, "terrainLeaves")).addAll(List.of(near, far));
            set(manager, "surfaceDesired", Set.of(near));
            set(manager, "ordinaryTargets", Map.of(near, 64, far, 64));
            set(manager, "terrainTargets", Map.of(near, 64, far, 64));
            set(manager, "previewWorkPending", true);
            manager.setWorkView(PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9));
            set(manager, "viewBands", Map.of(near, PredictionWorkView.VISIBLE, far, PredictionWorkView.VISIBLE));
            set(manager, "schedulingPixels", 1000D);
            var turns = (AtomicInteger) get(manager, "mediumSinceSurface");
            turns.set(0);
            assertTrue(priority(manager, far, false) < priority(manager, near, false), "large exposed error initially wins");
            turns.set(16);
            assertTrue(priority(manager, near, false) < priority(manager, far, false),
                    "a completed local turn must not be bypassed by the generic visible-screen branch");
            var input = (PredictionSchedulePreparation.Input) invoke(manager, "schedulingInput", new Class<?>[]{long.class}, System.nanoTime());
            assertTrue(input.priority(near, false) < input.priority(far, false), "worker and live admission agree");
            set(manager, "previewWorkPending", false);
            assertTrue(priority(manager, near, true) < priority(manager, far, false), "final grids must also let ready surfaces finish");
        }
    }

    @Test void composingRetainedFeaturesOnceKeepsExactOutputAndMeasuresWorkerCost() throws Exception {
        var previous = forestTile(2);
        previous.mesh().prepareGpuPayload(previous); previous.mesh().surfaceReadyForRendering = true;
        var raw = forestTile(1);
        var quadSource = raw.mesh().packed();
        long[][] nanos = new long[2][9], allocation = new long[2][9];
        List<String> rows = new ArrayList<>(List.of("round,mode,nanos,allocated"));
        PredictionTile legacy = null, composed = null;
        for (int round=-4;round<9;round++) for (int order=0;order<2;order++) {
            int mode = Math.floorMod(round + order, 2);
            var mesh = PredictionMesh.fromQuads(quadSource, raw.mesh().vertexCount(), raw.mesh().waterVertexCount(), 1);
            var tile = new PredictionTile(raw.key(), raw.heights(), raw.groundHeights(), raw.samples(), mesh,
                    raw.depthBound(), 0, raw.revision(), raw.cellAxis(), raw.spacingBlocks());
            long id=Thread.currentThread().getId(), bytes=AlgorithmSupport.THREADS.getThreadAllocatedBytes(id), start=System.nanoTime();
            if (mode == 0) {
                mesh.prepareGpuPayload(tile); mesh.retainFeatures(previous, 1);
            } else mesh.prepareGpuPayload(tile, previous);
            mesh.gpuPayload().prepareGpuStorage(); mesh.gpuPayload().prepareStorage();
            long elapsed=System.nanoTime()-start; bytes=AlgorithmSupport.THREADS.getThreadAllocatedBytes(id)-bytes;
            if (round>=0) {
                nanos[mode][round]=elapsed; allocation[mode][round]=bytes;
                rows.add(round+","+mode+","+elapsed+","+bytes);
            }
            if (mode==0) legacy=tile; else composed=tile;
        }
        var a=legacy.mesh().gpuPayload();var b=composed.mesh().gpuPayload();
        assertArrayEquals(a.cacheWords(), b.cacheWords(), "ground, retained features, UVs, light and source-cell mapping stay exact");
        assertArrayEquals(a.quads(), b.quads(), "all derived display levels stay identical");
        assertTrue(composed.mesh().surfaceReadyForRendering); assertTrue(composed.mesh().featureFallback);
        for (int tier=0;tier<4;tier++) {
            var first=a.drawRanges(false,31,0,tier,0,0,false);var second=b.drawRanges(false,31,0,tier,0,0,false);
            assertArrayEquals(first.first,second.first); assertArrayEquals(first.count,second.count);
        }
        assertEquals(legacy.mesh().seamMesh().sameContent(composed.mesh().seamMesh()),true);
        System.out.printf(Locale.ROOT,"FEATURE_COMPOSE_AB CPU_ms=%.3f->%.3f allocation_MiB=%.3f->%.3f source_quads=%d%n",
                AlgorithmSupport.median(nanos[0])/1e6,AlgorithmSupport.median(nanos[1])/1e6,
                AlgorithmSupport.median(allocation[0])/1048576.,AlgorithmSupport.median(allocation[1])/1048576.,a.terrainQuadCount());
        AlgorithmSupport.save("feature-compose-worker.csv", rows);
    }

    private static PredictionTile forestTile(int step) {
        int axis=64/step, grid=axis+1;
        var samples=new ClientColumnSample[grid*grid];Arrays.fill(samples,PredictionSimpleVegetationTest.sample(64));
        var blocks=new HashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int z=5;z<60;z+=6) for (int x=5;x<60;x+=6) {
            for (int y=65;y<73;y++) blocks.put(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            for (int y=70;y<75;y++) for (int dz=-2;dz<=2;dz++) for (int dx=-2;dx<=2;dx++)
                if (Math.abs(dx)+Math.abs(dz)+Math.abs(y-72)<6)
                    blocks.put(new net.minecraft.core.BlockPos(x+dx,y,z+dz),net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
        }
        var vegetation=PredictionVegetation.Tile.of(blocks,0,0,64,step,1);
        var mesh=PredictionMeshBuilder.build(samples,null,63,0xff3f76e4,step,grid,null,null,0,0,vegetation).compactForRendering();
        int[] heights=new int[samples.length];Arrays.fill(heights,64);
        return new PredictionTile(new PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,0),
                heights,heights,samples,mesh,new PredictionDepthBound(64,75),0,step,axis,step);
    }

    @SuppressWarnings("unchecked") private static Map<PredictionTileKey, PredictionTile> keysToTiles(PredictionTileManager manager) throws Exception {
        return (Map<PredictionTileKey, PredictionTile>) get(manager, "ready");
    }
    private static PredictionTile tile(PredictionTileKey key, int axis, ClientColumnSample sample) {
        int[] heights = new int[(axis + 1) * (axis + 1)]; Arrays.fill(heights, 64);
        var samples = new ClientColumnSample[heights.length]; Arrays.fill(samples, sample);
        return new PredictionTile(key, heights, heights, samples, null, new PredictionDepthBound(64, 64),
                0, axis, axis, (64 << key.lod()) / axis);
    }
    private static int priority(PredictionTileManager manager, PredictionTileKey key, boolean surface) throws Exception {
        return (int) invoke(manager, "workPriority", new Class<?>[]{PredictionTileKey.class, boolean.class}, key, surface);
    }
    private static Object get(Object owner, String name) throws Exception { return PredictionPlanningTestSupport.get(owner, name); }
    private static void set(Object owner, String name, Object value) throws Exception { PredictionPlanningTestSupport.set(owner, name, value); }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args);
    }
}
