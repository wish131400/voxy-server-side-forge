package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;

import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * 合并列写入、失效水位和缓存维护；文件 I/O 交给共享 DiskTaskRuntime。
 * <p>异步任务捕获生命周期 epoch，写入前核对失效水位，防止旧列覆盖新数据。
 * 完成回调可能同步执行或来自磁盘线程，调用方负责游戏线程交接；本类不拥有执行器。
 */
public final class PersistentColumnWriter {
    private final ServerLifecycleGuard lifecycle;
    private final PersistentColumnLodStore persistentStore;
    private final DiskTaskRuntime diskRuntime;
    private long nextMaintenanceNanos;
    private final java.util.concurrent.atomic.AtomicBoolean maintenancePending = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<ResourceKey<Level>, Long2LongLinkedOpenHashMap> pendingInvalidations = new HashMap<>();
    private final Map<ResourceKey<Level>, Long2LongLinkedOpenHashMap> invalidationWatermarks = new HashMap<>();

    public PersistentColumnWriter(PersistentColumnLodStore persistentStore, DiskTaskRuntime diskRuntime,
            ServerLifecycleGuard lifecycle) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        this.persistentStore = persistentStore;
        this.diskRuntime = diskRuntime;
    }

    public void write(MinecraftServer server, ResourceKey<Level> dimension, EncodedColumnData columnData) {
        if (lifecycle.isStopping()
                || !persistentStore.enabled()
                || columnData == null
                || !isWriteFresh(dimension, columnData)) {
            return;
        }
        long lifecycleEpoch = lifecycle.currentEpoch();
        diskRuntime.submitWrite(VSSServerConfig.CONFIG.persistentColumnCacheWriteQueueLimit, () -> {
            if (!lifecycle.isStale(lifecycleEpoch) && isWriteFresh(dimension, columnData)) {
                persistentStore.write(server, dimension, columnData);
            }
        }, e -> VSSLogger.debug("Persistent LOD write rejected: " + e.getMessage()));
    }

    /** Unlike opportunistic writes, explicit pre-generation waits for disk acknowledgement. */
    public boolean writeConfirmed(MinecraftServer server, ResourceKey<Level> dimension,
            EncodedColumnData columnData, java.util.function.Consumer<Boolean> completion) {
        if (lifecycle.isStopping() || !persistentStore.enabled()
                || columnData == null || !isWriteFresh(dimension, columnData)) {
            completion.accept(false);
            return true;
        }
        long epoch = lifecycle.currentEpoch();
        return diskRuntime.submitWriteUnrestricted(() -> {
            boolean saved = false;
            try {
                saved = !lifecycle.isStale(epoch) && isWriteFresh(dimension, columnData)
                        && persistentStore.writeConfirmed(server, dimension, columnData);
            } finally {
                completion.accept(saved);
            }
        }, error -> { });
    }

    public synchronized void invalidate(ResourceKey<Level> dimension, int cx, int cz, long dirtyTimestamp) {
        if (lifecycle.isStopping() || !persistentStore.enabled()) {
            return;
        }
        long packed = PositionUtil.packPosition(cx, cz);
        recordInvalidationWatermark(dimension, packed, dirtyTimestamp);
        Long2LongLinkedOpenHashMap invalidations = pendingInvalidations.computeIfAbsent(dimension, ignored -> {
            Long2LongLinkedOpenHashMap map = new Long2LongLinkedOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        long previousTimestamp = invalidations.get(packed);
        if (dirtyTimestamp > previousTimestamp) {
            invalidations.putAndMoveToLast(packed, dirtyTimestamp);
        } else if (previousTimestamp <= 0L) {
            invalidations.putAndMoveToLast(packed, dirtyTimestamp);
        }
    }

    public void flushInvalidations(MinecraftServer server) {
        if (lifecycle.isStopping() || !persistentStore.enabled()) {
            return;
        }
        InvalidationBatch batch = drainInvalidations(VSSServerConfig.CONFIG.persistentColumnInvalidationBatchSize);
        if (batch.isEmpty()) {
            return;
        }
        long lifecycleEpoch = lifecycle.currentEpoch();
        boolean submitted = diskRuntime.submitWrite(VSSServerConfig.CONFIG.persistentColumnCacheWriteQueueLimit, () -> {
            if (lifecycle.isStale(lifecycleEpoch)) {
                restoreInvalidations(batch);
                return;
            }
            applyInvalidationBatch(server, batch);
        }, e -> VSSLogger.debug("Persistent LOD invalidation rejected: " + e.getMessage()));
        if (!submitted) {
            restoreInvalidations(batch);
        }
    }

    public void scheduleMaintenance(MinecraftServer server) {
        if (!persistentStore.enabled() || lifecycle.isStopping()) return;
        long now = System.nanoTime();
        if (now < nextMaintenanceNanos || !maintenancePending.compareAndSet(false, true)) return;
        long epoch = lifecycle.currentEpoch();
        boolean accepted = diskRuntime.submitWrite(VSSServerConfig.CONFIG.persistentColumnCacheWriteQueueLimit, () -> {
            try { if (!lifecycle.isStale(epoch)) persistentStore.maintain(server); }
            finally { maintenancePending.set(false); }
        }, error -> maintenancePending.set(false));
        if (accepted) nextMaintenanceNanos = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        else maintenancePending.set(false);
    }

    public void flushInvalidationsBlocking(MinecraftServer server) {
        if (!persistentStore.enabled()) {
            return;
        }
        while (true) {
            InvalidationBatch batch = drainInvalidations(VSSServerConfig.CONFIG.persistentColumnInvalidationBatchSize);
            if (batch.isEmpty()) {
                return;
            }
            applyInvalidationBatch(server, batch);
        }
    }

    private synchronized InvalidationBatch drainInvalidations(int limit) {
        int remaining = Math.max(1, limit);
        InvalidationBatch batch = new InvalidationBatch();
        var dimensionIterator = pendingInvalidations.entrySet().iterator();
        while (dimensionIterator.hasNext() && remaining > 0) {
            Map.Entry<ResourceKey<Level>, Long2LongLinkedOpenHashMap> dimensionEntry = dimensionIterator.next();
            Long2LongLinkedOpenHashMap invalidations = dimensionEntry.getValue();
            var invalidationIterator = invalidations.long2LongEntrySet().fastIterator();
            while (invalidationIterator.hasNext() && remaining > 0) {
                Long2LongMap.Entry entry = invalidationIterator.next();
                batch.add(new Invalidation(dimensionEntry.getKey(), entry.getLongKey(), entry.getLongValue()));
                invalidationIterator.remove();
                remaining--;
            }
            if (invalidations.isEmpty()) {
                dimensionIterator.remove();
            }
        }
        return batch;
    }

    private synchronized void restoreInvalidations(InvalidationBatch batch) {
        for (Invalidation invalidation : batch.invalidations()) {
            Long2LongLinkedOpenHashMap invalidations = pendingInvalidations.computeIfAbsent(invalidation.dimension(), ignored -> {
                Long2LongLinkedOpenHashMap map = new Long2LongLinkedOpenHashMap();
                map.defaultReturnValue(0L);
                return map;
            });
            long previousTimestamp = invalidations.get(invalidation.packed());
            if (invalidation.dirtyTimestamp() > previousTimestamp || previousTimestamp <= 0L) {
                invalidations.putAndMoveToLast(invalidation.packed(), invalidation.dirtyTimestamp());
            }
        }
    }

    private void applyInvalidationBatch(MinecraftServer server, InvalidationBatch batch) {
        for (Invalidation invalidation : batch.invalidations()) {
            persistentStore.invalidateOlderThan(
                    server,
                    invalidation.dimension(),
                    PositionUtil.unpackX(invalidation.packed()),
                    PositionUtil.unpackZ(invalidation.packed()),
                    invalidation.dirtyTimestamp());
        }
    }

    private synchronized boolean isWriteFresh(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        Long2LongLinkedOpenHashMap watermarks = invalidationWatermarks.get(dimension);
        if (watermarks == null) {
            return true;
        }
        long packed = PositionUtil.packPosition(columnData.chunkX(), columnData.chunkZ());
        long watermark = watermarks.get(packed);
        return watermark <= 0L || columnData.columnStamp() >= watermark;
    }

    private synchronized void recordInvalidationWatermark(ResourceKey<Level> dimension, long packed, long dirtyTimestamp) {
        Long2LongLinkedOpenHashMap watermarks = invalidationWatermarks.computeIfAbsent(dimension, ignored -> {
            Long2LongLinkedOpenHashMap map = new Long2LongLinkedOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        long previousTimestamp = watermarks.get(packed);
        if (dirtyTimestamp > previousTimestamp || previousTimestamp <= 0L) {
            watermarks.putAndMoveToLast(packed, dirtyTimestamp);
        }
        evictWatermarkOverflow();
    }

    private void evictWatermarkOverflow() {
        int maxEntries = Math.max(1, VSSServerConfig.CONFIG.dirtyVersionCacheMaxEntries);
        while (watermarkCount() > maxEntries && !invalidationWatermarks.isEmpty()) {
            ResourceKey<Level> oldestDimension = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<ResourceKey<Level>, Long2LongLinkedOpenHashMap> entry : invalidationWatermarks.entrySet()) {
                Long2LongLinkedOpenHashMap watermarks = entry.getValue();
                if (!watermarks.isEmpty()) {
                    long timestamp = watermarks.get(watermarks.firstLongKey());
                    if (timestamp < oldestTimestamp) {
                        oldestTimestamp = timestamp;
                        oldestDimension = entry.getKey();
                    }
                }
            }
            if (oldestDimension == null) {
                invalidationWatermarks.clear();
                return;
            }
            Long2LongLinkedOpenHashMap watermarks = invalidationWatermarks.get(oldestDimension);
            watermarks.removeFirstLong();
            if (watermarks.isEmpty()) {
                invalidationWatermarks.remove(oldestDimension);
            }
        }
    }

    private int watermarkCount() {
        int count = 0;
        for (Long2LongLinkedOpenHashMap watermarks : invalidationWatermarks.values()) {
            count += watermarks.size();
        }
        return count;
    }

    private record Invalidation(ResourceKey<Level> dimension, long packed, long dirtyTimestamp) {
    }

    private static final class InvalidationBatch {
        private final java.util.ArrayList<Invalidation> invalidations = new java.util.ArrayList<>();

        void add(Invalidation invalidation) {
            invalidations.add(invalidation);
        }

        boolean isEmpty() {
            return invalidations.isEmpty();
        }

        java.util.List<Invalidation> invalidations() {
            return invalidations;
        }
    }
}
