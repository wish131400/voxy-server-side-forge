package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Requests one bounded 8x8-chunk city-planning summary. */
public record LostCityHintsC2SPayload(ResourceLocation dimension, int regionX, int regionZ,
                                      long session) {
    public static void encode(LostCityHintsC2SPayload payload, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(payload.dimension);
        buffer.writeInt(payload.regionX);
        buffer.writeInt(payload.regionZ);
        buffer.writeLong(payload.session);
    }

    public static LostCityHintsC2SPayload decode(FriendlyByteBuf buffer) {
        return new LostCityHintsC2SPayload(buffer.readResourceLocation(), buffer.readInt(),
                buffer.readInt(), buffer.readLong());
    }
}
