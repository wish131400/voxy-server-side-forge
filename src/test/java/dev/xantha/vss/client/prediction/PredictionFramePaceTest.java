package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PredictionFramePaceTest {
    @AfterEach void reset() { PredictionFramePace.resetForTesting(); }

    @Test void diagnosticAverageUpdatesAndLoadingGapsInvalidateOldSamples() {
        PredictionFramePace.resetForTesting();
        long now = 1_000_000_000L;
        assertEquals(-1.0, PredictionFramePace.averageFps());
        for (int i = 0; i <= 40; i++) PredictionFramePace.recordFrame(now + i * 10_000_000L);
        now += 400_000_000L;
        assertEquals(100.0, PredictionFramePace.averageFps(), .01);
        assertTrue(PredictionFramePace.diagnostics().contains("mode=diagnostic-only"));
        now += 3_000_000_000L;
        PredictionFramePace.recordFrame(now);
        assertEquals(-1.0, PredictionFramePace.averageFps());
        for (int i = 1; i <= 100; i++) PredictionFramePace.recordFrame(now + i * 33_333_333L);
        assertEquals(30.0, PredictionFramePace.averageFps(), .01);
    }

    @Test void sampledWindowCountsLongFramesAndDropsEvictedAndLoadingGaps() {
        long now = 1_000_000_000L;
        PredictionFramePace.recordFrame(now);
        for (int i = 0; i < 88; i++) PredictionFramePace.recordFrame(now += 10_000_000L);
        PredictionFramePace.recordFrame(now += 40_000_000L);
        PredictionFramePace.recordFrame(now += 80_000_000L);
        var window = PredictionFramePace.snapshot();
        assertEquals(90,window.samples()); assertEquals(1_000_000_000L,window.durationNanos());
        assertEquals(10_000_000L,window.p95Nanos()); assertEquals(80_000_000L,window.p99Nanos());
        assertEquals(80_000_000L,window.maxNanos()); assertEquals(2,window.over33ms()); assertEquals(1,window.over50ms());
        var counters=PredictionFramePace.counters();
        assertEquals(90L,counters.get("frames")); assertEquals(1L,counters.get("over50ms"));
        assertEquals(88L,((long[])counters.get("histogramMillis"))[10]);
        for (int i=0;i<90;i++) PredictionFramePace.recordFrame(now += 10_000_000L);
        assertEquals(0,PredictionFramePace.snapshot().over33ms());
        PredictionFramePace.recordFrame(now += 3_000_000_000L);
        assertEquals(0,PredictionFramePace.snapshot().samples());
        assertEquals(181L,PredictionFramePace.counters().get("frames"));
        assertEquals(1L,((long[])PredictionFramePace.counters().get("histogramMillis"))[501]);
    }
}
