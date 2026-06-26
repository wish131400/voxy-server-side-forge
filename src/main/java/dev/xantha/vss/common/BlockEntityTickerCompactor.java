package dev.xantha.vss.common;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.Level;

public final class BlockEntityTickerCompactor {
    private static final Set<Level> REQUESTED_LEVELS = ConcurrentHashMap.newKeySet();

    private BlockEntityTickerCompactor() {
    }

    public static void request(Level level) {
        if (level != null) {
            REQUESTED_LEVELS.add(level);
        }
    }

    public static boolean consume(Level level) {
        return level != null && REQUESTED_LEVELS.remove(level);
    }
}
