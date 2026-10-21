package dev.xantha.vss.networking.server.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkyWorkQueueTest {
    @Test void lazyCursorAdmitsMoreThanTheRemovedLimitsWithoutWaitingForCompletion() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 2047, 2047));
        assertEquals(0, queue.inFlightCount());
        for (int i = 0; i < 5000; i++) assertNotNull(queue.reserve());
        assertEquals(5000, queue.inFlightCount());
        assertEquals(0L, queue.processed());
        assertFalse(queue.allReserved());
        assertFalse(queue.finished());
    }

    @Test void pendingWritesDoNotBlockNewPositionsAndCompletionRemainsIdempotent() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(-1, -1, 0, 0));
        var first = queue.reserve();
        first.stage = ChunkyWorkQueue.Stage.WRITING;
        var next = queue.reserve();
        assertNotNull(next);
        assertNotEquals(first.requestId, next.requestId);
        assertEquals(0L, queue.completed());
        queue.finish(first, true);
        queue.finish(first, true);
        assertEquals(1L, queue.completed());
        assertEquals(1, queue.inFlightCount());
        assertFalse(queue.finished());
    }

    @Test void parallelReadersClaimEveryPositionExactlyOnce() throws Exception {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(-32, -32, 31, 31));
        var positions = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                ChunkyWorkQueue.Work work;
                while ((work = queue.reserve()) != null) {
                    assertTrue(positions.add(work.requestId));
                    queue.finish(work, true);
                }
            }));
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(4096, positions.size());
            assertEquals(4096, queue.completed());
            assertTrue(queue.finished());
        } finally { executor.shutdownNow(); }
    }

    @Test void cancellationClosesTheCursorAndDiscardsLateCallbacks() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 2047, 2047));
        var work = queue.reserve();
        queue.close();
        assertNull(queue.reserve());
        assertNull(queue.get(work.requestId));
        assertEquals(0, queue.inFlightCount());
        queue.finish(work, true);
        assertEquals(0, queue.processed());
    }

    @Test void failedAndReusedColumnsAreAccountedSeparatelyAndFinishRequiresDrain() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 1, 0));
        var first = queue.reserve();
        var second = queue.reserve();
        assertTrue(queue.allReserved());
        assertFalse(queue.finished());
        first.reused = true;
        queue.finish(first, true);
        queue.finish(second, false);
        assertTrue(queue.finished());
        assertEquals(1L, queue.completed());
        assertEquals(1L, queue.reused());
        assertEquals(1L, queue.failed());
        assertEquals(2L, queue.processed());
        assertNull(queue.reserve());
    }

    @Test void fullQueueKeepsItsCursorAndRefillsOnlyAfterFinalCompletion() {
        var queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 3, 0), () -> 1);
        var first = queue.reserve();
        assertNotNull(first);
        assertFalse(queue.hasCapacity());
        assertFalse(queue.allReserved());
        assertFalse(queue.finished());
        for (var stage : ChunkyWorkQueue.Stage.values()) {
            first.stage = stage;
            assertNull(queue.reserve(), stage.toString());
            assertEquals(1, queue.inFlightCount());
        }
        queue.finish(first, true);
        assertTrue(queue.hasCapacity());
        var second = queue.reserve();
        assertEquals(2, second.requestId);
        assertEquals(1, second.chunkX);
        queue.finish(first, true); // A duplicate callback must not release another slot.
        assertNull(queue.reserve());
        queue.finish(second, false);
        for (int x = 2; x < 4; x++) {
            var next = queue.reserve();
            assertNotNull(next);
            assertEquals(x, next.chunkX);
            queue.finish(next, true);
        }
        assertTrue(queue.finished());
        assertEquals(3, queue.completed());
        assertEquals(1, queue.failed());
    }

    @Test void concurrentReadersCannotOverrunTheQueueLimit() throws Exception {
        var queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 63, 0), () -> 5);
        var work = new java.util.concurrent.ConcurrentLinkedQueue<ChunkyWorkQueue.Work>();
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(12);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                start.await();
                ChunkyWorkQueue.Work claimed;
                while ((claimed = queue.reserve()) != null) work.add(claimed);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(5, work.size());
            assertEquals(5, queue.inFlightCount());
            assertEquals(5, work.stream().map(item -> item.requestId).distinct().count());
            assertFalse(queue.allReserved());
            for (var item : work) queue.finish(item, true);
            assertTrue(queue.hasCapacity());
            assertEquals(6, queue.reserve().requestId);
        } finally { executor.shutdownNow(); }
    }

    @Test void loweringTheLimitDrainsExistingWorkAndZeroRestoresUnlimitedAdmission() {
        var limit = new java.util.concurrent.atomic.AtomicInteger(3);
        var queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 7, 0), limit::get);
        var first = queue.reserve();
        var second = queue.reserve();
        var third = queue.reserve();
        limit.set(1);
        assertNull(queue.reserve());
        queue.finish(first, true);
        queue.finish(second, true);
        assertNull(queue.reserve()); // Existing work survives a lower limit.
        assertSame(third, queue.get(third.requestId));
        queue.finish(third, true);
        assertNotNull(queue.reserve());
        limit.set(0);
        for (int i = 0; i < 4; i++) assertNotNull(queue.reserve());
        assertTrue(queue.allReserved());
        assertEquals(5, queue.inFlightCount());
        queue.close();
        assertFalse(queue.hasCapacity());
        assertNull(queue.reserve());
    }
}
