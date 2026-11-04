package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** World-owned immutable columns, shared by LODs. A cancelled query never becomes a cached answer. */
final class FreeTerraForgedSamples implements AutoCloseable {
    private final int capacity;
    private final LinkedHashMap<Long, ClientColumnSample> answers = new LinkedHashMap<>(256, .75f, true);
    private final ConcurrentHashMap<Long, CompletableFuture<ClientColumnSample>> pending = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder(), loads = new LongAdder(), waits = new LongAdder();
    private boolean closed;

    FreeTerraForgedSamples(int capacity) { this.capacity = capacity; }

    ClientColumnSample get(int x, int z, Supplier<ClientColumnSample> query) {
        long key = (long) x << 32 | z & 0xffffffffL;
        synchronized (answers) {
            check();
            var cached = answers.get(key);
            if (cached != null) { hits.increment(); return cached; }
        }
        var future = new CompletableFuture<ClientColumnSample>();
        var previous = pending.putIfAbsent(key, future);
        if (previous != null) {
            waits.increment();
            try { return previous.join(); }
            catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw failure;
            }
        }
        try {
            // An answer may have been published between the first lookup and becoming the owner.
            ClientColumnSample result;
            synchronized (answers) { check(); result = answers.get(key); }
            if (result == null) { result = query.get(); loads.increment(); }
            synchronized (answers) {
                check(); answers.put(key, result);
                while (answers.size() > capacity) answers.remove(answers.keySet().iterator().next());
            }
            future.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            future.completeExceptionally(failure); throw failure;
        } finally { pending.remove(key, future); }
    }

    private void check() {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("FTF column query cancelled");
    }
    String diagnostics() {
        synchronized (answers) {
            return "entries=" + answers.size() + ",capacity=" + capacity + ",hits=" + hits.sum()
                    + ",loads=" + loads.sum() + ",waits=" + waits.sum();
        }
    }
    @Override public void close() {
        synchronized (answers) { closed = true; answers.clear(); }
        pending.forEach((key, future) -> future.completeExceptionally(new CancellationException("FTF sampler closed")));
    }
}
