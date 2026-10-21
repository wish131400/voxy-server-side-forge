package dev.xantha.vss.client.prediction;

import java.util.IdentityHashMap;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded background cold storage. Restore requests have priority over compression. */
final class LegacyMetadataCache {
    static final long LIMIT_BYTES = 64L * 1024 * 1024;
    static final LegacyMetadataCache SHARED = new LegacyMetadataCache(LIMIT_BYTES, 16,
            new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "vss-metadata-cache"); thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            }), () -> { Runtime r = Runtime.getRuntime(); return r.maxMemory() - r.totalMemory() + r.freeMemory(); });
    private final long limit;
    private final int maxJobs;
    private final Executor executor;
    private final LongSupplier freeHeap;
    private final IdentityHashMap<LegacyOwnershipRuns, Job> pending = new IdentityHashMap<>();
    private long bytes, epoch, sequence, freezes, restores, savedBytes, deferrals;

    LegacyMetadataCache(long limit, int maxJobs, Executor executor, LongSupplier freeHeap) {
        this.limit = limit; this.maxJobs = maxJobs; this.executor = executor; this.freeHeap = freeHeap;
    }
    void cool(LegacyOwnershipRuns owner, LegacyOwnershipData data, long version) {
        submit(owner, data, null, version);
    }
    void restore(LegacyOwnershipRuns owner, PredictionMeshCompression.Blob blob) {
        submit(owner, null, blob, 0);
    }
    private synchronized void submit(LegacyOwnershipRuns owner, LegacyOwnershipData data,
                                     PredictionMeshCompression.Blob blob, long version) {
        if (pending.containsKey(owner)) return;
        long raw = data != null ? data.encodedBytes() : blob.rawBytes();
        if (raw < PredictionMeshCompression.MIN_BYTES || raw > PredictionMeshCompression.MAX_BYTES) return;
        long workspace = raw * 3;
        if (pending.size() >= maxJobs || workspace > limit - bytes || freeHeap.getAsLong() < workspace + 128L * 1024 * 1024) {
            deferrals++; return;
        }
        Job job = new Job(owner, data, blob, version, epoch, sequence++, workspace);
        pending.put(owner, job); bytes += workspace;
        try { executor.execute(job); }
        catch (RejectedExecutionException full) { finish(job); deferrals++; }
    }
    private final class Job implements Runnable, Comparable<Job> {
        final LegacyOwnershipRuns owner;
        final LegacyOwnershipData data;
        final PredictionMeshCompression.Blob blob;
        final long version, generation, order, workspace;
        Job(LegacyOwnershipRuns owner, LegacyOwnershipData data, PredictionMeshCompression.Blob blob,
            long version, long generation, long order, long workspace) {
            this.owner = owner; this.data = data; this.blob = blob; this.version = version;
            this.generation = generation; this.order = order; this.workspace = workspace;
        }
        public int compareTo(Job other) {
            int priority = Boolean.compare(blob == null, other.blob == null);
            return priority == 0 ? Long.compare(order, other.order) : priority;
        }
        public void run() {
            try {
                synchronized (LegacyMetadataCache.this) { if (generation != epoch) return; }
                if (blob != null) {
                    var restored = LegacyOwnershipData.decode(blob.restore());
                    synchronized (LegacyMetadataCache.this) {
                        if (generation == epoch && owner.restore(blob, restored)) restores++;
                    }
                } else {
                    var packed = PredictionMeshCompression.compress(data.encode());
                    if (packed == null) { owner.uncompressible(version); return; }
                    synchronized (LegacyMetadataCache.this) {
                        if (generation == epoch && owner.freeze(data, packed, version)) {
                            freezes++; savedBytes += Math.max(0, data.retainedBytes() - packed.bytes().length);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                dev.xantha.vss.common.VSSLogger.error("VSS ownership metadata cache failed", failure);
            } finally { finish(this); }
        }
    }
    private synchronized void finish(Job job) {
        if (pending.get(job.owner) == job) { pending.remove(job.owner); bytes -= job.workspace; }
    }
    synchronized void clear() {
        epoch++;
        if (executor instanceof ThreadPoolExecutor pool) for (Runnable task : pool.getQueue().toArray(Runnable[]::new)) {
            if (task instanceof LegacyMetadataCache.Job job && pool.remove(task)) finish(job);
        }
        // Running jobs release their own promises and cannot publish into the new epoch.
    }
    synchronized long pendingBytes() { return bytes; }
    synchronized int pendingJobs() { return pending.size(); }
    synchronized String diagnostics() {
        return "jobs=" + pending.size() + ",workspaceBytes=" + bytes + ",freezes=" + freezes
                + ",restores=" + restores + ",savedBytes=" + savedBytes + ",deferrals=" + deferrals;
    }
}
