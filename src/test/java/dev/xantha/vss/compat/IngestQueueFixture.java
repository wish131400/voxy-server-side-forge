package dev.xantha.vss.compat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Small executable queue fixture; the external-JAR test also executes the original enqueue method. */
public class IngestQueueFixture {
    public final ConcurrentLinkedDeque<IngestNode> ingestQueue = new ConcurrentLinkedDeque<>();
    public final ConcurrentHashMap<IngestKey, IngestSection> pending = new ConcurrentHashMap<>();
    public final AtomicInteger queuedNodes = new AtomicInteger();
    public final Service service = new Service();
    public int processed;

    public record IngestKey(WorldEngine world, int cx, int cy, int cz) { }
    public record IngestNode(IngestKey key, Object section) { }
    public record IngestSection(int cx, int cy, int cz, WorldEngine world,
                               Object chunk, Object section, Object blockLight, Object skyLight) { }
    public static class WorldEngine {
        public int refs;
        public void acquireRef() { refs++; }
        public void releaseRef() { refs--; }
    }
    public static class Service {
        public int executions;
        public boolean fail;
        public void execute() { executions++; if (fail) throw new IllegalStateException("scheduler"); }
    }
    public static class PerfStats {
        public static final LongAdder ingestOverflowDropped = new LongAdder();
        public static final LongAdder ingestSuperseded = new LongAdder();
        public static final LongAdder ingestEnqueued = new LongAdder();
    }
    public static class Logger { public static void error(Object[] ignored) { } }

    public boolean enqueueJob(WorldEngine world, IngestSection task) {
        if (queuedNodes.get() >= 4) return true;
        world.acquireRef();
        IngestKey key = new IngestKey(world, task.cx(), task.cy(), task.cz());
        IngestSection previous = pending.put(key, task);
        if (previous != null) previous.world().releaseRef();
        else if (pending.size() > 2) {
            IngestNode oldest = ingestQueue.peekFirst();
            IngestSection evicted = oldest == null ? null : pending.remove(oldest.key());
            if (evicted == null) evicted = pending.remove(key);
            if (evicted != null) evicted.world().releaseRef();
        }
        queuedNodes.incrementAndGet();
        ingestQueue.add(new IngestNode(key, task.section()));
        try { service.execute(); return true; }
        catch (Exception shutdown) { return false; }
    }

    public void processJob() {
        IngestNode node;
        try { node = ingestQueue.pop(); }
        catch (java.util.NoSuchElementException empty) { return; }
        queuedNodes.decrementAndGet();
        IngestSection task = pending.remove(node.key());
        if (task == null) return;
        Object section = task.section;
        try {
            if (section instanceof RuntimeException failure) throw failure;
        } finally {
            task.world().releaseRef();
            processed++;
        }
        // Mirrors the normal RETURN callback; exceptional exits cannot reach it.
        VoxyIngestTracker.workerDone();
    }
}
