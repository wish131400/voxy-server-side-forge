package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class LostCityHintsPayloadTest {
    @Test
    void fixedSizeMessageRoundTripsAndOwnsItsArrays() {
        int[] entries = new int[64];
        entries[63] = 0x1fffffff;
        var payload = new LostCityHintsS2CPayload(ResourceLocation.parse("lostcities:lostcity"), -1, 2, 17, true, entries);
        entries[63] = 0;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        LostCityHintsS2CPayload.encode(payload, buffer);
        assertTrue(buffer.readableBytes() < 400);
        var decoded = LostCityHintsS2CPayload.decode(buffer);
        try {
            assertEquals(payload.dimension(), decoded.dimension());
            assertEquals(-1, decoded.regionX());
            assertEquals(2, decoded.regionZ());
            assertEquals(17, decoded.session());
            assertArrayEquals(payload.chunks(), decoded.chunks());
            int[] mutable = decoded.chunks();
            mutable[63] = 0;
            assertEquals(0x1fffffff, decoded.chunks()[63]);
        } finally { buffer.release(); }
    }

    @Test
    void rejectsInvalidEntryCounts() {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        assertThrows(IllegalArgumentException.class, () -> new LostCityHintsS2CPayload(dimension, 0, 0, 1, true, new int[65]));
        assertThrows(IllegalArgumentException.class, () -> new LostCityHintsS2CPayload(dimension, 0, 0, 1, false, new int[64]));
    }
}
