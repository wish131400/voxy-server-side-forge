package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-built surface aggregation. Only unions existing coplanar leaf faces:
 * no occupied volume is invented, no alpha holes are filled and no vertex moves. */
final class CoverageCanopyGeometry {
    record Result(int[] words, PredictionDrawRanges[][] ranges, float[] errors,
                  PredictionVegetationRanges[] vegetation, CoverageCanopyGeometry canopy) {
        Result(int[] words, PredictionDrawRanges[][] ranges) { this(words, ranges, null, null, null); }
    }
    private static final int STRIDE = PredictionPackedMesh.STRIDE_INTS;
    private final int[] leafFirst, leafCount, proxyFirst, proxyCount;
    private final PredictionVegetationRanges sourceVegetation;
    private final int[] groupFirst, groupCount;
    private final PredictionDrawRanges[][][] ranges = new PredictionDrawRanges[4][4][32];

    private CoverageCanopyGeometry(int[] leafFirst, int[] leafCount, int[] proxyFirst, int[] proxyCount,
                                     int[] first, int[] count, PredictionVegetationRanges vegetation) {
        this.leafFirst = leafFirst; this.leafCount = leafCount;
        this.proxyFirst = proxyFirst; this.proxyCount = proxyCount;
        this.groupFirst = first.clone(); this.groupCount = count.clone();
        this.sourceVegetation = vegetation;
    }

    static CoverageCanopyGeometry.Result build(int[] source, int[] first, int[] count, int axis,
                                                   PredictionVegetationRanges vegetation) {
        if (Boolean.getBoolean("vss.disablePredictionCanopyLod")) return new CoverageCanopyGeometry.Result(source, null);
        return build(source, first, count, axis, vegetation, VssLodSpriteTable.leafSprites());
    }

    static CoverageCanopyGeometry.Result build(int[] source, int[] first, int[] count, int axis,
                                                   PredictionVegetationRanges vegetation, int[] leaves) {
        int total = Arrays.stream(count).sum();
        if (axis <= 0 || total < 256 || total > 131072) return new CoverageCanopyGeometry.Result(source, null);
        int[] sizes = new int[count.length], starts = new int[count.length];
        for (int g = 0; g < count.length; g++) for (int q = first[g], end = q + count[g]; q < end; q++)
            if (leaf(source, q * STRIDE, leaves)) sizes[g]++;
        int leafTotal = Arrays.stream(sizes).sum();
        if (leafTotal < 256) return new CoverageCanopyGeometry.Result(source, null);
        int[] proxy = new int[leafTotal * STRIDE];
        int at = 0;
        for (int g = 0; g < count.length; g++) {
            starts[g] = at;
            for (int q = first[g], end = q + count[g]; q < end; q++) if (leaf(source, q * STRIDE, leaves)) {
                System.arraycopy(source, q * STRIDE, proxy, at * STRIDE, STRIDE);
                // Complete tile ownership is required by PreparedPass. Tangential
                // ownership is still resolved per fragment, as for terrain display
                // meshes. Keep the normal source coordinate and all material data.
                proxy[at * STRIDE + 9] &= ~(1 << 24);
                proxy[at * STRIDE + 10] |= PredictionPackedMesh.DISPLAY_APPROXIMATE;
                at++;
            }
        }
        var merged = PredictionScanMerge.mergeDisplay(proxy, starts, sizes, axis, 16);
        int proxyTotal = Arrays.stream(merged.count()).sum();
        // Delta residency: append just the aggregated leaf surfaces, reusing
        // original terrain, trunks, plants and water. Require substantial savings.
        if (leafTotal - proxyTotal < 64 || proxyTotal > leafTotal / 2 || proxyTotal > total / 4)
            return new CoverageCanopyGeometry.Result(source, null);
        int[] combined = Arrays.copyOf(source, source.length + proxyTotal * STRIDE);
        int[] sourceLeafFirst = first.clone(), sourceLeafCount = sizes.clone();
        // Leaves are kind zero in VegetationRanges. Moving only those leaves to
        // each group's prefix preserves its kind counts and all plant prefixes.
        for (int g = 0; g < count.length; g++) {
            at = first[g];
            for (int q = first[g], end = q + count[g]; q < end; q++) if (leaf(source, q * STRIDE, leaves))
                System.arraycopy(source, q * STRIDE, combined, at++ * STRIDE, STRIDE);
            for (int q = first[g], end = q + count[g]; q < end; q++) if (!leaf(source, q * STRIDE, leaves))
                System.arraycopy(source, q * STRIDE, combined, at++ * STRIDE, STRIDE);
        }
        int offset = source.length / STRIDE;
        System.arraycopy(merged.words(), 0, combined, source.length, proxyTotal * STRIDE);
        int[] proxyFirst = merged.first().clone();
        for (int g = 0; g < proxyFirst.length; g++) proxyFirst[g] += offset;
        var canopy = new CoverageCanopyGeometry(sourceLeafFirst, sourceLeafCount, proxyFirst,
                merged.count(), first, count, vegetation);
        var selected = new PredictionDrawRanges[4][32];
        for (int tier = 1; tier <= 3; tier++) for (int visible = 0; visible < 32; visible++)
            selected[tier][visible] = canopy.range(visible, 0, 0);
        // A conservative one-block screen gate keeps this optional representation
        // distant even though its positions, material/light and silhouette are exact.
        return new CoverageCanopyGeometry.Result(combined, selected,
                new float[]{Float.NaN, 1, 1, 1}, null, canopy);
    }

    private static boolean leaf(int[] words, int p, int[] leaves) {
        int flags = words[p + 6], row = flags & 65535;
        return row < leaves.length * 32 && (leaves[row >> 5] & 1 << (row & 31)) != 0
                && (flags & (PredictionPackedMesh.FLAG_MODEL_UV | PredictionPackedMesh.FLAG_UNSHADED
                        | 3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT)) == 0
                && (words[p + 9] & 0x06000000) == 0
                && (words[p + 10] & (PredictionPackedMesh.FEATURE | PredictionPackedMesh.PREVIEW)) == PredictionPackedMesh.FEATURE;
    }

    PredictionDrawRanges range(int visible, int plantTier, int aquaticTier) {
        var result = ranges[plantTier][aquaticTier][visible];
        if (result != null) return result;
        PredictionDrawRanges original = sourceVegetation == null ? null : sourceVegetation.range(visible, plantTier, aquaticTier);
        int[] starts = new int[32], counts = new int[32]; int n = 0;
        // Source ranges are ordered. Subtract only the original leaf prefix;
        // plant/seaweed selection and all retained source records stay independent.
        if (original == null) {
            for (int g = 0; g < groupCount.length; g++) if ((visible & 1 << g) != 0 && groupCount[g] > leafCount[g]) {
                starts[n] = groupFirst[g] + leafCount[g]; counts[n++] = groupCount[g] - leafCount[g];
            }
        } else for (int i = 0; i < original.first.length; i++) {
            int cursor = original.first[i], end = cursor + original.count[i];
            for (int g = 0; g < leafCount.length; g++) {
                int lo = leafFirst[g], hi = lo + leafCount[g];
                if (hi <= cursor || lo >= end) continue;
                if (cursor < lo) { starts[n] = cursor; counts[n++] = lo - cursor; }
                cursor = Math.max(cursor, hi);
            }
            if (cursor < end) { starts[n] = cursor; counts[n++] = end - cursor; }
        }
        for (int g = 0; g < proxyCount.length; g++) if ((visible & 1 << g) != 0 && proxyCount[g] != 0) {
            starts[n] = proxyFirst[g]; counts[n++] = proxyCount[g];
        }
        return ranges[plantTier][aquaticTier][visible] = new PredictionDrawRanges(Arrays.copyOf(starts, n), Arrays.copyOf(counts, n));
    }

    long retainedHeapBytes() {
        long bytes = 4096; // prefix arrays and the bounded 4*4*32 reference table
        for (var plants : ranges) for (var aquatic : plants) for (var r : aquatic)
            if (r != null) bytes += 64L + r.first.length * 8L;
        return bytes;
    }
}
