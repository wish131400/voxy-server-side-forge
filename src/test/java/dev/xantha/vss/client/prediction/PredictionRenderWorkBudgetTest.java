package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionRenderWorkBudgetTest {
    @Test void pressureIsIndependentAndStaleMeasurementsCannotPauseWorkForever() {
        var budget=new PredictionRenderWorkBudget();long now=1_000_000_000L;
        budget.sample(PredictionRenderWorkBudget.Lane.WATER,3_000_000,now);
        assertTrue(budget.busy(PredictionRenderWorkBudget.Lane.WATER,now));
        assertFalse(budget.busy(PredictionRenderWorkBudget.Lane.VEGETATION,now));
        assertEquals(4,budget.surfaceConcurrency(4,now));
        budget.sample(PredictionRenderWorkBudget.Lane.VEGETATION,4_000_000,now);
        assertEquals(1,budget.surfaceConcurrency(4,now));
        budget.sample(PredictionRenderWorkBudget.Lane.SEAM,1_500_000,now);
        assertTrue(budget.busy(PredictionRenderWorkBudget.Lane.SEAM,now));
        assertFalse(budget.busy(now+2_000_000_001L));
        for(int i=0;i<12;i++)budget.sample(PredictionRenderWorkBudget.Lane.VEGETATION,100_000,now+i*100_000_000L);
        assertEquals(4,budget.surfaceConcurrency(4,now+1_200_000_000L));
        budget.clear();assertFalse(budget.busy(now));
    }
}
