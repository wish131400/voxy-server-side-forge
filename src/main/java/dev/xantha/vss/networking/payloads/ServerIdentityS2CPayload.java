package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.ColumnCoverage;
import java.util.function.IntSupplier;
import net.minecraft.network.FriendlyByteBuf;

public final class ServerIdentityS2CPayload implements IntSupplier {
    private static final int MAX_SERVER_IDENTITY_LENGTH = 16;
    private static final int MAX_NODE_IDENTITY_LENGTH = 32;

    private int loginIndex;
    private final String serverIdentity;
    private final boolean sharedWorld;
    private final String nodeIdentity;
    private final String columnFilterKey;

    public ServerIdentityS2CPayload(String serverIdentity, boolean sharedWorld, String nodeIdentity) {
        this(serverIdentity, sharedWorld, nodeIdentity, "");
    }

    public ServerIdentityS2CPayload(String serverIdentity, boolean sharedWorld, String nodeIdentity, String columnFilterKey) {
        this.serverIdentity = serverIdentity;
        this.sharedWorld = sharedWorld;
        this.nodeIdentity = nodeIdentity;
        this.columnFilterKey = new ColumnCoverage(columnFilterKey).filterKey();
    }

    public static ServerIdentityS2CPayload fromConfig() {
        return fromConfig("");
    }

    public static ServerIdentityS2CPayload fromConfig(String columnFilterKey) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return new ServerIdentityS2CPayload(config.serverIdentity, config.sharedWorld, config.nodeIdentity, columnFilterKey);
    }

    public String serverIdentity() {
        return serverIdentity;
    }

    public boolean sharedWorld() {
        return sharedWorld;
    }

    public String nodeIdentity() {
        return nodeIdentity;
    }

    public String columnFilterKey() { return columnFilterKey; }

    @Override
    public int getAsInt() {
        return loginIndex;
    }

    public void setLoginIndex(int loginIndex) {
        this.loginIndex = loginIndex;
    }

    public static void encode(ServerIdentityS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.serverIdentity, MAX_SERVER_IDENTITY_LENGTH);
        buf.writeBoolean(payload.sharedWorld);
        buf.writeUtf(payload.nodeIdentity, MAX_NODE_IDENTITY_LENGTH);
        buf.writeUtf(payload.columnFilterKey, 64);
    }

    public static ServerIdentityS2CPayload decode(FriendlyByteBuf buf) {
        return new ServerIdentityS2CPayload(
                buf.readUtf(MAX_SERVER_IDENTITY_LENGTH),
                buf.readBoolean(),
                buf.readUtf(MAX_NODE_IDENTITY_LENGTH), buf.readUtf(64));
    }
}
