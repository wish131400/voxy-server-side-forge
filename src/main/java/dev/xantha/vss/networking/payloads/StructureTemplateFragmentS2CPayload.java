package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** total=0 acknowledges an unavailable template; data fragments have aligned offsets. */
public record StructureTemplateFragmentS2CPayload(String catalog, ResourceLocation id, String hash,
                                                 int total, int offset, byte[] data) {

    public StructureTemplateFragmentS2CPayload {
        if (!StructureTemplateDigest.validHash(catalog) || !StructureTemplateDigest.validHash(hash) || id == null
                || total < 0 || total > StructureTemplateDigest.MAX_BYTES || offset < 0 || data == null
                || offset % StructureTemplateDigest.FRAGMENT_BYTES != 0 || offset > total
                || data.length != Math.min(StructureTemplateDigest.FRAGMENT_BYTES, total - offset)
                || total > 0 && data.length == 0)
            throw new IllegalArgumentException("Invalid template fragment");
    }

    public static void encode(StructureTemplateFragmentS2CPayload value, FriendlyByteBuf buf) {
        buf.writeUtf(value.catalog, 64); buf.writeResourceLocation(value.id); buf.writeUtf(value.hash, 64);
        buf.writeVarInt(value.total); buf.writeVarInt(value.offset); buf.writeByteArray(value.data);
    }
    public static StructureTemplateFragmentS2CPayload decode(FriendlyByteBuf buf) {
        return new StructureTemplateFragmentS2CPayload(buf.readUtf(64), buf.readResourceLocation(), buf.readUtf(64),
                buf.readVarInt(), buf.readVarInt(), buf.readByteArray(StructureTemplateDigest.FRAGMENT_BYTES));
    }
}
