package dev.xantha.vss.client.prediction;

/** Java 17 equivalent of the test fixtures' Java 21 executor resource scope. */
final class TestExecutor extends java.util.concurrent.ThreadPoolExecutor implements AutoCloseable {
    TestExecutor(int threads) {
        super(threads, threads, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
    }
    public void close() {
        shutdownNow();
        try {
            if (!awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker did not terminate");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
