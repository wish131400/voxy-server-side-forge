package dev.xantha.vss.networking.server.broadcast;

final class VehicleFullDataSyncPolicy {
    private final long successIntervalNanos;
    private final long minimumRetryNanos;
    private final long maximumRetryNanos;
    private long lastAttemptNanos = Long.MIN_VALUE;
    private long lastSuccessNanos = Long.MIN_VALUE;
    private int consecutiveFailures;

    VehicleFullDataSyncPolicy(
            long successIntervalNanos,
            long minimumRetryNanos,
            long maximumRetryNanos) {
        this.successIntervalNanos = Math.max(0L, successIntervalNanos);
        this.minimumRetryNanos = Math.max(0L, minimumRetryNanos);
        this.maximumRetryNanos = Math.max(this.minimumRetryNanos, maximumRetryNanos);
    }

    boolean shouldAttempt(long now) {
        if (lastAttemptNanos == Long.MIN_VALUE) {
            return true;
        }
        if (lastSuccessNanos == lastAttemptNanos) {
            return elapsed(now, lastSuccessNanos, successIntervalNanos);
        }
        return elapsed(now, lastAttemptNanos, retryDelayNanos());
    }

    void recordResult(long now, boolean success) {
        lastAttemptNanos = now;
        if (success) {
            lastSuccessNanos = now;
            consecutiveFailures = 0;
        } else if (consecutiveFailures < 31) {
            consecutiveFailures++;
        }
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    long retryDelayNanos() {
        if (consecutiveFailures <= 1) {
            return minimumRetryNanos;
        }
        long delay = minimumRetryNanos;
        for (int i = 1; i < consecutiveFailures && delay < maximumRetryNanos; i++) {
            if (delay > maximumRetryNanos / 2L) {
                return maximumRetryNanos;
            }
            delay *= 2L;
        }
        return Math.min(delay, maximumRetryNanos);
    }

    private static boolean elapsed(long now, long start, long duration) {
        return now >= start && now - start >= duration;
    }
}
