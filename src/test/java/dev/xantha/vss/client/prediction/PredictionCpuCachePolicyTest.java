package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionCpuCachePolicyTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void coldBytesConvergeToLowWaterWithoutChurnAtHighWater() {
        var policy = new PredictionCpuCachePolicy(100, true);
        assertEquals(0, policy.reclaim(100)); assertEquals(26, policy.reclaim(101));
        assertEquals(15, policy.reclaim(90)); assertEquals(0, policy.reclaim(75));
        assertEquals(0, policy.reclaim(90), "a completed trim must not restart below high water");
    }
    @Test void protectedGroundAndDecorationNeedDrawableReplacementBeforeEviction() {
        long age = PredictionCpuCachePolicy.COLD_AGE_NANOS;
        assertTrue(PredictionCpuCachePolicy.safe(false, false, false, false, true, true, true, true, true, true, age));
        for (int protection = 0; protection < 9; protection++) {
            boolean safe = PredictionCpuCachePolicy.safe(protection == 0, protection == 1, protection == 2,
                    protection == 3, protection != 4, true, protection != 5, protection != 6,
                    true, protection != 7, protection == 8 ? age - 1 : age);
            assertFalse(safe, "protection " + protection);
        }
        assertTrue(PredictionCpuCachePolicy.safe(false, false, false, false, true, false, true, false, true, false, age));
    }
    @Test void byteTargetsLeaveRoomForOtherHeapUsersAndReservationReaccountsColdStorage() {
        assertTrue(new PredictionCpuCachePolicy(12L << 30).highBytes <= 768L << 20);
        assertTrue(new PredictionCpuCachePolicy(2L << 30).highBytes < 256L << 20);
        var budget = new PredictionMemoryBudget(1000, 0, () -> Long.MAX_VALUE, System::nanoTime);
        var reservation = budget.tryReserve(500); assertNotNull(reservation); assertTrue(reservation.retain(400));
        reservation.accountResident(100); assertEquals(100, budget.usedBytes());
        reservation.accountResident(400); assertEquals(400, budget.usedBytes());
        reservation.close(); assertEquals(0, budget.usedBytes()); reservation.accountResident(200); assertEquals(0, budget.usedBytes());
    }

    @Test void managerRetiresStoredColdDetailOnlyAfterGroundAndFeatureFallbackUpload() throws Exception {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
                "noise", "minecraft:overworld", 1L);
        var sampler = new ClientTerrainSampler(42, profile);
        var budget = PredictionMemoryBudget.adaptive(12L << 30, () -> 8L << 30, System::nanoTime, 1, () -> 0);
        try (var manager = new PredictionTileManager(profile.levelKey(), sampler, budget, null)) {
            var layout = VssLodLayout.of(4096, 6, true, false);
            field(manager, "layout").set(manager, layout);
            field(manager, "cpuCachePolicy").set(manager, new PredictionCpuCachePolicy(1, true));
            var child = tile(32, 0, 0, layout, true); var parent = tile(16, 0, 1, layout, false);
            @SuppressWarnings("unchecked") var ready = (java.util.Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field(manager, "ready").get(manager);
            @SuppressWarnings("unchecked") var stored = (java.util.Set<PredictionTileManager.PredictionTileKey>) field(manager, "storedTiles").get(manager);
            ready.put(child.key(), child); ready.put(parent.key(), parent); stored.add(child.key());
            var noParentUploaded = new PredictionTileManager.RenderSnapshot(profile.levelKey(), layout, java.util.Map.of(child.key(), child), java.util.Map.of());
            manager.setDrawableSnapshot(noParentUploaded); maintain(manager); assertSame(child, ready.get(child.key()));
            manager.setDrawableSnapshot(new PredictionTileManager.RenderSnapshot(profile.levelKey(), layout, java.util.Map.copyOf(ready), java.util.Map.of()));
            maintain(manager); assertSame(child, ready.get(child.key()), "ground fallback cannot remove the only feature coverage");
            parent.mesh().surfaceReadyForRendering = true;
            maintain(manager); assertFalse(ready.containsKey(child.key())); assertSame(parent, ready.get(parent.key()));
            assertEquals(0, manager.renderSnapshot().tiles().containsKey(child.key()) ? 1 : 0);
            assertTrue(manager.surfaceDiagnostics().contains("evictions=1"));
        }
    }
    private static void maintain(PredictionTileManager manager) throws Exception {
        field(manager, "nextCacheMaintenance").setLong(manager, 0);
        var method = PredictionTileManager.class.getDeclaredMethod("maintainCpuCache", int.class, int.class, long.class);
        method.setAccessible(true); method.invoke(manager, 0, 0, System.nanoTime());
    }
    private static java.lang.reflect.Field field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static PredictionTileManager.PredictionTile tile(int x, int z, int lod, VssLodLayout layout, boolean feature) {
        int[] words = PredictionPreviewHandoffTest.quad(1, 1, 64, 2, 2, 0, false);
        if (!feature) words[10] &= ~PredictionPackedMesh.FEATURE;
        var payload = PredictionPackedMesh.seamRecords(words, 64);
        var mesh = PredictionMesh.restored(6, 0, payload, null);
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, x, z, lod);
        return new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                mesh, new PredictionDepthBound(64, 80), System.nanoTime() - 60_000_000_000L, 1, 64, layout.sampleSpacing(lod));
    }
}
