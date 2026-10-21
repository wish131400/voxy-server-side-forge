package dev.xantha.vss.client.prediction;

/** Soft, measured lanes. Pressure slows optional work; it never skips an
 * already visible water surface, plant, seam or missing terrain fallback. */
final class PredictionRenderWorkBudget {
    enum Lane { WATER, VEGETATION, SEAM }
    static final PredictionRenderWorkBudget SHARED = new PredictionRenderWorkBudget();
    private final double[] average = new double[3];
    private final long[] updated = new long[3];
    private volatile int pressure;
    private volatile long expires;

    void sample(Lane lane, long nanos, long now) {
        int i = lane.ordinal();
        average[i] = updated[i] == 0 ? nanos : average[i] * .75 + nanos * .25;
        updated[i] = now;
        int flags = 0;
        for (int n = 0; n < 3; n++) {
            long target = n == Lane.SEAM.ordinal() ? 1_000_000L : 2_000_000L;
            if (now - updated[n] < 2_000_000_000L && average[n] > target) flags |= 1 << n;
        }
        pressure = flags; expires = now + 2_000_000_000L;
    }
    boolean busy(Lane lane, long now) { return now < expires && (pressure & 1 << lane.ordinal()) != 0; }
    boolean busy(long now) { return now < expires && pressure != 0; }
    int surfaceConcurrency(int ordinary, long now) { return busy(Lane.VEGETATION, now) ? Math.min(1, ordinary) : ordinary; }
    void clear() { java.util.Arrays.fill(average, 0); java.util.Arrays.fill(updated, 0); pressure = 0; expires = 0; }
}
