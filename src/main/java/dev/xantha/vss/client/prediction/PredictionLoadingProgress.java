package dev.xantha.vss.client.prediction;

/** Global CPU progress and, when available, current-view CPU/GPU readiness. */
public record PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady,
                                        int targetTotal, int targetReady, int surfaceTotal, int surfaceReady,
                                        boolean planned, ViewProgress view) {
    public static final PredictionLoadingProgress INITIALIZING =
            new PredictionLoadingProgress(0, 0, 0, 0, 0, 0, 0, 0, false);
    public static final PredictionLoadingProgress EMPTY =
            new PredictionLoadingProgress(0, 0, 0, 0, 0, 0, 0, 0, true);

    /** Visible ordinary targets only; optional upgrades and deferred work do not hold admission. */
    public record ViewProgress(int total, int terrainReady, int surfaceTotal, int surfaceReady,
                               int drawableReady, int deferred) {
        boolean ready(int percent) {
            return enough(total, terrainReady, percent) && enough(surfaceTotal, surfaceReady, percent)
                    && enough(total, drawableReady, percent);
        }
    }

    public PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady,
                                     int targetTotal, int targetReady, int surfaceTotal, int surfaceReady,
                                     boolean planned) {
        this(coverageTotal, coverageReady, nearTotal, nearReady, targetTotal, targetReady,
                surfaceTotal, surfaceReady, planned, null);
    }

    public PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady,
                                     int targetTotal, int targetReady, int surfaceTotal, int surfaceReady) {
        this(coverageTotal, coverageReady, nearTotal, nearReady,
                targetTotal, targetReady, surfaceTotal, surfaceReady, true);
    }

    public PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady) {
        this(coverageTotal, coverageReady, nearTotal, nearReady, nearTotal, nearReady, 0, 0, coverageTotal > 0);
    }

    public boolean ready(int percent) {
        return planned && enough(coverageTotal, coverageReady, percent)
                && (view != null ? view.ready(percent)
                : enough(nearTotal, nearReady, percent) && enough(targetTotal, targetReady, percent)
                    && enough(surfaceTotal, surfaceReady, percent));
    }

    public int score() {
        if (!planned) return 0;
        if (view != null) return ratio(coverageTotal, coverageReady) + ratio(view.total(), view.terrainReady())
                + ratio(view.surfaceTotal(), view.surfaceReady()) + ratio(view.total(), view.drawableReady());
        return ratio(coverageTotal, coverageReady) + ratio(nearTotal, nearReady)
                + ratio(targetTotal, targetReady) + ratio(surfaceTotal, surfaceReady);
    }

    public String waitingFor(int percent) {
        if (!planned) return "initializing";
        if (!enough(coverageTotal, coverageReady, percent)) return "coverage";
        if (view != null) {
            if (!enough(view.total(), view.terrainReady(), percent)) return "view-terrain";
            if (!enough(view.surfaceTotal(), view.surfaceReady(), percent)) return "view-surface";
            if (!enough(view.total(), view.drawableReady(), percent)) return "view-upload";
        } else {
            if (!enough(nearTotal, nearReady, percent)) return "near-ground";
            if (!enough(targetTotal, targetReady, percent)) return "near-target";
            if (!enough(surfaceTotal, surfaceReady, percent)) return "surface";
        }
        return "complete";
    }

    private static boolean enough(int total, int ready, int percent) {
        return total == 0 || total > 0 && (long) Math.max(0, ready) * 100 >= (long) total * percent;
    }

    private static int ratio(int total, int ready) {
        return total <= 0 ? 10000 : (int) ((long) Math.min(total, Math.max(0, ready)) * 10000 / total);
    }
}
