package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.EncodedColumnData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class ColumnLodCache {
    private final VSSServerConfig config;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(1024, 0.75F, true);
    private long cachedBytes;
    private long hits;
    private long misses;
    private long puts;
    private long evictions;
    private long invalidations;
    private long preloadBytes;
    private int preloadEntries;
    private long preloadPuts;
    private long preloadUsefulHits;
    private long preloadUnusedEvictions;

    public ColumnLodCache(VSSServerConfig config) {
        this.config = config;
    }

    public synchronized Entry get(ResourceKey<Level> dimension, int cx, int cz) {
        if (!config.enableColumnCache) {
            return null;
        }

        Entry entry = entries.get(new Key(dimension.location(), cx, cz));
        if (entry == null) {
            misses++;
        } else {
            hits++;
            if (entry.preloaded()) {
                preloadUsefulHits++;
                preloadBytes -= entry.sizeBytes();
                preloadEntries--;
                entry = entry.promoted();
                entries.put(new Key(dimension.location(), cx, cz), entry);
            }
        }
        return entry;
    }

    public synchronized void put(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        put(dimension, columnData, false);
    }

    public synchronized void putPreloaded(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        put(dimension, columnData, true);
    }

    private void put(ResourceKey<Level> dimension, EncodedColumnData columnData, boolean preloaded) {
        if (!config.enableColumnCache || columnData == null || columnData.encodedBytes() == null || !columnData.completeColumn()) {
            return;
        }

        int sizeBytes = columnData.encodedBytes().length;
        if (sizeBytes <= 0 || sizeBytes > config.columnCacheMaxBytes) {
            return;
        }

        Key key = new Key(dimension.location(), columnData.chunkX(), columnData.chunkZ());
        Entry previous = entries.remove(key);
        boolean effectivePreloaded = preloaded;
        if (previous != null) {
            if (previous.timestamp() > columnData.columnStamp()) {
                entries.put(key, previous);
                return;
            }
            effectivePreloaded = preloaded && previous.preloaded();
            cachedBytes -= previous.sizeBytes();
            removePreloadAccounting(previous, false);
        }

        entries.put(key, new Entry(
                columnData.chunkX(),
                columnData.chunkZ(),
                columnData.columnStamp(),
                columnData.compression(),
                columnData.rawSize(),
                columnData.encodedBytes(),
                sizeBytes,
                columnData.schemaVersion(),
                columnData.completeColumn(),
                columnData.sectionYs(),
                columnData.encodedCrc32c(),
                effectivePreloaded));
        this.cachedBytes += sizeBytes;
        if (effectivePreloaded) {
            preloadEntries++;
            preloadBytes += sizeBytes;
            preloadPuts++;
        }
        puts++;
        evictPreloadOverflow();
        evictOverflow();
    }

    public synchronized void invalidate(ResourceKey<Level> dimension, int cx, int cz) {
        Entry removed = entries.remove(new Key(dimension.location(), cx, cz));
        if (removed != null) {
            cachedBytes -= removed.sizeBytes();
            removePreloadAccounting(removed, false);
            invalidations++;
        }
    }

    public synchronized void invalidateOlderThan(ResourceKey<Level> dimension, int cx, int cz, long minimumInvalidTimestamp) {
        Key key = new Key(dimension.location(), cx, cz);
        Entry entry = entries.get(key);
        if (entry == null || entry.timestamp() >= minimumInvalidTimestamp) {
            return;
        }
        entries.remove(key);
        cachedBytes -= entry.sizeBytes();
        removePreloadAccounting(entry, false);
        invalidations++;
    }

    public synchronized void clear() {
        entries.clear();
        cachedBytes = 0L;
        preloadBytes = 0L;
        preloadEntries = 0;
    }

    public synchronized String diagnostics() {
        return String.format(
                "entries=%d, bytes=%.2f MiB, hits=%d, misses=%d, puts=%d, evictions=%d, invalidations=%d, preloadPuts=%d, preloadUsefulHits=%d, preloadUnusedEvictions=%d, preloadEntries=%d, preloadBytes=%.2f MiB",
                entries.size(),
                cachedBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                hits,
                misses,
                puts,
                evictions,
                invalidations,
                preloadPuts,
                preloadUsefulHits,
                preloadUnusedEvictions,
                preloadEntries,
                preloadBytes / (double) VSSServerConfig.BYTES_PER_MIB);
    }

    private void evictPreloadOverflow() {
        int maxEntries = Math.max(1, config.columnCacheMaxEntries / 4);
        long maxBytes = Math.max(1L, config.columnCacheMaxBytes / 4L);
        while (preloadEntries > maxEntries || preloadBytes > maxBytes) {
            Map.Entry<Key, Entry> victim = null;
            for (Map.Entry<Key, Entry> candidate : entries.entrySet()) {
                if (candidate.getValue().preloaded()) {
                    victim = candidate;
                    break;
                }
            }
            if (victim == null) {
                break;
            }
            entries.remove(victim.getKey());
            cachedBytes -= victim.getValue().sizeBytes();
            removePreloadAccounting(victim.getValue(), true);
            evictions++;
        }
    }

    private void evictOverflow() {
        while ((entries.size() > config.columnCacheMaxEntries || cachedBytes > config.columnCacheMaxBytes)
                && !entries.isEmpty()) {
            Map.Entry<Key, Entry> eldest = entries.entrySet().iterator().next();
            cachedBytes -= eldest.getValue().sizeBytes();
            removePreloadAccounting(eldest.getValue(), true);
            entries.remove(eldest.getKey());
            evictions++;
        }
    }

    private void removePreloadAccounting(Entry entry, boolean eviction) {
        if (!entry.preloaded()) {
            return;
        }
        preloadEntries--;
        preloadBytes -= entry.sizeBytes();
        if (eviction) {
            preloadUnusedEvictions++;
        }
    }

    private record Key(ResourceLocation dimension, int chunkX, int chunkZ) {
    }

    public record Entry(
            int chunkX,
            int chunkZ,
            long timestamp,
            int compression,
            int rawSize,
            byte[] encodedBytes,
            int sizeBytes,
            int schemaVersion,
            boolean completeColumn,
            int[] sectionYs,
            int encodedCrc32c,
            boolean preloaded) {
        public EncodedColumnData columnData() {
            return new EncodedColumnData(
                    chunkX,
                    chunkZ,
                    compression,
                    rawSize,
                    encodedBytes,
                    timestamp,
                    schemaVersion,
                    completeColumn,
                    sectionYs,
                    encodedCrc32c);
        }

        Entry promoted() {
            return new Entry(chunkX, chunkZ, timestamp, compression, rawSize, encodedBytes, sizeBytes,
                    schemaVersion, completeColumn, sectionYs, encodedCrc32c, false);
        }
    }
}
