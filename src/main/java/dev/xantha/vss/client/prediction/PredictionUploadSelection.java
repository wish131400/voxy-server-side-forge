package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.Predicate;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;

/** Select the same ordered prefix as a full sort, without sorting the entire backlog. */
final class PredictionUploadSelection {
    static Comparator<PredictionTile> order(PredictionRenderResidency residency, VssLodLayout layout,
            double x, double z, VssLodFocus focus) {
        // Missing regions still establish their largest cover first. Once a
        // fallback is resident, nearby refinements need not wait for far ancestors.
        return Comparator.comparingInt((PredictionTile tile) ->
                        residency.hasResidentCover(tile.key(), layout) ? 1 : -tile.key().lod())
                .thenComparingInt(tile -> PredictionWorkOrder.priority(tile.key(), layout,
                        PredictionWorkOrder.distanceSquared(tile.key(), layout, x, z), 0, false, focus))
                .thenComparingDouble(tile -> PredictionWorkOrder.orderingDistance(tile.key(), layout, x, z, focus));
    }

    private record Ranked<T>(T value, int order) { }
    static <T> List<T> select(List<T> input, Predicate<T> eligible, Comparator<T> order, int limit) {
        if (limit <= 0) return List.of();
        if (input.size() <= 512) {
            // Keep the previous filter/copy/sort route for small backlogs.
            var visible = new ArrayList<T>();
            for (var value : input) if (eligible.test(value)) visible.add(value);
            var selected = new ArrayList<>(visible);
            selected.sort(order);
            return selected.size() <= limit ? selected : selected.subList(0, limit);
        }
        Comparator<Ranked<T>> ranked = (a, b) -> {
            int compared = order.compare(a.value(), b.value());
            return compared == 0 ? Integer.compare(a.order(), b.order()) : compared;
        };
        var best = new PriorityQueue<Ranked<T>>(limit, ranked.reversed());
        int index = 0;
        for (var value : input) {
            int position = index++;
            if (!eligible.test(value)) continue;
            if (best.size() < limit) best.add(new Ranked<>(value, position));
            else if (order.compare(value, best.peek().value()) < 0) {
                best.poll(); best.add(new Ranked<>(value, position));
            }
        }
        var sorted = new ArrayList<>(best); sorted.sort(ranked);
        var result = new ArrayList<T>(sorted.size());
        for (var entry : sorted) result.add(entry.value());
        return result;
    }
}
