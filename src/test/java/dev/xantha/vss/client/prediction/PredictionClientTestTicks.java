package dev.xantha.vss.client.prediction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Tests drive later ticks after background control work; production never waits. */
final class PredictionClientTestTicks {
    static void awaitPreparation(PredictionTileManager manager) {
        try {
            for (var name : new String[]{"planPreparation", "demandPreparation", "workPreparation", "loadingPreparation"}) {
                var preparation = PredictionPlanningTestSupport.get(manager, name);
                var pending = preparation.getClass().getDeclaredField("pending"); pending.setAccessible(true);
                var future = (CompletableFuture<?>) pending.get(preparation);
                if (future != null) future.get(10, TimeUnit.SECONDS);
            }
        } catch (Exception failure) { throw new AssertionError("client preparation did not complete", failure); }
    }
    static void plan(PredictionTileManager manager, double x, double y, double z, double pixels) {
        for (int i = 0; i < 5; i++) {
            manager.tick(x, y, z, pixels, null); awaitPreparation(manager);
        }
    }
}
