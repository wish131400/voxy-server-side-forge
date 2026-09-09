package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

/** Forge 1.20.1 has no NeoForge generic payload splitter. */
public record WorldgenProfileFragmentS2CPayload(int transfer, int total, int offset, byte[] data) {
    public static final int CHUNK_BYTES = 512 * 1024;
    public static final int MAX_BYTES = 144 * 1024 * 1024;

    public WorldgenProfileFragmentS2CPayload {
        if (total <= 0 || total > MAX_BYTES || offset < 0 || data == null
                || data.length == 0 || data.length > CHUNK_BYTES || offset > total - data.length) {
            throw new IllegalArgumentException("Invalid worldgen snapshot fragment");
        }
    }

    public static void encode(WorldgenProfileFragmentS2CPayload value, FriendlyByteBuf buf) {
        buf.writeInt(value.transfer);
        buf.writeVarInt(value.total);
        buf.writeVarInt(value.offset);
        buf.writeByteArray(value.data);
    }

    public static WorldgenProfileFragmentS2CPayload decode(FriendlyByteBuf buf) {
        return new WorldgenProfileFragmentS2CPayload(buf.readInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readByteArray(CHUNK_BYTES));
    }
}
