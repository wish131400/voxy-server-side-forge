package dev.xantha.vss.networking.server.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 同一服务端运行时共享的停止标志与 epoch。
 * <p>异步提交捕获 currentEpoch，交接前检查 isStale；start/stop 由生命周期协调器串行调用。
 * 原子版本只保证旧任务识别，不赋予任务访问 Minecraft 世界或跨线程交付的权限。
 */
public final class ServerLifecycleGuard {
    private final AtomicLong epoch = new AtomicLong();
    private volatile boolean stopping;

    public long start() {
        long nextEpoch = epoch.incrementAndGet();
        stopping = false;
        return nextEpoch;
    }

    public long stop() {
        stopping = true;
        return epoch.incrementAndGet();
    }

    public long currentEpoch() {
        return epoch.get();
    }

    public boolean isStopping() {
        return stopping;
    }

    public boolean isStale(long capturedEpoch) {
        return stopping || capturedEpoch != epoch.get();
    }
}
