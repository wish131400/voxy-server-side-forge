package dev.xantha.vss.client.prediction;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-size queue-wait histogram; records allocate nothing on worker threads. */
final class PredictionQueueTimings {
    private final AtomicLongArray buckets = new AtomicLongArray(32);
    private final LongAdder count = new LongAdder(), nanos = new LongAdder();
    private final AtomicLong maximum = new AtomicLong();

    void record(long waitNanos) {
        long wait = Math.max(0, waitNanos), micros = Math.max(1, wait / 1000);
        int bucket = Math.min(31, 63 - Long.numberOfLeadingZeros(micros));
        buckets.incrementAndGet(bucket);
        count.increment(); nanos.add(wait); maximum.accumulateAndGet(wait, Math::max);
    }

    double percentileUpperMillis(int percent) {
        long total = 0;
        for (int i = 0; i < buckets.length(); i++) total += buckets.get(i);
        if (total == 0) return 0;
        long target = Math.max(1, (total * Math.max(0, Math.min(100, percent)) + 99) / 100);
        long seen = 0;
        for (int i = 0; i < buckets.length(); i++) {
            seen += buckets.get(i);
            if (seen >= target) return (1L << (i + 1)) / 1000D;
        }
        return maximum.get() / 1_000_000D;
    }

    String diagnostics() {
        long samples = count.sum();
        return "samples=" + samples + ",meanMs=" + (samples == 0 ? 0 : nanos.sum() / (samples * 1_000_000D))
                + ",p50UpperMs=" + percentileUpperMillis(50) + ",p95UpperMs=" + percentileUpperMillis(95)
                + ",maxMs=" + maximum.get() / 1_000_000D;
    }
}
