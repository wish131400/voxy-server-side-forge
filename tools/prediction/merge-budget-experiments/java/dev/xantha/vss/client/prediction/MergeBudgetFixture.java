package dev.xantha.vss.client.prediction;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Three tile-local canonical meshes from the October 10 capture; no seed or camera. */
final class MergeBudgetFixture {
    record Mesh(int id, int[] words, int[] first, int[] count, int[][] retained, int axis, int spacing, int water) { }
    final boolean[] approximate = new boolean[256], aquatic = new boolean[256], land = new boolean[256], plants = new boolean[256];
    final List<Mesh> meshes = new ArrayList<>();

    MergeBudgetFixture() throws IOException {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/merge-budget/historical-source.bin.gz"));
             var in = new DataInputStream(new GZIPInputStream(stream))) {
            for (int i = 0; i < 256; i++) {
                approximate[i] = in.readBoolean(); aquatic[i] = in.readBoolean();
                land[i] = in.readBoolean(); plants[i] = in.readBoolean();
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int id = in.readInt(), length = in.readInt(), terrain = in.readInt(), water = in.readInt();
                int axis = in.readInt(), spacing = in.readInt();
                int[] words = new int[length]; for (int w = 0; w < length; w++) words[w] = in.readInt();
                boolean crossed = true;
                for (int q = 1; q < terrain; q++) if (group(words[q * 12 + 6], true) < group(words[(q - 1) * 12 + 6], true)) { crossed = false; break; }
                int[] first = new int[5], count = new int[5];
                for (int q = 0; q < terrain; q++) count[group(words[q * 12 + 6], crossed)]++;
                int at = 0; for (int g = 0; g < 5; g++) { first[g] = at; at += count[g]; }
                var merged = PredictionScanMerge.merge(words, first, count, axis, spacing, true, 16);
                boolean[] thin = aquatic.clone(); for (int s = 0; s < thin.length; s++) thin[s] |= land[s];
                var retained = PredictionSpatialOrder.arrange(merged.words(), merged.first(), merged.count(), thin);
                PredictionVegetationRanges.arrange(merged.words(), merged.first(), merged.count(), plants, aquatic);
                meshes.add(new Mesh(id, merged.words(), merged.first(), merged.count(), retained, axis, spacing, water));
            }
        }
    }
    private static int group(int flags, boolean crossed) {
        if (crossed && (flags & PredictionPackedMesh.FLAG_UNSHADED) != 0 && (flags & PredictionPackedMesh.FLAG_MODEL_UV) == 0) return 0;
        int axis = flags >>> 24 & 3; boolean positive = (flags & 1 << 30) != 0;
        return axis == 1 ? (positive ? 4 : 3) : axis == 2 ? (positive ? 2 : 1) : 0;
    }
    MergeBudgetLegacyDisplayGeometry.Result before(Mesh m) {
        return MergeBudgetLegacyDisplayGeometry.build(m.words, m.first, m.count, m.retained, m.axis, approximate);
    }
    PredictionDisplayGeometry.Result after(Mesh m) {
        return PredictionDisplayGeometry.build(m.words, m.first, m.count, m.retained, m.axis, approximate);
    }
    static PredictionDrawRanges source(Mesh m, int mask) { return new PredictionDrawRanges(m.first, m.count, mask); }
    static PredictionDrawRanges range(Mesh m, PredictionDrawRanges[][] ranges, int tier, int mask) {
        return tier == 0 || ranges == null || ranges[tier][mask] == null ? source(m, mask) : ranges[tier][mask];
    }
}
