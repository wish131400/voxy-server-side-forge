package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import dev.xantha.vss.networking.server.compat.LostCityHintService;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class LostCityHintsTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void negativeCoordinatesAndSignedGroundRoundTrip() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        int[] entries = new int[64];
        entries[63] = LostCityHintService.pack(2, -32, 7, 12);
        assertTrue(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), -1, -1, 17, true, entries)));
        int value = hints.chunk(-1, -1);
        assertEquals(2, LostCityHints.kind(value));
        assertEquals(-32, LostCityHints.ground(value));
        assertEquals(7, LostCityHints.floors(value));
        assertEquals(12, LostCityHints.style(value));
        assertEquals(0, hints.chunk(0, 0));
        int[] snapshot = hints.snapshot(-64, -64, 64);
        assertEquals(value, snapshot[15]);
        snapshot[15] = 0;
        assertEquals(value, hints.chunk(-1, -1));
    }

    @Test
    void staleSessionAndWrongDimensionCannotReplaceHints() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        int[] entries = new int[64];
        entries[0] = LostCityHintService.pack(2, 64, 3, 0);
        assertFalse(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 16, true, entries)));
        assertFalse(hints.accept(new LostCityHintsS2CPayload(Level.NETHER.location(), 0, 0, 17, true, entries)));
        assertNull(hints.snapshot(0, 0, 64));
    }

    @Test
    void repeatedSummaryDoesNotRebuildAndRemovalDoes() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        int[] entries = new int[64];
        entries[0] = LostCityHintService.pack(2, 64, 3, 0);
        var response = new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, true, entries);
        assertTrue(hints.accept(response));
        assertFalse(hints.accept(response));
        assertTrue(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, false, new int[0])));
        assertNull(hints.snapshot(0, 0, 64));
    }

    @Test
    void missingModAndEmptyRegionHaveNoCityGeometry() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, false);
        hints.observeArea(0, 0, 64);
        assertNull(hints.snapshot(0, 0, 64));
        var present = new LostCityHints(Level.OVERWORLD, 17, true);
        assertFalse(present.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, true, new int[64])));
        assertNull(present.snapshot(0, 0, 64));
    }

    @Test
    void cityBuildingsExtendTileDepthAndHaveDistinctCacheIdentities() {
        int[] low = new int[16], high = new int[16];
        low[0] = LostCityHintService.pack(2, 64, 3, 1);
        high[0] = LostCityHintService.pack(2, 64, 4, 1);
        assertEquals(new PredictionDepthBound(40, 82),
                LostCityHints.includeBuildings(new PredictionDepthBound(40, 70), low));
        byte[] base = new byte[32];
        assertArrayEquals(base, PredictionMeshCodec.withCityBuildings(base, null));
        assertArrayEquals(PredictionMeshCodec.withCityBuildings(base, low),
                PredictionMeshCodec.withCityBuildings(base, low.clone()));
        assertFalse(java.util.Arrays.equals(PredictionMeshCodec.withCityBuildings(base, low),
                PredictionMeshCodec.withCityBuildings(base, high)));
    }
}
