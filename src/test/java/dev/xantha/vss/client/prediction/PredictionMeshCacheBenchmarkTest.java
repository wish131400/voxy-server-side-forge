package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Locale;
import org.junit.jupiter.api.*;

/** Run in a fresh JVM so static mocking in correctness tests cannot inflate allocations. */
class PredictionMeshCacheBenchmarkTest {
    private static final byte[] ID = new byte[32];
    @BeforeAll static void bootstrap() { PredictionMeshDerivedCacheTest.bootstrap(); }

    @Test void preparedRestoreRecordsTimeAndAllocationAgainstCanonicalFormat() throws Exception {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int features : new int[]{0, 8192}) {
            var mesh = PredictionMeshDerivedCacheTest.fixture(features, true);
            byte[] prepared = PredictionMeshCodec.encode(mesh, ID);
            byte[] canonical = PredictionMeshCacheTestRecords.legacy(mesh, ID, ID, true, true, 9);
            for (int i = 0; i < 20; i++) { PredictionMeshCodec.decode(canonical, ID, 64); PredictionMeshCodec.decode(prepared, ID, 64); }
            long[] baseline = measure(bean, canonical), restored = measure(bean, prepared);
            System.out.printf(Locale.ROOT, "CACHE_RESTORE_BENCH features=%d iterations=48 legacyMs=%.3f preparedMs=%.3f legacyKiB=%.1f preparedKiB=%.1f legacyBytes=%d preparedBytes=%d%n",
                    features, baseline[0] / 48e6, restored[0] / 48e6, baseline[1] / 48.0 / 1024, restored[1] / 48.0 / 1024,
                    canonical.length, prepared.length);
        }
    }

    private static long[] measure(com.sun.management.ThreadMXBean bean, byte[] bytes) throws Exception {
        long id = Thread.currentThread().getId(), allocation = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        for (int i = 0; i < 48; i++) assertNotNull(PredictionMeshCodec.decode(bytes, ID, 64));
        return new long[]{System.nanoTime() - start, bean.getThreadAllocatedBytes(id) - allocation};
    }
}
