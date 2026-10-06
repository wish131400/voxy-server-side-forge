package dev.xantha.vss.client.prediction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Limits cold upgrades during fast travel without stopping cached or incremental progress. */
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
        long elapsed = now - lastAt;
        if (lastAt != 0 && elapsed >= 20_000_000L && elapsed <= 500_000_000L) {
            double dx = x - lastX;
            double dz = z - lastZ;
            double distanceSquared = dx * dx + dz * dz;
            double minimumDistance = (sprinting ? 5.5D : 6.0D) * elapsed / 1_000_000_000D;
            if (distanceSquared >= minimumDistance * minimumDistance) {
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

    static int upgradeBuildLimit(int workers) {
        return Math.max(1, Math.min(2, workers / 3));
    }
}
