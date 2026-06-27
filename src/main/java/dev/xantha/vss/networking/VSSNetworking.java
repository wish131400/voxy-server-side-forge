package dev.xantha.vss.networking;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

public final class VSSNetworking {
    private static final String PROTOCOL = Integer.toString(VSSConstants.PROTOCOL_VERSION);
    private static final long INTEGRATED_HOST_DELIVERY_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static volatile long lastIntegratedHostDeliveryDiagnosticNanos;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(VSSConstants.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private VSSNetworking() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(HandshakeC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(HandshakeC2SPayload::encode)
                .decoder(HandshakeC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleHandshake)
                .add();
        CHANNEL.messageBuilder(BatchChunkRequestC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BatchChunkRequestC2SPayload::encode)
                .decoder(BatchChunkRequestC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleBatchRequest)
                .add();
        CHANNEL.messageBuilder(CancelRequestC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CancelRequestC2SPayload::encode)
                .decoder(CancelRequestC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleCancel)
                .add();
        CHANNEL.messageBuilder(BandwidthUpdateC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BandwidthUpdateC2SPayload::encode)
                .decoder(BandwidthUpdateC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleBandwidthUpdate)
                .add();
        CHANNEL.messageBuilder(SessionConfigS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SessionConfigS2CPayload::encode)
                .decoder(SessionConfigS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleSessionConfig)
                .add();
        CHANNEL.messageBuilder(BatchResponseS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BatchResponseS2CPayload::encode)
                .decoder(BatchResponseS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleBatchResponse)
                .add();
        CHANNEL.messageBuilder(DirtyColumnsS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DirtyColumnsS2CPayload::encode)
                .decoder(DirtyColumnsS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleDirtyColumns)
                .add();
        CHANNEL.messageBuilder(VoxelColumnS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VoxelColumnS2CPayload::encode)
                .decoder(VoxelColumnS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleVoxelColumn)
                .add();
        CHANNEL.messageBuilder(FarPlayersS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FarPlayersS2CPayload::encode)
                .decoder(FarPlayersS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleFarPlayers)
                .add();
    }

    public static void sendToServer(Object payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        if (trySendToIntegratedHost(player, payload)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static boolean trySendToIntegratedHost(ServerPlayer player, Object payload) {
        Boolean delivered = DistExecutor.safeCallWhenOn(
                Dist.CLIENT,
                () -> () -> ClientPacketHandlers.tryHandleIntegratedHostPayload(player, payload));
        return delivered != null && delivered;
    }

    private static void handleSessionConfig(SessionConfigS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleSessionConfig(payload, contextSupplier));
    }

    private static void handleBatchResponse(BatchResponseS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleBatchResponse(payload, contextSupplier));
    }

    private static void handleDirtyColumns(DirtyColumnsS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleDirtyColumns(payload, contextSupplier));
    }

    private static void handleVoxelColumn(VoxelColumnS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleVoxelColumn(payload, contextSupplier));
    }

    private static void handleFarPlayers(FarPlayersS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleFarPlayers(payload, contextSupplier));
    }

    private static final class ClientPacketHandlers {
        private static boolean tryHandleIntegratedHostPayload(ServerPlayer player, Object payload) {
            Minecraft minecraft = Minecraft.getInstance();
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null || server != player.server) {
                return false;
            }

            LocalPlayer localPlayer = minecraft.player;
            if (localPlayer == null) {
                return false;
            }

            ServerPlayer integratedPlayer = server.getPlayerList().getPlayer(localPlayer.getUUID());
            if (integratedPlayer == null || integratedPlayer != player) {
                return false;
            }

            logIntegratedHostDelivery(payload);
            minecraft.execute(() -> handleDirectPayload(payload));
            return true;
        }

        private static void logIntegratedHostDelivery(Object payload) {
            long now = System.nanoTime();
            if (now - lastIntegratedHostDeliveryDiagnosticNanos < INTEGRATED_HOST_DELIVERY_DIAGNOSTIC_INTERVAL_NANOS) {
                return;
            }
            lastIntegratedHostDeliveryDiagnosticNanos = now;
            VSSLogger.debug("Integrated host direct S2C delivered: " + payload.getClass().getSimpleName());
        }

        private static void handleDirectPayload(Object payload) {
            if (payload instanceof SessionConfigS2CPayload sessionConfig) {
                handleSessionConfig(sessionConfig, () -> null);
            } else if (payload instanceof BatchResponseS2CPayload batchResponse) {
                handleBatchResponse(batchResponse, () -> null);
            } else if (payload instanceof DirtyColumnsS2CPayload dirtyColumns) {
                handleDirtyColumns(dirtyColumns, () -> null);
            } else if (payload instanceof VoxelColumnS2CPayload voxelColumn) {
                handleVoxelColumn(voxelColumn, () -> null);
            } else if (payload instanceof FarPlayersS2CPayload farPlayers) {
                handleFarPlayers(farPlayers, () -> null);
            }
        }

        private static void handleSessionConfig(SessionConfigS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleSessionConfig(payload, contextSupplier);
        }

        private static void handleBatchResponse(BatchResponseS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleBatchResponse(payload, contextSupplier);
        }

        private static void handleDirtyColumns(DirtyColumnsS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleDirtyColumns(payload, contextSupplier);
        }

        private static void handleVoxelColumn(VoxelColumnS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleVoxelColumn(payload, contextSupplier);
        }

        private static void handleFarPlayers(FarPlayersS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.FarPlayerClientRenderer.handleFarPlayers(payload, contextSupplier);
        }
    }
}
