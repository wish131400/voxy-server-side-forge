package dev.xantha.vss.client.prediction;

import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/** Session-local, bounded city planning hints for prediction tiles. */
final class LostCityHints {
    private final ResourceKey<Level> dimension;
    private final long session;
    private final boolean installed;
    private final Map<Long, int[]> regions = new ConcurrentHashMap<>();
    private final Set<Long> wanted = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> requested = new ConcurrentHashMap<>();
    private long lastSent;
    private long lastPrune;

    LostCityHints(ResourceKey<Level> dimension, long session) {
        this(dimension, session, ModList.get() != null && ModList.get().isLoaded("lostcities"));
    }

    LostCityHints(ResourceKey<Level> dimension, long session, boolean installed) {
        this.dimension = dimension;
        this.session = session;
        this.installed = installed;
    }

    void observeArea(int baseX, int baseZ, int span) {
        if (!installed) return;
        for (int z = Math.floorDiv(baseZ, 128); z <= Math.floorDiv(baseZ + span - 1, 128); z++)
            for (int x = Math.floorDiv(baseX, 128); x <= Math.floorDiv(baseX + span - 1, 128); x++) {
                long key = key(x, z);
                if (!regions.containsKey(key)) wanted.add(key);
            }
    }

    void tick(int playerChunkX, int playerChunkZ) {
        if (!installed) return;
        long now = System.nanoTime();
        if (now - lastPrune >= 5_000_000_000L) {
            regions.keySet().removeIf(key -> Math.abs((long) (int) (key >> 32) * 8 - playerChunkX) > 288
                    || Math.abs((long) key.intValue() * 8 - playerChunkZ) > 288);
            lastPrune = now;
        }
        if (wanted.isEmpty() || now - lastSent < 200_000_000L) return;
        long best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (long key : wanted) {
            if (regions.containsKey(key)) { wanted.remove(key); continue; }
            long dx = (long) (int) (key >> 32) * 8 + 4 - playerChunkX;
            long dz = (long) (int) key * 8 + 4 - playerChunkZ;
            long distance = dx * dx + dz * dz;
            if (distance > 256L * 256L) { wanted.remove(key); requested.remove(key); continue; }
            if (now - requested.getOrDefault(key, 0L) < 5_000_000_000L) continue;
            if (distance < bestDistance) { best = key; bestDistance = distance; }
        }
        if (bestDistance == Long.MAX_VALUE) return;
        lastSent = now;
        requested.put(best, now);
        VSSNetworking.sendToServer(new LostCityHintsC2SPayload(dimension.location(),
                (int) (best >> 32), (int) best, session));
    }

    boolean accept(LostCityHintsS2CPayload response) {
        if (!installed || !dimension.location().equals(response.dimension()) || session != response.session()) return false;
        long key = key(response.regionX(), response.regionZ());
        requested.remove(key);
        wanted.remove(key);
        int[] value = response.active() ? response.chunks() : new int[0];
        int[] old = regions.put(key, value);
        return !Arrays.equals(old, value) && (containsBuildings(old) || containsBuildings(value));
    }

    private static boolean containsBuildings(int[] values) {
        if (values == null) return false;
        for (int value : values) if (kind(value) == 2) return true;
        return false;
    }

    int chunk(int chunkX, int chunkZ) {
        int[] region = regions.get(key(Math.floorDiv(chunkX, 8), Math.floorDiv(chunkZ, 8)));
        return region == null || region.length == 0 ? 0 : region[Math.floorMod(chunkZ, 8) * 8 + Math.floorMod(chunkX, 8)];
    }

    int[] snapshot(int baseX, int baseZ, int span) {
        if (!installed) return null;
        int baseChunkX = Math.floorDiv(baseX, 16), baseChunkZ = Math.floorDiv(baseZ, 16);
        int side = Math.floorDiv(span, 16);
        int[] result = new int[side * side];
        boolean hasBuildings = false;
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int hint = chunk(baseChunkX + x, baseChunkZ + z);
            result[z * side + x] = hint;
            hasBuildings |= kind(hint) == 2;
        }
        return hasBuildings ? result : null;
    }

    static int kind(int packed) { return packed & 3; }
    static int ground(int packed) { return (short) (packed >>> 2); }
    static int floors(int packed) { return packed >>> 18 & 127; }
    static int style(int packed) { return packed >>> 25 & 15; }
    static int top(int packed) { return ground(packed) + Math.min(20, floors(packed)) * 6; }

    static PredictionDepthBound includeBuildings(PredictionDepthBound bounds, int[] buildings) {
        if (buildings == null) return bounds;
        int min = bounds.minY(), max = bounds.maxY();
        for (int hint : buildings) if (kind(hint) == 2) {
            min = Math.min(min, ground(hint));
            max = Math.max(max, top(hint));
        }
        return new PredictionDepthBound(min, max);
    }

    private static long key(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
}
