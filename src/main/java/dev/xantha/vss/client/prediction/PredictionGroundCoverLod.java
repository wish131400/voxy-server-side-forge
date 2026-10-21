package dev.xantha.vss.client.prediction;

/** Screen-size selection for small crossed ground plants, independent of trees and water. */
final class PredictionGroundCoverLod {
    static final int TIERS = 5;
    // Two blocks bound both halves of tall grass/flowers. Thresholds are pixels,
    // not a terrain displacement budget: these plants do not define the ground.
    static final double SIZE = 2;
    static final double FIRST_PIXELS = 16;
    static final double ENTER = .8;
    private int tier;

    int tier() { return tier; }

    boolean update(double distance, double pixels, int fullDistance, boolean forceSource) {
        int previous = tier;
        tier = 0;
        if (!forceSource && distance > Math.max(256, fullDistance) && Double.isFinite(distance)
                && Double.isFinite(pixels) && pixels > 0) {
            double projected = SIZE * pixels / Math.max(1, distance - SIZE);
            for (int candidate = 1; candidate < TIERS; candidate++) {
                double threshold = FIRST_PIXELS / (1 << (candidate - 1));
                if (projected <= threshold * (candidate <= previous ? 1 : ENTER)) tier = candidate;
            }
        }
        return previous != tier;
    }

    /** Only canonical crossed features can disappear; model UVs, fluids and previews stay exact. */
    static boolean eligible(int[] words, int p, boolean[] materials) {
        int flags = words[p + 6], sprite = flags & 65535;
        return sprite < materials.length && materials[sprite]
                && (flags & PredictionPackedMesh.FLAG_UNSHADED) != 0
                && (flags & PredictionPackedMesh.FLAG_MODEL_UV) == 0
                && (flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) == 0
                && (words[p + 10] & (PredictionPackedMesh.FEATURE | PredictionPackedMesh.PREVIEW))
                        == PredictionPackedMesh.FEATURE;
    }
}
