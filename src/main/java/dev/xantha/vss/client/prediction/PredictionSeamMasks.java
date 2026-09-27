package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Computes independent boundary masks before the render thread publishes any of them. */
final class PredictionSeamMasks {
    private static final int PARALLEL_THRESHOLD = 24;
    private static final int WORKERS = 2;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKERS, task -> {
        var thread = new Thread(task, "VSS Seam Mask " + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final LongAdder parallelBatches = new LongAdder();
    private static final LongAdder parallelSurfaces = new LongAdder();
    private static final LongAdder parallelWaitNanos = new LongAdder();

    private PredictionSeamMasks() { }

    static boolean shouldParallel(int count) {
        return count >= PARALLEL_THRESHOLD && !Boolean.getBoolean("vss.sequentialSeamMasks");
    }

    static Map<PredictionTileKey, byte[]> build(Collection<PredictionTileKey> affected,
            Map<PredictionTileKey, PredictionLodSeams.Surface> surfaces, PredictionLodSeams.Index index) {
        var entries = new ArrayList<Map.Entry<PredictionTileKey, PredictionLodSeams.Surface>>(affected.size());
        for (var key : affected) {
            var surface = surfaces.get(key);
            if (surface != null) entries.add(Map.entry(key, surface));
        }
        if (entries.isEmpty()) return Map.of();
        var masks = new byte[entries.size()][];
        if (!shouldParallel(entries.size())) {
            buildRange(entries, masks, index, 0, entries.size(), 1);
        } else {
            index.prepareReads();
            long start = System.nanoTime();
            List<CompletableFuture<Void>> jobs = new ArrayList<>(WORKERS);
            for (int worker = 1; worker <= WORKERS; worker++) {
                int offset = worker;
                jobs.add(CompletableFuture.runAsync(
                        () -> buildRange(entries, masks, index, offset, entries.size(), WORKERS + 1), EXECUTOR));
            }
            try {
                buildRange(entries, masks, index, 0, entries.size(), WORKERS + 1);
            } finally {
                CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
            }
            parallelBatches.increment();
            parallelSurfaces.add(entries.size());
            parallelWaitNanos.add(System.nanoTime() - start);
        }
        var result = new java.util.HashMap<PredictionTileKey, byte[]>(entries.size() * 2);
        for (int i = 0; i < entries.size(); i++) result.put(entries.get(i).getKey(), masks[i]);
        return result;
    }

    private static void buildRange(List<Map.Entry<PredictionTileKey, PredictionLodSeams.Surface>> entries,
            byte[][] masks, PredictionLodSeams.Index index, int offset, int end, int stride) {
        for (int i = offset; i < end; i += stride)
            masks[i] = PredictionBoundaryWalls.build(entries.get(i).getValue(), index);
    }

    static String diagnostics() {
        return "parallelMaskBatches=" + parallelBatches.sum() + ",parallelMaskSurfaces=" + parallelSurfaces.sum()
                + ",parallelMaskWallMs=" + parallelWaitNanos.sum() / 1_000_000L;
    }
}
