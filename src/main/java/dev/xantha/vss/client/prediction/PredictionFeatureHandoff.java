package dev.xantha.vss.client.prediction;

/** Independent ground/decoration ownership in the existing R8 cell mask. */
final class PredictionFeatureHandoff {
    static final int TERRAIN = 128, FEATURES = 64;

    private PredictionFeatureHandoff() { }

    static boolean available(PredictionTileManager.PredictionTile tile) {
        if (tile == null || tile.mesh() == null) return false;
        var payload = tile.mesh().gpuPayload();
        // Completed empty decoration is a valid replacement, e.g. a city clearing.
        return tile.mesh().surfaceReadyForRendering || payload != null && payload.hasFeatures;
    }

    static byte[] mask(boolean[] ground, boolean[] features, byte[] boundary) {
        byte[] mask = new byte[ground.length];
        for (int cell = 0; cell < mask.length; cell++) {
            int value = ground[cell] ? TERRAIN | (boundary == null ? 0 : boundary[cell] & 15) : 0;
            mask[cell] = (byte) (value | (features[cell] ? FEATURES : 0));
        }
        return mask;
    }

    static boolean any(boolean[] allowed) {
        for (boolean value : allowed) if (value) return true;
        return false;
    }
}
