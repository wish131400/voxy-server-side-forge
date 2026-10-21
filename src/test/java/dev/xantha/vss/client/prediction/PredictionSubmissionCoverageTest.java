package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class PredictionSubmissionCoverageTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void sparseCoverageMatchesPerQuadReferenceWithoutReorderingWater() {
        var random = new Random(421);
        int[] words = new int[128 * 12];
        for (int q = 0; q < 128; q++) {
            int[] face = quad(1 + random.nextInt(32) * 16, 1 + random.nextInt(32) * 16, 0);
            if (q % 3 == 0) face[10] |= PredictionPackedMesh.FEATURE;
            System.arraycopy(face, 0, words, q * 12, 12);
        }
        var runs = new LegacyOwnershipRuns(words);
        var input = new PredictionDrawRanges(new int[]{90, 0, 45}, new int[]{30, 30, 20});
        var index = mask(0, 18);
        byte[] proof = runs.staticCoverage(index, 0, 0, (x,y,z,xx,yy,zz) -> true);
        var scratch = new LegacyOwnershipRuns.Scratch();
        PredictionDrawRanges previous = null;
        for (int frame = 0; frame < 100; frame++) {
            double x = random.nextInt(512), y = random.nextInt(300), z = random.nextInt(512);
            int radius = 32 + random.nextInt(600);
            float morphMin = frame % 2 == 0 ? -64 : Float.POSITIVE_INFINITY;
            float morphMax = frame % 2 == 0 ? 320 : Float.NEGATIVE_INFINITY;
            var actual = runs.filter(input, index, 0, 0, x, y, z, radius, morphMin, morphMax,
                    proof, LegacyOwnershipRuns.allStaticCoverage(proof), scratch, previous);
            var expected = new ArrayList<Integer>();
            for (int r = 0; r < input.first.length; r++) for (int q = input.first[r]; q < input.first[r] + input.count[r]; q++) {
                int run = 0; while (runs.end(run) <= q) run++;
                int b = run * 6;
                boolean owned = proof[run] != 0 && PredictionVoxyOwnership.containsBox(x, y, z, radius,
                        runs.coordinate(run, 0) - 4, Math.min(runs.coordinate(run, 1), morphMin) - 4, runs.coordinate(run, 2) - 4,
                        runs.coordinate(run, 3) + 4, Math.max(runs.coordinate(run, 4), morphMax) + 4, runs.coordinate(run, 5) + 4);
                if (!owned) expected.add(q);
            }
            var selected = new ArrayList<Integer>();
            for (int r = 0; r < actual.first.length; r++) for (int q = actual.first[r]; q < actual.first[r] + actual.count[r]; q++) selected.add(q);
            assertEquals(expected, selected, "frame " + frame);
            previous = actual;
        }
    }

    @Test void stationaryMorphChangeRestoresGeometryImmediately() {
        var mesh = PredictionPackedMesh.terrainRecords(quad(33, 33, 0), 64);
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        state.update(mask(0, 6), new Vec3(4, 68, 4), 480);
        assertEquals(0, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL).quads);
        mesh.morph(new float[]{0}, -64, 1000);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        mesh.morph(null, 0, 0);
        assertEquals(0, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL).quads);
    }

    private static PredictionExactCoverageMask.Snapshot mask(int min, int max) {
        var index = new PredictionExactCoverageIndex();
        for (int z = min; z <= max; z++) for (int x = min; x <= max; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        return index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS);
    }
    private static int[] quad(int x, int z, int flags) {
        int y = 32768 + 64 * 4;
        return new int[]{x | (x + 1) << 16, x | (x + 1) << 16, z | z << 16, z + 1 | (z + 1) << 16,
                y | y << 16, y | y << 16, flags, 0xffffff, 0, 0xffffff, 0xffffff, 0xffffff};
    }
    private static PredictionDrawRanges filter(int[] words, PredictionExactCoverageMask.Snapshot index,
                                               double base, double height, double radius) {
        var ranges = new PredictionDrawRanges(new int[]{0}, new int[]{words.length / 12});
        var runs = new LegacyOwnershipRuns(words);
        var proof = runs.staticCoverage(index, base, base, (x,y,z,xx,yy,zz) -> true);
        return runs.filter(ranges, index, base, base, 4, height, 4, radius,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, proof,
                LegacyOwnershipRuns.allStaticCoverage(proof), new LegacyOwnershipRuns.Scratch(), null);
    }

    @Test void stableInteriorRemovesOpaqueAndWaterWithoutAnyDepthTest() {
        for (int fluid : new int[]{0, 1}) {
            var words = quad(33, 33, fluid << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
            assertEquals(0, filter(words, mask(0, 6), 0, 68, 4096).quads);
            assertEquals(1, filter(words, null, 0, 68, 4096).quads);
            assertEquals(1, filter(words, mask(0, 6), 0, 68, 0).quads);
        }
    }

    @Test void wholeMeshOwnershipProofIsRevokedWithCoverageAltitudeAndMorph() {
        var packed = PredictionPackedMesh.terrainRecords(quad(33,33,0),1);
        var coverage = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        coverage.update(mask(0,6),new Vec3(4,68,4),4096);
        assertTrue(coverage.fullyOwned(packed,0,0));
        coverage.update(null,new Vec3(4,68,4),4096);
        assertFalse(coverage.fullyOwned(packed,0,0));
        coverage.update(mask(0,6),new Vec3(4,1000,4),480);
        assertFalse(coverage.fullyOwned(packed,0,0));
        packed.morph(new float[]{0},-64,1000);
        coverage.update(mask(0,6),new Vec3(4,68,4),480);
        assertFalse(coverage.fullyOwned(packed,0,0));
    }

    @Test void boundaryCrossingsMissingAndUnsettledColumnsPreserveWholeGeometry() {
        assertEquals(1, filter(quad(1, 33, 0), mask(0, 6), 0, 68, 4096).quads, "one chunk boundary fallback");
        assertEquals(1, filter(quad(16, 33, 0), mask(0, 6), 0, 68, 4096).quads, "closed edge also touches boundary chunk");
        var index = new PredictionExactCoverageIndex();
        long now = ExactCoverageGate.SETTLE_NANOS;
        for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        index.remove(Level.OVERWORLD, 2, 2);
        assertEquals(1, filter(quad(33, 33, 0), index.snapshot(Level.OVERWORLD, 0, 0, 64, now), 0, 68, 4096).quads);
        index.confirm(Level.OVERWORLD, 2, 2, now);
        assertEquals(1, filter(quad(33, 33, 0), index.snapshot(Level.OVERWORLD, 0, 0, 64, now), 0, 68, 4096).quads);
        assertEquals(0, filter(quad(33, 33, 0), index.snapshot(Level.OVERWORLD, 0, 0, 64, now * 2), 0, 68, 4096).quads);
        assertEquals(1, filter(quad(33, 33, 0), index.snapshot(Level.NETHER, 0, 0, 64, now * 2), 0, 68, 4096).quads);
    }

    @Test void negativeCoordinatesAltitudeMorphAndCameraCellMarginAreConservative() {
        var words = quad(33, 33, 0);
        assertEquals(0, filter(words, mask(-7, 0), -96, 68, 4096).quads);
        assertEquals(1, filter(words, mask(0, 6), 0, 1000, 480).quads);
        assertEquals(1, filter(words, mask(0, 6), 0, 68, 64).quads);
        var runs = new LegacyOwnershipRuns(words);
        var proof = runs.staticCoverage(mask(0, 6), 0, 0, (x,y,z,xx,yy,zz) -> true);
        assertEquals(1, runs.filter(new PredictionDrawRanges(new int[]{0}, new int[]{1}), mask(0, 6),
                0, 0, 4, 68, 4, 480, -64, 1000, proof, true,
                new LegacyOwnershipRuns.Scratch(), null).quads, "morph can move the mesh outside Voxy's sphere");
    }

    @Test void interleavedOwnershipPreservesOrderAndDoesNotApplyFaceBitmaskToRuns() {
        int[] words = new int[160 * 12];
        for (int q = 0; q < 160; q++) System.arraycopy(quad(q % 2 == 0 ? 33 : 1, 33, 0), 0, words, q * 12, 12);
        var filtered = filter(words, mask(0, 6), 0, 68, 4096);
        assertEquals(80, filtered.quads); assertEquals(80, filtered.first.length);
        for (int i = 0; i < 80; i++) { assertEquals(i * 2 + 1, filtered.first[i]); assertEquals(1, filtered.count[i]); }
    }

    @Test void passCacheRestoresGeometryWhenIndexRangeOrPositionChanges() {
        var mesh = PredictionPackedMesh.terrainRecords(quad(33, 33, 0), 64);
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()); var index = mask(0, 6);
        state.update(index, new Vec3(0, 64, 0), 4096);
        var removed = state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL);
        assertEquals(0, removed.quads);
        assertSame(removed, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        long revision = state.revision();
        state.update(index, new Vec3(7.99, 71.99, 7.99), 4096); assertEquals(revision, state.revision());
        state.update(index, new Vec3(0, 10000, 0), 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        state.update(index, new Vec3(0, 64, 0), 0);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        state.update(null, new Vec3(0, 64, 0), 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
    }

    @Test void voxyRetirementDoesNotWaitForAnUnuploadedPredictionAncestor() {
        var layout = VssLodLayout.of(8192, 6, true, true);
        var tile = PredictionCoverageWorkTest.tile(0, 0, 0, layout);
        var parent = PredictionCoverageWorkTest.tile(0, 0, 1, layout);
        var initial = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(tile.key(), tile), Map.of());
        var state = new PredictionRenderResidency(); state.retain(initial); state.uploaded(tile);
        var waiting = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(parent.key(), parent), Map.of());
        state.retain(waiting); assertTrue(state.contains(tile), "normal prediction refinement must wait");
        var handedOff = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout,
                Map.of(parent.key(), parent), Map.of(), null, Set.of(tile.key()));
        state.retain(handedOff); assertTrue(state.contains(tile), "Voxy residency cannot replace the unuploaded prediction parent");
    }

    @Test void equivalentSnapshotsKeepRevisionAndFarChangesKeepLocalProofAndResult() throws Exception {
        int[] words = new int[24];
        System.arraycopy(quad(33, 33, 0), 0, words, 0, 12);
        System.arraycopy(quad(1, 33, 0), 0, words, 12, 12);
        var mesh = PredictionPackedMesh.terrainRecords(words, 64);
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()); var index = mask(0, 6);
        var camera = new Vec3(0, 64, 0); state.update(index, camera, 4096);
        var filtered = state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL);
        assertEquals(1, filtered.quads);
        var cacheField = LegacyResidentCoverage.class.getDeclaredField("cache"); cacheField.setAccessible(true);
        var entry = ((Map<?, ?>) cacheField.get(state)).get(mesh);
        var proofField = entry.getClass().getDeclaredField("staticCoverage"); proofField.setAccessible(true);
        var proof = proofField.get(entry); long revision = state.revision();
        state.update(mask(0, 6), camera, 4096);
        assertEquals(revision, state.revision(), "a new snapshot object with identical interior must not invalidate");
        assertSame(filtered, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));

        var far = new PredictionExactCoverageMask.Snapshot(index.originX(), index.originZ(), index.size(), index.columns().clone());
        for (int z = 40; z <= 44; z++) for (int x = 40; x <= 44; x++) far.mark(x, z);
        far.preserveBoundaryFallback(); state.update(far, camera, 4096);
        assertTrue(state.revision() > revision);
        var revisionsField = entry.getClass().getDeclaredField("filteredRevision"); revisionsField.setAccessible(true);
        assertEquals(state.revision(), ((long[]) revisionsField.get(entry))[VssLodFaceGroup.ALL],
                "unaffected slots must advance without re-filtering");
        assertSame(filtered, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        assertSame(proof, proofField.get(entry), "a far column change must reuse this mesh's static proof");

        var withdrawn = new PredictionExactCoverageMask.Snapshot(far.originX(), far.originZ(), far.size(), far.columns().clone());
        withdrawn.columns()[(2 - withdrawn.originZ()) * withdrawn.size() + 2 - withdrawn.originX()] = 0;
        withdrawn.preserveBoundaryFallback(); state.update(withdrawn, camera, 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL),
                "local withdrawal must restore geometry in the same update");
        assertNotSame(proof, proofField.get(entry));
    }

    @Test void maskWindowMigrationAndRadiusChangesCannotRetainOldOwnership() {
        var mesh = PredictionPackedMesh.terrainRecords(quad(33, 33, 0), 64);
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()); var camera = new Vec3(0, 64, 0);
        state.update(mask(0, 6), camera, 4096);
        assertEquals(0, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL).quads);
        state.update(PredictionExactCoverageMask.Snapshot.around(1024, 1024, 64), camera, 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        state.update(mask(0, 6), camera, 4096);
        assertEquals(0, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL).quads);
        state.update(mask(0, 6), camera, 32);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        state.update(null, camera, 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
    }
}
