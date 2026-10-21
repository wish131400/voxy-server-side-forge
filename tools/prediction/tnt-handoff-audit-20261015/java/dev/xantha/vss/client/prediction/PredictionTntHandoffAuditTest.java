package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the current handoff, including known loss of edited terrain.
 * These assertions record audit evidence; they are not acceptance tests for a fix.
 * The synthetic crater is ten blocks wide and eight blocks deep in a flat world.
 */
class PredictionTntHandoffAuditTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            Level.OVERWORLD.location(), 42L, -64, 384, "noise", "minecraft:overworld", 1L);
    private int previousDistance;

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void boundedLayout() {
        previousDistance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 4096;
    }
    @AfterEach void cleanup() throws Exception {
        VSSClientConfig.CONFIG.predictionDistanceBlocks = previousDistance;
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    @Test void realColumnPresenceWithoutACaptureRestoresUneditedPredictionOutsideVoxyRange() throws Exception {
        var sampler = sampler(false);
        var actual = ClientCaptureExtractor.extractAll(2, 2, crater(), sampler);
        assertEquals(56, actual[8 + 8 * 16].surfaceY());
        try (var manager = manager(sampler)) {
            // A local storage discovery supplies presence, not VSS capture data.
            var presence = settledMask();
            var predicted = sampleAt(manager, 40, 40, 0, 1);
            assertEquals(64, predicted.surfaceY());
            assertFalse(predicted.captured());
            var mesh = PredictionPackedMesh.terrainRecords(quad(40, 40, predicted.surfaceY()), 64);
            var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
            var submission = new PredictionSubmissionCoverage();
            int radius = PredictionVoxyOwnership.radiusBlocks(32);
            submission.update(presence, new Vec3(0, 64, 0), radius);
            assertEquals(0, submission.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL).quads);
            submission.update(presence, new Vec3(1024, 64, 0), radius);
            assertSame(ranges, submission.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
            assertEquals(64, sampleAt(manager, 40, 40, 0, 1).surfaceY());
            System.out.println("TNT_AUDIT local-presence: actual=56, prediction=64, nearQuads=0, farQuads=1");
        }
    }

    @Test void completeServerCapturePreservesCraterWhenASamplePointHitsIt() throws Exception {
        try (var manager = manager(sampler(false))) {
            capture(manager, 2, 2);
            for (int spacing : new int[]{1, 2, 4, 8, 16, 32}) {
                var resolved = sampleAt(manager, 40, 40, Integer.numberOfTrailingZeros(spacing), spacing);
                assertEquals(56, resolved.surfaceY());
                assertTrue(resolved.captured());
            }
            assertEquals(64, sampleAt(manager, 32, 32, 0, 1).surfaceY());
            System.out.println("TNT_AUDIT server-capture: hitPoint=56 at spacings 1/2/4/8/16/32");
        }
    }

    @Test void aCoarseGridCanMissAnEntireCapturedCraterBetweenItsSamplePoints() throws Exception {
        try (var manager = manager(sampler(false))) {
            capture(manager, 1, 1);
            assertEquals(56, sampleAt(manager, 24, 24, 0, 1).surfaceY());
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 5);
            assertEquals(32, manager.layout().sampleSpacing(key.lod()));
            assertFalse(PredictionTileManager.captureIntersectsGrid(key, manager.layout(), 1, 1));
            for (int z : new int[]{0, 32}) for (int x : new int[]{0, 32}) {
                assertEquals(64, sampleAt(manager, x, z, 5, 32).surfaceY());
            }
            System.out.println("TNT_AUDIT coarse-grid: craterAt24=56, four spacing32 grid points=64, dependency=false");
        }
    }

    @Test void dirtyInvalidationFallsBackToSeedUntilTheReplacementCaptureArrives() throws Exception {
        try (var manager = manager(sampler(false))) {
            capture(manager, 2, 2);
            assertEquals(56, sampleAt(manager, 40, 40, 0, 1).surfaceY());
            manager.invalidate(2, 2);
            assertTrue(manager.isRenderAuthoritative(2, 2));
            assertEquals(64, sampleAt(manager, 40, 40, 0, 1).surfaceY());
            capture(manager, 2, 2);
            assertEquals(56, sampleAt(manager, 40, 40, 0, 1).surfaceY());
            System.out.println("TNT_AUDIT dirty-refresh: 56 -> 64 without replacement -> 56 after replacement");
        }
    }

    @Test void anInteriorBackendDoesNotUseTheCapturedExteriorHeightAsItsVolume() throws Exception {
        try (var manager = manager(sampler(true))) {
            capture(manager, 2, 2);
            var field = PredictionTileManager.class.getDeclaredField("sampleCache");
            field.setAccessible(true);
            var cache = (VssLodSampleCache) field.get(manager);
            assertEquals(56, cache.get(pack(40, 40)).surfaceY());
            var interior = sampleAt(manager, 40, 40, 0, 1);
            assertEquals(64, interior.surfaceY());
            assertNotNull(interior.volume());
            System.out.println("TNT_AUDIT interior: storedCapture=56, predictedVolumeTop=64");
        }
    }

    private static ClientTerrainSampler sampler(boolean interior) {
        return new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                return new ClientColumnSample(64, 64, 0,
                        BuiltInRegistries.BLOCK.getId(Blocks.GRAVEL), 0, 0, 0, 0, 0,
                        ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                        BuiltInRegistries.BLOCK.getId(Blocks.GRAVEL), BuiltInRegistries.BLOCK.getId(Blocks.GRAVEL),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override boolean interiorTerrain() { return interior; }
            @Override ClientColumnSample sampleInterior(int x, int z) {
                return PredictionColumnVolume.ofRuns(new int[]{0, 64,
                        BuiltInRegistries.BLOCK.getId(Blocks.GRAVEL), 0}).asSample();
            }
        };
    }

    private static PredictionTileManager manager(ClientTerrainSampler sampler) {
        return new PredictionTileManager(Level.OVERWORLD, sampler,
                new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB, 0,
                        () -> Long.MAX_VALUE, System::nanoTime), null);
    }

    private static VoxelColumnData crater() {
        var section = ClientCaptureExtractorTest.section();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int top = x >= 3 && x <= 12 && z >= 3 && z <= 12 ? 8 : 16;
            for (int y = 0; y < top; y++) section.setBlockState(x, y, z, Blocks.GRAVEL.defaultBlockState());
        }
        return new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(3, section, null, null)}, 1L, true);
    }

    private static void capture(PredictionTileManager manager, int x, int z) throws Exception {
        manager.captureExactColumn(x, z, crater());
        var field = PredictionTileManager.class.getDeclaredField("pendingCaptures");
        field.setAccessible(true);
        Set<?> pending = (Set<?>) field.get(manager);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!pending.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(pending.isEmpty(), "capture worker did not finish within its bounded timeout");
        assertEquals(0, manager.failedTileCount());
    }

    private static ClientColumnSample sampleAt(PredictionTileManager manager, int x, int z, int lod, int spacing) throws Exception {
        Method method = PredictionTileManager.class.getDeclaredMethod("sampleAt", int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (ClientColumnSample) method.invoke(manager, x, z, lod, spacing);
    }

    private static PredictionExactCoverageMask.Snapshot settledMask() {
        var index = new PredictionExactCoverageIndex();
        for (int z = 0; z <= 6; z++) for (int x = 0; x <= 6; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        return index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS);
    }

    private static int[] quad(int x, int z, int height) {
        int y = 32768 + height * 4;
        return new int[]{x | (x + 1) << 16, x | (x + 1) << 16, z | z << 16, z + 1 | (z + 1) << 16,
                y | y << 16, y | y << 16, 0, 0xffffff, 0, 0xffffff, 0xffffff, 0xffffff};
    }
    private static long pack(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
}
