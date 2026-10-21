package dev.xantha.vss.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ClientLodSessionTest {
    @Test void disconnectedSessionCannotClaimCoverageOrTriggerResyncWork() {
        var session = ClientLodSession.DISCONNECTED;
        assertFalse(session.isServerEnabled());
        assertFalse(session.isPredictionActive());
        assertFalse(session.isClientLodSessionActive());
        assertFalse(session.shouldApplyStrictLodOrder());
        assertFalse(session.strictColumnReady(1, 2));
        assertFalse(session.strictColumnRenderReady(1, 2, ignored -> { fail("no readiness source"); return true; }));
        assertEquals(0, session.getServerLodDistance());
        assertEquals(0, session.getEffectiveLodDistanceChunks());
        session.forceLodResync("disconnected test");
    }

    @Test void apiUsesLiveProviderAndRejectsNullWithoutDiscardingIt() {
        var old = VSSApi.clientSession();
        class MutableSession implements ClientLodSession {
            boolean enabled;
            int distance;
            @Override public boolean isServerEnabled() { return enabled; }
            @Override public int getServerLodDistance() { return distance; }
        }
        var provider = new MutableSession();
        try {
            VSSApi.bindClientSession(provider);
            assertFalse(VSSApi.isServerEnabled());
            provider.enabled = true;
            provider.distance = 64;
            assertTrue(VSSApi.isServerEnabled());
            assertEquals(64, VSSApi.getServerLodDistance());
            assertThrows(NullPointerException.class, () -> VSSApi.bindClientSession(null));
            assertSame(provider, VSSApi.clientSession());
            VSSApi.bindClientSession(ClientLodSession.DISCONNECTED);
            assertFalse(VSSApi.isServerEnabled());
        } finally { VSSApi.bindClientSession(old); }
    }
}
