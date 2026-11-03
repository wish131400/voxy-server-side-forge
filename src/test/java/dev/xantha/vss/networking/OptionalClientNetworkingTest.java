package dev.xantha.vss.networking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.Test;

class OptionalClientNetworkingTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        if (net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "optional-client-tests"));
        }
    }
    @Test
    void bothChannelDirectionsAcceptAbsentClientsButRejectIncompatibleInstalledVersions() throws Exception {
        var instanceField = net.minecraftforge.network.simple.SimpleChannel.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object instance = instanceField.get(VSSNetworking.CHANNEL);
        for (String name : new String[] {"tryClientVersionOnServer", "tryServerVersionOnClient"}) {
            var check = instance.getClass().getDeclaredMethod(name, String.class);
            check.setAccessible(true);
            assertEquals(true, check.invoke(instance, net.minecraftforge.network.NetworkRegistry.ABSENT.version()));
            assertEquals(true, check.invoke(instance, net.minecraftforge.network.NetworkRegistry.ACCEPTVANILLA));
            assertEquals(true, check.invoke(instance, Integer.toString(VSSConstants.PROTOCOL_VERSION)));
            assertEquals(false, check.invoke(instance, Integer.toString(VSSConstants.PROTOCOL_VERSION + 1)));
        }
    }

    @Test
    void playersWithoutVssReceiveNoPlayHandshakeAndInstalledPlayersStillDo() throws Exception {
        var player = mock(ServerPlayer.class);
        var listener = mock(ServerGamePacketListenerImpl.class);
        ServerPlayer.class.getField("connection").set(player, listener);
        var event = new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player);
        // Keep the real SimpleChannel capability check; only supply negotiated channel data.
        var connectionData = mock(net.minecraftforge.network.ConnectionData.class);
        try (var sending = mockStatic(VSSNetworking.class, CALLS_REAL_METHODS);
             var hooks = mockStatic(net.minecraftforge.network.NetworkHooks.class)) {
            sending.when(() -> VSSNetworking.sendToPlayer(any(), any())).thenAnswer(call -> null);
            VSSServerNetworking.onPlayerLogin(event);
            sending.verify(() -> VSSNetworking.sendToPlayer(any(), any()), never());

            hooks.when(() -> net.minecraftforge.network.NetworkHooks.getConnectionData(listener.connection))
                    .thenReturn(connectionData);
            when(connectionData.getChannels()).thenReturn(com.google.common.collect.ImmutableMap.of(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("vss", "main"),
                    Integer.toString(VSSConstants.PROTOCOL_VERSION)));
            VSSServerNetworking.onPlayerLogin(event);
            sending.verify(() -> VSSNetworking.sendToPlayer(eq(player), any(HandshakeRequestS2CPayload.class)));
        }
    }
}
