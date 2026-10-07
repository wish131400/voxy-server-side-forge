package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GenerationTickBudgetTest {
    @Test
    void pausesAdmissionWhenBusyAndResumesWhenHealthy() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 40, 40);
        assertFalse(budget.canStart());
        budget.beginTick(2, 30, 40);
        assertTrue(budget.canStart());
        budget.beginTick(3, Double.NaN, 40);
        assertFalse(budget.canStart());
        budget.beginTick(4, 100, 0);
        assertTrue(budget.canStart());
    }

    @Test
    void countsLiveAndGeneratedSnapshotsAgainstOneTickBudget() {
        GenerationTickBudget budget = new GenerationTickBudget();
        assertTrue(budget.beginTick(1, 10, 40));
        assertTrue(budget.canSnapshot(1, 2));
        budget.recordSnapshot(200_000);
        assertFalse(budget.canSnapshot(1, 2));
        assertFalse(budget.beginTick(1, 10, 40));
        assertFalse(budget.canSnapshot(1, 2));
        assertTrue(budget.beginTick(2, 10, 40));
        assertTrue(budget.canSnapshot(1, 2));
    }

    @Test
    void timeBudgetDefersNextSnapshotButDoesNotPretendToInterruptOne() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 10, 40);
        budget.recordSnapshot(3_000_000);
        assertFalse(budget.canSnapshot(10, 2));
        assertTrue(budget.canSnapshot(0, 0));
        assertTrue(budget.diagnostics().contains("snapshotMsThisTick=3.000"));
        budget.beginTick(2, 100, 40);
        // Already generated chunks may still be handed off and their tickets released.
        assertTrue(budget.canSnapshot(1, 2));
    }
}
