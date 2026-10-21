package dev.xantha.vss.client.prediction;

/** Render-only selection of already uploaded representations. Never changes
 * terrain ownership, cached generation detail, water or the seam version. */
final class PredictionDisplayLod {
    static final double PIXEL_ERROR = 1.5;
    private int tier;
    int tier() { return tier; }

    boolean update(float[] errors, double distance, double pixels, int fullDistance, boolean scoped) {
        return update(errors, distance, pixels, fullDistance, scoped, PIXEL_ERROR);
    }

    static double terrainPixelError(String detail) {
        if (detail == null) return 2;
        return switch (detail) {
            case "low" -> 3;
            case "high" -> PIXEL_ERROR;
            case "extreme" -> 1;
            default -> 2;
        };
    }

    boolean update(float[] errors, double distance, double pixels, int fullDistance, boolean scoped, double pixelError) {
        int previous = tier;
        if (scoped || distance <= Math.max(256, fullDistance) || !Double.isFinite(distance)
                || !Double.isFinite(pixels) || pixels <= 0 || !Double.isFinite(pixelError) || pixelError <= 0) tier = 0;
        else {
            int selected = 0;
            for (int candidate = 1; candidate < errors.length; candidate++) {
                if (!Float.isFinite(errors[candidate])) continue;
                double projected = errors[candidate] * pixels / Math.max(1, distance - errors[candidate]);
                // Returning from a coarse representation may reuse any finer
                // level inside the full error bound. Requiring its entry
                // margin again can skip a valid terrain level after an alias
                // slot gains a feature proxy, briefly falling back to source.
                double threshold = candidate <= tier ? pixelError : pixelError * .8;
                if (projected <= threshold) selected = candidate;
            }
            tier = selected;
        }
        return tier != previous;
    }
}
