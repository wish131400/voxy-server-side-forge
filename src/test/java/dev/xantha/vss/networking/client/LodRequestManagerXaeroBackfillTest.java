package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.xantha.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class LodRequestManagerXaeroBackfillTest {
    @Test
    void cacheMissKeepsKnownVssPresence() throws Exception {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long packed = PositionUtil.packPosition(12, -4);
        replaySet(manager).add(packed);
        timestamps(manager).put(packed, 123L);

        int requestId = tracker.track(packed, false, true, false, 1_000_000_000L, 0L);
        manager.onColumnNotGenerated(requestId);

        assertEquals(123L, timestamps(manager).get(packed));
        assertFalse(replaySet(manager).contains(packed));
    }

    @Test
    void cacheMissMarksUnknownLocalColumnWithoutSchedulingGeneration() throws Exception {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long packed = PositionUtil.packPosition(-8, 17);
        replaySet(manager).add(packed);

        int requestId = tracker.track(packed, false, true, false, 1_000_000_000L, 0L);
        manager.onColumnNotGenerated(requestId);

        assertEquals(0L, timestamps(manager).get(packed));
        assertFalse(replaySet(manager).contains(packed));
    }

    @SuppressWarnings("unchecked")
    private static LongOpenHashSet replaySet(LodRequestManager manager) throws Exception {
        Field field = LodRequestManager.class.getDeclaredField("xaeroReplayColumns");
        field.setAccessible(true);
        return (LongOpenHashSet) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Long2LongOpenHashMap timestamps(LodRequestManager manager) throws Exception {
        Field field = LodRequestManager.class.getDeclaredField("columnTimestamps");
        field.setAccessible(true);
        return (Long2LongOpenHashMap) field.get(manager);
    }
}
