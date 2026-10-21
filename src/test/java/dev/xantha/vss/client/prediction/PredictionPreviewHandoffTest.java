package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.compat.StrictVoxyCoverageChanges;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionPreviewHandoffTest {
    static final Vec3 CAMERA = new Vec3(32, 80, 32);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static final class Uploads implements PredictionPreviewHandoff.Uploads {
        final StrictVoxyCoverageChanges changes = new StrictVoxyCoverageChanges();
        boolean ready;
        int calls;
        int top = Integer.MAX_VALUE;
        @Override public long revision() { return changes.revision(); }
        @Override public StrictVoxyCoverageChanges.Delta changesSince(long previous) { return changes.since(previous); }
        @Override public boolean covers(ResourceKey<Level> dim, int x, int y, int z, int xx, int yy, int zz) {
            calls++;
            return ready && dim == Level.OVERWORLD && yy <= top;
        }
        void ready(boolean value) { ready = value; changes.columnChanged(0, 0); }
    }

    static PredictionExactCoverageMask.Snapshot settled(boolean ready) {
        var index = new PredictionExactCoverageIndex();
        for (int z = -10; z <= 10; z++) for (int x = -10; x <= 10; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        return index.snapshot(Level.OVERWORLD, 0, 0, 64, ready ? ExactCoverageGate.SETTLE_NANOS : 0);
    }

    static PredictionTileManager.PredictionTile mixed(boolean segmented) {
        int axis = 16, step = 4, grid = 18;
        var samples = new ClientColumnSample[grid * grid];
        Arrays.fill(samples, PredictionSimpleVegetationTest.sample(64));
        var plants = PredictionVegetation.Tile.of(Map.of(
                new BlockPos(30, 65, 30), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(30, 66, 30), Blocks.OAK_LEAVES.defaultBlockState(),
                new BlockPos(32, 65, 30), Blocks.GRASS.defaultBlockState()), 0, 0, 64, step, 1);
        var tree = new PredictionSimpleVegetation.Species(Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
        var forms = new PredictionSimpleVegetation.Result(List.of(
                new PredictionSimpleVegetation.Form(2 * axis + 2, 10, 10, 64, 8, tree, 0xff669944),
                new PredictionSimpleVegetation.Form(2 * axis + 3, 13, 9, 64, 1, null, 0xff669944)), null, 72);
        var mesh = segmented ? PredictionMeshBuilder.buildForRendering(samples, null, 63, 0, step, grid,
                null, null, 0, 0, plants, forms, null, () -> true)
                : PredictionMeshBuilder.build(samples, null, 63, 0, step, grid, null, null, 0, 0, plants, forms);
        mesh = mesh.compactForRendering();
        int[] heights = new int[samples.length]; Arrays.fill(heights, 64);
        var tile = new PredictionTileManager.PredictionTile(
                new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0),
                heights, heights, samples, mesh, new PredictionDepthBound(64, 72), 0, 1, axis, step);
        mesh.prepareGpuPayload(tile);
        return tile;
    }

    static int count(PredictionPackedMesh packed, PredictionDrawRanges ranges, int kind) {
        int[] words = packed.restoreWords();
        int count = 0;
        for (int r = 0; r < ranges.first.length; r++) for (int q = ranges.first[r]; q < ranges.first[r] + ranges.count[r]; q++) {
            int flags = words[q * 12 + 10];
            int found = (flags & PredictionPackedMesh.FEATURE) == 0 ? 0
                    : (flags & PredictionPackedMesh.PREVIEW) == 0 ? 1 : 3;
            if (kind == found) count++;
        }
        return count;
    }

    static PredictionDrawRanges filter(LegacyResidentCoverage state, PredictionPackedMesh packed) {
        return state.filter(packed, packed.drawRanges(false, VssLodFaceGroup.ALL), 0, 0, false, VssLodFaceGroup.ALL);
    }

    @Test void realEmptyLandClearsRepresentativesAfterUploadAndRetractionRestoresThem() {
        var packed = mixed(false).mesh().gpuPayload();
        var input = packed.drawRanges(false, VssLodFaceGroup.ALL);
        int previews = count(packed, input, 3), complete = count(packed, input, 1);
        assertTrue(previews > 10); assertTrue(complete > 0);
        var uploads = new Uploads();
        var state = new LegacyResidentCoverage(uploads);
        var mask = settled(true);
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        var waiting = filter(state, packed);
        assertEquals(previews, count(packed, waiting, 3), "settle time is not an upload fence");
        assertEquals(complete, count(packed, waiting, 1));
        assertEquals(count(packed, input, 0), count(packed, waiting, 0), "ground also waits for uploaded real coverage");
        int calls = uploads.calls;
        assertSame(waiting, filter(state, packed)); assertEquals(calls, uploads.calls);
        uploads.ready(true);
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        var owned = filter(state, packed);
        assertEquals(0, count(packed, owned, 3), "real absence of trees replaces the biome representatives too");
        assertEquals(complete, owned.quads, "complete trees/grass remain depth-tested");
        uploads.ready(false);
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        assertEquals(previews, count(packed, filter(state, packed), 3), "upload withdrawal restores previews this frame");
        state.update(null, CAMERA, 4096, Level.OVERWORLD);
        assertSame(input, filter(state, packed), "data withdrawal restores all geometry");
    }

    @Test void unconfirmedEdgesFarDistanceAndWrongDimensionKeepPreviewFallback() {
        var packed = mixed(true).mesh().gpuPayload();
        var input = packed.drawRanges(false, VssLodFaceGroup.ALL);
        int previews = count(packed, input, 3);
        var uploads = new Uploads(); uploads.ready(true);
        var state = new LegacyResidentCoverage(uploads);
        state.update(settled(false), CAMERA, 4096, Level.OVERWORLD);
        assertEquals(previews, count(packed, filter(state, packed), 3));
        var edge = PredictionExactCoverageMask.Snapshot.around(0, 0, 64);
        edge.mark(0, 0); edge.preserveBoundaryFallback();
        state.update(edge, CAMERA, 4096, Level.OVERWORLD);
        assertEquals(previews, count(packed, filter(state, packed), 3), "an isolated indexed column is not stable interior");
        state.update(settled(true), new Vec3(5000, 80, 5000), 1024, Level.OVERWORLD);
        assertSame(input, filter(state, packed));
        state.update(settled(true), CAMERA, 4096, Level.NETHER);
        assertEquals(previews, count(packed, filter(state, packed), 3));
        state.clear(); state.update(null, CAMERA, 4096, Level.OVERWORLD);
        assertSame(input, filter(state, packed));
    }

    @Test void allFacesUseTheWholeRepresentativeIncludingCrownAndNeighbourColumn() {
        // A trunk is wholly inside one column; its canopy crosses into the next.
        var trunk = quad(14, 14, 64, 15, 15, 1, true);
        var crown = quad(12, 12, 72, 18, 18, 1, true);
        int[] words = new int[24]; System.arraycopy(trunk, 0, words, 0, 12); System.arraycopy(crown, 0, words, 12, 12);
        var mesh = PredictionPackedMesh.terrainRecords(words, 4);
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        var runs = LegacyOwnershipRuns.of(mesh);
        var mask = settled(true);
        var proof = runs.staticCoverage(mask, 0, 0, (x,y,z,xx,yy,zz) -> yy < 72);
        assertEquals(ranges.quads, runs.filter(ranges, mask, 0, 0, 16, 68, 16, 4096,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, proof,
                LegacyOwnershipRuns.allStaticCoverage(proof), new LegacyOwnershipRuns.Scratch(), null).quads,
                "missing crown height retains every face");
        proof = runs.staticCoverage(mask, 0, 0, (x,y,z,xx,yy,zz) -> xx < 1 && zz < 1);
        assertFalse(LegacyOwnershipRuns.anyStaticCoverage(proof), "missing canopy neighbour retains the trunk too");
        proof = runs.staticCoverage(mask, 0, 0, (x,y,z,xx,yy,zz) -> y == 64 && yy == 72 && xx == 1 && zz == 1);
        assertTrue(LegacyOwnershipRuns.allStaticCoverage(proof));
        assertEquals(0, runs.filter(ranges, mask, 0, 0, 16, 68, 16, 4096,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, proof, true, new LegacyOwnershipRuns.Scratch(), null).quads);
    }

    @Test void unrelatedUploadsAndInteriorMovementKeepCoverageProofsCached() {
        var packed = mixed(false).mesh().gpuPayload();
        var uploads = new Uploads(); uploads.ready(true);
        var state = new LegacyResidentCoverage(uploads);
        var mask = settled(true);
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        var removed = filter(state, packed); int calls = uploads.calls;
        long revision = state.revision();
        uploads.changes.columnChanged(900, 900);
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        assertEquals(revision, state.revision()); assertSame(removed, filter(state, packed)); assertEquals(calls, uploads.calls);
        uploads.changes.windowMoved(0, 0, 1, 0, 254);
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        assertEquals(revision, state.revision());
        state.update(mask, new Vec3(40, 80, 32), 4096, Level.OVERWORLD);
        assertSame(removed, filter(state, packed)); assertEquals(calls, uploads.calls);
        uploads.changes.invalidateAll();
        uploads.ready = false;
        state.update(mask, CAMERA, 4096, Level.OVERWORLD);
        assertTrue(count(packed, filter(state, packed), 3) > 0, "a renderer reset revokes preview ownership");
    }

    @Test void previewIdentitySurvivesSegmentsCompressionDiskRestoreAndFeatureInheritance() throws Exception {
        for (boolean segmented : new boolean[]{false, true}) {
            var tile = mixed(segmented); var packed = tile.mesh().gpuPayload();
            int[] source = packed.cacheWords().clone();
            assertTrue(LegacyOwnershipRuns.of(packed).hasPreviews);
            var encoding = PredictionGpuEncoding.rectangles(PredictionGpuEncoding.encode(source), source.length / 12);
            assertArrayEquals(source, PredictionGpuEncoding.decodeGpu(encoding, source.length / 12));
            byte[] identity = new byte[32];
            var restored = PredictionMeshCodec.decode(PredictionMeshCodec.encode(tile.mesh(), identity), identity, tile.cellAxis());
            assertNotNull(restored); assertTrue(LegacyOwnershipRuns.of(restored.gpuPayload()).hasPreviews);
            var ranges = packed.drawRanges(false, VssLodFaceGroup.ALL);
            assertEquals(count(packed, ranges, 3), count(restored.gpuPayload(), restored.gpuPayload().drawRanges(false, VssLodFaceGroup.ALL), 3));
            packed.prepareGpuStorage(); packed.prepareStorage();
            assertArrayEquals(source, packed.cacheWords());
            var next = PredictionFeatureHandoffTest.tile(0, 1, false, false, 2);
            next.mesh().retainFeatures(tile, 1);
            assertTrue(LegacyOwnershipRuns.of(next.mesh().gpuPayload()).hasPreviews);
            var uploads = new Uploads(); uploads.ready(true);
            var state = new LegacyResidentCoverage(uploads); state.update(settled(true), CAMERA, 4096, Level.OVERWORLD);
            assertEquals(0, count(next.mesh().gpuPayload(), filter(state, next.mesh().gpuPayload()), 3));
            assertTrue(count(next.mesh().gpuPayload(), filter(state, next.mesh().gpuPayload()), 1) > 0);
        }
    }

    @Test void dictionaryRectanglesAndFarCopiesKeepPreviewFlagsAndOwnershipSeparate() {
        int[] words = PredictionDisplayGeometryTest.grid(0);
        // Exact cutout features coexist with terrain that can build display copies.
        for (int q = 0; q < 32; q++) {
            words[q * 12 + 6] |= PredictionPackedMesh.FLAG_CUTOUT;
            words[q * 12 + 10] |= PredictionPackedMesh.FEATURE | (q < 16 ? PredictionPackedMesh.PREVIEW : 0);
        }
        var encoded = PredictionGpuEncoding.encode(words);
        assertTrue(encoded.paletteBaseTexel() > 0, "the fixture must exercise the dictionary");
        var rectangles = PredictionGpuEncoding.rectangles(encoded, words.length / 12);
        assertTrue(rectangles.paletteBaseTexel() < 0, "the fixture must exercise rectangle GPU storage");
        assertArrayEquals(words, PredictionGpuEncoding.decodeGpu(rectangles, words.length / 12));
        var packed = PredictionPackedMesh.terrainRecords(words, 64);
        assertTrue(packed.hasDisplayLod());
        var uploads = new Uploads(); uploads.ready(true);
        var state = new LegacyResidentCoverage(uploads);
        state.update(settled(true), CAMERA, 4096, Level.OVERWORLD);
        for (int tier = 0; tier <= 3; tier++) {
            var ranges = packed.drawRanges(false, VssLodFaceGroup.ALL, 0, tier);
            assertTrue(count(packed, ranges, 3) > 0, "display copies retain preview identity");
            var selected = state.filter(packed, ranges, 0, 0, false, VssLodFaceGroup.ALL);
            assertEquals(0, count(packed, selected, 3));
            assertTrue(count(packed, selected, 1) > 0);
        }
    }

    @Test void productionUploadBridgeClearsRealEmptyRegionsAndImmediatelyRestoresOnReset() throws Exception {
        var visibility = dev.xantha.vss.compat.StrictLodVisibility.class;
        var hook = field(visibility, "renderHookSeen");
        boolean oldHook = hook.getBoolean(null);
        dev.xantha.vss.compat.StrictLodVisibility.reset();
        try {
            hook.setBoolean(null, true);
            field(visibility, "dimension").set(null, Level.OVERWORLD);
            field(visibility, "uploadedNodes").setBoolean(null, true);
            field(visibility, "meshPipeline").set(null, new dev.xantha.vss.compat.StrictVoxyPipeline());
            dev.xantha.vss.compat.StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 64);
            var nodes = (dev.xantha.vss.compat.StrictVoxyNodeIndex) field(visibility, "NODES").get(null);
            var packed = mixed(false).mesh().gpuPayload();
            var state = new LegacyResidentCoverage();
            var mask = settled(true);
            state.update(mask, CAMERA, 4096, Level.OVERWORLD);
            assertTrue(count(packed, filter(state, packed), 3) > 0);
            long node = dev.xantha.vss.compat.StrictVoxyNodeIndex.key(0, 0, 2, 0);
            nodes.update(1, node, 0xFFFFFF);
            state.update(mask, CAMERA, 4096, Level.OVERWORLD);
            assertTrue(count(packed, filter(state, packed), 3) > 0, "an unmeshed node cannot clear preview");
            nodes.update(1, node, 0);
            state.update(mask, CAMERA, 4096, Level.OVERWORLD);
            assertEquals(0, count(packed, filter(state, packed), 3), "an uploaded empty region is a valid owner");
            assertTrue(count(packed, filter(state, packed), 1) > 0);
            dev.xantha.vss.compat.StrictLodVisibility.reset();
            state.update(mask, CAMERA, 4096, Level.OVERWORLD);
            assertTrue(count(packed, filter(state, packed), 3) > 0);
        } finally {
            dev.xantha.vss.compat.StrictLodVisibility.reset();
            hook.setBoolean(null, oldHook);
        }
    }

    private static java.lang.reflect.Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }

    static int[] quad(int x, int z, int y, int xx, int zz, int cell, boolean preview) {
        int by = 32768 + y * 4;
        return new int[]{x | xx << 16, xx | x << 16, z | z << 16, zz | zz << 16,
                by | by << 16, by | by << 16, 0, 0xff2020, cell, 0xff2020 | 1 << 24,
                0xff2020 | PredictionPackedMesh.FEATURE | (preview ? PredictionPackedMesh.PREVIEW : 0), 0xff2020};
    }
}
