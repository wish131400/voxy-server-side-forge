package dev.xantha.vss.networking.server.sending;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.state.PlayerSendQueue;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 已完成列的交付边界：校验请求、范围与完整性，分片后加入玩家发送队列。
 * <p>由服务端线程调用；异步读取必须先回到该线程并验证会话、生命周期版本。
 * 本类不生成地形、不访问磁盘，也不拥有发送队列；拒绝时沿用原请求响应语义。
 * 配置按调用读取，传输编号在同一运行时内递增，不因单个玩家重连而重置。
 */
public final class ColumnDeliveryService {
    private final AtomicLong nextTransferId = new AtomicLong(1L);
    private final ServerLifecycleGuard lifecycle;
    private final Supplier<VSSServerConfig> config;
    private final BiConsumer<ServerPlayer, BatchResponseS2CPayload> responses;

    public ColumnDeliveryService(ServerLifecycleGuard lifecycle, Supplier<VSSServerConfig> config,
            BiConsumer<ServerPlayer, BatchResponseS2CPayload> responses) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.config = Objects.requireNonNull(config, "config");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    public boolean queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload) {
        return queueColumn(player, state, payload, false);
    }

    public boolean queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload, boolean priority) {
        if (state.consumeCancelled(payload.requestId()) || !state.isActiveRequest(payload.requestId())) {
            return false;
        }
        if (lifecycle.isStopping()) {
            state.clearRequest(payload.requestId());
            return false;
        }
        if (!payload.isDeliverable()
                || !payload.coverage().filterKey().equals(dev.xantha.vss.api.column.ColumnFilters.snapshot().cacheKey())) {
            if (VSSLogger.isDebugEnabled()) {
                VSSLogger.debug("LOD column unavailable: reason=incomplete, dimension=" + payload.dimension().location()
                        + ", chunk=" + payload.chunkX() + "," + payload.chunkZ() + ", request=" + payload.requestId());
            }
            state.clearRequest(payload.requestId());
            sendNotGenerated(player, payload.requestId());
            return false;
        }
        if (!isPayloadStillRelevant(player, payload)) {
            state.clearRequest(payload.requestId());
            sendBackpressured(player, payload.requestId());
            return false;
        }
        boolean allowZstdColumns = state.supportsZstdColumns();
        VoxelColumnS2CPayload transferPayload = payload.withTransferMetadata(
                nextTransferId(),
                0,
                1,
                payload.replacementSectionYs());
        transferPayload.setAllowZstdEncoding(allowZstdColumns);
        VSSServerConfig config = this.config.get();
        long effectiveBandwidth = Math.min(config.totalBandwidthBytesPerSecond(), state.desiredBandwidth());
        List<VoxelColumnS2CPayload> payloads = ColumnPayloadSplitter.splitForBandwidth(
                player.serverLevel(),
                transferPayload,
                effectiveBandwidth,
                config.enableNetworkColumnCompression);
        for (VoxelColumnS2CPayload queuedPayload : payloads) {
            queuedPayload.setAllowZstdEncoding(allowZstdColumns);
        }
        state.prepareSendOrder(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        PlayerSendQueue.EnqueueResult admission = state.enqueue(payloads, priority);
        for (int rejectedRequestId : admission.rejectedRequestIds()) {
            sendBackpressured(player, rejectedRequestId);
        }
        return admission.accepted();
    }

    private boolean isPayloadStillRelevant(ServerPlayer player, VoxelColumnS2CPayload payload) {
        return isColumnStillRelevant(player, payload.dimension(), payload.chunkX(), payload.chunkZ());
    }

    public boolean isColumnStillRelevant(
            ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            int cx,
            int cz) {
        if (lifecycle.isStopping() || player == null || !config.get().enabled) {
            return false;
        }
        if (!player.serverLevel().dimension().equals(dimension)) {
            return false;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = config.get().effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        return PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDistance;
    }

    private void sendBackpressured(ServerPlayer player, int requestId) {
        sendResponse(player, requestId, VSSConstants.RESPONSE_BACKPRESSURE);
    }

    private void sendNotGenerated(ServerPlayer player, int requestId) {
        sendResponse(player, requestId, VSSConstants.RESPONSE_NOT_GENERATED);
    }

    private void sendResponse(ServerPlayer player, int requestId, byte responseType) {
        if (requestId < 0) {
            return;
        }
        responses.accept(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {responseType},
                        new int[] {requestId},
                        1));
    }

    private long nextTransferId() {
        return nextTransferId.getAndUpdate(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
    }
}
