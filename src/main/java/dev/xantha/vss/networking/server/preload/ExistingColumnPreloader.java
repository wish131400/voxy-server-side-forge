package dev.xantha.vss.networking.server.preload;

import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.sending.ColumnDeliveryService;

import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.runtime.PersistentColumnReadCoordinator;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 扫描已有缓存区域，按玩家预加载前沿补齐缺失列，不建立第二套玩家会话。
 * <p>服务端线程管理前沿和队列；磁盘读取交给共享运行时，完成后回服务端线程
 * 复核 epoch、玩家状态身份与当前维度，再由交付服务统一准入。严格顺序客户端自行拉取。
 */
public final class ExistingColumnPreloader {
    private final ServerLifecycleGuard lifecycle;
    private final ColumnDeliveryService delivery;
    private static final int PRELOAD_COLUMNS_PER_REGION = 1024;
    private static final int PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD = 256;
    private static final int PRELOAD_FRONTIER_RING_SLACK = 8;
    private static final int PRELOAD_PENDING_DISK_LIMIT = 32;
    private static final int MANUAL_DISK_READ_RESERVE = 32;

    private final PlayerRequestRegistry playerRegistry;
    private final PersistentColumnLodStore persistentStore;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final DiskTaskRuntime diskRuntime;
    private final PersistentColumnReadCoordinator readCoordinator;

    public ExistingColumnPreloader(
            PlayerRequestRegistry playerRegistry,
            PersistentColumnLodStore persistentStore,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            DiskTaskRuntime diskRuntime,
            PersistentColumnReadCoordinator readCoordinator,
            ServerLifecycleGuard lifecycle,
            ColumnDeliveryService delivery) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        this.delivery = java.util.Objects.requireNonNull(delivery, "delivery");
        this.playerRegistry = playerRegistry;
        this.persistentStore = persistentStore;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.diskRuntime = diskRuntime;
        this.readCoordinator = readCoordinator;
    }

    public void schedule(ServerPlayer player, PlayerRequestState state) {
        // Strict clients pull only the next incomplete ring. Unsolicited cache
        // replay bypasses that order and wastes disk/bandwidth on hidden columns.
        if (state.requiresStrictLodOrder()) return;
        if (lifecycle.isStopping() || !persistentStore.enabled()) {
            return;
        }
        int regionSize = PersistentColumnLodStore.regionSize();
        int centerRegionX = Math.floorDiv(player.getBlockX() >> 4, regionSize);
        int centerRegionZ = Math.floorDiv(player.getBlockZ() >> 4, regionSize);
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks();
        int maxRegionRing = Math.max(0, (maxDistance + regionSize - 1) / regionSize);
        state.resetPreloadRegions(player.serverLevel().dimension(), centerRegionX, centerRegionZ, maxRegionRing);
        VSSLogger.debug("Queued " + state.preloadRegionCount() + " LOD cache regions for cold-start indexed preload around region "
                + centerRegionX + "," + centerRegionZ);
    }

    public void scanRegions(MinecraftServer server) {
        if (lifecycle.isStopping() || !persistentStore.enabled()) {
            return;
        }
        for (Map.Entry<UUID, PlayerRequestState> entry : playerRegistry.entries()) {
            if (!hasPreloadReadCapacity()) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PlayerRequestState state = entry.getValue();
            if (state.requiresStrictLodOrder()) continue;
            if (player != null) {
                updateWindow(player, state);
            }
            if (player == null
                    || state.preloadRegionCount() <= 0
                    || state.preloadColumnCount() >= PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD) {
                continue;
            }
            while (hasPreloadReadCapacity()
                    && state.preloadColumnCount() < PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD) {
                PlayerRequestState.PreloadRegion region = state.pollPreloadRegion();
                if (region == null) {
                    break;
                }
                submitRegionScan(player, state, region);
            }
        }
    }

    public void flushColumns(MinecraftServer server) {
        if (lifecycle.isStopping() || !persistentStore.enabled()) {
            return;
        }
        boolean submittedAny;
        do {
            submittedAny = false;
            for (Map.Entry<UUID, PlayerRequestState> entry : playerRegistry.entries()) {
                if (!hasPreloadReadCapacity()) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                PlayerRequestState state = entry.getValue();
                if (state.requiresStrictLodOrder()) continue;
                if (player == null || state.preloadColumnCount() <= 0) {
                    continue;
                }
                int playerCx = player.getBlockX() >> 4;
                int playerCz = player.getBlockZ() >> 4;
                submittedAny |= submitNextColumnRead(player, state, playerCx, playerCz);
            }
        } while (submittedAny && hasPreloadReadCapacity());
    }

    private void updateWindow(ServerPlayer player, PlayerRequestState state) {
        int regionSize = PersistentColumnLodStore.regionSize();
        int centerRegionX = Math.floorDiv(player.getBlockX() >> 4, regionSize);
        int centerRegionZ = Math.floorDiv(player.getBlockZ() >> 4, regionSize);
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks();
        int maxRegionRing = Math.max(0, (maxDistance + regionSize - 1) / regionSize);
        state.updatePreloadRegions(player.serverLevel().dimension(), centerRegionX, centerRegionZ, maxRegionRing);
    }

    private void submitRegionScan(ServerPlayer player, PlayerRequestState state, PlayerRequestState.PreloadRegion region) {
        UUID playerId = player.getUUID();
        MinecraftServer server = player.server;
        ServerLevel level = player.serverLevel();
        long lifecycleEpoch = lifecycle.currentEpoch();
        int centerCx = player.getBlockX() >> 4;
        int centerCz = player.getBlockZ() >> 4;
        int minDistance = 0;
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks();
        int regionMinimumDistance = minimumChunkDistanceToRegion(centerCx, centerCz, region.regionX(), region.regionZ());
        state.beginPreloadRegionScan(regionMinimumDistance);
        diskRuntime.submitPreloadRead(preloadReadLimit(), manualReadReserve(), () -> {
            ArrayList<PersistentColumnLodStore.ExistingColumn> columns = new ArrayList<>();
            try {
                if (!lifecycle.isStale(lifecycleEpoch)) {
                    columns = persistentStore.findExistingColumnsInRegion(
                            server,
                            level.dimension(),
                            region.regionX(),
                            region.regionZ(),
                            centerCx,
                            centerCz,
                            minDistance,
                            maxDistance,
                            PRELOAD_COLUMNS_PER_REGION);
                }
            } catch (Exception e) {
                VSSLogger.debug("Existing LOD preload region scan failed: " + e.getMessage());
            }
            if (lifecycle.isStale(lifecycleEpoch)) {
                state.finishPreloadRegionScan(regionMinimumDistance);
                return;
            }
            ArrayList<PersistentColumnLodStore.ExistingColumn> foundColumns = columns;
            try {
                server.execute(() -> {
                    try {
                        if (lifecycle.isStale(lifecycleEpoch) || !playerRegistry.isCurrent(playerId, state)) {
                            return;
                        }
                        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                        if (onlinePlayer == null || foundColumns.isEmpty()) {
                            return;
                        }
                        foundColumns.removeIf(column -> generationService.isGenerating(level.dimension(), column.chunkX(), column.chunkZ()));
                        foundColumns.removeIf(column -> state.isClientKnownCurrent(
                                level.dimension(),
                                column.chunkX(),
                                column.chunkZ(),
                                Math.max(
                                        column.timestamp(),
                                        DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), column.chunkX(), column.chunkZ()))));
                        state.addPreloadColumns(foundColumns);
                    } finally {
                        state.finishPreloadRegionScan(regionMinimumDistance);
                    }
                });
            } catch (RejectedExecutionException e) {
                state.finishPreloadRegionScan(regionMinimumDistance);
                VSSLogger.debug("Existing LOD preload region scan handoff rejected: " + e.getMessage());
            }
        }, e -> {
            state.finishPreloadRegionScan(regionMinimumDistance);
            VSSLogger.debug("Existing LOD preload region scan rejected: " + e.getMessage());
        });
    }

    private boolean submitNextColumnRead(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz) {
        while (hasPreloadReadCapacity()) {
            PlayerRequestState.PreloadColumn preload = state.pollFrontierPreloadColumn(
                    playerCx,
                    playerCz,
                    PRELOAD_FRONTIER_RING_SLACK);
            if (preload == null) {
                return false;
            }
            if (!delivery.isColumnStillRelevant(player, player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ())) {
                continue;
            }
            long requiredTimestamp = Math.max(
                    preload.timestamp(),
                    DirtyColumnBroadcaster.latestDirtyTimestamp(player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ()));
            if (state.isClientKnownCurrent(player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ(), requiredTimestamp)) {
                continue;
            }
            submitRead(player, state, preload);
            return true;
        }
        return false;
    }

    private void submitRead(ServerPlayer player, PlayerRequestState state, PlayerRequestState.PreloadColumn preload) {
        if (lifecycle.isStopping()) {
            return;
        }
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        long lifecycleEpoch = lifecycle.currentEpoch();
        state.beginPreloadColumnRead();
        boolean submitted = readCoordinator.submit(
                level.dimension(),
                preload.chunkX(),
                preload.chunkZ(),
                preloadReadLimit(),
                true,
                () -> {
                    if (lifecycle.isStale(lifecycleEpoch)) {
                        return null;
                    }
                    long requiredTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(
                            level.dimension(), preload.chunkX(), preload.chunkZ());
                    ColumnLodCache.Entry cached = columnCache.get(
                            level.dimension(), preload.chunkX(), preload.chunkZ());
                    if (cached != null && cached.isDeliverable() && cached.timestamp() >= requiredTimestamp) {
                        return new PersistentColumnLodStore.Entry(cached.columnData());
                    }
                    return persistentStore.read(
                            server,
                            level.dimension(),
                            preload.chunkX(),
                            preload.chunkZ(),
                            requiredTimestamp);
                },
                storedData -> {
            if (lifecycle.isStale(lifecycleEpoch)) {
                state.finishPreloadColumnRead();
                return;
            }
            if (storedData == null
                    || storedData.columnData() == null
                    || !storedData.columnData().isDeliverable()
                    || lifecycle.isStale(lifecycleEpoch)) {
                state.finishPreloadColumnRead();
                return;
            }
            EncodedColumnData columnData = storedData.columnData();
            try {
                server.execute(() -> {
                    try {
                        if (lifecycle.isStale(lifecycleEpoch) || !playerRegistry.isCurrent(playerId, state)) {
                            return;
                        }
                        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                        if (onlinePlayer == null) {
                            return;
                        }
                        if (generationService.isGenerating(level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                            return;
                        }
                        if (!delivery.isColumnStillRelevant(onlinePlayer, level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                            return;
                        }
                        long requiredTimestamp = Math.max(
                                columnData.columnStamp(),
                                DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), columnData.chunkX(), columnData.chunkZ()));
                        if (state.isClientKnownCurrent(level.dimension(), columnData.chunkX(), columnData.chunkZ(), requiredTimestamp)) {
                            return;
                        }
                        columnCache.putPreloaded(level.dimension(), columnData);
                    } finally {
                        state.finishPreloadColumnRead();
                    }
                });
            } catch (RejectedExecutionException e) {
                state.finishPreloadColumnRead();
                VSSLogger.debug("Existing LOD preload read handoff rejected: " + e.getMessage());
            }
                },
                error -> {
                    state.finishPreloadColumnRead();
                    VSSLogger.debug("Existing LOD preload read failed: " + error.getMessage());
                },
                e -> {
            state.finishPreloadColumnRead();
            VSSLogger.debug("Existing LOD preload read rejected: " + e.getMessage());
        });
    }

    public static int minimumChunkDistanceToRegion(int centerCx, int centerCz, int regionX, int regionZ) {
        int regionSize = PersistentColumnLodStore.regionSize();
        int minX = regionX * regionSize;
        int minZ = regionZ * regionSize;
        int maxX = minX + regionSize - 1;
        int maxZ = minZ + regionSize - 1;
        int dx = centerCx < minX ? minX - centerCx : Math.max(0, centerCx - maxX);
        int dz = centerCz < minZ ? minZ - centerCz : Math.max(0, centerCz - maxZ);
        return Math.max(dx, dz);
    }

    private boolean hasPreloadReadCapacity() {
        return diskRuntime.hasPreloadReadCapacity(preloadReadLimit(), manualReadReserve());
    }

    private static int preloadReadLimit() {
        return Math.min(
                PRELOAD_PENDING_DISK_LIMIT,
                Math.max(1, VSSServerConfig.CONFIG.diskReadQueueLimit));
    }

    private static int manualReadReserve() {
        int limit = preloadReadLimit();
        return Math.min(MANUAL_DISK_READ_RESERVE, Math.max(1, limit / 8));
    }
}
