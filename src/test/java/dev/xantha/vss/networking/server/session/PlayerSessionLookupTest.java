package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlayerSessionLookupTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }
    @Test void lookupTracksTheSharedRegistryWithoutAnotherOnlineCache() {
        var registry = new PlayerRequestRegistry();
        var lifecycle = new ServerLifecycleGuard();
        var lookup = new PlayerSessionLookup(registry, lifecycle);
        var player = mock(ServerPlayer.class);
        UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);
        var level = mock(ServerLevel.class);
        when(level.players()).thenReturn(List.of(player));
        assertFalse(lookup.isRegistered(player));
        assertFalse(lookup.hasRegisteredPlayers());
        registry.put(id, new PlayerRequestState());
        assertTrue(lookup.hasRegisteredPlayers(level));
        assertTrue(lookup.hasRegisteredPlayers());
        registry.remove(id);
        assertFalse(lookup.hasRegisteredPlayers(level));
    }

    @Test void timestampsStayDimensionScopedAndShutdownMakesSessionsUnavailable() {
        var registry = new PlayerRequestRegistry();
        var lifecycle = new ServerLifecycleGuard();
        var lookup = new PlayerSessionLookup(registry, lifecycle);
        var player = mock(ServerPlayer.class);
        UUID id = UUID.randomUUID();
        when(player.getUUID()).thenReturn(id);
        var state = new PlayerRequestState();
        registry.put(id, state);
        state.markClientKnownColumn(Level.OVERWORLD, PositionUtil.packPosition(-2, 3), 77L);
        assertEquals(77L, lookup.clientKnownColumnTimestamp(player, Level.OVERWORLD, -2, 3));
        assertEquals(0L, lookup.clientKnownColumnTimestamp(player, Level.NETHER, -2, 3));
        assertEquals(0L, lookup.clientKnownColumnTimestamp(null, Level.OVERWORLD, -2, 3));
        lifecycle.stop();
        assertFalse(lookup.isRegistered(player));
        assertFalse(lookup.hasRegisteredPlayers());
        assertEquals(0L, lookup.clientKnownColumnTimestamp(player, Level.OVERWORLD, -2, 3));
    }
}
