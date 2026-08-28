package dev.xantha.vss.networking.server.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleFullDataSyncTrackerTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void changedUuidGetsFreshAttemptEvenWhenEntityIdAndTypeAreReused() {
        VehicleFullDataSyncTracker<TestIdentity> tracker = new VehicleFullDataSyncTracker<>(
                10 * SECOND,
                2 * SECOND,
                8 * SECOND);
        TestIdentity first = new TestIdentity(42, "test:vehicle", UUID.randomUUID());
        TestIdentity replacement = new TestIdentity(42, "test:vehicle", UUID.randomUUID());

        assertTrue(tracker.shouldAttempt(first, 100 * SECOND));
        tracker.recordResult(first, 100 * SECOND, true);
        assertFalse(tracker.shouldAttempt(first, 101 * SECOND));

        tracker.retain(List.of(replacement));
        assertEquals(0, tracker.trackedIdentities());
        assertTrue(tracker.shouldAttempt(replacement, 101 * SECOND));
    }

    private record TestIdentity(int entityId, String entityType, UUID uuid) {
    }
}
