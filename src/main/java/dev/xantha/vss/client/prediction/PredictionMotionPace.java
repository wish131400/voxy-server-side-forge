package dev.xantha.vss.client.prediction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Keeps expensive upgrades behind new coverage during fast travel. */
final class PredictionMotionPace {
    private static final long SETTLE_NANOS = 800_000_000L;
    private static ResourceKey<Level> lastDimension;
    private static double lastX, lastZ;
    private static long lastAt;
    private static volatile long fastUntil;

    private PredictionMotionPace() { }

    static void record(ResourceKey<Level> dimension, double x, double z, boolean sprinting, long now) {
        if (dimension == null || !dimension.equals(lastDimension) || now < lastAt) {
            lastDimension = dimension;
            lastAt = now;
            lastX = x;
            lastZ = z;
            fastUntil = 0;
            return;
        }
        if (lastAt != 0 && now - lastAt <= 500_000_000L) {
            double dx = x - lastX;
            double dz = z - lastZ;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared >= 0.24D * 0.24D || sprinting && distanceSquared >= 0.08D * 0.08D) {
                fastUntil = now + SETTLE_NANOS;
            }
        }
        lastX = x;
        lastZ = z;
        lastAt = now;
    }

    static boolean fastMoving() {
        return fastMoving(System.nanoTime());
    }

    static boolean fastMoving(long now) {
        return now < fastUntil && now >= fastUntil - SETTLE_NANOS;
    }

    static boolean deferUpgrade(boolean moving, boolean residentCover, boolean surface,
                                boolean dirty, boolean scoped) {
        return moving && !dirty && !scoped && (surface || residentCover);
    }

    static int coverageBuildLimit(int workers) {
        return Math.max(1, Math.min(2, workers / 3));
    }
}
