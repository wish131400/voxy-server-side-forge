package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StructureTemplatePayloadTest {
    @Test void requestAndFragmentRoundTripWithUnavailableAcknowledgement() {
        var id = ResourceLocation.parse("mod:tree/base");
        var request = new StructureTemplateRequestC2SPayload("a".repeat(64), id, "b".repeat(64));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StructureTemplateRequestC2SPayload.encode(request, buf);
            assertEquals(request, StructureTemplateRequestC2SPayload.decode(buf));
            var fragment = new StructureTemplateFragmentS2CPayload(request.catalog(), id, request.hash(), 3, 0, new byte[] {1, 2, 3});
            StructureTemplateFragmentS2CPayload.encode(fragment, buf);
            var received = StructureTemplateFragmentS2CPayload.decode(buf);
            assertEquals(fragment.id(), received.id()); assertEquals(fragment.catalog(), received.catalog());
            assertEquals(fragment.hash(), received.hash()); assertArrayEquals(fragment.data(), received.data());
            var missing = new StructureTemplateFragmentS2CPayload(request.catalog(), id, request.hash(), 0, 0, new byte[0]);
            StructureTemplateFragmentS2CPayload.encode(missing, buf);
            assertEquals(0, StructureTemplateFragmentS2CPayload.decode(buf).total());
        } finally { buf.release(); }
    }

    @Test void oversizedFragmentsUnalignedOffsetsAndInvalidHashesAreRejected() {
        var id = ResourceLocation.parse("mod:tree"); String hash = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new StructureTemplateRequestC2SPayload("../cache", id, hash));
        assertThrows(IllegalArgumentException.class, () -> new StructureTemplateFragmentS2CPayload(hash, id, hash, 2, 1, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new StructureTemplateFragmentS2CPayload(hash, id, hash, 17 * 1024 * 1024, 0, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new StructureTemplateFragmentS2CPayload(hash, id, hash, 3, 0, new byte[2]));
    }
}
