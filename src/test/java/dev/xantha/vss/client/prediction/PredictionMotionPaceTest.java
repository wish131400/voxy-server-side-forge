package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

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
    void fastTravelUsesSpeedRatherThanSmallDisplacementAndSettlesAfterMovement() {
        long start = 10_000_000_000L;
        ResourceKey<Level> first = ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse("test:first"));
        ResourceKey<Level> second = ResourceKey.create(Registries.DIMENSION, ResourceLocation.tryParse("test:second"));
        PredictionMotionPace.record(first, 0, 0, false, start);
        PredictionMotionPace.record(second, 0, 0, false, start);
        assertFalse(PredictionMotionPace.fastMoving(start));

        PredictionMotionPace.record(second, 0.3, 0, false, start + 500_000_000L);
        assertFalse(PredictionMotionPace.fastMoving(start + 500_000_000L), "slow movement is not fast travel");
        PredictionMotionPace.record(second, 0.6, 0, true, start + 550_000_000L);
        assertTrue(PredictionMotionPace.fastMoving(start + 550_000_000L));
        assertFalse(PredictionMotionPace.fastMoving(start + 1_350_000_000L));
        assertEquals(1, PredictionMotionPace.upgradeBuildLimit(1));
        assertEquals(1, PredictionMotionPace.upgradeBuildLimit(4));
        assertEquals(2, PredictionMotionPace.upgradeBuildLimit(8));
        PredictionMotionPace.record(null, 0, 0, false, start + 2_000_000_000L);
    }

    @Test void continuousWalkingDoesNotExtendTheFastTravelWindow() {
        long start = 20_000_000_000L;
        PredictionMotionPace.record(null, 0, 0, false, start);
        PredictionMotionPace.record(Level.OVERWORLD, 0, 0, false, start);
        for (int tick = 1; tick <= 40; tick++) {
            long now = start + tick * 50_000_000L;
            PredictionMotionPace.record(Level.OVERWORLD, tick * 0.215, 0, false, now);
            assertFalse(PredictionMotionPace.fastMoving(now), "ordinary walking must not reduce refinement indefinitely");
        }
        PredictionMotionPace.record(null, 0, 0, false, start + 3_000_000_000L);
    }
}
