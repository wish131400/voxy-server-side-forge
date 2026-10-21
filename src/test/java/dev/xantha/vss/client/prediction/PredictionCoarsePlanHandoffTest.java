package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCoarsePlanHandoffTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void settledDiskDataCannotRetireThePlannedCoarseFallback() throws Exception {
        var indexField = ClientPredictionState.class.getDeclaredField("exactCoverage"); indexField.setAccessible(true);
        var index = (PredictionExactCoverageIndex) indexField.get(null); index.clear();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(), 42, -64, 384, "noise", "minecraft:overworld", 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD,
                ClientTerrainSampler.custom(42, profile, (x, z) -> 64),
                new PredictionMemoryBudget(256 * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), null)) {
            var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            int span = manager.layout().tileBlocks(0), chunks = span / 16;
            field("cameraBlockX").setDouble(manager, span / 2.0);
            field("cameraBlockY").setDouble(manager, 128);
            field("cameraBlockZ").setDouble(manager, span / 2.0);
            long settled = System.nanoTime() - ExactCoverageGate.SETTLE_NANOS;
            for (int z = -1; z <= chunks; z++) for (int x = -1; x <= chunks; x++)
                index.confirm(Level.OVERWORLD, x, z, settled);
            var tile = PredictionCoverageWorkTest.tile(0, 0, 0, manager.layout());
            @SuppressWarnings("unchecked") var ready = (Map<PredictionTileKey, PredictionTile>) field("ready").get(manager);
            ready.put(key, tile);
            assertFalse(PredictionDemandTestSupport.fullyVoxyOwned(manager, key, 8192),
                    "stored columns cannot remove a coarse tile before real GPU coverage exists");
            var uploads = PredictionCoarseHandoffTest.ready(); uploads.maxY = 319;
            field("voxyUploads").set(manager, uploads);
            assertTrue(PredictionDemandTestSupport.fullyVoxyOwned(manager, key, 8192));
            PredictionDemandTestSupport.refreshVoxyOwnership(manager, List.of(key), 512);
            assertSame(tile, ready.get(key), "ownership stops new work without destroying completed fallback content");
            assertTrue(manager.renderSnapshot().voxyOwned().contains(key));
            uploads.present(false);
            var refresh = PredictionTileManager.class.getDeclaredMethod("refreshUploadedOwnership"); refresh.setAccessible(true);
            assertEquals(true, refresh.invoke(manager));
            assertFalse(manager.renderSnapshot().voxyOwned().contains(key), "upload withdrawal revokes published ownership immediately");
            assertSame(tile, manager.renderSnapshot().tiles().get(key));
            uploads.present(true);
            var input = PredictionDemandTestSupport.planningInput(manager, 512);
            var result = PredictionPlanningTestSupport.buildPlan(input);
            assertTrue(result.owned().contains(key));
            var preparation = new PredictionClientPreparation<PredictionPlanPreparation.Input, PredictionPlanPreparation.Result>(
                    Runnable::run, ignored -> result);
            PredictionPlanningTestSupport.set(manager, "planPreparation", preparation); assertTrue(preparation.request(input));
            uploads.present(false);
            var publish = PredictionTileManager.class.getDeclaredMethod("publishPlan", long.class); publish.setAccessible(true);
            long discarded = (long) PredictionPlanningTestSupport.get(manager, "planDiscards");
            assertEquals(false, publish.invoke(manager, System.nanoTime()));
            assertEquals(discarded + 1, (long) PredictionPlanningTestSupport.get(manager, "planDiscards"), "worker proof is checked again on handoff");
            assertSame(tile, ready.get(key));
        } finally { index.clear(); }
    }

    @Test void oldOwnedSnapshotCannotDeleteGpuFallbackAfterVoxyReset() {
        var layout = VssLodLayout.of(8192, 6, true, true);
        var tile = PredictionCoverageWorkTest.tile(0, 0, 0, layout);
        var parent = PredictionCoverageWorkTest.tile(0, 0, 1, layout);
        var state = new PredictionRenderResidency();
        state.retain(new RenderSnapshot(Level.OVERWORLD, layout, Map.of(tile.key(), tile), Map.of()));
        state.uploaded(tile);
        state.retain(new RenderSnapshot(Level.OVERWORLD, layout,
                Map.of(parent.key(), parent), Map.of(), null, Set.of(tile.key())));
        assertTrue(state.contains(tile), "an old owned bit is not a replacement after Voxy loses its GPU scene");
    }

    static Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
