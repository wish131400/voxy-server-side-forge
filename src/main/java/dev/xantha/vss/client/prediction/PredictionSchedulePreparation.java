package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Priority hints and sorted candidates. Admission always rechecks live resource limits. */
final class PredictionSchedulePreparation {
    record Request(PredictionTileKey key, boolean surface, int priority, double distance) { }
    record Hint(int terrain, int surface, double distance) { }
    record Input(VssLodLayout layout, VssLodFocus focus, PredictionWorkView view, double x, double y, double z,
                 double pixels, double minY, double maxY, long revision, List<PredictionTileKey> plan,
                 PredictionTileManager.RenderSnapshot residents, Set<PredictionTileKey> desired,
                 Set<PredictionTileKey> surfaceDesired, Set<PredictionTileKey> surfaceReady,
                 Set<PredictionTileKey> dirty, Set<PredictionTileKey> fallback, Set<PredictionTileKey> pending,
                 Map<PredictionTileKey, Integer> ordinary, Map<PredictionTileKey, Integer> terrain,
                 Map<PredictionTileKey, Integer> transitions, Map<PredictionTileKey, Integer> idle,
                 PredictionMediumCoverage medium, boolean mediumPending, boolean previewPending,
                 boolean localTurn, boolean moving, Map<PredictionTileKey, Integer> bands,
                 Set<PredictionTileKey> dependencies, Set<PredictionTileKey> hidden,
                 List<Integer> initialAxes, PredictionDiskCache disk, Map<PredictionTileKey, Long> cacheMiss,
                 Map<PredictionTileKey, Long> failed, Map<PredictionTileKey, Long> deferred, long now, boolean reclaimNeeded) {
        int axis(PredictionTileKey key) {
            var tile = residents.tiles().get(key); return tile == null ? 0 : tile.cellAxis();
        }
        boolean scoped(PredictionTileKey key) { return PredictionWorkOrder.scoped(key, layout, focus); }
        int band(PredictionTileKey key) {
            if (scoped(key)) return PredictionWorkView.VISIBLE;
            Integer known = bands.get(key);
            return known == null ? view == null ? PredictionWorkView.VISIBLE : view.band(key, layout, focus, minY, maxY) : known;
        }
        int mediumAxis(PredictionTileKey key) {
            int axis = medium.frontier().contains(key) ? PredictionWorkOrder.PREVIEW_CELL_AXIS : initialAxes.get(key.lod());
            return view != null && terrain.containsKey(key) ? Math.min(axis, ordinary.getOrDefault(key, axis)) : axis;
        }
        boolean mediumWork(PredictionTileKey key) {
            return mediumPending && medium.paths().contains(key) && axis(key) < mediumAxis(key);
        }
        boolean required(PredictionTileKey key, boolean surface) {
            if (dirty.contains(key) || fallback.contains(key) || scoped(key)) return true;
            if (surface) return false;
            var tile = residents.tiles().get(key);
            return (medium.paths().contains(key) || dependencies.contains(key)) && (tile == null || tile.scopeOnly())
                    || dependencies.contains(key) && transitions.containsKey(key) && axis(key) < transitions.get(key);
        }
        boolean relevant(PredictionTileKey key) { return band(key) <= PredictionWorkView.BUFFER || dependencies.contains(key); }
        boolean idleWork(PredictionTileKey key) {
            return idle.containsKey(key) && !dirty.contains(key) && !scoped(key)
                    && (!ordinary.containsKey(key) || axis(key) >= ordinary.get(key));
        }
        int offset(PredictionTileKey key, boolean surface) {
            if (hidden.contains(key) && !required(key, surface)) return band(key) == PredictionWorkView.VISIBLE ? 350_000 : 650_000;
            if (band(key) == PredictionWorkView.VISIBLE || !surface && dependencies.contains(key)) return 0;
            return band(key) == PredictionWorkView.BUFFER ? 300_000
                    : band(key) == PredictionWorkView.RECENT ? 600_000 : PredictionWorkOrder.BACKGROUND_PRIORITY;
        }
        int priority(PredictionTileKey key, boolean surface) {
            if (dirty.contains(key)) return Integer.MIN_VALUE + 1 + key.lod();
            if (!surface && idleWork(key)) return view == null ? 2_000_000 : 400_000 + offset(key, surface);
            if (view != null && !surface && required(key, false) && !scoped(key))
                return 10_000 + (relevant(key) ? 0 : 10_000) + (layout.levelCount() - 1 - key.lod()) * 100;
            if (!moving && !scoped(key) && surfaceDesired.contains(key) && localTurn && !mediumWork(key)
                    && !hidden.contains(key)) {
                double nearby = PredictionWorkOrder.distanceSquared(key, layout, x, z);
                return offset(key, surface) + 5000 + Math.min(1023, (int) (Math.sqrt(nearby) / 64)) * 3 + (surface ? 0 : 1);
            }
            if (view != null && band(key) <= PredictionWorkView.BUFFER && !scoped(key) && !mediumWork(key)) {
                double projected = PredictionScreenDetail.projected(view, key, layout, x, y, z, pixels, minY, maxY);
                double perCell = layout.pixelThreshold() / VssLodLayout.TILE_QUADS;
                double error = surface ? projected * 8 / layout.tileBlocks(key.lod()) / Math.max(1.5, Math.min(3, perCell))
                        : projected / Math.max(1, axis(key) == 0 ? 8 : axis(key)) / perCell;
                return offset(key, surface) + 40_000 + (int) (6000 / (1 + Math.max(0, error)));
            }
            double distance = PredictionWorkOrder.distanceSquared(key, layout, x, z);
            boolean ordinaryWork = !required(key, false) && (!mediumWork(key) || band(key) >= PredictionWorkView.RECENT);
            if (ordinaryWork && moving && !scoped(key) && (surface || axis(key) > 0))
                return offset(key, surface) + 500_000 + Math.min(65535, (int) (Math.sqrt(distance) / 64));
            if (!surface && mediumWork(key) && !scoped(key))
                return offset(key, surface) + 10_000 + (axis(key) == 0 ? 0 : 10_000) + (layout.levelCount() - 1 - key.lod()) * 100;
            return offset(key, surface) + (scoped(key) ? PredictionWorkOrder.priority(key, layout, distance, axis(key), surface, focus)
                    : PredictionWorkOrder.localRefinementPriority(distance, axis(key), surface));
        }
        int target(PredictionTileKey key) {
            int target = terrain.getOrDefault(key, initialAxes.get(key.lod()));
            if (medium.frontier().contains(key)) target = Math.max(mediumAxis(key), target);
            if (mediumPending && !scoped(key) && medium.paths().contains(key)
                    && (!medium.frontier().contains(key) || mediumWork(key))) return mediumAxis(key);
            return Math.max(target, transitions.getOrDefault(key, target));
        }
        boolean retry(PredictionTileKey key) {
            return now - deferred.getOrDefault(key, Long.MIN_VALUE / 2) >= 0
                    && now - failed.getOrDefault(key, Long.MIN_VALUE / 2) > 30_000_000_000L;
        }
        boolean defer(PredictionTileKey key, boolean surface) {
            return view != null && band(key) >= PredictionWorkView.RECENT && (surface || axis(key) > 0)
                    && !required(key, surface) && !(!surface && dependencies.contains(key));
        }
    }
    record Result(Input input, List<Request> work, List<PredictionTileKey> cached, List<PredictionTileKey> reclaim,
                  Map<PredictionTileKey, Hint> hints) { }
    static Result build(Input i) {
        var work = new ArrayList<Request>();
        var keys = new LinkedHashSet<>(i.dirty()); keys.addAll(i.plan()); keys.addAll(i.surfaceDesired());
        var cached = new ArrayList<PredictionTileKey>();
        var hints = new HashMap<PredictionTileKey, Hint>();
        for (var key : keys) {
            if (!i.desired().contains(key) || key.lod() < 0 || key.lod() >= i.layout().levelCount()) continue;
            double distance = PredictionWorkOrder.orderingDistance(key, i.layout(), i.x(), i.z(), i.focus());
            var hint = new Hint(i.priority(key, false), i.priority(key, true), distance); hints.put(key, hint);
            if (i.pending().contains(key)) continue;
            if (i.retry(key) && !i.defer(key, false) && (i.dirty().contains(key) || i.axis(key) < i.target(key)))
                work.add(new Request(key, false, hint.terrain(), distance));
            if (i.retry(key) && !i.defer(key, true) && i.surfaceDesired().contains(key)
                    && i.axis(key) == i.layout().cellAxis(key.lod()) && !i.dirty().contains(key) && !i.surfaceReady().contains(key))
                work.add(new Request(key, true, hint.surface(), distance));
            if (i.disk() != null && i.now() - i.cacheMiss().getOrDefault(key, Long.MIN_VALUE / 2) >= 0
                    && i.disk().cachedTerrainAxis(PredictionDiskCache.Key.terrain(key.tileX(), key.tileZ(), key.lod())) > i.axis(key))
                cached.add(key);
        }
        work.sort(Comparator.comparingInt(Request::priority).thenComparingDouble(Request::distance)
                .thenComparingInt(request -> -request.key().lod()));
        cached.sort(Comparator.<PredictionTileKey>comparingInt(i::band).thenComparingInt(key -> i.axis(key) > 0 ? 1 : 0)
                .thenComparingInt(key -> -key.lod()).thenComparingDouble(key -> PredictionWorkOrder.orderingDistance(
                        key, i.layout(), i.x(), i.z(), i.focus())));
        var reclaim = new ArrayList<PredictionTileKey>();
        if (i.reclaimNeeded()) {
        reclaim.addAll(i.residents().tiles().keySet());
        reclaim.removeIf(key -> key.lod() < 0 || key.lod() >= i.layout().levelCount() || i.scoped(key)
                || i.medium().paths().contains(key) || i.pending().contains(key)
                || !PredictionTileResidencyPolicy.coveredByAncestor(i.residents().tiles().keySet(), i.layout(), key));
        reclaim.sort(Comparator.<PredictionTileKey>comparingInt(key -> PredictionWorkOrder.priority(key, i.layout(),
                        PredictionWorkOrder.distanceSquared(key, i.layout(), i.x(), i.z()), 0, false, i.focus()))
                .thenComparingDouble(key -> PredictionWorkOrder.orderingDistance(key, i.layout(), i.x(), i.z(), i.focus()))
                .reversed());
        }
        return new Result(i, List.copyOf(work), List.copyOf(cached), List.copyOf(reclaim), Map.copyOf(hints));
    }
}
