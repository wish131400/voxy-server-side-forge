package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionGpuSceneTest {
    @Test void deletingAndReusingAnIntervalNeverMovesItsNeighbours() {
        var slots = new PredictionGpuScene.SlotAllocator(16);
        assertEquals(0, slots.allocate(4));
        assertEquals(4, slots.allocate(4));
        assertEquals(8, slots.allocate(8));
        assertEquals(-1, slots.allocate(1));
        slots.release(4, 4);
        assertEquals(4, slots.allocate(2));
        assertEquals(6, slots.allocate(2));
        assertEquals(16, slots.extent());
        slots.release(8, 8);
        assertEquals(8, slots.extent());
        assertEquals(8, slots.allocate(8));
    }

    @Test void fragmentedSceneIntervalsStayAlignedAndDisjointThroughChurn() {
        var slots = new PredictionGpuScene.SlotAllocator(512);
        var live = new ArrayList<int[]>();
        var random = new Random(0x51534c);
        for (int operation = 0; operation < 5000; operation++) {
            if (!live.isEmpty() && random.nextInt(3) == 0) {
                var removed = live.remove(random.nextInt(live.size()));
                slots.release(removed[0], removed[1]);
            } else {
                int count = 1 << random.nextInt(7), first = slots.allocate(count);
                if (first >= 0) {
                    assertEquals(0, first % count);
                    assertTrue(first + count <= 512);
                    for (var interval : live)
                        assertTrue(first + count <= interval[0] || first >= interval[0] + interval[1]);
                    live.add(new int[]{first, count});
                }
            }
            int extent = 0;
            for (var interval : live) extent = Math.max(extent, interval[0] + interval[1]);
            assertEquals(extent, slots.extent());
        }
        for (var interval : live) slots.release(interval[0], interval[1]);
        assertEquals(0, slots.extent());
        assertEquals(0, slots.allocate(512));
    }

    @Test void rejectsUnboundedAndUnalignedReservationSizes() {
        var slots = new PredictionGpuScene.SlotAllocator(32);
        for (int size : new int[]{-1, 0, 3, 33, 64})
            assertThrows(IllegalArgumentException.class, () -> slots.allocate(size));
    }
}
