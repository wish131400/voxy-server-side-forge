package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyCoverageChanges;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Uploaded regional ownership clears temporary representatives, including real empty land.
 * It does not prove pixel visibility and must never retire complete predicted decoration. */
final class PredictionPreviewHandoff {
    private PredictionPreviewHandoff() { }

    interface Uploads {
        long revision();
        StrictVoxyCoverageChanges.Delta changesSince(long previous);
        boolean covers(ResourceKey<Level> dimension, int minX, int minY, int minZ,
                       int maxX, int maxY, int maxZ);
    }

    static final Uploads VOXY = new Uploads() {
        @Override public long revision() { return StrictLodVisibility.coverageRevision(); }
        @Override public StrictVoxyCoverageChanges.Delta changesSince(long previous) {
            return StrictLodVisibility.coverageChangesSince(previous);
        }
        @Override public boolean covers(ResourceKey<Level> dimension, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ) {
            return dimension != null && StrictLodVisibility.predictionCoverageBox(
                    dimension, minX, minY, minZ, maxX, maxY, maxZ);
        }
    };
}
