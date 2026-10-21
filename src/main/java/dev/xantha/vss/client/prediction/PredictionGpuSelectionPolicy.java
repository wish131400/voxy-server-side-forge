package dev.xantha.vss.client.prediction;

/** Fixed scene selection has no camera-motion or visible-list dependency. */
final class PredictionGpuSelectionPolicy {
    static final long MIN_QUADS = 16_384;
    static final int MIN_TERRAIN_TILES = 64;
    boolean eligible(boolean supported, long candidateQuads, int terrainTiles) {
        return supported && candidateQuads >= MIN_QUADS && terrainTiles >= MIN_TERRAIN_TILES
                && !Boolean.getBoolean("vss.disableGpuPredictionSelection");
    }
}
