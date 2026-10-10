package dev.xantha.vss.networking.server.generation;

import static dev.xantha.vss.networking.server.generation.GenerationTickBudget.SnapshotSource.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GenerationTickBudgetTest {
    @Test
    void loadPauseAffectsAutomaticStartsAndResumesWhenHealthy() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 40, 40);
        assertFalse(budget.canStart(false));
        assertTrue(budget.canStart(true));
        budget.beginTick(2, 30, 40);
        assertTrue(budget.canStart(false));
        budget.beginTick(3, Double.NaN, 40);
        assertFalse(budget.canStart(false));
        assertTrue(budget.canStart(true));
        budget.beginTick(4, 100, 0);
        assertTrue(budget.canStart(false));
    }

    @Test
    void actualServerTickSharesLiveAndGeneratedBudgetWithoutResettingAtFlush() {
        GenerationTickBudget budget = new GenerationTickBudget();
        assertTrue(budget.beginTick(1, 10, 40));
        budget.recordSnapshot(LIVE, 200_000);
        assertFalse(budget.beginTick(1, 10, 40));
        assertTrue(budget.canSnapshot(GENERATED, 2, 2));
        budget.recordSnapshot(GENERATED, 200_000);
        assertFalse(budget.canSnapshot(LIVE, 2, 2));
        assertTrue(budget.beginTick(2, 10, 40));
        assertTrue(budget.canSnapshot(LIVE, 2, 2));
    }

    @Test
    void largeCountBudgetLeavesOnlyOneSlotForReadyGeneration() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 10, 0);
        for (int i = 0; i < 127; i++) {
            assertFalse(budget.shouldReserveGeneratedSnapshot(128, 0));
            budget.recordSnapshot(LIVE, 100);
        }
        assertTrue(budget.shouldReserveGeneratedSnapshot(128, 0));
        budget.recordSnapshot(GENERATED, 100);
        assertFalse(budget.shouldReserveGeneratedSnapshot(128, 0));
        assertFalse(budget.canSnapshot(LIVE, 128, 0));
    }

    @Test
    void singleSlotGivesBothSourcesProgressUnderContinuousLoad() {
        GenerationTickBudget budget = new GenerationTickBudget();
        int live = 0;
        int generated = 0;
        for (int tick = 1; tick <= 20; tick++) {
            budget.beginTick(tick, 10, 0);
            if (!budget.shouldReserveGeneratedSnapshot(1, 0)) {
                budget.recordSnapshot(LIVE, 100);
                live++;
            }
            if (budget.canSnapshot(GENERATED, 1, 0)) {
                budget.recordSnapshot(GENERATED, 100);
                generated++;
            } else budget.deferGeneratedSnapshot();
        }
        assertEquals(10, live);
        assertEquals(10, generated);
    }

    @Test
    void timeAllowanceReservesOnlyAfterLiveWorkHasUsedHalf() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 10, 0);
        assertFalse(budget.shouldReserveGeneratedSnapshot(0, 50));
        budget.recordSnapshot(LIVE, 24_000_000);
        assertFalse(budget.shouldReserveGeneratedSnapshot(0, 50));
        budget.recordSnapshot(LIVE, 1_000_000);
        assertTrue(budget.shouldReserveGeneratedSnapshot(0, 50));
        budget.recordSnapshot(GENERATED, 2_000_000);
        assertFalse(budget.shouldReserveGeneratedSnapshot(0, 50));
        assertTrue(budget.canSnapshot(LIVE, 0, 50));
    }

    @Test
    void oneExpensiveSnapshotCannotStarveTheOtherSourceAcrossTicks() {
        GenerationTickBudget budget = new GenerationTickBudget();
        int live = 0;
        int generated = 0;
        for (int tick = 1; tick <= 20; tick++) {
            budget.beginTick(tick, 10, 0);
            if (!budget.shouldReserveGeneratedSnapshot(0, 2)) {
                budget.recordSnapshot(LIVE, 3_000_000);
                live++;
            }
            if (budget.canSnapshot(GENERATED, 0, 2)) {
                budget.recordSnapshot(GENERATED, 3_000_000);
                generated++;
            } else budget.deferGeneratedSnapshot();
        }
        assertEquals(10, live);
        assertEquals(10, generated);
    }

    @Test
    void chunkySnapshotsIgnoreAndDoNotSpendAutomaticBudgets() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 100, 40);
        budget.recordSnapshot(CHUNKY, 100_000_000);
        assertTrue(budget.canSnapshot(LIVE, 1, 2));
        budget.recordSnapshot(LIVE, 3_000_000);
        assertFalse(budget.canSnapshot(GENERATED, 1, 2));
        assertTrue(budget.canSnapshot(CHUNKY, 1, 2));
        assertTrue(budget.diagnostics().contains("snapshotsThisTick=1"));
        assertTrue(budget.diagnostics().contains("chunkySnapshotsThisTick=1"));
    }

    @Test
    void disabledBudgetsKeepAutomaticAdmissionUnrestricted() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 100, 0);
        for (int i = 0; i < 200; i++) {
            budget.recordSnapshot(LIVE, 10_000_000);
            assertTrue(budget.canSnapshot(GENERATED, 0, 0));
            assertFalse(budget.shouldReserveGeneratedSnapshot(0, 0));
        }
    }

    @Test
    void shutdownResetDropsOldPauseAndFairnessStateEvenAtSameTickNumber() {
        GenerationTickBudget budget = new GenerationTickBudget();
        budget.beginTick(1, 100, 40);
        budget.recordSnapshot(LIVE, 3_000_000);
        budget.deferGeneratedSnapshot();
        budget.beginTick(2, 100, 40);
        assertTrue(budget.shouldReserveGeneratedSnapshot(1, 2));
        budget.reset();
        budget.beginTick(2, 10, 40);
        assertTrue(budget.canStart(false));
        assertFalse(budget.shouldReserveGeneratedSnapshot(1, 2));
        assertTrue(budget.canSnapshot(LIVE, 1, 2));
    }
}
