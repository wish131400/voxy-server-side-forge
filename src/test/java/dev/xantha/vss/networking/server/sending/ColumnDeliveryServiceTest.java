package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Observes queues and protocol responses at the extracted boundary. */
class ColumnDeliveryServiceTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }

    @Test void cancelledAndUnknownRequestsCannotBeQueuedOrRepliedTo() {
        var f = new Fixture();
        f.begin(7);
        f.state.cancel(7);
        assertFalse(f.delivery.queueColumn(f.player, f.state, f.payload(7, true)));
        assertFalse(f.delivery.queueColumn(f.player, f.state, f.payload(8, true)));
        assertEquals(0, f.state.queuedPayloadCount());
        assertTrue(f.responses.isEmpty());
    }

    @Test void shutdownClearsAnActiveRequestWithoutEnqueueing() {
        var f = new Fixture();
        f.begin(7);
        f.lifecycle.stop();
        assertFalse(f.delivery.queueColumn(f.player, f.state, f.payload(7, true)));
        assertFalse(f.state.isActiveRequest(7));
        assertEquals(0, f.state.queuedPayloadCount());
        assertTrue(f.responses.isEmpty());
    }

    @Test void incompleteColumnsAreReportedAsNotGenerated() {
        var f = new Fixture();
        f.begin(7);
        assertFalse(f.delivery.queueColumn(f.player, f.state, f.payload(7, false)));
        assertFalse(f.state.isActiveRequest(7));
        f.assertResponse(7, VSSConstants.RESPONSE_NOT_GENERATED);
    }

    @Test void dimensionAndDistanceChecksRejectStaleResults() {
        var f = new Fixture();
        f.begin(7);
        var otherDimension = new VoxelColumnS2CPayload(7, 0, 0, Level.NETHER, 100L, new byte[] {0});
        assertFalse(f.delivery.queueColumn(f.player, f.state, otherDimension));
        f.assertResponse(7, VSSConstants.RESPONSE_BACKPRESSURE);
        f.responses.clear();
        f.begin(8);
        int outside = f.config.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER + 1;
        var distant = new VoxelColumnS2CPayload(8, outside, 0, Level.OVERWORLD, 100L, new byte[] {0});
        assertFalse(f.delivery.queueColumn(f.player, f.state, distant));
        f.assertResponse(8, VSSConstants.RESPONSE_BACKPRESSURE);
        assertEquals(0, f.state.queuedPayloadCount());
    }

    @Test void acceptedTransfersRetainPriorityAndReceiveDistinctIds() {
        var f = new Fixture();
        f.begin(7);
        assertTrue(f.delivery.queueColumn(f.player, f.state, f.payload(7, true), true));
        var first = f.state.peekPriorityQueuedPayload(0, 0).payload();
        assertTrue(first.transferId() > 0);
        assertTrue(f.state.isActiveRequest(7), "request completes only after sending, not admission");
        assertEquals(1, f.state.priorityQueuedPayloadCount());
        assertTrue(f.state.beginRequest(8, Level.OVERWORLD, PositionUtil.packPosition(1, 0)));
        assertTrue(f.delivery.queueColumn(f.player, f.state,
                new VoxelColumnS2CPayload(8, 1, 0, Level.OVERWORLD, 101L, new byte[] {0})));
        var second = f.state.peekNormalQueuedPayload(0, 0).payload();
        assertNotEquals(first.transferId(), second.transferId());
        assertEquals(2, f.state.queuedPayloadCount());
        assertTrue(f.responses.isEmpty());
    }

    @Test void fullQueueClearsRejectedRequestAndReportsBackpressure() {
        var f = new Fixture();
        int previous = VSSServerConfig.CONFIG.sendQueueLimitPerPlayer;
        try {
            VSSServerConfig.CONFIG.sendQueueLimitPerPlayer = 0;
            f.begin(7);
            assertFalse(f.delivery.queueColumn(f.player, f.state, f.payload(7, true)));
            assertFalse(f.state.isActiveRequest(7));
            f.assertResponse(7, VSSConstants.RESPONSE_BACKPRESSURE);
        } finally { VSSServerConfig.CONFIG.sendQueueLimitPerPlayer = previous; }
    }

    @Test void liveConfigurationAndNegativePreloadIdsKeepTheirExistingSemantics() {
        var f = new Fixture();
        assertTrue(f.delivery.isColumnStillRelevant(f.player, Level.OVERWORLD, 0, 0));
        f.config.enabled = false;
        assertFalse(f.delivery.isColumnStillRelevant(f.player, Level.OVERWORLD, 0, 0));
        assertFalse(f.delivery.queueColumn(f.player, f.state, f.payload(-1, true)));
        assertTrue(f.responses.isEmpty(), "preload identifiers are not client requests");
    }

    private static final class Fixture {
        final ServerLifecycleGuard lifecycle = new ServerLifecycleGuard();
        final VSSServerConfig config = new VSSServerConfig();
        final PlayerRequestState state = new PlayerRequestState();
        final ServerPlayer player = mock(ServerPlayer.class);
        final List<BatchResponseS2CPayload> responses = new ArrayList<>();
        final ColumnDeliveryService delivery = new ColumnDeliveryService(lifecycle, () -> config,
                (recipient, response) -> { assertSame(player, recipient); responses.add(response); });
        Fixture() {
            var level = mock(ServerLevel.class);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.serverLevel()).thenReturn(level);
        }
        void begin(int id) { assertTrue(state.beginRequest(id, Level.OVERWORLD, PositionUtil.packPosition(0, 0))); }
        VoxelColumnS2CPayload payload(int id, boolean complete) {
            return new VoxelColumnS2CPayload(id, 0, 0, Level.OVERWORLD, 100L, new byte[] {0}, complete);
        }
        void assertResponse(int id, byte kind) {
            assertEquals(1, responses.size());
            assertEquals(1, responses.get(0).count());
            assertArrayEquals(new int[] {id}, responses.get(0).requestIds());
            assertArrayEquals(new byte[] {kind}, responses.get(0).responseTypes());
        }
    }
}
