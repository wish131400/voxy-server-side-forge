package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** One bounded, ordered transfer per connection, owned by the client thread. */
public final class WorldgenProfileAssembly {
    private byte[] bytes;
    private int transfer;
    private int received;

    public WorldgenProfileS2CPayload accept(WorldgenProfileFragmentS2CPayload part) {
        if (part.offset() == 0) {
            clear();
            bytes = new byte[part.total()];
            transfer = part.transfer();
        }
        if (bytes == null || transfer != part.transfer() || bytes.length != part.total()
                || received != part.offset()) {
            clear();
            throw new IllegalArgumentException("Out-of-order worldgen snapshot fragment");
        }
        System.arraycopy(part.data(), 0, bytes, received, part.data().length);
        received += part.data().length;
        if (received != bytes.length) return null;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        clear();
        try {
            WorldgenProfileS2CPayload result = WorldgenProfileS2CPayload.decode(buf);
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing worldgen snapshot data");
            return result;
        } finally {
            buf.release();
        }
    }

    public void clear() { bytes = null; received = 0; }
}
