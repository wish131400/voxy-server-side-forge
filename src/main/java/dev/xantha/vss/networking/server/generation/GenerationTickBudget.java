package dev.xantha.vss.networking.server.generation;

/** Main-thread admission control, not a hard deadline for an individual snapshot. */
final class GenerationTickBudget {
    private boolean initialized;
    private int lastTick;
    private boolean paused;
    private int snapshots;
    private long snapshotNanos;

    boolean beginTick(int tick, double averageTickMillis, int pauseAtMillis) {
        boolean changed = !initialized || tick != lastTick;
        if (changed) {
            initialized = true;
            lastTick = tick;
            snapshots = 0;
            snapshotNanos = 0L;
        }
        paused = pauseAtMillis > 0
                && (!Double.isFinite(averageTickMillis) || averageTickMillis >= pauseAtMillis);
        return changed;
    }

    boolean canStart() {
        return !paused;
    }

    boolean canSnapshot(int limit, int budgetMillis) {
        return (limit <= 0 || snapshots < limit)
                && (budgetMillis <= 0 || snapshotNanos < budgetMillis * 1_000_000L);
    }

    void recordSnapshot(long elapsedNanos) {
        snapshots++;
        snapshotNanos += Math.max(0L, elapsedNanos);
    }

    String diagnostics() {
        return String.format(java.util.Locale.ROOT,
                "generationLoadPaused=%s, snapshotsThisTick=%d, snapshotMsThisTick=%.3f",
                paused, snapshots, snapshotNanos / 1_000_000.0D);
    }
}
