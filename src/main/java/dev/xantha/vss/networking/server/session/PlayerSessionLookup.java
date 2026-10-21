package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 只读会话查询，复用注册表和生命周期，不复制在线状态。
 * <p>玩家与世界属性只在服务端线程读取；停止期间统一表现为无有效会话。
 */
public final class PlayerSessionLookup {
    private final PlayerRequestRegistry players;
    private final ServerLifecycleGuard lifecycle;

    public PlayerSessionLookup(PlayerRequestRegistry players, ServerLifecycleGuard lifecycle) {
        this.players = Objects.requireNonNull(players, "players");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public boolean isRegistered(ServerPlayer player) {
        return !lifecycle.isStopping() && players.contains(player.getUUID());
    }

    public boolean hasRegisteredPlayers() {
        return !lifecycle.isStopping() && !players.isEmpty();
    }

    public boolean hasRegisteredPlayers(ServerLevel level) {
        if (lifecycle.isStopping()) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (isRegistered(player)) {
                return true;
            }
        }
        return false;
    }

    public long clientKnownColumnTimestamp(
            ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            int cx,
            int cz) {
        if (lifecycle.isStopping() || player == null) {
            return 0L;
        }
        PlayerRequestState state = players.get(player.getUUID());
        return state != null ? state.clientKnownTimestamp(dimension, cx, cz) : 0L;
    }

}
