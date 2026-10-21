package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionQueueTimingsTest {
    @Test void percentileSeparatesTypicalWaitFromTailWithoutRetainingSamples() {
        var timings = new PredictionQueueTimings();
        assertEquals(0, timings.percentileUpperMillis(95));
        for (int i = 0; i < 90; i++) timings.record(2_000_000);
        for (int i = 0; i < 10; i++) timings.record(100_000_000);
        assertEquals(2.048, timings.percentileUpperMillis(50));
        assertEquals(131.072, timings.percentileUpperMillis(95));
        assertTrue(timings.diagnostics().contains("samples=100"));
        assertTrue(timings.diagnostics().contains("maxMs=100.0"));
    }
    @Test void negativeAndLongWaitsRemainBounded() {
        var timings = new PredictionQueueTimings();
        timings.record(-1);
        timings.record(60_000_000_000L);
        assertTrue(timings.percentileUpperMillis(100) >= 60_000);
        assertTrue(timings.diagnostics().contains("maxMs=60000.0"));
    }
}
