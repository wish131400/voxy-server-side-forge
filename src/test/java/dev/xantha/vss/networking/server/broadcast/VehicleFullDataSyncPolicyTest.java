package dev.xantha.vss.networking.server.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VehicleFullDataSyncPolicyTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void successfulDeliveryUsesNormalRefreshInterval() {
        VehicleFullDataSyncPolicy policy = new VehicleFullDataSyncPolicy(10 * SECOND, 2 * SECOND, 8 * SECOND);

        assertTrue(policy.shouldAttempt(100 * SECOND));
        policy.recordResult(100 * SECOND, true);
        assertFalse(policy.shouldAttempt(109 * SECOND));
        assertTrue(policy.shouldAttempt(110 * SECOND));
    }

    @Test
    void failedDeliveryBacksOffAndCanRetry() {
        VehicleFullDataSyncPolicy policy = new VehicleFullDataSyncPolicy(10 * SECOND, 2 * SECOND, 8 * SECOND);

        policy.recordResult(100 * SECOND, false);
        assertEquals(1, policy.consecutiveFailures());
        assertEquals(2 * SECOND, policy.retryDelayNanos());
        assertFalse(policy.shouldAttempt(101 * SECOND));
        assertTrue(policy.shouldAttempt(102 * SECOND));

        policy.recordResult(102 * SECOND, false);
        assertEquals(4 * SECOND, policy.retryDelayNanos());
        policy.recordResult(106 * SECOND, false);
        assertEquals(8 * SECOND, policy.retryDelayNanos());
        policy.recordResult(114 * SECOND, false);
        assertEquals(8 * SECOND, policy.retryDelayNanos());
    }

    @Test
    void successResetsFailureBackoff() {
        VehicleFullDataSyncPolicy policy = new VehicleFullDataSyncPolicy(10 * SECOND, 2 * SECOND, 8 * SECOND);

        policy.recordResult(100 * SECOND, false);
        policy.recordResult(102 * SECOND, false);
        policy.recordResult(106 * SECOND, true);

        assertEquals(0, policy.consecutiveFailures());
        assertFalse(policy.shouldAttempt(115 * SECOND));
        assertTrue(policy.shouldAttempt(116 * SECOND));
    }
}
