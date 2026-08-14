package dev.xantha.vss.networking.server.request;


import dev.xantha.vss.networking.server.diagnostics.ServerRequestStats;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.runtime.PersistentColumnReadCoordinator;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.NbtSectionSerializer;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;

public final class ColumnStorageReadPipeline {
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final PersistentColumnLodStore persistentStore;
    private final PersistentColumnWriter persistentWriter;
    private final ServerRequestStats requestStats;
    private final DiskTaskRuntime diskRuntime;
    private final PersistentColumnReadCoordinator readCoordinator;
    private final ConcurrentHashMap<NbtReadKey, NbtSharedRead> inFlightNbtReads = new ConcurrentHashMap<>();
    private final Object nbtGateLock = new Object();
    private final ArrayDeque<NbtGateWaiter> nbtGateWaiters = new ArrayDeque<>();
    private final AtomicLong nbtReadsSubmitted = new AtomicLong();
    private final AtomicLong nbtReadsCompleted = new AtomicLong();
    private final AtomicLong nbtReadHits = new AtomicLong();
    private final AtomicLong nbtReadMisses = new AtomicLong();
    private final AtomicLong nbtReadFailures = new AtomicLong();
    private final AtomicLong nbtReadsCoalesced = new AtomicLong();
    private final AtomicLong nbtGateRejected = new AtomicLong();
    private int activeNbtReads;
    private long nbtGateLifecycleEpoch = Long.MIN_VALUE;

    public ColumnStorageReadPipeline(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            PersistentColumnLodStore persistentStore,
            PersistentColumnWriter persistentWriter,
            ServerRequestStats requestStats,
            DiskTaskRuntime diskRuntime,
            PersistentColumnReadCoordinator readCoordinator) {
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.persistentStore = persistentStore;
        this.persistentWriter = persistentWriter;
        this.requestStats = requestStats;
        this.diskRuntime = diskRuntime;
        this.readCoordinator = readCoordinator;
    }

    public String nbtDiagnostics() {
        NbtDiagnostics snapshot = nbtDiagnosticsSnapshot();
        return String.format(
                "submitted=%d, completed=%d, hits=%d, misses=%d, failures=%d, active=%d, queued=%d, coalesced=%d, rejected=%d",
                snapshot.submitted(),
                snapshot.completed(),
                snapshot.hits(),
                snapshot.misses(),
                snapshot.failures(),
                snapshot.active(),
                snapshot.queued(),
                snapshot.coalesced(),
                snapshot.rejected());
    }

    public NbtDiagnostics nbtDiagnosticsSnapshot() {
        synchronized (nbtGateLock) {
            return new NbtDiagnostics(
                    nbtReadsSubmitted.get(),
                    nbtReadsCompleted.get(),
                    nbtReadHits.get(),
                    nbtReadMisses.get(),
                    nbtReadFailures.get(),
                    activeNbtReads,
                    nbtGateWaiters.size(),
                    nbtReadsCoalesced.get(),
                    nbtGateRejected.get());
        }
    }

    public void resetNbtReads() {
        synchronized (nbtGateLock) {
            nbtGateLifecycleEpoch = Long.MIN_VALUE;
            nbtGateWaiters.clear();
            activeNbtReads = 0;
        }
        diskRuntime.resetExpensiveReads();
        DiskNbtReadResult stopped = DiskNbtReadResult.empty();
        for (var entry : inFlightNbtReads.entrySet()) {
            if (inFlightNbtReads.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().result().complete(stopped);
            }
        }
    }

    public boolean submitLoadedColumn(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            LevelChunk chunk,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        return generationService.submitLoadedColumn(
                player.getUUID(),
                state,
                requestId,
                level,
                chunk,
                cx,
                cz,
                minimumTimestamp,
                priority);
    }

    public void submitStorageRead(
            ServerPlayer player,
            PlayerRequestState state,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            long dirtyTimestamp,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean priority) {
        if (VSSServerNetworking.isServerStopping()) {
            state.clearRequest(requestId);
            return;
        }
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        DiskReadContext readContext = new DiskReadContext(
                VSSServerNetworking.lifecycleEpoch(),
                playerId,
                level,
                state,
                requestId,
                cx,
                cz,
                columnTimestamp,
                preferLoadedColumn,
                allowGeneration,
                priority);
        boolean submitted = readCoordinator.submit(
                level.dimension(),
                cx,
                cz,
                VSSServerConfig.CONFIG.diskReadQueueLimit,
                false,
                () -> readPersistentColumn(server, readContext, dirtyTimestamp),
                storedData -> readFromDisk(server, readContext, storedData, dirtyTimestamp),
                error -> {
                    readContext.requestState().clearRequest(readContext.requestId());
                    VSSLogger.warn("Failed to finish VSS disk read at "
                            + readContext.cx() + ", " + readContext.cz() + ": " + error.getMessage());
                },
                e -> {
                    readContext.requestState().clearRequest(readContext.requestId());
                    sendBackpressured(player, readContext.requestId());
                });
        if (submitted) {
            requestStats.recordDiskReadSubmitted();
        }
    }

    private PersistentColumnLodStore.Entry readPersistentColumn(
            MinecraftServer server,
            DiskReadContext readContext,
            long dirtyTimestamp) {
        if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
            return null;
        }
        ColumnLodCache.Entry cached = columnCache.get(
                readContext.level().dimension(),
                readContext.cx(),
                readContext.cz());
        long minimumTimestamp = Math.max(0L, dirtyTimestamp);
        if (cached != null
                && cached.completeColumn()
                && cached.timestamp() >= minimumTimestamp) {
            return new PersistentColumnLodStore.Entry(cached.columnData());
        }
        PersistentColumnLodStore.Entry storedData = persistentStore.read(
                server,
                readContext.level().dimension(),
                readContext.cx(),
                readContext.cz(),
                minimumTimestamp);
        return VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch()) ? null : storedData;
    }

    private void readFromDisk(
            MinecraftServer server,
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            long dirtyTimestamp) {
        try {
            if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
                return;
            }
            readExistingChunkNbtAsync(readContext, storedData).whenComplete((diskNbtRead, error) -> {
                DiskNbtReadResult completed = error == null && diskNbtRead != null
                        ? diskNbtRead
                        : DiskNbtReadResult.failure();
                try {
                    server.execute(() -> finishDiskRead(
                            readContext,
                            storedData,
                            completed.columnData(),
                            completed.failed()));
                } catch (RejectedExecutionException e) {
                    readContext.requestState().clearRequest(readContext.requestId());
                }
            });
        } catch (RejectedExecutionException e) {
            readContext.requestState().clearRequest(readContext.requestId());
        } catch (Exception e) {
            readContext.requestState().clearRequest(readContext.requestId());
            VSSLogger.warn("Failed to finish VSS disk read at "
                    + readContext.cx() + ", " + readContext.cz() + ": " + e.getMessage());
        }
    }

    private CompletableFuture<DiskNbtReadResult> readExistingChunkNbtAsync(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData) {
        if (storedData != null
                || readContext.preferLoadedColumn()
                || !shouldReadExistingChunkNbt(readContext.allowGeneration())) {
            return CompletableFuture.completedFuture(DiskNbtReadResult.empty());
        }
        NbtReadKey key = new NbtReadKey(
                readContext.lifecycleEpoch(),
                readContext.level().dimension(),
                readContext.cx(),
                readContext.cz());
        NbtSharedRead created = new NbtSharedRead(readContext);
        NbtSharedRead existing = inFlightNbtReads.putIfAbsent(key, created);
        if (existing != null) {
            nbtReadsCoalesced.incrementAndGet();
            existing.contexts().add(readContext);
            return existing.result();
        }
        created.result().whenComplete((ignored, error) -> inFlightNbtReads.remove(key, created));
        Runnable start = () -> startNbtRead(readContext, created);
        nbtReadsSubmitted.incrementAndGet();
        if (!enqueueNbtRead(readContext.lifecycleEpoch(), start)) {
            nbtGateRejected.incrementAndGet();
            created.result().complete(DiskNbtReadResult.failure());
        } else {
            created.result().whenComplete(this::recordNbtReadCompletion);
        }
        return created.result();
    }

    private void recordNbtReadCompletion(DiskNbtReadResult result, Throwable error) {
        nbtReadsCompleted.incrementAndGet();
        if (error != null || result == null || result.outcome() == NbtReadOutcome.FAILED) {
            nbtReadFailures.incrementAndGet();
            return;
        }
        if (result.outcome() == NbtReadOutcome.HIT) {
            nbtReadHits.incrementAndGet();
        } else if (result.outcome() == NbtReadOutcome.MISS) {
            nbtReadMisses.incrementAndGet();
        }
    }

    private boolean enqueueNbtRead(long lifecycleEpoch, Runnable start) {
        boolean runNow = false;
        synchronized (nbtGateLock) {
            if (nbtGateLifecycleEpoch == Long.MIN_VALUE) {
                nbtGateLifecycleEpoch = lifecycleEpoch;
            } else if (nbtGateLifecycleEpoch != lifecycleEpoch) {
                return false;
            }
            if (activeNbtReads < Math.max(1, VSSServerConfig.CONFIG.maxConcurrentNbtReads)) {
                activeNbtReads++;
                diskRuntime.beginExpensiveRead();
                runNow = true;
            } else if (nbtGateWaiters.size() < Math.max(1, VSSServerConfig.CONFIG.diskReadQueueLimit)) {
                nbtGateWaiters.addLast(new NbtGateWaiter(lifecycleEpoch, start));
                return true;
            } else {
                return false;
            }
        }
        if (runNow) {
            start.run();
        }
        return true;
    }

    private void releaseNbtGate(long lifecycleEpoch) {
        NbtGateWaiter next;
        synchronized (nbtGateLock) {
            if (nbtGateLifecycleEpoch != lifecycleEpoch) {
                return;
            }
            next = nbtGateWaiters.pollFirst();
            if (next == null) {
                activeNbtReads = Math.max(0, activeNbtReads - 1);
                diskRuntime.finishExpensiveRead();
                return;
            }
        }
        next.start().run();
    }

    private void startNbtRead(
            DiskReadContext readContext,
            NbtSharedRead shared) {
        CompletableFuture<DiskNbtReadResult> result = shared.result();
        if (!hasActiveNbtListener(shared)) {
            releaseNbtGate(readContext.lifecycleEpoch());
            result.complete(DiskNbtReadResult.empty());
            return;
        }
        CompletableFuture<Optional<CompoundTag>> ioFuture;
        try {
            ioFuture = readContext.level().getChunkSource().chunkMap
                    .read(new ChunkPos(readContext.cx(), readContext.cz()))
                    .thenApply(value -> value)
                    .orTimeout(VSSServerConfig.CONFIG.diskReadTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            releaseNbtGate(readContext.lifecycleEpoch());
            result.complete(DiskNbtReadResult.failure());
            return;
        }
        ioFuture.whenComplete((optionalTag, ioError) -> {
            if (ioError != null || VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
                releaseNbtGate(readContext.lifecycleEpoch());
                result.complete(ioError != null
                        ? DiskNbtReadResult.failure()
                        : DiskNbtReadResult.empty());
                return;
            }
            boolean submitted = diskRuntime.submitManualRead(
                    VSSServerConfig.CONFIG.diskReadQueueLimit,
                    pendingTask -> {
                        try {
                            if (!hasActiveNbtListener(shared)) {
                                result.complete(DiskNbtReadResult.empty());
                                return;
                            }
                            LoadedColumnData rawDiskData = NbtSectionSerializer.serializeTag(
                                    readContext.level(), readContext.cx(), readContext.cz(), optionalTag);
                            EncodedColumnData encoded = rawDiskData != null
                                            && rawDiskData.completeColumn()
                                            && rawDiskData.sizeBytes() > 0
                                    ? EncodedColumnData.encode(rawDiskData, 0L)
                                    : null;
                            result.complete(encoded == null
                                    ? DiskNbtReadResult.miss()
                                    : DiskNbtReadResult.hit(encoded));
                        } catch (Throwable error) {
                            VSSLogger.warn("Failed to parse chunk NBT from disk at "
                                    + readContext.cx() + ", " + readContext.cz() + ": " + error.getMessage());
                            result.complete(DiskNbtReadResult.failure());
                        } finally {
                            pendingTask.complete();
                            releaseNbtGate(readContext.lifecycleEpoch());
                        }
                    },
                    rejected -> {
                        releaseNbtGate(readContext.lifecycleEpoch());
                        result.complete(DiskNbtReadResult.failure());
                    });
            if (!submitted && !result.isDone()) {
                releaseNbtGate(readContext.lifecycleEpoch());
                result.complete(DiskNbtReadResult.failure());
            }
        });
    }

    private boolean isReadContextActive(DiskReadContext readContext) {
        return !VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())
                && playerRegistry.isCurrent(readContext.playerId(), readContext.requestState())
                && readContext.requestState().isActiveRequest(readContext.requestId());
    }

    private boolean hasActiveNbtListener(NbtSharedRead shared) {
        for (DiskReadContext context : shared.contexts()) {
            if (isReadContextActive(context)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldReadExistingChunkNbt(boolean allowGeneration) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return shouldReadExistingChunkNbt(
                config.enableChunkNbtColumnSync,
                allowGeneration,
                config.enableChunkGeneration);
    }

    public static boolean shouldReadExistingChunkNbt(
            boolean enableChunkNbtColumnSync,
            boolean allowGeneration,
            boolean enableChunkGeneration) {
        return enableChunkNbtColumnSync || !allowGeneration || !enableChunkGeneration;
    }

    private void finishDiskRead(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            EncodedColumnData diskData,
            boolean readFailed) {
        if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
            return;
        }
        if (readFailed) {
            requestStats.recordDiskReadFailure();
        }
        ServerLevel level = readContext.level();
        PlayerRequestState requestState = readContext.requestState();
        int requestId = readContext.requestId();
        int cx = readContext.cx();
        int cz = readContext.cz();
        long columnTimestamp = readContext.columnTimestamp();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(readContext.playerId());
        if (player == null || !playerRegistry.isCurrent(readContext.playerId(), requestState)
                || requestState.consumeCancelled(requestId)) {
            return;
        }
        if (!player.serverLevel().dimension().equals(level.dimension())) {
            requestState.clearRequest(requestId);
            return;
        }
        if (!VSSServerNetworking.isColumnStillRelevant(player, level.dimension(), cx, cz)) {
            requestState.clearRequest(requestId);
            return;
        }
        long latestDirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
        long effectiveColumnTimestamp = Math.max(columnTimestamp, latestDirtyTimestamp);
        if (storedData != null
                && storedData.columnData() != null
                && storedData.columnData().completeColumn()
                && storedData.timestamp() >= latestDirtyTimestamp) {
            sendStoredColumn(readContext, storedData, player, effectiveColumnTimestamp);
            return;
        }
        if (readContext.preferLoadedColumn()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                if (submitLoadedColumn(player, requestState, level, chunk, requestId, cx, cz, effectiveColumnTimestamp, readContext.priority())) {
                    return;
                }
                requestState.clearRequest(requestId);
                sendRateLimited(player, requestId);
                return;
            }
        }
        if (latestDirtyTimestamp > columnTimestamp) {
            handleMissingDiskColumn(readContext, player, effectiveColumnTimestamp);
            return;
        }
        if (diskData == null || !diskData.hasBody() || !diskData.completeColumn()) {
            handleMissingDiskColumn(readContext, player, effectiveColumnTimestamp);
            return;
        }
        sendDiskColumn(readContext, player, diskData, effectiveColumnTimestamp);
    }

    private void sendStoredColumn(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            ServerPlayer player,
            long columnTimestamp) {
        ServerLevel level = readContext.level();
        EncodedColumnData columnData = storedData.columnData().withColumnStamp(
                Math.max(storedData.timestamp(), columnTimestamp));
        requestStats.recordDiskReadHit();
        VSSServerNetworking.queueColumn(player, readContext.requestState(), new VoxelColumnS2CPayload(
                readContext.requestId(),
                level.dimension(),
                columnData), readContext.priority());
        columnCache.put(level.dimension(), columnData);
    }

    private void handleMissingDiskColumn(DiskReadContext readContext, ServerPlayer player, long minimumTimestamp) {
        requestStats.recordDiskReadMiss();
        if (readContext.allowGeneration() && VSSServerConfig.CONFIG.enableChunkGeneration) {
            submitGeneration(
                    player,
                    readContext.requestState(),
                    readContext.level(),
                    readContext.requestId(),
                    readContext.cx(),
                    readContext.cz(),
                    minimumTimestamp,
                    readContext.priority());
            return;
        }
        readContext.requestState().clearRequest(readContext.requestId());
        sendNotGenerated(player, readContext.requestId());
    }

    private void sendDiskColumn(
            DiskReadContext readContext,
            ServerPlayer player,
            EncodedColumnData diskData,
            long columnTimestamp) {
        ServerLevel level = readContext.level();
        requestStats.recordDiskReadHit();
        EncodedColumnData encodedDiskData = diskData.withColumnStamp(columnTimestamp);
        VSSServerNetworking.queueColumn(player, readContext.requestState(), new VoxelColumnS2CPayload(
                readContext.requestId(),
                level.dimension(),
                encodedDiskData), readContext.priority());
        columnCache.put(level.dimension(), encodedDiskData);
        persistentWriter.write(level.getServer(), level.dimension(), encodedDiskData);
    }

    private void submitGeneration(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        if (VSSServerNetworking.isServerStopping()) {
            state.clearRequest(requestId);
            return;
        }
        boolean accepted = generationService.submitGeneration(
                player.getUUID(),
                state,
                requestId,
                level,
                cx,
                cz,
                minimumTimestamp,
                priority);
        if (!accepted) {
            state.clearRequest(requestId);
            sendRateLimited(player, requestId);
            return;
        }
        sendGenerationQueued(player, requestId);
    }

    private static void sendRateLimited(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                        new int[] {requestId},
                        1));
    }

    private static void sendBackpressured(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_BACKPRESSURE},
                        new int[] {requestId},
                        1));
    }

    private static void sendGenerationQueued(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_GENERATION_QUEUED},
                        new int[] {requestId},
                        1));
    }

    private static void sendNotGenerated(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {requestId},
                        1));
    }

    private record DiskReadContext(
            long lifecycleEpoch,
            UUID playerId,
            ServerLevel level,
            PlayerRequestState requestState,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean priority) {
    }

    private record DiskNbtReadResult(EncodedColumnData columnData, NbtReadOutcome outcome) {
        static DiskNbtReadResult empty() {
            return new DiskNbtReadResult(null, NbtReadOutcome.CANCELLED);
        }

        static DiskNbtReadResult hit(EncodedColumnData columnData) {
            return new DiskNbtReadResult(columnData, NbtReadOutcome.HIT);
        }

        static DiskNbtReadResult miss() {
            return new DiskNbtReadResult(null, NbtReadOutcome.MISS);
        }

        static DiskNbtReadResult failure() {
            return new DiskNbtReadResult(null, NbtReadOutcome.FAILED);
        }

        boolean failed() {
            return outcome == NbtReadOutcome.FAILED;
        }
    }

    private enum NbtReadOutcome {
        HIT,
        MISS,
        FAILED,
        CANCELLED
    }

    private record NbtReadKey(long lifecycleEpoch, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    }

    public record NbtDiagnostics(
            long submitted,
            long completed,
            long hits,
            long misses,
            long failures,
            int active,
            int queued,
            long coalesced,
            long rejected) {
    }

    private record NbtGateWaiter(long lifecycleEpoch, Runnable start) {
    }

    private record NbtSharedRead(
            CompletableFuture<DiskNbtReadResult> result,
            ConcurrentLinkedQueue<DiskReadContext> contexts) {
        private NbtSharedRead(DiskReadContext initialContext) {
            this(new CompletableFuture<>(), new ConcurrentLinkedQueue<>());
            contexts.add(initialContext);
        }
    }
}
