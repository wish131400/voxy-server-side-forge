package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Screen error for ordinary visible refinement, independent of the idle-quality lane. */
final class PredictionScreenDetail {
    private PredictionScreenDetail() { }

    static double projected(PredictionWorkView view, PredictionTileKey key, VssLodLayout layout,
                            double x, double y, double z, double pixels, double minY, double maxY) {
        if (view != null) return view.projectedSize(key, layout, minY, maxY, pixels);
        double vertical = Math.max(0, Math.max(minY - y, y - maxY));
        double distance = Math.sqrt(PredictionWorkOrder.distanceSquared(key, layout, x, z) + vertical * vertical);
        return VssLodProjection.projectedSize(layout.tileBlocks(key.lod()), distance, pixels);
    }

    static int targetAxis(double projected, VssLodLayout layout) {
        double cellPixels = layout.pixelThreshold() / VssLodLayout.TILE_QUADS;
        return projected <= 8 * cellPixels ? 8 : PredictionDetailBands.projectedCellAxis(projected, cellPixels);
    }

    static boolean surfaceVisible(PredictionWorkView view, PredictionTileKey key, VssLodLayout layout,
                                  double x, double y, double z, double pixels, double minY, double maxY) {
        if (view == null || !layout.trees() || view.band(key, layout, null, minY, maxY) > PredictionWorkView.BUFFER)
            return false;
        // A tree/building-sized feature can still be distinct when a single block is subpixel.
        // Only plant-capable leaves generate exact decoration; this predicate also guides subdivision.
        double featurePixels = projected(view, key, layout, x, y, z, pixels, minY, maxY)
                * 8 / layout.tileBlocks(key.lod());
        double threshold = Math.max(1.5, Math.min(3, layout.pixelThreshold() / VssLodLayout.TILE_QUADS));
        return featurePixels > threshold;
    }
}
