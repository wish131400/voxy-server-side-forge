package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class XaeroMapCompatQueueTest {
    @Test
    void intakePausesAtSoftWatermarkBeforeTheHardQueueCanDropColumns() {
        var bridge = bridge();
        for (int i = 0; i < XaeroMapCompat.INTAKE_QUEUE_HIGH_WATERMARK; i++) {
            bridge.offerPrepared("dimension", tile(i, 0));
        }

        assertTrue(bridge.shouldBackpressureInputNow());
        assertTrue(bridge.counterForTest("dropped_overflow") == 0L);

        bridge.clearQueue();
        assertFalse(bridge.shouldBackpressureInputNow());
        assertFalse(bridge.hasPendingWorkNow());
    }

    @Test
    void realReplayRejectsTheSoftLimitWithoutEvictingAcceptedColumns() {
        var bridge = bridge();
        for (int i = 0; i < XaeroMapCompat.INTAKE_QUEUE_HIGH_WATERMARK; i++)
            bridge.offerPrepared("dimension", tile(i, 0));
        assertFalse(bridge.tryOfferReplay("dimension", tile(100_000, 0)));
        assertTrue(bridge.hasQueuedForTest(0, 0));
        assertFalse(bridge.hasQueuedForTest(100_000, 0));
        assertEquals(0, bridge.counterForTest("dropped_overflow"));
        bridge.clearQueue();
        assertTrue(bridge.tryOfferReplay("dimension", tile(100_000, 0)));
        assertTrue(bridge.hasQueuedForTest(100_000, 0));
    }

    @Test
    void disabledReplayDoesNotLeaveAWaitingPregenJob() {
        var bridge = new XaeroMapCompat(null, null, () -> false, () -> true,
                new ArrayList<>()::add, new ArrayList<>()::remove);
        assertTrue(bridge.tryOfferReplay("dimension", tile(1, 2)));
        assertEquals(0, bridge.queuedForTest());
    }

    private static XaeroMapCompat bridge() {
        var enabled = new AtomicBoolean(true);
        return new XaeroMapCompat(null, null, enabled::get, () -> true,
                new ArrayList<>()::add, new ArrayList<>()::remove);
    }

    @SuppressWarnings("unchecked")
    private static XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        return new XaeroTileExtractor.PreparedTile(
                chunkX, chunkZ, -64,
                new net.minecraft.world.level.block.state.BlockState[256],
                new short[256], new short[256],
                (net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>[])
                        new net.minecraft.resources.ResourceKey<?>[256],
                new byte[256], new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }
}
