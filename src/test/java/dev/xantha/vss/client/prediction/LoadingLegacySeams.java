package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.concurrent.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** One in-flight immutable boundary transaction. No world reads or GL calls on this worker. */
final class LoadingLegacySeams {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "vss-boundary-preparation"); thread.setDaemon(true); return thread;
    });
    record Surface(PredictionTile tile, boolean[] allowed, boolean[] features) { }
    record Result(long version, Map<PredictionTileKey, byte[]> masks,
                  List<PredictionLodSeams.Patch> lod, List<PredictionLodSeams.Patch> real) { }
    private final Executor executor;
    private Builder builder = new Builder();
    private CompletableFuture<Result> pending;
    private long submitted, completed, discarded, pendingGeneration;
    private volatile long generation;

    LoadingLegacySeams() { this(WORKER); }
    LoadingLegacySeams(Executor executor) { this.executor = executor; }
    boolean pending() { return pending != null; }

    void request(long version, Map<PredictionTileKey, Surface> surfaces,
                 List<PredictionRealBoundarySeams.Edge> edges) {
        if (pending != null) return;
        var input = Map.copyOf(surfaces);
        var state = builder;
        submitted++; pendingGeneration = generation;
        long requestedGeneration = generation;
        pending = CompletableFuture.supplyAsync(() -> {
            if (generation != requestedGeneration) return null;
            var result = state.build(version, input, edges);
            // Leaving a world may stop render polling entirely. Do not let a
            // completed abandoned future retain that world's full tile map.
            return generation == requestedGeneration ? result : null;
        }, executor);
    }
    Result poll(long version) {
        if (pending == null || !pending.isDone()) return null;
        var finished = pending; pending = null;
        if (pendingGeneration != generation) { discarded++; return null; }
        Result result;
        try { result = finished.getNow(null); }
        catch (CompletionException failure) {
            discarded++; builder = new Builder();
            dev.xantha.vss.common.VSSLogger.warn("VSS boundary preparation failed; retaining the drawable fallback", failure);
            return null;
        }
        if (result.version() != version) { discarded++; return null; }
        completed++;
        return result;
    }
    void clear() {
        // An old task may still be finishing; its isolated builder can never
        // mutate the state or publish into the next world/resource generation.
        generation++; builder = new Builder();
        if (pending != null) {
            var abandoned = pending;
            abandoned.handle((result, failure) -> { abandoned.obtrudeValue(null); return null; });
        }
    }
    String diagnostics() { return "boundaryJobs=" + submitted + ",boundaryCommits=" + completed
            + ",boundaryDiscards=" + discarded + ",boundaryPending=" + pending(); }

    private static final class Builder {
        final PredictionLodSeams lod = new PredictionLodSeams();
        final PredictionRealBoundarySeams real = new PredictionRealBoundarySeams();
        final Map<PredictionTileKey, Surface> previous = new HashMap<>();
        final Map<PredictionTileKey, byte[]> masks = new HashMap<>();
        Result build(long version, Map<PredictionTileKey, Surface> next,
                     List<PredictionRealBoundarySeams.Edge> edges) {
            var replacements = new ArrayList<PredictionLodSeams.Surface>();
            var removed = new HashSet<>(previous.keySet()); removed.removeAll(next.keySet());
            var features = new HashSet<PredictionTileKey>();
            for (var entry : next.entrySet()) {
                var key = entry.getKey(); var surface = entry.getValue(); var old = previous.get(key);
                if (old == null || old.tile() != surface.tile() || old.allowed() != surface.allowed())
                    replacements.add(new PredictionLodSeams.Surface(surface.tile(), surface.allowed()));
                if (old == null || old.tile() != surface.tile() || old.features() != surface.features()) features.add(key);
            }
            var delta = lod.apply(replacements, removed); features.addAll(delta.boundaries());
            var realDelta = real.apply(lod.index(), delta.boundaries(), edges);
            for (var key : removed) masks.remove(key);
            for (var key : features) {
                var surface = next.get(key);
                if (surface != null) masks.put(key, PredictionFeatureHandoff.mask(
                        surface.allowed(), surface.features(), lod.boundaryMask(key)));
            }
            // Seam uploads also consume pass storage. Prepare it here rather
            // than accidentally packing a water/opaque pass in ensureSeams().
            for (var patch : delta.patches().values()) patch.mesh().preparePassStorage(patch.mesh().quads());
            for (var patch : realDelta.patches().values()) patch.mesh().preparePassStorage(patch.mesh().quads());
            previous.clear(); previous.putAll(next);
            return new Result(version, Map.copyOf(masks), lod.patchesSnapshot(), real.patchesSnapshot());
        }
    }
}
