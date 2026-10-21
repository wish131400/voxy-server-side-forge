package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.concurrent.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** One in-flight immutable boundary transaction. No world reads or GL calls on this worker. */
final class PredictionSeamPreparation {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "vss-boundary-preparation"); thread.setDaemon(true); return thread;
    });
    record Surface(PredictionTile tile, boolean[] allowed, boolean[] features, PredictionCoverageGrid grid) {
        Surface(PredictionTile tile, boolean[] allowed, boolean[] features) { this(tile,allowed,features,null); }
    }
    record Result(long version, Map<PredictionTileKey, byte[]> masks,
                  List<PredictionLodSeams.Patch> lod, List<PredictionLodSeams.Patch> real,
                  Map<PredictionTileKey, byte[]> maskChanges,
                  PredictionLodSeams.Changes lodChanges, PredictionLodSeams.Changes realChanges) { }
    private final Executor executor;
    private Builder builder = new Builder();
    private CompletableFuture<Result> pending;
    private Result published;
    private long submitted, completed, discarded, pendingGeneration;
    private volatile long generation;

    PredictionSeamPreparation() { this(WORKER); }
    PredictionSeamPreparation(Executor executor) { this.executor = executor; }
    boolean pending() { return pending != null; }

    static PredictionTileTable<Surface> updateSurfaces(PredictionTileTable<Surface> previous,
            Collection<PredictionTileKey> changed, java.util.function.Function<PredictionTileKey, Surface> lookup) {
        var updates = new HashMap<PredictionTileKey, Surface>(); var removed = new ArrayList<PredictionTileKey>();
        for (var key : changed) {
            var value = lookup.apply(key); var old = previous.get(key);
            if (value == null) removed.add(key);
            else if (old == null || old.tile() != value.tile() || old.allowed() != value.allowed() || old.features() != value.features() || old.grid() != value.grid())
                updates.put(key, value);
        }
        return previous.changed(updates, removed);
    }

    void request(long version, Map<PredictionTileKey, Surface> surfaces,
                 List<PredictionRealBoundarySeams.Edge> edges) {
        if (pending != null) return;
        var input = surfaces instanceof PredictionTileTable<?> ? surfaces : Map.copyOf(surfaces);
        var state = builder;
        var baseline = published;
        submitted++; pendingGeneration = generation;
        long requestedGeneration = generation;
        pending = CompletableFuture.supplyAsync(() -> {
            if (generation != requestedGeneration) return null;
            var result = state.build(version, input, edges, baseline);
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
        completed++; published = result;
        return result;
    }
    void clear() {
        // An old task may still be finishing; its isolated builder can never
        // mutate the state or publish into the next world/resource generation.
        generation++; builder = new Builder(); published = null;
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
        Map<PredictionTileKey, Surface> previous = Map.of();
        final Map<PredictionTileKey, byte[]> masks = new HashMap<>();
        Result build(long version, Map<PredictionTileKey, Surface> next,
                     List<PredictionRealBoundarySeams.Edge> edges, Result baseline) {
            var replacements = new ArrayList<PredictionLodSeams.Surface>();
            var changed = new HashSet<PredictionTileKey>();
            if (next instanceof PredictionTileTable<Surface> after && previous instanceof PredictionTileTable<Surface> before)
                after.forEachDifference(before, changed::add);
            else { changed.addAll(previous.keySet()); changed.addAll(next.keySet()); }
            var removed = new HashSet<PredictionTileKey>();
            var features = new HashSet<PredictionTileKey>();
            for (var key : changed) {
                var surface = next.get(key); var old = previous.get(key);
                if (surface == null) { removed.add(key); continue; }
                if (old == null || old.tile() != surface.tile() || old.allowed() != surface.allowed() || old.grid() != surface.grid())
                    replacements.add(new PredictionLodSeams.Surface(surface.tile(), surface.allowed(), surface.grid()));
                if (old == null || !Arrays.equals(old.allowed(), surface.allowed())
                        || !Arrays.equals(old.features(), surface.features()) || !PredictionCoverageGrid.same(old.grid(), surface.grid())) features.add(key);
            }
            var delta = lod.apply(replacements, removed); features.addAll(delta.boundaries());
            var rebound = new HashSet<>(delta.surfaces()); rebound.removeAll(delta.boundaries());
            real.rebind(lod.index(), rebound);
            var realDelta = real.apply(lod.index(), delta.boundaries(), edges);
            for (var key : removed) masks.remove(key);
            for (var key : features) {
                var surface = next.get(key);
                if (surface != null) masks.put(key, surface.grid() == null ? PredictionFeatureHandoff.mask(
                        surface.allowed(), surface.features(), lod.boundaryMask(key)) : surface.grid().mask(lod.boundaryMask(key)));
            }
            // Seam uploads also consume pass storage. Prepare it here rather
            // than accidentally packing a water/opaque pass in ensureSeams().
            for (var patch : delta.patches().values()) patch.mesh().preparePassStorage(patch.mesh().quads());
            for (var patch : realDelta.patches().values()) patch.mesh().preparePassStorage(patch.mesh().quads());
            previous = next;
            var maskChanges = new HashMap<PredictionTileKey, byte[]>();
            for (var entry : masks.entrySet()) if (baseline == null || baseline.masks().get(entry.getKey()) != entry.getValue())
                maskChanges.put(entry.getKey(), entry.getValue());
            var lodPatches = lod.patchesSnapshot(); var realPatches = real.patchesSnapshot();
            return new Result(version, Map.copyOf(masks), lodPatches, realPatches, Map.copyOf(maskChanges),
                    patchChanges(lodPatches, baseline == null ? List.of() : baseline.lod()),
                    patchChanges(realPatches, baseline == null ? List.of() : baseline.real()));
        }

        private static PredictionLodSeams.Changes patchChanges(List<PredictionLodSeams.Patch> next,
                                                               List<PredictionLodSeams.Patch> previous) {
            var old = new HashMap<PredictionTileKey, PredictionLodSeams.Patch>();
            for (var patch : previous) old.put(patch.surface().tile().key(), patch);
            var updates = new LinkedHashMap<PredictionTileKey, PredictionLodSeams.Patch>();
            for (var patch : next) {
                var key = patch.surface().tile().key();
                if (old.remove(key) != patch) updates.put(key, patch);
            }
            return new PredictionLodSeams.Changes(Map.copyOf(updates), Set.copyOf(old.keySet()), Set.of(), Set.of());
        }
    }
}
