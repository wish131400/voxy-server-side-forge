package dev.xantha.vss.client.prediction;

import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 客户端后台准备任务的唯一异步状态机：每个实例保留一个请求，共享低优先级工作线程。
 * <p>request/poll/clear 在所属客户端线程调用，build 在执行器线程运行且不得读可变世界或操作 GPU。
 * clear 递增代次并释放完成结果的引用，仍保留旧任务槽位直至退出；异常保留已发布状态并允许重试。
 */
final class PredictionClientPreparation<I, O> {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "vss-client-planning");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Executor executor;
    private final Function<I, O> build;
    private CompletableFuture<O> pending;
    private volatile long generation;
    private long requestedGeneration;
    private final java.util.concurrent.atomic.LongAdder preparationNanos = new java.util.concurrent.atomic.LongAdder();

    PredictionClientPreparation(Function<I, O> build) { this(WORKER, build); }
    PredictionClientPreparation(Executor executor, Function<I, O> build) {
        this.executor = executor; this.build = build;
    }
    boolean pending() { return pending != null; }
    boolean request(I input) {
        if (pending != null) return false;
        long requestGeneration = generation;
        requestedGeneration = requestGeneration;
        pending = CompletableFuture.supplyAsync(() -> {
            if (requestGeneration != generation) return null;
            long started = System.nanoTime();
            try {
                var result = build.apply(input);
                return requestGeneration == generation ? result : null;
            } finally { preparationNanos.add(System.nanoTime() - started); }
        }, executor);
        return true;
    }
    long preparationNanos() { return preparationNanos.sum(); }
    O poll() {
        if (pending == null || !pending.isDone()) return null;
        var finished = pending; pending = null;
        if (requestedGeneration != generation) return null;
        try { return finished.getNow(null); }
        catch (CompletionException failure) {
            dev.xantha.vss.common.VSSLogger.warn("VSS client preparation failed; retaining the published state", failure);
            return null;
        }
    }
    void clear() {
        generation++;
        // Keep the slot until completion; repeated resets cannot enqueue more
        // work. Release a completed result even if an exited world never polls.
        if (pending != null) {
            var abandoned = pending;
            abandoned.handle((result, failure) -> { abandoned.obtrudeValue(null); return null; });
        }
    }
}
