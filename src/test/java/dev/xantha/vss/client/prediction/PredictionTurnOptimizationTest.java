package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

class PredictionTurnOptimizationTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static PredictionTileManager manager() {
        var profile = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
                42, -64, 384, "noise", "minecraft:overworld", 1);
        return new PredictionTileManager(Level.OVERWORLD, ClientTerrainSampler.custom(42, profile, (x, z) -> 64),
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime),
                null, PredictionCpuBudget.fixed(1));
    }
    private static PredictionTileKey key(int x, int z, int lod) {
        return new PredictionTileKey(Level.OVERWORLD, x, z, lod);
    }
    private static PredictionTile tile(PredictionTileKey key, long revision, boolean scoped) {
        return new PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0], null,
                new PredictionDepthBound(64, 64), 0, revision, 8, (64 << key.lod()) / 8, scoped);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(target, args);
    }
    @SuppressWarnings("unchecked") private static Map<PredictionTileKey, PredictionTile> ready(PredictionTileManager manager)
            throws Exception { return (Map<PredictionTileKey, PredictionTile>) field(manager, "ready"); }

    @Test void localMeshAndEpochPublicationSharesPagesAndKeepsOldFramesImmutable() throws Exception {
        try (var manager = manager()) {
            var ready = ready(manager);
            for (int x = -32; x < 32; x++) for (int z = -16; z < 16; z++)
                ready.put(key(x, z, 0), tile(key(x, z, 0), 1, false));
            var original = manager.renderSnapshot();
            assertInstanceOf(PredictionTileTable.class, original.tiles());
            var changedKey = key(-4, -3, 0);
            var previous = ready.get(changedKey);
            var replacement = tile(changedKey, 2, false);
            ready.put(changedKey, replacement); manager.markCoverageDirty(changedKey);
            var updated = manager.renderSnapshot();
            assertSame(previous, original.tiles().get(changedKey));
            assertSame(replacement, updated.coveringTile(-16, -12, 0));
            assertEquals(0, original.epoch(changedKey)); assertEquals(1, updated.epoch(changedKey));
            Set<PredictionTileKey> changes = new HashSet<>(); updated.forEachChangedTile(original, changes::add);
            assertEquals(Set.of(changedKey), changes);
            Object[] oldPages = (Object[]) field(original.tiles(), "pages");
            Object[] newPages = (Object[]) field(updated.tiles(), "pages");
            long replacedPages = java.util.stream.IntStream.range(0, oldPages.length)
                    .filter(i -> oldPages[i] != newPages[i]).count();
            assertEquals(1, replacedPages, "one changed mesh must copy only its page");

            manager.markCoverageDirty(changedKey);
            var epochOnly = manager.renderSnapshot();
            assertSame(updated.tiles(), epochOnly.tiles(), "an epoch-only change must share the whole mesh table");
            assertEquals(2, epochOnly.epoch(changedKey)); assertEquals(1, updated.epoch(changedKey));
            call(manager, "removeTile", new Class<?>[]{PredictionTileKey.class}, changedKey);
            var removed = manager.renderSnapshot();
            assertFalse(removed.tiles().containsKey(changedKey)); assertSame(replacement, updated.tiles().get(changedKey));
            assertEquals(3, removed.epoch(changedKey), "withdrawal and its new epoch must be published together");
            assertThrows(UnsupportedOperationException.class, () -> removed.tiles().put(changedKey, previous));
        }
    }

    @Test void scopedPublicationAndRemovalUpdateFamiliesWithoutScanningAnOrdinaryFrame() throws Exception {
        try (var manager = manager()) {
            var fineKey = key(-2, -2, 0); var parentKey = key(-1, -1, 1);
            ready(manager).put(parentKey, tile(parentKey, 1, false));
            var ordinary = manager.renderSnapshot(); assertFalse(ordinary.hasScopedTiles());
            ready(manager).put(fineKey, tile(fineKey, 2, true)); manager.markCoverageDirty(fineKey);
            var focused = manager.renderSnapshot();
            assertTrue(focused.hasScopedTiles()); assertTrue(focused.scopeAffects(parentKey));
            call(manager, "removeTile", new Class<?>[]{PredictionTileKey.class}, fineKey);
            assertFalse(manager.renderSnapshot().hasScopedTiles()); assertTrue(focused.hasScopedTiles());
        }
    }

    @Test void turnReusesDependencyPathsAndMembershipChangesRebuildThem() throws Exception {
        try (var manager = manager()) {
            @SuppressWarnings("unchecked") var desired = (Set<PredictionTileKey>) field(manager, "desiredKeys");
            var front = key(4, 0, 1); var back = key(-4, 0, 1);
            desired.addAll(List.of(front, back, key(2, 0, 2), key(-2, 0, 2)));
            manager.setWorkView(PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1));
            PredictionDemandTestSupport.refresh(manager);
            var paths = PredictionDemandTestSupport.paths(manager);
            manager.setWorkView(PredictionWorkView.of(0, 80, 0, -1, 0, 0, 70, 1));
            PredictionDemandTestSupport.refresh(manager);
            assertSame(paths, PredictionDemandTestSupport.paths(manager));
            @SuppressWarnings("unchecked") var dependencies = (Set<PredictionTileKey>) field(manager, "viewDependencies");
            assertTrue(dependencies.contains(back)); assertTrue(dependencies.contains(key(-2, 0, 2)));
            assertFalse(dependencies.contains(front));
            var neighbour = key(-3, 0, 1); desired.add(neighbour);
            PredictionDemandTestSupport.refresh(manager);
            assertNotSame(paths, PredictionDemandTestSupport.paths(manager));
            @SuppressWarnings("unchecked") var updated = (Map<PredictionTileKey, List<PredictionTileKey>>) PredictionDemandTestSupport.paths(manager);
            assertTrue(updated.get(back).contains(neighbour));
        }
    }

    @Test void refreshedIdenticalHiddenHintsDoNotReorderAndExpiryRevokesTheirPriority() throws Exception {
        try (var manager = manager()) {
            var key = key(4, 0, 2); ready(manager).put(key, tile(key, 7, false));
            @SuppressWarnings("unchecked") var desired = (Set<PredictionTileKey>) field(manager, "desiredKeys"); desired.add(key);
            var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1); manager.setWorkView(view);
            var history = new PredictionVisibilityFeedback.History(); long now = System.nanoTime();
            var stamp = new PredictionVisibilityFeedback.Stamp(key, 7, 0);
            manager.setVisibilityFeedback(history.observe(List.of(stamp), new boolean[]{true}, view, now, now));
            assertEquals(true, PredictionDemandTestSupport.hiddenChanged(manager, now));
            manager.setVisibilityFeedback(history.observe(List.of(stamp), new boolean[]{true}, view, now + 1, now + 1));
            assertEquals(false, PredictionDemandTestSupport.hiddenChanged(manager, now + 1));
            assertEquals(true, PredictionDemandTestSupport.hiddenChanged(manager, now + PredictionVisibilityFeedback.MAX_AGE_NANOS + 2));
            manager.setVisibilityFeedback(history.observe(List.of(stamp), new boolean[]{true}, view, now + 3, now + 3));
            assertEquals(true, PredictionDemandTestSupport.hiddenChanged(manager, now + 3));
            manager.markCoverageDirty(key);
            assertEquals(true, PredictionDemandTestSupport.hiddenChanged(manager, now + 4));
        }
    }

    @Test void planningTestsEachCandidatesOcclusionOnceAndPreservesThePlan() {
        var layout = VssLodLayout.of(8192, 6, true, false);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9);
        Map<PredictionTileKey, Integer> calls = new HashMap<>();
        var actual = PredictionLodPlanner.plan(Level.OVERWORLD, 0, 80, 0, layout, null, 1000, -64, 384, 0,
                key -> false, key -> false, view, key -> { calls.merge(key, 1, Integer::sum); return key.tileZ() < 0; });
        var expected = PredictionLodPlanner.plan(Level.OVERWORLD, 0, 80, 0, layout, null, 1000, -64, 384, 0,
                key -> false, key -> false, view, key -> key.tileZ() < 0);
        assertEquals(expected, actual); assertFalse(calls.isEmpty());
        assertTrue(calls.values().stream().allMatch(n -> n == 1), "heap comparisons must not resample the same hint");
    }
}
