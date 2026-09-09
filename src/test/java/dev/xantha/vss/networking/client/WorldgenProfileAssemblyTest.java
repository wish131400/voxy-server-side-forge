package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorldgenProfileAssemblyTest {
    @Test void largeSnapshotRoundTripsAcrossForgePacketBoundaries() {
        byte[] registry = new byte[3 * 1024 * 1024 + 13];
        new java.util.Random(42).nextBytes(registry);
        var profile = new WorldgenProfileS2CPayload(3, 42, 7, 0, registry.length, registry, List.of());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            WorldgenProfileS2CPayload.encode(profile, buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            var assembly = new WorldgenProfileAssembly();
            WorldgenProfileS2CPayload result = null;
            for (int offset = 0; offset < bytes.length;) {
                int end = Math.min(bytes.length, offset + WorldgenProfileFragmentS2CPayload.CHUNK_BYTES);
                var part = new WorldgenProfileFragmentS2CPayload(1, bytes.length, offset, Arrays.copyOfRange(bytes, offset, end));
                buf.clear();
                WorldgenProfileFragmentS2CPayload.encode(part, buf);
                assertTrue(buf.readableBytes() < 1024 * 1024);
                result = assembly.accept(WorldgenProfileFragmentS2CPayload.decode(buf));
                if (end < bytes.length) assertNull(result);
                offset = end;
            }
            assertNotNull(result);
            assertTrue(profile.sameWorldgen(result));
            assertEquals(profile.revision(), result.revision());
        } finally { buf.release(); }
    }

    @Test void disconnectAndOutOfOrderTransfersCannotCombineWorlds() {
        var assembly = new WorldgenProfileAssembly();
        var first = new WorldgenProfileFragmentS2CPayload(1, 6, 0, new byte[]{1, 2});
        assertNull(assembly.accept(first));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(
                new WorldgenProfileFragmentS2CPayload(2, 6, 2, new byte[]{3, 4})));
        assertNull(assembly.accept(first));
        assembly.clear();
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(
                new WorldgenProfileFragmentS2CPayload(1, 6, 2, new byte[]{3, 4})));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(
                1, Integer.MAX_VALUE, 0, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(
                1, 6, 5, new byte[]{1, 2}));
    }
}
