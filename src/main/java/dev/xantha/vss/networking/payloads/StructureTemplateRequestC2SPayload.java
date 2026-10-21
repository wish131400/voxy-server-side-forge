package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** A request is bound to an immutable catalog and exact expected content. */
public record StructureTemplateRequestC2SPayload(String catalog, ResourceLocation id, String hash)
        {

    public StructureTemplateRequestC2SPayload {
        if (!StructureTemplateDigest.validHash(catalog) || !StructureTemplateDigest.validHash(hash) || id == null)
            throw new IllegalArgumentException("Invalid template request");
    }

    public static void encode(StructureTemplateRequestC2SPayload value, FriendlyByteBuf buf) {
        buf.writeUtf(value.catalog, 64); buf.writeResourceLocation(value.id); buf.writeUtf(value.hash, 64);
    }
    public static StructureTemplateRequestC2SPayload decode(FriendlyByteBuf buf) {
        return new StructureTemplateRequestC2SPayload(buf.readUtf(64), buf.readResourceLocation(), buf.readUtf(64));
    }
}
