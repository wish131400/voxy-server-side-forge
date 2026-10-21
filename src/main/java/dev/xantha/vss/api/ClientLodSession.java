package dev.xantha.vss.api;

import java.util.function.IntPredicate;

/**
 * 客户端 LOD 会话的窄边界，由网络模块实现，供 API、预测和兼容层查询。
 * <p>实现必须读取现有会话，不能另存一份握手、距离或覆盖状态。默认实现表示未连接，
 * 不会因缺少信息而撤销预测覆盖。除重新同步请求外，调用方应位于客户端线程；
 * 本接口不赋予工作线程读取 Minecraft 可变世界或执行 GPU 操作的权限。
 */
public interface ClientLodSession {
    ClientLodSession DISCONNECTED = new ClientLodSession() { };

    /** 稳定的预测缓存身份；尚未解析或断线为 null，不得据此写入其他服务器的缓存。 */
    default String currentPredictionScope() { return null; }
    default boolean isServerEnabled() { return false; }
    /** 服务端协商距离，单位为区块；未协商为零。 */
    default int getServerLodDistance() { return 0; }
    /** 当前有效的 VSS 请求半径，与 Voxy 自身绘制距离不同。 */
    default int getEffectiveLodDistanceChunks() { return 0; }
    default boolean isPredictionActive() { return false; }
    default boolean shouldApplyStrictLodOrder() { return false; }
    default boolean strictColumnReady(int chunkX, int chunkZ) { return false; }
    /** ready 判断上传节点；请求完成本身不能作为可绘制的证明。 */
    default boolean strictColumnRenderReady(int chunkX, int chunkZ, IntPredicate ready) { return false; }
    default boolean isClientLodSessionActive() { return false; }
    /** 可从任意线程请求；实现负责切回客户端线程并重新检查连接。 */
    default void forceLodResync(String reason) { }
}
