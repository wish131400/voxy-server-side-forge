package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldgenProfileDeliveryTest {
    @Test
    void aModLinkageFailureDoesNotPreventTheNextPlayerFromReceivingTheirProfile() {
        AtomicInteger sent = new AtomicInteger();
        var healthy = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42L, 1L, List.of());

        assertDoesNotThrow(() -> PlayerSessionManager.sendWorldgenProfile("broken player",
                () -> { throw new IllegalAccessError("ModStructure tried to access Structure.settings"); },
                payload -> sent.incrementAndGet()));
        assertEquals(0, sent.get());

        PlayerSessionManager.sendWorldgenProfile("next player", () -> healthy, payload -> {
            assertSame(healthy, payload);
            sent.incrementAndGet();
        });
        assertEquals(1, sent.get());
    }

    @Test
    void senderLinkageFailureIsContainedAtTheSameSessionBoundary() {
        var payload = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42L, 2L, List.of());
        assertDoesNotThrow(() -> PlayerSessionManager.sendWorldgenProfile("refresh player", () -> payload,
                ignored -> { throw new NoSuchMethodError("third party networking hook"); }));
    }

    @Test
    void virtualMachineFailuresStillEscapeTheSessionBoundary() {
        var fatal = new OutOfMemoryError("test sentinel");
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> PlayerSessionManager.sendWorldgenProfile(
                "player", () -> { throw fatal; }, ignored -> fail("no payload should be sent"))));
    }
}
