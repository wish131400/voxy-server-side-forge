package dev.xantha.vss.networking.client;

import dev.xantha.vss.api.ClientLodSession;
import java.util.function.IntPredicate;

/** 将唯一网络会话暴露为契约；无缓存、无独立状态，由客户端初始化入口安装。 */
public final class ClientLodSessionAdapter implements ClientLodSession {
    @Override public String currentPredictionScope() { return ClientConnectionIdentity.currentPredictionScope(); }
    @Override public boolean isServerEnabled() { return VSSClientNetworking.isServerEnabled(); }
    @Override public int getServerLodDistance() { return VSSClientNetworking.getServerLodDistance(); }
    @Override public int getEffectiveLodDistanceChunks() { return VSSClientNetworking.getEffectiveLodDistanceChunks(); }
    @Override public boolean isPredictionActive() { return VSSClientNetworking.isPredictionActive(); }
    @Override public boolean shouldApplyStrictLodOrder() { return VSSClientNetworking.shouldApplyStrictLodOrder(); }
    @Override public boolean strictColumnReady(int chunkX, int chunkZ) {
        return VSSClientNetworking.strictColumnReady(chunkX, chunkZ);
    }
    @Override public boolean strictColumnRenderReady(int chunkX, int chunkZ, IntPredicate ready) {
        return VSSClientNetworking.strictColumnRenderReady(chunkX, chunkZ, ready);
    }
    @Override public boolean isClientLodSessionActive() { return VSSClientNetworking.isClientLodSessionActive(); }
    @Override public void forceLodResync(String reason) { VSSClientNetworking.forceLodResync(reason); }
}
