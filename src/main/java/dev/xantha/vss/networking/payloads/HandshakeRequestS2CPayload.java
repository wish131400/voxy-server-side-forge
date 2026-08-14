package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

public record HandshakeRequestS2CPayload() {
    public static void encode(HandshakeRequestS2CPayload payload, FriendlyByteBuf buf) {
    }

    public static HandshakeRequestS2CPayload decode(FriendlyByteBuf buf) {
        return new HandshakeRequestS2CPayload();
    }
}
