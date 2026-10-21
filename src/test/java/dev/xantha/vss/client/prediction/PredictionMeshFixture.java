package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Shared mesh input for retained CPU regressions and historical comparisons. */
final class PredictionMeshFixture {
    private PredictionMeshFixture() { }

    static int[] decorated(int featureCount) {
        int[] words = Arrays.copyOf(PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE), (1024 + featureCount) * 12);
        for (int q = 0; q < 1024; q++) {
            int y = 32768 + (80 + q % 2) * 4;
            words[q * 12 + 4] = words[q * 12 + 5] = y | y << 16;
        }
        for (int q = 1024; q < 1024 + featureCount; q++) {
            PredictionSpatialOrderTest.quad(words, q, q % 32, q / 32 % 32, 160,
                    PredictionPackedMesh.FLAG_MODEL_UV | 7);
            words[q * 12 + 8] = q % 1024;
            words[q * 12 + 10] = 0x556633 | PredictionPackedMesh.FEATURE;
            // Distinct models remain exact; the ground proxy must share them.
            words[q * 12 + 11] = q;
        }
        return words;
    }

}
