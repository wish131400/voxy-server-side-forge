package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
