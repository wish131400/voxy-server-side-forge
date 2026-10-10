package dev.xantha.vss.networking.server.generation;

/** Main-thread admission control, not a hard deadline for an individual snapshot. */
final class GenerationTickBudget {
    enum SnapshotSource { LIVE, GENERATED, CHUNKY }

    private boolean initialized;
    private int lastTick;
    private boolean paused;
    private int snapshots;
    private long snapshotNanos;
    private int liveSnapshots;
    private int generatedSnapshots;
    private int chunkySnapshots;
    private long chunkySnapshotNanos;
    private boolean generatedDeferred;
    private boolean reserveFirstGenerated;

    boolean beginTick(int tick, double averageTickMillis, int pauseAtMillis) {
        boolean changed = !initialized || tick != lastTick;
        if (changed) {
            // A single live snapshot can exceed a soft time budget. Give a ready
            // generated result the next turn, then allow live work first again.
            reserveFirstGenerated = generatedDeferred && liveSnapshots > 0 && generatedSnapshots == 0;
            initialized = true;
            lastTick = tick;
            snapshots = 0;
            snapshotNanos = 0L;
            liveSnapshots = 0;
            generatedSnapshots = 0;
            chunkySnapshots = 0;
            chunkySnapshotNanos = 0L;
            generatedDeferred = false;
        }
        paused = pauseAtMillis > 0
                && (!Double.isFinite(averageTickMillis) || averageTickMillis >= pauseAtMillis);
        return changed;
    }

    boolean canStart(boolean explicitJob) {
        return explicitJob || !paused;
    }

    boolean canSnapshot(SnapshotSource source, int limit, int budgetMillis) {
        return source == SnapshotSource.CHUNKY
                || ((limit <= 0 || snapshots < limit)
                && (budgetMillis <= 0 || snapshotNanos < budgetMillis * 1_000_000L));
    }

    boolean shouldReserveGeneratedSnapshot(int limit, int budgetMillis) {
        if ((limit <= 0 && budgetMillis <= 0) || generatedSnapshots > 0) return false;
        if (reserveFirstGenerated) return true;
        if (liveSnapshots == 0) return false;
        // Keep at most the last count slot or half the soft time allowance.
        // The caller must also find a ready result with packing capacity.
        return (limit > 0 && snapshots >= limit - 1)
                || (budgetMillis > 0 && snapshotNanos >= budgetMillis * 500_000L);
    }

    void deferGeneratedSnapshot() {
        generatedDeferred = true;
    }

    void recordSnapshot(SnapshotSource source, long elapsedNanos) {
        long elapsed = Math.max(0L, elapsedNanos);
        if (source == SnapshotSource.CHUNKY) {
            chunkySnapshots++;
            chunkySnapshotNanos += elapsed;
            return;
        }
        snapshots++;
        snapshotNanos += elapsed;
        if (source == SnapshotSource.LIVE) liveSnapshots++;
        else generatedSnapshots++;
    }

    void reset() {
        initialized = false;
        paused = false;
        snapshots = liveSnapshots = generatedSnapshots = chunkySnapshots = 0;
        snapshotNanos = chunkySnapshotNanos = 0L;
        generatedDeferred = reserveFirstGenerated = false;
    }

    String diagnostics() {
        return String.format(java.util.Locale.ROOT,
                "generationLoadPaused=%s, snapshotsThisTick=%d, snapshotMsThisTick=%.3f, liveSnapshotsThisTick=%d, generatedSnapshotsThisTick=%d, generatedSnapshotDeferred=%s, chunkySnapshotsThisTick=%d, chunkySnapshotMsThisTick=%.3f",
                paused, snapshots, snapshotNanos / 1_000_000.0D,
                liveSnapshots, generatedSnapshots, generatedDeferred,
                chunkySnapshots, chunkySnapshotNanos / 1_000_000.0D);
    }
}
