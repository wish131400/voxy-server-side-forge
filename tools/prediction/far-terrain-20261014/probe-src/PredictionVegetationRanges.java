package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-built opaque ranges. Exact content, land plants and aquatic plants
 * have separate contiguous prefixes; changing detail never copies GPU geometry. */
final class PredictionVegetationRanges {
    private static final int STRIDE = PredictionPackedMesh.STRIDE_INTS;
    private static final int KINDS = 3;
    // Plant importance is a screen-size proxy. It is independent of the
    // measured displacement error of the ground's display representation.
    static final float[] ERRORS = {Float.NaN, 2F, 4F, 8F};
    private final int[] first;
    private final int[][] count;
    private final PredictionDrawRanges[][][] selected = new PredictionDrawRanges[4][4][];
    private final boolean hasPlants;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);
    private static final class Workspace {
        long[] keys = new long[0]; int[] words = new int[0];
        void ensure(int size) {
            if (keys.length < size) keys = new long[size];
            if (words.length < size * STRIDE) words = new int[size * STRIDE];
        }
    }
    private PredictionVegetationRanges(int[] first, int[][] count) {
        this.first = first; this.count = count;
        boolean different = false;
        for (int i = 1; i < first.length; i += KINDS) different |= count[0][i] != count[3][i];
        hasPlants = different;
    }
    boolean hasPlants() { return hasPlants; }
    long retainedHeapBytes() {
        long bytes = 256L + first.length * 4L;
        for (int[] values : count) bytes += 16L + values.length * 4L;
        for (var row : selected) for (var masks : row) if (masks != null) {
            bytes += 16L + masks.length * 4L;
            for (var ranges : masks) if (ranges != null) bytes += 64L + ranges.first.length * 8L;
        }
        return bytes;
    }

    static PredictionVegetationRanges arrange(int[] words, int[] groupFirst, int[] groupCount,
                                               boolean[] vegetation, boolean[] aquatic) {
        int sections = groupCount.length * KINDS;
        int[] starts = new int[sections]; int[][] counts = new int[4][sections];
        int capacity = 0; for (int n : groupCount) capacity = Math.max(capacity, n);
        Workspace workspace = WORKSPACE.get(); workspace.ensure(capacity);
        boolean thinWater = !Boolean.getBoolean("vss.disablePredictionAquaticLod");
        boolean thinLand = thinWater && !Boolean.getBoolean("vss.disablePredictionVegetationLod");
        for (int g = 0; g < groupCount.length; g++) {
            int n = groupCount[g]; boolean ordered = true;
            for (int i = 0; i < n; i++) {
                int q = groupFirst[g] + i, p = q * STRIDE;
                int kind = kind(words, p, vegetation, aquatic, thinLand, thinWater);
                int through = kind == 0 ? 3 : through(words, p);
                int section = g * KINDS + kind;
                for (int tier = 0; tier <= through; tier++) counts[tier][section]++;
                // Preserve the existing spatial order inside a selected cohort.
                long key = ((long) (kind * 4 + 3 - through) << 32) | (q & 0xffffffffL);
                workspace.keys[i] = key;
                ordered &= i == 0 || workspace.keys[i - 1] <= key;
            }
            int at = groupFirst[g];
            for (int kind = 0; kind < KINDS; kind++) {
                int section = g * KINDS + kind; starts[section] = at; at += counts[0][section];
            }
            if (!ordered) {
                Arrays.sort(workspace.keys, 0, n);
                for (int i = 0; i < n; i++) System.arraycopy(words, ((int) workspace.keys[i]) * STRIDE,
                        workspace.words, i * STRIDE, STRIDE);
                System.arraycopy(workspace.words, 0, words, groupFirst[g] * STRIDE, n * STRIDE);
            }
        }
        return new PredictionVegetationRanges(starts, counts);
    }

    private static int kind(int[] words, int p, boolean[] vegetation, boolean[] aquatic, boolean thinLand, boolean thinWater) {
        int flags = words[p + 6], sprite = flags & 65535;
        if ((flags & PredictionPackedMesh.FLAG_UNSHADED) == 0
                || (flags & PredictionPackedMesh.FLAG_MODEL_UV) != 0
                || (flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) != 0
                || (words[p + 10] & PredictionPackedMesh.PREVIEW) != 0) return 0;
        if (sprite < aquatic.length && aquatic[sprite])
            return thinWater && sprite < vegetation.length && vegetation[sprite] ? 2 : 0;
        return sprite < vegetation.length && vegetation[sprite]
                && (words[p + 10] & PredictionPackedMesh.FEATURE) != 0
                && thinLand ? 1 : 0;
    }
    private static int through(int[] words, int p) {
        int flags = words[p + 6];
        double scale = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0 ? 1.0 / 16
                : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
        int column = (int) Math.floor(center(words, p) * scale) | (int) Math.floor(center(words, p + 2) * scale);
        if ((column & 1) != 0) return 0;
        if ((column & 3) != 0) return 1;
        return (column & 7) != 0 ? 2 : 3;
    }
    private static double center(int[] words, int p) {
        int a = words[p], b = words[p + 1];
        return ((a & 65535) + (a >>> 16) + (b & 65535) + (b >>> 16)) * .25;
    }

    /** Called once by the worker when a terrain variant is appended. */
    void offset(int quads) { for (int i = 0; i < first.length; i++) first[i] += quads; }

    PredictionDrawRanges range(int visible, int vegetationTier, int aquaticTier) {
        var cache = selected[vegetationTier][aquaticTier];
        if (cache == null) selected[vegetationTier][aquaticTier] = cache = new PredictionDrawRanges[32];
        var result = cache[visible];
        if (result != null) return result;
        int[] starts = new int[first.length], lengths = new int[first.length]; int n = 0;
        for (int section = 0; section < first.length; section++) {
            if ((visible & 1 << (section / KINDS)) == 0) continue;
            int kind = section % KINDS, tier = kind == 1 ? vegetationTier : kind == 2 ? aquaticTier : 0;
            int length = count[tier][section];
            if (length == 0) continue;
            if (n > 0 && starts[n - 1] + lengths[n - 1] == first[section]) lengths[n - 1] += length;
            else { starts[n] = first[section]; lengths[n++] = length; }
        }
        return cache[visible] =
                new PredictionDrawRanges(Arrays.copyOf(starts, n), Arrays.copyOf(lengths, n));
    }
}
