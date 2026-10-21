package dev.xantha.vss.client.prediction;

import java.util.IdentityHashMap;
import java.util.concurrent.*;

/** Busy publishing workers leave one bounded retry; render uploads never wait for compression. */
final class PredictionCompressionRetry {
    static final long LIMIT_BYTES = 64L << 20;
    private static final IdentityHashMap<PredictionPackedMesh, Long> PENDING = new IdentityHashMap<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "vss-compression-retry"); thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            });
    private static long bytes, epoch, submitted, completed, deferrals;

    static synchronized void request(PredictionPackedMesh mesh) {
        long raw = mesh.storageBytes();
        if (PENDING.containsKey(mesh) || raw < PredictionMeshCompression.MIN_BYTES || raw > PredictionMeshCompression.MAX_BYTES) return;
        Runtime r = Runtime.getRuntime();
        if (PENDING.size() >= 8 || raw * 3 > LIMIT_BYTES - bytes
                || r.maxMemory() - r.totalMemory() + r.freeMemory() < raw * 3 + (128L << 20)) { deferrals++; return; }
        long generation = epoch;
        PENDING.put(mesh, generation); bytes += raw * 3; submitted++;
        try {
            WORKER.execute(() -> {
                try { if (current(generation) && mesh.retryCompression(generation)) synchronized (PredictionCompressionRetry.class) { completed++; } }
                catch (RuntimeException failure) { dev.xantha.vss.common.VSSLogger.error("VSS mesh compression retry failed", failure); }
                finally { synchronized (PredictionCompressionRetry.class) {
                    if (PENDING.get(mesh) != null && PENDING.get(mesh) == generation) { PENDING.remove(mesh); bytes -= raw * 3; }
                } }
            });
        } catch (RejectedExecutionException full) { PENDING.remove(mesh); bytes -= raw * 3; deferrals++; }
    }
    static synchronized boolean current(long generation) { return generation == epoch; }
    static synchronized void clear() { epoch++; }
    static synchronized String diagnostics() {
        return "jobs=" + PENDING.size() + ",workspaceBytes=" + bytes + ",submitted=" + submitted
                + ",completed=" + completed + ",deferrals=" + deferrals;
    }
}
