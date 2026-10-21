package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionTurnMainTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public void execute(Runnable job) { jobs.add(job); }
        void finish() { jobs.removeFirst().run(); }
    }

    @Test void boundaryPreparationNeverWaitsAndPublishesTheCompleteMatchingTransaction() {
        var executor = new ManualExecutor(); var preparation = new PredictionSeamPreparation(executor);
        var low = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(-1, -1, 2, 64));
        var high = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0, -1, 4, 96));
        var input = Map.of(low.tile().key(), new PredictionSeamPreparation.Surface(low.tile(), low.allowed(), low.allowed()),
                high.tile().key(), new PredictionSeamPreparation.Surface(high.tile(), high.allowed(), high.allowed()));
        preparation.request(1, input, List.of());
        preparation.request(2, Map.of(), List.of());
        assertEquals(1, executor.jobs.size(), "only one retained boundary job");
        assertNull(preparation.poll(1), "render thread returns while the worker is still pending");
        executor.finish(); var result = preparation.poll(1); assertNotNull(result);
        var expected = new PredictionLodSeams(); var patches = expected.update(List.of(low, high));
        assertEquals(patches.size(), result.lod().size());
        var byKey = new HashMap<PredictionTileKey, PredictionLodSeams.Patch>();
        result.lod().forEach(p -> byKey.put(p.surface().tile().key(), p));
        for (var patch : patches) assertArrayEquals(patch.mesh().quads(), byKey.get(patch.surface().tile().key()).mesh().quads());
        for (var surface : List.of(low, high)) assertArrayEquals(
                PredictionFeatureHandoff.mask(surface.allowed(), surface.allowed(), expected.boundaryMask(surface.tile().key())),
                result.masks().get(surface.tile().key()));
        preparation.request(2, input, List.of()); executor.finish();
        assertNull(preparation.poll(3), "changed ownership rejects stale work");
        preparation.request(3, Map.of(), List.of()); executor.finish();
        assertTrue(preparation.poll(3).lod().isEmpty(), "withdrawn coverage removes its old connector");
    }

    @Test void resetCannotPublishAJobFromThePreviousWorldOrGrowTheWorkerQueue() throws Exception {
        var executor = new ManualExecutor(); var preparation = new PredictionSeamPreparation(executor);
        preparation.request(0, Map.of(), List.of());
        var pendingField = PredictionSeamPreparation.class.getDeclaredField("pending"); pendingField.setAccessible(true);
        var abandoned = (java.util.concurrent.CompletableFuture<?>) pendingField.get(preparation);
        preparation.clear();
        preparation.request(0, Map.of(), List.of());
        assertEquals(1, executor.jobs.size()); executor.finish();
        assertNull(abandoned.getNow(null), "abandoned results release snapshots even if the exited world never polls again");
        assertNull(preparation.poll(0), "equal version numbers across worlds cannot reuse a previous generation");
        preparation.request(0, Map.of(), List.of()); executor.finish(); assertNotNull(preparation.poll(0));
        preparation.request(1, Map.of(), List.of()); executor.finish();
        abandoned = (java.util.concurrent.CompletableFuture<?>) pendingField.get(preparation);
        assertNotNull(abandoned.getNow(null));
        preparation.clear();
        assertNull(abandoned.getNow(null), "a reset also drops results completed before the last render poll");
    }

    @Test void viewClassificationRunsOffThreadAndIncludesAncestorAndNeighbourCoverage() {
        var executor = new ManualExecutor();
        var calculation = new PredictionDemandPreparation();
        var preparation = new PredictionClientPreparation<PredictionDemandPreparation.Input, PredictionDemandPreparation.Result>(executor, calculation::build);
        var layout = VssLodLayout.of(4096, 6, true, false);
        var front = new PredictionTileKey(Level.OVERWORLD, 4, 0, 1);
        var parent = new PredictionTileKey(Level.OVERWORLD, 2, 0, 2);
        var neighbour = new PredictionTileKey(Level.OVERWORLD, 3, 0, 1);
        var back = new PredictionTileKey(Level.OVERWORLD, -4, 0, 1);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1);
        var desired = Set.of(front, parent, neighbour, back);
        var input = new PredictionDemandPreparation.Input(layout, view, null, desired, Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Map.of(), -64, 384, 10);
        preparation.request(input); preparation.request(input);
        assertEquals(1, executor.jobs.size()); assertNull(preparation.poll());
        executor.finish(); var result = preparation.poll(); assertNotNull(result);
        for (var key : desired) assertEquals(view.band(key, layout, null, -64, 384), result.bands().get(key));
        assertTrue(result.dependencies().containsAll(List.of(front, parent, neighbour)));
        assertFalse(result.dependencies().contains(back));
        assertThrows(UnsupportedOperationException.class, () -> result.bands().put(front, 5));
        preparation.request(input); preparation.clear(); executor.finish(); assertNull(preparation.poll());
    }

    @Test void failedBoundaryJobRetainsTheFallbackAndCanPrepareAReplacement() {
        var executor = new ManualExecutor(); var preparation = new PredictionSeamPreparation(executor);
        var surface = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0, 0, 2, 64));
        preparation.request(1, Map.of(surface.tile().key(), new PredictionSeamPreparation.Surface(null, null, null)), List.of());
        executor.finish();
        assertNull(preparation.poll(1)); assertFalse(preparation.pending());
        preparation.request(2, Map.of(surface.tile().key(), new PredictionSeamPreparation.Surface(
                surface.tile(), surface.allowed(), surface.allowed())), List.of());
        executor.finish(); assertNotNull(preparation.poll(2));
    }

    @Test void motionPreservesTheMeasuredWinnerAndSettlingSchedulesOneNewComparison() {
        var policy = new PredictionOcclusionPolicy(); var view = new Matrix4f();
        policy.chooseStable(view, 0, 0, 0, 512, 512, 100000, 7);
        long epoch = policy.epoch();
        for (int i = 0; i < 8; i++) policy.accept(epoch, true, 8_000_000);
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 6_000_000);
        assertFalse(policy.chooseStable(view, 0, 0, 0, 512, 512, 100000, 7));
        policy.chooseStable(new Matrix4f().rotateY(.1f), 0, 0, 0, 512, 512, 101000, 7);
        long movingEpoch = policy.epoch();
        for (int i = 2; i < 120; i++) {
            assertFalse(policy.chooseStable(new Matrix4f().rotateY(i * .1f), 0, 0, 0, 512, 512, 101000 + i, 7));
            assertFalse(policy.sample()); assertEquals(movingEpoch, policy.epoch(), "turns must not repeatedly clear comparison state");
        }
        view = new Matrix4f().rotateY(11.9f);
        for (int i = 0; i < 12; i++) policy.chooseStable(view, 0, 0, 0, 512, 512, 101119, 7);
        assertNotEquals(movingEpoch, policy.epoch()); assertTrue(policy.diagnostics().contains("measuring-direct"));
        long beforeMaterial = policy.epoch(); policy.chooseStable(view, 0, 0, 0, 512, 512, 101119, 8);
        assertNotEquals(beforeMaterial, policy.epoch());
    }

    @Test void distanceBandsDependOnBoundsAndCameraInsteadOfVisibleListLength() {
        int[] expected = {0, 1, 2, 3, 4, 5, 6, 7};
        for (int i = 0; i < expected.length; i++) {
            double x = i == 0 ? 32 : 256L << i - 1;
            var draw = new PredictionRenderer.Draw(null, null, null, false, 0, 0, new AABB(x, 60, 0, x + 64, 80, 64));
            assertEquals(expected[i], PredictionOpaqueBatches.distanceBucket(draw, 0, 0));
        }
    }

    @Test void movingTheMaskWindowKeepsUnchangedPerimeterSamplesAndDirtyNotifications() throws Exception {
        var mask = new PredictionVanillaMask();
        var reanchor = PredictionVanillaMask.class.getDeclaredMethod("reanchor", int.class, int.class, int.class, int.class, int.class);
        reanchor.setAccessible(true); reanchor.invoke(mask, 5, 3, -2, -2, -4);
        var compiledField = PredictionVanillaMask.class.getDeclaredField("compiledGround"); compiledField.setAccessible(true);
        var dirtyField = PredictionVanillaMask.class.getDeclaredField("edgeDirty"); dirtyField.setAccessible(true);
        Arrays.fill((boolean[]) compiledField.get(mask), true);
        var dirty = (boolean[]) dirtyField.get(mask); Arrays.fill(dirty, false); dirty[2 * 5 + 2] = true;
        reanchor.invoke(mask, 5, 3, -1, -2, -4);
        dirty = (boolean[]) dirtyField.get(mask);
        for (int z = 0; z < 5; z++) for (int x = 0; x < 5; x++)
            assertEquals(x == 0 || x == 4 || x == 1 && z == 2, dirty[z * 5 + x],
                    "only newly exposed boundaries and the existing dirty column need samples: " + x + "," + z);
    }
}
