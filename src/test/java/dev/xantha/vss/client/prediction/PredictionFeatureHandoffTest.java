package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionFeatureHandoffTest {
    private static final VssLodLayout LAYOUT = VssLodLayout.of(4096, 6, true, false);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static PredictionTileManager.PredictionTile tile(int lod, int step, boolean plants, boolean complete, long revision) {
        int axis = (64 << lod) / step, grid = axis + 1;
        var samples = new ClientColumnSample[grid * grid];
        Arrays.fill(samples, new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(),
                0, 0, 0, 0, 0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        var blocks = plants ? Map.of(new BlockPos(8, 65, 8), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(8, 66, 8), Blocks.OAK_LEAVES.defaultBlockState(),
                new BlockPos(10, 65, 8), Blocks.GRASS.defaultBlockState()) : Map.<BlockPos, net.minecraft.world.level.block.state.BlockState>of();
        var vegetation = PredictionVegetation.Tile.of(blocks, 0, 0, 64 << lod, step, 1);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0xff3f76e4, step, grid,
                null, null, 0, 0, vegetation).compactForRendering();
        int[] heights = new int[samples.length]; Arrays.fill(heights, 64);
        var tile = new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, lod),
                heights, heights, samples, mesh, new PredictionDepthBound(64, 68), 0, revision, axis, step);
        mesh.prepareGpuPayload(tile);
        mesh.surfaceReadyForRendering = complete;
        return tile;
    }

    static PredictionTileManager.RenderSnapshot snapshot(PredictionTileManager.PredictionTile... tiles) {
        var values = new HashMap<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>();
        for (var tile : tiles) values.put(tile.key(), tile);
        return new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, LAYOUT, values, Map.of());
    }

    @Test void finerGroundCannotStealDecorationBeforeACompleteReplacementUploads() throws Exception {
        var parent = tile(1, 4, true, true, 1);
        var ground = tile(0, 1, false, false, 2);
        var state = new PredictionRenderResidency();
        var initial = snapshot(parent); state.retain(initial); state.uploaded(parent);
        var next = snapshot(parent, ground); state.retain(next); state.uploaded(ground);
        var frame = state.snapshot(next);
        assertSame(ground, frame.coveringTile(0, 0, 0));
        assertSame(parent, frame.coveringFeatureTileAtDetail(0, 0, 0));
        var coverage = coverage(parent, frame);
        assertFalse(coverage.allowed()[0], "parent ground must not overlap the finer ground");
        assertTrue(coverage.features()[0], "parent tree/grass must remain drawable");
        var completed = tile(0, 1, false, true, 3);
        var awaitingUpload = snapshot(parent, completed); state.retain(awaitingUpload);
        assertSame(parent, state.snapshot(awaitingUpload).coveringFeatureTileAtDetail(0, 0, 0));
        state.uploaded(completed);
        var replaced = state.snapshot(awaitingUpload);
        assertSame(completed, replaced.coveringFeatureTileAtDetail(0, 0, 0), "completed empty decoration clears old plants too");
        assertFalse(coverage(parent, replaced).features()[0]);
        assertSame(parent, frame.coveringFeatureTileAtDetail(0, 0, 0), "previous frame is immutable");
    }

    @Test void previewWithPartialPlantsCannotOverrideCompletedDecoration() {
        var parent = tile(1, 4, true, true, 1);
        var partial = tile(0, 1, true, false, 2);
        var frame = snapshot(parent, partial);
        var owners = new PredictionCoverageOwners(64);
        assertSame(partial, owners.coveringTileAtDetail(frame, 0, 0, 0));
        assertSame(parent, owners.coveringFeatureTileAtDetail(frame, 0, 0, 0));
        var completed = tile(0, 1, true, true, 3);
        var ready = snapshot(parent, completed);
        assertSame(completed, owners.coveringFeatureTileAtDetail(ready, 0, 0, 0));
    }

    @Test void ingestedColumnsRemoveGroundButLeaveAllFeatureFacesForDepthTesting() {
        var tile = tile(0, 2, true, true, 1);
        var mesh = tile.mesh().gpuPayload();
        var index = new PredictionExactCoverageIndex();
        for (int z = -10; z <= 10; z++) for (int x = -10; x <= 10; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        var mask = index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS);
        var submission = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        var input = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        int featureCount = 0; int[] words = mesh.cacheWords();
        for (int p = 0; p < words.length; p += 12) if ((words[p + 10] & PredictionPackedMesh.FEATURE) != 0) featureCount++;
        assertTrue(featureCount > 0);
        submission.update(mask, new Vec3(32, 80, 32), 4096);
        assertFalse(submission.fullyOwned(mesh, 0, 0));
        var filtered = submission.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL);
        assertEquals(featureCount, filtered.quads);
        for (int r = 0; r < filtered.first.length; r++) for (int q = filtered.first[r]; q < filtered.first[r] + filtered.count[r]; q++)
            assertNotEquals(0, words[q * 12 + 10] & PredictionPackedMesh.FEATURE);
        submission.update(null, new Vec3(32, 80, 32), 4096);
        assertSame(input, submission.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL), "retraction restores terrain immediately");
    }

    @Test void sameFootprintUpgradeCopiesExactOldFeaturesWithoutDuplicatingNewPartialFeatures() {
        var old = tile(0, 4, true, true, 1);
        var next = tile(0, 1, true, false, 2);
        var original = old.mesh().gpuPayload().cacheWords().clone();
        old.mesh().gpuPayload().prepareGpuStorage(); old.mesh().gpuPayload().prepareStorage();
        next.mesh().retainFeatures(old, next.spacingBlocks());
        assertTrue(next.mesh().featureFallback); assertTrue(next.mesh().surfaceReadyForRendering);
        int[] result = next.mesh().gpuPayload().cacheWords();
        int oldFeatures = 0, nextFeatures = 0;
        for (int p = 0; p < original.length; p += 12) if ((original[p + 10] & PredictionPackedMesh.FEATURE) != 0) {
            oldFeatures++;
            boolean found = false;
            for (int q = 0; q < result.length; q += 12) {
                if ((result[q + 10] & PredictionPackedMesh.FEATURE) == 0) continue;
                boolean equal = true;
                for (int i = 0; i < 12; i++) if (i != 8 && original[p + i] != result[q + i]) equal = false;
                if (equal) {
                    int cell = original[p + 8];
                    assertEquals(cell / old.cellAxis() * 4 * next.cellAxis() + cell % old.cellAxis() * 4, result[q + 8]);
                    found = true; break;
                }
            }
            assertTrue(found, "old geometry/color/UV/light must survive exactly");
        }
        for (int p = 0; p < result.length; p += 12) if ((result[p + 10] & PredictionPackedMesh.FEATURE) != 0) nextFeatures++;
        assertEquals(oldFeatures, nextFeatures);
        assertArrayEquals(original, old.mesh().gpuPayload().cacheWords(), "the old published mesh is never mutated");
        var empty = tile(0, 4, false, true, 3);
        var partial = tile(0, 1, true, false, 4);
        partial.mesh().retainFeatures(empty, partial.spacingBlocks());
        assertFalse(LegacyOwnershipRuns.of(partial.mesh().gpuPayload()).hasFeatures, "complete empty surface must not inherit partial new plants");
    }


    @Test void matchingGroundAndClearedDecorationKeepTheirOwnershipAfterGreedyMerging() {
        var sample = new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(),
                0, 0, 0, 0, 0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        var samples = new ClientColumnSample[9]; Arrays.fill(samples, sample);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, 1, 3, false);
        // A cleared cell can start directly with a full-cell roof/prop top of the
        // same material and height as neighbouring ground. Their owners differ.
        mesh.terrainEnds[1] = mesh.cellOffsets[1];
        var quads = mesh.packed();
        float groundArea = 0, featureArea = 0;
        for (int q = 0; q < quads.quadCount(); q++) {
            float area = Math.abs((quads.x(q, 1) - quads.x(q, 0)) * (quads.z(q, 3) - quads.z(q, 0)));
            if (quads.feature(q)) featureArea += area; else groundArea += area;
        }
        assertEquals(1, featureArea, "merging must not make decoration borrow a neighbour's ground owner");
        assertEquals(3, groundArea, "ground must remain independently replaceable");
    }

    @Test void groundAndFeatureBitsPreserveOnlyGroundSeamDirections() {
        assertArrayEquals(new byte[]{(byte) 129, 64, (byte) 194, 0}, PredictionFeatureHandoff.mask(
                new boolean[]{true, false, true, false}, new boolean[]{false, true, true, false},
                new byte[]{(byte) 129, 15, (byte) 130, 15}));
    }

    @Test void cpuEvictionCannotRemoveTheUploadedFeatureFallbackBeforeChildrenFinish() {
        var parent = tile(1, 4, true, true, 1);
        var child = tile(0, 1, false, false, 2);
        var state = new PredictionRenderResidency();
        var initial = snapshot(parent); state.retain(initial); state.uploaded(parent);
        var groundOnly = snapshot(child); state.retain(groundOnly); state.uploaded(child); state.retain(groundOnly);
        assertTrue(state.contains(parent));
        assertSame(parent, state.snapshot(groundOnly).coveringFeatureTileAtDetail(0, 0, 0));
        var ready = tile(0, 1, false, true, 3);
        var completed = snapshot(ready); state.retain(completed);
        assertTrue(state.contains(parent));
        state.uploaded(ready); state.retain(completed);
        assertFalse(state.contains(parent), "old GPU features release once a drawable successor replaces them");
    }

    private static PredictionRenderer.TileCoverage coverage(PredictionTileManager.PredictionTile tile,
                                                             PredictionTileManager.RenderSnapshot snapshot) throws Exception {
        var method = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTileManager.PredictionTile.class,
                int.class, int.class, double.class, PredictionTileManager.RenderSnapshot.class, VssLodFocus.class);
        method.setAccessible(true);
        return (PredictionRenderer.TileCoverage) method.invoke(null, tile, 0, 0, 1400, snapshot, null);
    }
}
