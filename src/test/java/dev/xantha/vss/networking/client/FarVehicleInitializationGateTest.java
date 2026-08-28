package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarVehicleInitializationGateTest {
    @Test
    void poseOnlySnapshotRemainsPending() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        assertFalse(gate.begin(false, false));
        assertEquals(FarVehicleInitializationGate.State.WAITING_FULL_DATA, gate.state());
        assertFalse(gate.isReady());
    }

    @Test
    void fullSnapshotRequiresInitializationData() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        assertFalse(gate.begin(true, false));
        assertEquals(FarVehicleInitializationGate.State.WAITING_FULL_DATA, gate.state());
        assertTrue(gate.begin(true, true));
        assertEquals(FarVehicleInitializationGate.State.INITIALIZING, gate.state());
    }

    @Test
    void failedInitializationCanRetryOnLaterFullSnapshot() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        assertTrue(gate.begin(true, true));
        gate.markInvalid();
        assertEquals(FarVehicleInitializationGate.State.INVALID, gate.state());
        assertTrue(gate.begin(true, true));
        gate.markReady();
        assertTrue(gate.isReady());
    }
}
