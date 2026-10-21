package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionVisibilityFeedbackTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void hiddenParentCanLowerUnbuiltChildButVisibleChildAndContentChangesOverrideIt() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        var parent = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 2, 0, 3);
        var child = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 4, 0, 2);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1);
        var history = new PredictionVisibilityFeedback.History();
        long now = System.nanoTime();
        var snapshot = history.observe(List.of(new PredictionVisibilityFeedback.Stamp(parent, 7, 3)),
                new boolean[]{true}, view, now, now);
        var ready = new java.util.HashMap<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>();
        ready.put(parent, tile(parent, 7));
        var epochs = Map.of(parent, 3L);
        assertTrue(snapshot.hidden(child, layout, view, now, ready, epochs, Set.of()));
        assertFalse(snapshot.hidden(child, layout, view, now, ready, Map.of(parent, 4L), Set.of()));
        assertFalse(snapshot.hidden(child, layout, view, now, ready, epochs, Set.of(parent)));
        ready.put(parent, tile(parent, 8));
        assertFalse(snapshot.hidden(child, layout, view, now, ready, epochs, Set.of()));
        ready.put(parent, tile(parent, 7)); ready.put(child, tile(child, 9));
        snapshot = history.observe(List.of(new PredictionVisibilityFeedback.Stamp(child, 9, 0)),
                new boolean[]{false}, view, now + 1, now + 1);
        assertFalse(snapshot.hidden(child, layout, view, now + 1, ready, epochs, Set.of()),
                "visible child must win over hidden ancestor");
    }

    @Test void turnMotionExpiryAndOutOfOrderReadbackCannotHoldOldHiddenPriority() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 4, 0, 2);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1);
        var history = new PredictionVisibilityFeedback.History();
        long now = System.nanoTime();
        var stamp = new PredictionVisibilityFeedback.Stamp(key, 7, 0);
        var ready = Map.of(key, tile(key, 7));
        var snapshot = history.observe(List.of(stamp), new boolean[]{true}, view, now, now);
        assertTrue(snapshot.hidden(key, layout, view, now, ready, Map.of(), Set.of()));
        assertFalse(snapshot.hidden(key, layout, PredictionWorkView.of(0, 80, 0, -1, 0, 0, 70, 1),
                now, ready, Map.of(), Set.of()));
        assertFalse(snapshot.hidden(key, layout, PredictionWorkView.of(16, 80, 0, 1, 0, 0, 70, 1),
                now, ready, Map.of(), Set.of()));
        assertFalse(snapshot.hidden(key, layout, view, now + PredictionVisibilityFeedback.MAX_AGE_NANOS + 1,
                ready, Map.of(), Set.of()));
        history.observe(List.of(stamp), new boolean[]{false}, view, now + 10, now + 10);
        snapshot = history.observe(List.of(stamp), new boolean[]{true}, view, now + 5, now + 11);
        assertFalse(snapshot.hidden(key, layout, view, now + 11, ready, Map.of(), Set.of()),
                "older hidden readback cannot overwrite a newer exposed result");
    }

    private static PredictionTileManager.PredictionTile tile(PredictionTileManager.PredictionTileKey key, long revision) {
        return new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                null, new PredictionDepthBound(64, 64), 0, revision, 8, (64 << key.lod()) / 8);
    }
}
