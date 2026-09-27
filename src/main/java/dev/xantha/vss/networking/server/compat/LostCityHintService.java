package dev.xantha.vss.networking.server.compat;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/** Queries Lost Cities planning data without asking Minecraft to generate chunks. */
public final class LostCityHintService {
    private static final int MAX_DISTANCE_CHUNKS = 256;
    private static final int MAX_QUEUED = 8;
    private static final Map<UUID, Long> LAST_REQUEST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong LIFECYCLE = new java.util.concurrent.atomic.AtomicLong();
    private record Region(net.minecraft.resources.ResourceKey<Level> dimension, int x, int z) { }
    private static final Map<Region, int[]> SUMMARIES = new java.util.LinkedHashMap<>(64, .75F, true);
    private static volatile ThreadPoolExecutor executor;
    private static volatile boolean loggedFailure;

    private LostCityHintService() { }

    public static void handle(ServerPlayer player, LostCityHintsC2SPayload request) {
        if (!VSSServerConfig.CONFIG.enabled || !VSSServerConfig.CONFIG.enablePredictionSync
                || !player.serverLevel().dimension().location().equals(request.dimension())) return;
        long x = (long) request.regionX() * LostCityHintsS2CPayload.REGION_CHUNKS + 4;
        long z = (long) request.regionZ() * LostCityHintsS2CPayload.REGION_CHUNKS + 4;
        if (Math.abs(x - Math.floorDiv(player.getBlockX(), 16)) > MAX_DISTANCE_CHUNKS
                || Math.abs(z - Math.floorDiv(player.getBlockZ(), 16)) > MAX_DISTANCE_CHUNKS) return;
        long now = System.nanoTime();
        UUID playerId = player.getUUID();
        Long last = LAST_REQUEST.get(playerId);
        if (last != null && now - last < 150_000_000L) return;
        LAST_REQUEST.put(playerId, now);
        if (!ModList.get().isLoaded("lostcities")) {
            send(player, request, false, new int[0]);
            return;
        }
        Object info;
        try {
            // Older releases mutate shared planner caches without a dimension lock.
            Class.forName("mcjty.lostcities.worldgen.lost.BuildingInfo").getDeclaredMethod(
                    "getDimensionLock", net.minecraft.resources.ResourceKey.class);
            Class<?> mod = Class.forName("mcjty.lostcities.LostCities");
            Object api = mod.getField("lostCitiesImp").get(null);
            info = api.getClass().getMethod("getLostInfo", Level.class).invoke(api, player.serverLevel());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logFailure(failure);
            send(player, request, false, new int[0]);
            return;
        }
        if (info == null) {
            send(player, request, false, new int[0]);
            return;
        }
        Region region = new Region(player.serverLevel().dimension(), request.regionX(), request.regionZ());
        synchronized (SUMMARIES) {
            int[] cached = SUMMARIES.get(region);
            if (cached != null) { send(player, request, true, cached); return; }
        }
        if (IN_FLIGHT.incrementAndGet() > MAX_QUEUED) {
            IN_FLIGHT.decrementAndGet();
            return;
        }
        ServerLevel level = player.serverLevel();
        long lifecycle = LIFECYCLE.get();
        try {
            executor().execute(() -> {
                try {
                    int[] chunks = query(info, request.regionX(), request.regionZ());
                    synchronized (SUMMARIES) {
                        if (lifecycle != LIFECYCLE.get()) return;
                        SUMMARIES.put(region, chunks);
                        while (SUMMARIES.size() > 512) SUMMARIES.remove(SUMMARIES.keySet().iterator().next());
                    }
                    level.getServer().execute(() -> {
                        if (lifecycle == LIFECYCLE.get() && player.isAlive() && player.serverLevel() == level)
                            send(player, request, true, chunks);
                    });
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    if (lifecycle != LIFECYCLE.get() || Thread.currentThread().isInterrupted()) return;
                    logFailure(failure);
                    level.getServer().execute(() -> {
                        if (lifecycle == LIFECYCLE.get() && player.isAlive() && player.serverLevel() == level)
                            send(player, request, false, new int[0]);
                    });
                } finally {
                    IN_FLIGHT.updateAndGet(count -> Math.max(0, count - 1));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            IN_FLIGHT.decrementAndGet();
        }
    }

    static int[] query(Object info, int regionX, int regionZ) throws ReflectiveOperationException {
        ClassLoader loader = info.getClass().getClassLoader();
        Class<?> infoType = Class.forName("mcjty.lostcities.api.ILostCityInformation", false, loader);
        Method getChunk = infoType.getMethod("getChunkInfo", int.class, int.class);
        Method realHeight = infoType.getMethod("getRealHeight", int.class);
        Class<?> chunkType = Class.forName("mcjty.lostcities.api.ILostChunkInfo", false, loader);
        Method isCity = chunkType.getMethod("isCity");
        Method building = chunkType.getMethod("getBuildingId");
        Method cityLevel = chunkType.getMethod("getCityLevel");
        Method floors = chunkType.getMethod("getNumFloors");
        int[] chunks = new int[LostCityHintsS2CPayload.ENTRY_COUNT];
        for (int z = 0; z < LostCityHintsS2CPayload.REGION_CHUNKS; z++) {
            for (int x = 0; x < LostCityHintsS2CPayload.REGION_CHUNKS; x++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                Object chunk = getChunk.invoke(info, regionX * 8 + x, regionZ * 8 + z);
                Object id = building.invoke(chunk);
                int kind = id != null ? 2 : Boolean.TRUE.equals(isCity.invoke(chunk)) ? 1 : 0;
                if (kind == 0) continue;
                int ground = (Integer) realHeight.invoke(info, (Integer) cityLevel.invoke(chunk));
                int count = kind == 2 ? Math.min(127, Math.max(1, (Integer) floors.invoke(chunk))) : 0;
                int style = id == null ? 0 : id.hashCode() & 15;
                chunks[z * 8 + x] = pack(kind, ground, count, style);
            }
        }
        return chunks;
    }

    public static int pack(int kind, int ground, int floors, int style) {
        return kind & 3 | (ground & 0xffff) << 2 | (floors & 127) << 18 | (style & 15) << 25;
    }

    private static void send(ServerPlayer player, LostCityHintsC2SPayload request,
                             boolean active, int[] chunks) {
        VSSNetworking.sendToPlayer(player, new LostCityHintsS2CPayload(request.dimension(),
                request.regionX(), request.regionZ(), request.session(), active, chunks));
    }

    private static synchronized ThreadPoolExecutor executor() {
        if (executor == null || executor.isShutdown()) {
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_QUEUED), task -> {
                        Thread thread = new Thread(task, "VSS-LostCities-hints");
                        thread.setDaemon(true);
                        return thread;
                    });
        }
        return executor;
    }

    public static synchronized void stop() {
        LIFECYCLE.incrementAndGet();
        synchronized (SUMMARIES) { SUMMARIES.clear(); }
        if (executor != null) {
            int cancelled = executor.shutdownNow().size();
            IN_FLIGHT.updateAndGet(count -> Math.max(0, count - cancelled));
        }
        executor = null;
        LAST_REQUEST.clear();
        loggedFailure = false;
    }

    public static void forgetPlayer(UUID playerId) { LAST_REQUEST.remove(playerId); }

    private static void logFailure(Exception failure) {
        if (!loggedFailure) {
            loggedFailure = true;
            VSSLogger.warn("VSS Lost Cities planning unavailable; city preview disabled", failure);
        }
    }
}
