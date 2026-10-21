package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class PredictionCompressionRetryTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void reset() { PredictionMeshRestore.clear(); }

    @Test void busyCompressionRetriesAfterUploadWithoutRetainingRawPassArrays() throws Exception {
        var payload = PredictionMeshCompressionTest.payload(128 * 1024);
        payload.prepareGpuStorage(); int[] before = payload.quads().clone();
        var slots = (Semaphore) field(PredictionPackedMesh.class, "COMPRESSORS").get(null); slots.acquire(2);
        try {
            payload.prepareStorage(); assertFalse(payload.compressed()); payload.uploaded();
            assertTrue(payload.uploadReady(), "raw upload keeps progressing while compressors are busy");
        } finally { slots.release(2); }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!payload.compressed() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(payload.compressed()); assertArrayEquals(before, payload.quads());
        assertNull(field(PredictionPackedMesh.class, "quads").get(payload));
        assertNull(field(PredictionPackedMesh.class, "opaqueStorage").get(payload));
        assertNull(field(PredictionPackedMesh.class, "waterStorage").get(payload));
        assertNull(PredictionMeshRestore.peek(payload));
    }

    @Test void resetRejectsDelayedCompressionFromThePreviousWorldAndReleasesQueueBytes() throws Exception {
        var executor = (ThreadPoolExecutor) field(PredictionCompressionRetry.class, "WORKER").get(null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var gate = executor.submit(() -> { entered.countDown(); release.await(); return true; });
        var payload = PredictionMeshCompressionTest.payload(128 * 1024);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); PredictionCompressionRetry.request(payload);
            assertTrue((long) field(PredictionCompressionRetry.class, "bytes").get(null) > 0);
            PredictionMeshRestore.clear();
        } finally { release.countDown(); gate.get(5, TimeUnit.SECONDS); }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((long) field(PredictionCompressionRetry.class, "bytes").get(null) > 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertFalse(payload.compressed()); assertEquals(0L, field(PredictionCompressionRetry.class, "bytes").get(null));
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
