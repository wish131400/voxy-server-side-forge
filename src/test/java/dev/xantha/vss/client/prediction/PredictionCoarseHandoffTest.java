package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.StrictVoxyCoverageChanges;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionCoarseHandoffTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static final class Uploads implements PredictionPreviewHandoff.Uploads {
        final StrictVoxyCoverageChanges changes = new StrictVoxyCoverageChanges();
        boolean present;
        int maxY = Integer.MAX_VALUE;
        int queries;
        @Override public long revision() { return changes.revision(); }
        @Override public StrictVoxyCoverageChanges.Delta changesSince(long previous) { return changes.since(previous); }
        @Override public boolean covers(ResourceKey<Level> dimension, int x, int y, int z, int xx, int yy, int zz) {
            queries++;
            return present && yy <= maxY && (dimension == null || dimension == Level.OVERWORLD);
        }
        void present(boolean value) { present = value; changes.columnChanged(2, 2); }
    }

    /** Existing index/range fixtures explicitly represent a completed regional GPU upload. */
    static Uploads ready() { var uploads = new Uploads(); uploads.present(true); return uploads; }

    @Test void settledDiskColumnsCannotRetireCoarseGroundOrWaterBeforeTheirGpuRegionArrives() {
        for (boolean water : new boolean[]{false, true}) {
            var mesh = mesh(false, water);
            var input = mesh.drawRanges(water, VssLodFaceGroup.ALL);
            assertTrue(input.quads > 0);
            var uploads = new Uploads(); var coverage = new LegacyResidentCoverage(uploads);
            var index = PredictionAuthoritativeCoverageViewTest.mask(); var camera = new Vec3(4, 80, 4);
            coverage.update(index, camera, 480, Level.OVERWORLD);
            assertFalse(coverage.fullyOwned(Level.OVERWORLD, mesh, 0, 0), "disk data is not a GPU replacement");
            assertSame(input, coverage.filter(mesh, input, 0, 0, water, VssLodFaceGroup.ALL));
            uploads.present(true); coverage.update(index, camera, 480, Level.OVERWORLD);
            assertTrue(coverage.fullyOwned(Level.OVERWORLD, mesh, 0, 0));
            assertEquals(0, coverage.filter(mesh, input, 0, 0, water, VssLodFaceGroup.ALL).quads);
            long revision = coverage.revision();
            uploads.present(false); coverage.update(index, camera, 480, Level.OVERWORLD);
            assertTrue(coverage.revision() > revision);
            assertFalse(coverage.fullyOwned(Level.OVERWORLD, mesh, 0, 0));
            assertSame(input, coverage.filter(mesh, input, 0, 0, water, VssLodFaceGroup.ALL));
        }
    }

    @Test void aMixedTileWithoutPreviewsRestoresGroundOnUploadWithdrawalWithoutCameraMotion() {
        var mesh = mesh(true, false); var input = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        assertFalse(LegacyOwnershipRuns.of(mesh).hasPreviews);
        var uploads = ready(); var coverage = new LegacyResidentCoverage(uploads);
        var index = PredictionAuthoritativeCoverageViewTest.mask(); var camera = new Vec3(4, 80, 4);
        coverage.update(index, camera, 480, Level.OVERWORLD);
        var filtered = coverage.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL);
        assertEquals(1, filtered.quads, "the complete feature still uses frame depth");
        long revision = coverage.revision();
        uploads.present(false); coverage.update(index, camera, 480, Level.OVERWORLD);
        assertTrue(coverage.revision() > revision, "ground-only upload dependencies must invalidate too");
        assertSame(input, coverage.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL));
    }

    @Test void wholeMeshProofSurvivesUnrelatedUploadsButIsRevokedOnRendererReset() {
        var mesh = mesh(false, false); var uploads = ready();
        var coverage = new LegacyResidentCoverage(uploads);
        var index = PredictionAuthoritativeCoverageViewTest.mask(); var camera = new Vec3(4, 80, 4);
        coverage.update(index, camera, 480, Level.OVERWORLD);
        assertTrue(coverage.fullyOwned(Level.OVERWORLD, mesh, 0, 0));
        int queries = uploads.queries; long revision = coverage.revision();
        uploads.changes.columnChanged(900, 900); coverage.update(index, camera, 480, Level.OVERWORLD);
        assertEquals(revision, coverage.revision());
        assertTrue(coverage.fullyOwned(Level.OVERWORLD, mesh, 0, 0));
        assertEquals(queries, uploads.queries, "steady frame/remote uploads reuse the GPU proof");
        uploads.present = false; uploads.changes.invalidateAll();
        coverage.update(index, camera, 480, Level.OVERWORLD);
        assertFalse(coverage.fullyOwned(Level.OVERWORLD, mesh, 0, 0), "Voxy restart revokes whole-mesh deletion");
    }

    private static PredictionPackedMesh mesh(boolean feature, boolean water) {
        int[] ground = PredictionAuthoritativeCoverageViewTest.mesh(0).cacheWords().clone();
        if (water) ground[6] |= 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT;
        int[] words = new int[feature ? 24 : 12]; System.arraycopy(ground, 0, words, 0, 12);
        if (feature) {
            int[] plant = PredictionAuthoritativeCoverageViewTest.mesh(PredictionPackedMesh.FEATURE).cacheWords();
            System.arraycopy(plant, 0, words, 12, 12);
        }
        if (!water) return PredictionPackedMesh.terrainRecords(words, 8);
        int[] first = new int[VssLodFaceGroup.COUNT], count = new int[VssLodFaceGroup.COUNT];
        count[VssLodFaceGroup.HORIZONTAL] = 1;
        return new PredictionPackedMesh(words, 8, 0,
                new int[VssLodFaceGroup.COUNT], new int[VssLodFaceGroup.COUNT], first, count, false, 0);
    }
}
