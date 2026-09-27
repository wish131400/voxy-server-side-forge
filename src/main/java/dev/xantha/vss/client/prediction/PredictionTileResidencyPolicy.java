package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.util.Set;

/** Residency decisions only; the manager owns tasks, tiles and resource release. */
final class PredictionTileResidencyPolicy {
    static final double RETIREMENT_MARGIN_BLOCKS = 1024.0D;
    static final long COLD_TILE_NANOS = 30_000_000_000L;

    private PredictionTileResidencyPolicy() { }

    static boolean retireAfterDistanceReduction(PredictionTileKey key, Set<PredictionTileKey> desired,
                                                VssLodLayout layout, double cameraX, double cameraZ) {
        if (key.lod() < 0 || key.lod() >= layout.levelCount()) return true;
        if (desired.contains(key)) return false;
        int span = layout.tileBlocks(key.lod());
        return beyondHorizon(key.tileX() * span, key.tileZ() * span, span,
                (int) Math.floor(cameraX), (int) Math.floor(cameraZ), layout.maxDistanceBlocks());
    }

    static boolean outOfRetirementRange(PredictionTileKey key, VssLodLayout layout,
                                        int cameraBlockX, int cameraBlockZ) {
        if (key.lod() < 0 || key.lod() >= layout.levelCount()) return true;
        int span = layout.tileBlocks(key.lod());
        return beyondHorizon(key.tileX() * span, key.tileZ() * span, span,
                cameraBlockX, cameraBlockZ, layout.maxDistanceBlocks() + RETIREMENT_MARGIN_BLOCKS);
    }

    static PredictionTileKey firstMissingAncestor(PredictionTileKey key, Set<PredictionTileKey> readyKeys,
                                                  VssLodLayout layout) {
        if (key == null || layout == null || key.lod() < 0 || key.lod() >= layout.levelCount() - 1) return null;
        PredictionTileKey parent = new PredictionTileKey(key.dimension(),
                key.tileX() >> 1, key.tileZ() >> 1, key.lod() + 1);
        return readyKeys.contains(parent) ? null : parent;
    }

    static boolean shouldRetirePinned(PredictionTileKey key, Set<PredictionTileKey> readyKeys,
                                       Set<PredictionTileKey> desiredKeys, VssLodLayout layout,
                                       int cameraBlockX, int cameraBlockZ) {
        if (desiredKeys.contains(key)) return false;
        if (!outOfRetirementRange(key, layout, cameraBlockX, cameraBlockZ)) return false;
        if (key.lod() < 0 || key.lod() >= layout.levelCount() - 1) return true;
        // A queued parent cannot replace resident detail. GPU residency handles the final upload hand-off.
        return coveredByAncestor(readyKeys, layout, key);
    }

    static boolean canRetireStored(PredictionTileKey key, long ageNanos, boolean desired, boolean stored,
                                    VssLodLayout layout, boolean outsideHorizon) {
        return stored && !desired && ageNanos >= COLD_TILE_NANOS && key.lod() < layout.levelCount()
                && outsideHorizon;
    }

    static boolean beyondHorizon(int minX, int minZ, int span,
                                 int cameraX, int cameraZ, double distanceBlocks) {
        double dx = cameraX < minX ? minX - cameraX
                : cameraX > minX + span ? cameraX - (minX + span) : 0.0D;
        double dz = cameraZ < minZ ? minZ - cameraZ
                : cameraZ > minZ + span ? cameraZ - (minZ + span) : 0.0D;
        return Math.sqrt(dx * dx + dz * dz) > distanceBlocks;
    }

    static boolean coveredByAncestor(Set<PredictionTileKey> readyKeys, VssLodLayout layout,
                                      PredictionTileKey key) {
        for (int lod = key.lod() + 1; lod < layout.levelCount(); lod++) {
            int span = layout.tileBlocks(lod) / 16;
            PredictionTileKey ancestor = new PredictionTileKey(key.dimension(),
                    Math.floorDiv(key.tileX() * (layout.tileBlocks(key.lod()) / 16), span),
                    Math.floorDiv(key.tileZ() * (layout.tileBlocks(key.lod()) / 16), span), lod);
            if (readyKeys.contains(ancestor)) return true;
        }
        return false;
    }
}
