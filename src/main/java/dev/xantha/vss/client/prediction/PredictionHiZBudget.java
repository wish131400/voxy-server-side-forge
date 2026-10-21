package dev.xantha.vss.client.prediction;

/**
 * Render-period budget shared by the production Hi-Z pass and refinement
 * probes. A probe is a priority hint, so it must give way to the main pass
 * instead of rebuilding another full pyramid when the frame is already busy.
 */
final class PredictionHiZBudget {
    static final int MAX_REBUILDS = 2;

    // Zero is also useful for standalone refinement GPU tests that do not
    // construct an indirect batch first. Production frames replace it with
    // the opaque submission token before any probe is scheduled.
    private static long token;
    private static int used;

    private PredictionHiZBudget() { }

    static void begin(long nextToken) {
        if (token != nextToken) {
            token = nextToken;
            used = 0;
        }
    }

    static boolean acquire(long expectedToken) {
        if (token != expectedToken || used >= MAX_REBUILDS) return false;
        used++;
        return true;
    }

    static boolean acquireRefinement() {
        if (used >= MAX_REBUILDS) return false;
        used++;
        return true;
    }

    static int used() { return used; }
    static long token() { return token; }
}
