package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoxyIngestControlTest {
    @Test
    void serverIngestMarkerIsScopedAndNestable() throws Throwable {
        assertFalse(VoxyIngestControl.isServerIngestActive());

        boolean nestedResult = VoxyIngestControl.runServerIngest(() -> {
            assertTrue(VoxyIngestControl.isServerIngestActive());
            return VoxyIngestControl.runServerIngest(() -> {
                assertTrue(VoxyIngestControl.isServerIngestActive());
                return true;
            });
        });

        assertTrue(nestedResult);
        assertFalse(VoxyIngestControl.isServerIngestActive());
    }

    @Test
    void serverIngestMarkerIsClearedAfterFailure() {
        assertThrows(IllegalStateException.class, () ->
                VoxyIngestControl.runServerIngest(() -> {
                    throw new IllegalStateException("expected");
                }));

        assertFalse(VoxyIngestControl.isServerIngestActive());
    }
}
