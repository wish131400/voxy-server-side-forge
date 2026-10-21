package dev.xantha.vss.api;

import dev.xantha.vss.common.VSSLogger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 客户端列消费扩展入口。消费者在调用线程执行，应快速接收数据并自行安排安全工作。
 * <p>单个消费者失败不会阻止其余消费者；聚合成功只在所有已调用消费者接受时成立。
 * 本类含 ClientLevel 契约，不能作为专用服务器的通用 API 初始化入口。
 */
public final class VSSApi {
    private static volatile ClientLodSession clientSession = ClientLodSession.DISCONNECTED;

    /** 由客户端启动入口绑定转发器；绑定不是网络状态快照，断线仍由网络模块处理。 */
    public static void bindClientSession(ClientLodSession session) {
        clientSession = java.util.Objects.requireNonNull(session, "session");
    }

    public static ClientLodSession clientSession() {
        return clientSession;
    }

    private static final List<VoxelColumnConsumer> COLUMN_CONSUMERS = new CopyOnWriteArrayList<>();
    private static final List<VoxelColumnProcessingConsumer> PROCESSING_COLUMN_CONSUMERS = new CopyOnWriteArrayList<>();

    private VSSApi() {
    }

    public static void registerColumnConsumer(VoxelColumnConsumer consumer) {
        if (!COLUMN_CONSUMERS.contains(consumer)) {
            COLUMN_CONSUMERS.add(consumer);
        }
        VSSLogger.info("Registered voxel column consumer: " + consumer.getClass().getName());
    }

    public static void removeColumnConsumer(VoxelColumnConsumer consumer) {
        COLUMN_CONSUMERS.remove(consumer);
    }

    public static void registerColumnProcessingConsumer(VoxelColumnProcessingConsumer consumer) {
        PROCESSING_COLUMN_CONSUMERS.add(consumer);
        VSSLogger.info("Registered voxel column processing consumer: " + consumer.getClass().getName());
    }

    public static boolean hasVoxelConsumers() {
        return !COLUMN_CONSUMERS.isEmpty() || !PROCESSING_COLUMN_CONSUMERS.isEmpty();
    }

    public static boolean isServerEnabled() {
        return clientSession.isServerEnabled();
    }

    public static int getServerLodDistance() {
        return clientSession.getServerLodDistance();
    }

    public static void dispatchColumn(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData) {
        for (VoxelColumnConsumer consumer : COLUMN_CONSUMERS) {
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                VSSLogger.error("Voxel column consumer threw exception", e);
            }
        }
    }

    public static boolean dispatchColumnAndReport(
            ClientLevel level,
            ResourceKey<Level> dimension,
            int chunkX,
            int chunkZ,
            VoxelColumnData columnData) {
        boolean handled = false;
        boolean accepted = true;
        for (VoxelColumnConsumer consumer : COLUMN_CONSUMERS) {
            handled = true;
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                accepted = false;
                VSSLogger.error("Voxel column consumer threw exception", e);
            }
        }
        for (VoxelColumnProcessingConsumer consumer : PROCESSING_COLUMN_CONSUMERS) {
            handled = true;
            try {
                accepted &= consumer.processVoxelColumn(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                accepted = false;
                VSSLogger.error("Voxel column processing consumer threw exception", e);
            }
        }
        return handled && accepted;
    }
}
