package dev.xantha.vss.networking.server.broadcast;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

final class VehicleFullDataSyncTracker<K> {
    private final long successIntervalNanos;
    private final long minimumRetryNanos;
    private final long maximumRetryNanos;
    private final Map<K, VehicleFullDataSyncPolicy> policies = new HashMap<>();

    VehicleFullDataSyncTracker(
            long successIntervalNanos,
            long minimumRetryNanos,
            long maximumRetryNanos) {
        this.successIntervalNanos = successIntervalNanos;
        this.minimumRetryNanos = minimumRetryNanos;
        this.maximumRetryNanos = maximumRetryNanos;
    }

    boolean shouldAttempt(K identity, long now) {
        return policies.computeIfAbsent(identity, ignored -> new VehicleFullDataSyncPolicy(
                        successIntervalNanos,
                        minimumRetryNanos,
                        maximumRetryNanos))
                .shouldAttempt(now);
    }

    void recordResult(K identity, long now, boolean success) {
        VehicleFullDataSyncPolicy policy = policies.get(identity);
        if (policy != null) {
            policy.recordResult(now, success);
        }
    }

    void retain(Collection<K> identities) {
        policies.keySet().retainAll(identities);
    }

    int trackedIdentities() {
        return policies.size();
    }
}
