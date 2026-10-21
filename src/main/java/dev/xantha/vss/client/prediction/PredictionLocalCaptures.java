package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import dev.xantha.vss.api.VoxelColumnData;

/** Client-thread inbox. It retains coordinates, never references to mutable chunks or worlds. */
final class PredictionLocalCaptures {
    static final int MAX_PENDING = 2048;
    static final long QUIET_NANOS = 100_000_000L, MAX_DELAY_NANOS = 500_000_000L;
    record Request(long key, long first, long latest, boolean edited) { }
    private final Map<Long, Request> pending = new LinkedHashMap<>();
    long coalesced, rejected, snapshots;

    boolean changed(int x, int z, boolean edited, long now) {
        long key = (long) x << 32 | z & 0xffffffffL;
        Request previous = pending.get(key);
        if (previous != null) coalesced++;
        else if (pending.size() >= MAX_PENDING) {
            // Prefer edits over an unmodified chunk arrival. Keep the queue finite during fast travel.
            var iterator = pending.values().iterator();
            boolean removed = false;
            if (edited) while (iterator.hasNext()) if (!iterator.next().edited()) { iterator.remove(); removed = true; break; }
            if (!removed) { rejected++; return false; }
        }
        pending.put(key, new Request(key, previous == null ? now : previous.first(), now,
                edited || previous != null && previous.edited()));
        return true;
    }
    Request next(long now) {
        Request result = null;
        for (var entry : pending.values()) {
            if (now - entry.latest() < QUIET_NANOS && now - entry.first() < MAX_DELAY_NANOS) continue;
            if (result == null || entry.edited() && !result.edited()) result = entry;
            if (result.edited()) break;
        }
        return result;
    }
    Request get(int x, int z) { return pending.get((long) x << 32 | z & 0xffffffffL); }
    Request beforeUnload(int x, int z, long now) {
        Request request = get(x, z);
        // Chunk arrival can precede asynchronous prediction initialization.
        // The unload hook is the last safe opportunity to detach that real data.
        if (request == null && changed(x, z, false, now)) request = get(x, z);
        return request;
    }
    void completed(Request request) { pending.remove(request.key(), request); }
    int size() { return pending.size(); }
    void clear() { pending.clear(); }

    /** Called only on the client thread, before a worker can see the detached section palettes. */
    static VoxelColumnData snapshot(LevelChunk chunk) {
        var source = chunk.getSections();
        if (source.length > 256) return null;
        var sections = new java.util.ArrayList<VoxelColumnData.SectionData>();
        for (int i = 0; i < source.length; i++) {
            var section = source[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (!(section.getBiomes() instanceof PalettedContainer<?> biomes)) return null;
            @SuppressWarnings("unchecked")
            var typed = (PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>) biomes;
            var detached = new LevelChunkSection(section.getStates().copy(), typed.copy());
            sections.add(new VoxelColumnData.SectionData(chunk.getMinSection() + i, detached, null, null));
        }
        return new VoxelColumnData(sections.toArray(VoxelColumnData.SectionData[]::new),
                System.currentTimeMillis(), true);
    }
}
