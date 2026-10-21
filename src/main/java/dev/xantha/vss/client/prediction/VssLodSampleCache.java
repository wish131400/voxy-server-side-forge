package dev.xantha.vss.client.prediction;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * Bounded JVM-side sample cache. Parent and child tiles share samples at their
 * borders, which avoids re-running the expensive density march on every LOD
 * rebuild while retaining deterministic results.
 *
 * <p>Reads are lock-free: a dozen prediction workers poll this cache between
 * noise evaluations, and a single monitor here once serialized them into a
 * lock convoy. Insertion keeps the strongest sample for a key and sheds
 * arbitrary overflow under one short eviction lock; every entry is
 * re-computable, so losing LRU ordering is acceptable.</p>
 */
public final class VssLodSampleCache {
    private static final int DEFAULT_CAPACITY = 131_072;
    /** Evict down to this fraction of the capacity so the lock is taken rarely. */
    private static final float EVICT_TARGET_FRACTION = 0.875F;

    private final int capacity;
    private final int evictTarget;
    private final Map<Long, ClientColumnSample> entries = new ConcurrentHashMap<>();
    private final Object evictionLock = new Object();
    private final AtomicLong evictions = new AtomicLong();

    public VssLodSampleCache() {
        this(DEFAULT_CAPACITY);
    }

    public VssLodSampleCache(int capacity) {
        if (capacity < 256) {
            throw new IllegalArgumentException("sample cache capacity too small");
        }
        this.capacity = capacity;
        this.evictTarget = (int) Math.max(256L, (long) (capacity * EVICT_TARGET_FRACTION));
    }

    public ClientColumnSample get(long key) {
        return entries.get(key);
    }

    public ClientColumnSample getOrCompute(long key,
                                           LongFunction<ClientColumnSample> factory) {
        ClientColumnSample existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        // Density sampling is the expensive part; no lock is held while a
        // worker evaluates the NoiseRouter.
        ClientColumnSample created = factory.apply(key);
        if (created == null) {
            throw new IllegalArgumentException("sample factory returned null");
        }
        return retain(key, created);
    }

    public void put(long key, ClientColumnSample sample) {
        if (sample == null) return;
        retain(key, sample);
    }

    private ClientColumnSample retain(long key, ClientColumnSample sample) {
        ClientColumnSample selected = entries.compute(key, (ignored, previous) ->
                previous != null && previous.captured() && !sample.captured() ? previous : sample);
        if (entries.size() > capacity) {
            evictOverflow();
        }
        // Overflow eviction or another worker may remove this key immediately.
        // The caller still owns the selected immutable sample; rereading the
        // cache here can return null and discard an otherwise completed tile.
        return selected;
    }

    private void evictOverflow() {
        synchronized (evictionLock) {
            if (entries.size() <= capacity) {
                return;
            }
            int oversize = entries.size() - evictTarget;
            Iterator<Map.Entry<Long, ClientColumnSample>> iterator =
                    entries.entrySet().iterator();
            while (oversize > 0 && iterator.hasNext()) {
                var entry = iterator.next();
                // Predicted columns are recomputable; an authoritative capture
                // may be the only copy when terrain memory is disabled. Also
                // avoid deleting a capture that replaced this entry meanwhile.
                if (!entry.getValue().captured() && entries.remove(entry.getKey(), entry.getValue())) {
                    oversize--; evictions.incrementAndGet();
                }
            }
            // Captures still share the finite cache budget. A cache containing
            // only captures must remain bounded rather than pinning old worlds.
            if (entries.size() > capacity) {
                iterator = entries.entrySet().iterator();
                oversize = entries.size() - evictTarget;
                while (oversize > 0 && iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entries.remove(entry.getKey(), entry.getValue())) { oversize--; evictions.incrementAndGet(); }
                }
            }
        }
    }

    public void remove(long key) {
        entries.remove(key);
    }

    /** Removes only the edited chunk's column coordinates, including negative chunks. */
    public void removeChunk(int chunkX, int chunkZ) {
        int baseX = chunkX * 16, baseZ = chunkZ * 16;
        for (int z = baseZ; z < baseZ + 16; z++) for (int x = baseX; x < baseX + 16; x++)
            entries.remove((long) x << 32 | z & 0xffffffffL);
    }

    /** Dirty notifications withdraw predictions, while the last real snapshot remains a valid fallback. */
    public void removePredictedChunk(int chunkX, int chunkZ) {
        int baseX = chunkX * 16, baseZ = chunkZ * 16;
        for (int z = baseZ; z < baseZ + 16; z++) for (int x = baseX; x < baseX + 16; x++) {
            long key = (long) x << 32 | z & 0xffffffffL;
            var sample = entries.get(key);
            if (sample != null && !sample.captured()) entries.remove(key, sample);
        }
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
