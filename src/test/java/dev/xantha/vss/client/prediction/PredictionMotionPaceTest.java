package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionMotionPaceTest {
    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void runningDefersCoveredUpgradesButNeverInitialCoverageOrDirtyRefresh() {
        long start = 10_000_000_000L;
        ResourceKey<Level> first = ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse("test:first"));
        ResourceKey<Level> second = ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse("test:second"));
        PredictionMotionPace.record(first, 0, 0, false, start);
        PredictionMotionPace.record(second, 0, 0, false, start);
        assertFalse(PredictionMotionPace.fastMoving(start));

        PredictionMotionPace.record(second, 0.3, 0, true, start + 50_000_000L);
        assertTrue(PredictionMotionPace.fastMoving(start + 50_000_000L));
        assertTrue(PredictionMotionPace.deferUpgrade(true, true, false, false, false));
        assertTrue(PredictionMotionPace.deferUpgrade(true, true, true, false, false));
        assertFalse(PredictionMotionPace.deferUpgrade(true, false, false, false, false));
        assertFalse(PredictionMotionPace.deferUpgrade(true, true, false, true, false));
        assertFalse(PredictionMotionPace.deferUpgrade(true, true, false, false, true));
        assertFalse(PredictionMotionPace.deferUpgrade(false, true, true, false, false));
        assertFalse(PredictionMotionPace.fastMoving(start + 850_000_000L));
    }
}
