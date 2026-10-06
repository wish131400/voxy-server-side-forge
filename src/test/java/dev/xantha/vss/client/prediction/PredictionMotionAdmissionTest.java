package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionMotionAdmissionTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test @SuppressWarnings("unchecked") void continuousTravelMakesBoundedColdProgressAndKeepsCoverageAndDirtyWorkAdmitted() throws Exception {
        var config = VSSClientConfig.CONFIG;
        int previousWorkers = config.predictionRefinementWorkers;
        String previousTier = config.performanceTier;
        config.predictionRefinementWorkers = 4; config.performanceTier = "high";
        CountDownLatch release = new CountDownLatch(1), firstStarted = new CountDownLatch(1), threeStarted = new CountDownLatch(3);
        var threads = ConcurrentHashMap.<Thread>newKeySet();
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        var profile = new DimensionProfile(new ResourceLocation("minecraft", "overworld"), 42, -64, 384,
                "noise", "minecraft:overworld", 1);
        var ground = PredictionRefinementOptimizationsTest.samples(2)[0];
        var sampler = new ClientTerrainSampler(42, profile) {
            @Override int initialTerrainCellAxis(int lod) { return 16; }
            @Override public ClientColumnSample sample(int x, int z) {
                if (threads.add(Thread.currentThread())) {
                    int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
                    firstStarted.countDown(); threeStarted.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("motion gate timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                    finally { active.decrementAndGet(); }
                }
                return ground;
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
            @Override public ClientColumnSample samplePreview(int x, int z, int step) { return sample(x, z); }
        };
        var budget = new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 4);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, sampler, budget, null)) {
            var ready = (Map<PredictionTileKey, PredictionTile>) field(manager, "ready");
            var desired = (Set<PredictionTileKey>) field(manager, "desiredKeys");
            var leaves = (Set<PredictionTileKey>) field(manager, "terrainLeaves");
            var pending = (Set<PredictionTileKey>) field(manager, "pending");
            var a = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var b = new PredictionTileKey(Level.OVERWORLD, 2, 0, 0);
            var coverage = new PredictionTileKey(Level.OVERWORLD, 4, 0, 0);
            var dirty = new PredictionTileKey(Level.OVERWORLD, 6, 0, 0);
            for (var key : List.of(a, b, coverage, dirty)) {
                desired.add(key); leaves.add(key);
                var parent = new PredictionTileKey(Level.OVERWORLD, key.tileX() >> 1, 0, 1);
                ready.put(parent, tile(parent, 1, 128));
            }
            for (var key : List.of(a, b, dirty)) ready.put(key, tile(key, 32, 2));
            ((Set<PredictionTileKey>) field(manager, "dirtyTiles")).add(dirty);
            var enqueue = manager.getClass().getDeclaredMethod("enqueue", PredictionTileKey.class, int.class, int.class, boolean.class);
            enqueue.setAccessible(true);
            var priority = manager.getClass().getDeclaredMethod("workPriority", PredictionTileKey.class, boolean.class);
            priority.setAccessible(true);
            moving();
            assertTrue((int) priority.invoke(manager, coverage, false) < (int) priority.invoke(manager, a, false));
            assertTrue((int) priority.invoke(manager, dirty, false) < (int) priority.invoke(manager, a, false));
            enqueue.invoke(manager, a, 0, 0, false);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            moving(); enqueue.invoke(manager, b, 0, 0, false);
            assertFalse(pending.contains(b), "a moving four-worker pool admits one cold resident upgrade");
            enqueue.invoke(manager, coverage, 0, 0, false);
            enqueue.invoke(manager, dirty, 0, 0, false);
            assertTrue(threeStarted.await(5, TimeUnit.SECONDS), manager.surfaceDiagnostics());
            assertTrue(pending.contains(dirty), "dirty repair bypasses the moving upgrade quota");
            assertTrue(maximum.get() <= 4); assertTrue(budget.activeBuildCount() <= 4);
            release.countDown(); idle(manager);
            assertEquals(64, ready.get(a).cellAxis(), "cold detail completes while travel remains active");
            moving(); enqueue.invoke(manager, b, 0, 0, false); idle(manager);
            assertEquals(64, ready.get(b).cellAxis(), "the next cold tile advances without waiting for movement to stop");
            assertEquals(0, budget.activeBuildCount()); assertEquals(0, manager.failedTileCount());
        } finally {
            release.countDown(); PredictionMotionPace.record(null, 0, 0, false, System.nanoTime());
            config.predictionRefinementWorkers = previousWorkers; config.performanceTier = previousTier;
        }
    }

    private static void moving() {
        long now = System.nanoTime();
        PredictionMotionPace.record(null, 0, 0, false, now - 50_000_000L);
        PredictionMotionPace.record(Level.OVERWORLD, 0, 0, true, now - 50_000_000L);
        PredictionMotionPace.record(Level.OVERWORLD, 0.4, 0, true, now);
        assertTrue(PredictionMotionPace.fastMoving());
    }

    private static PredictionTile tile(PredictionTileKey key, int axis, int spacing) {
        var samples = PredictionRefinementOptimizationsTest.samples(axis + 1);
        int[] heights = new int[samples.length]; Arrays.fill(heights, 64);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, spacing, axis + 1, false).compactForRendering();
        var tile = new PredictionTile(key, heights, heights, samples, mesh,
                new PredictionDepthBound(64, 64), 0, 1, axis, spacing);
        mesh.retainedSampleObjects = PredictionSampleCompaction.compact(samples); mesh.prepareGpuPayload(tile);
        return tile;
    }

    private static void idle(PredictionTileManager manager) throws Exception {
        var executor = (ThreadPoolExecutor) field(manager, "executor");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((manager.pendingCount() > 0 || executor.getActiveCount() > 0) && System.nanoTime() < until) {
            moving(); Thread.sleep(2);
        }
        assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics()); assertEquals(0, executor.getActiveCount());
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
