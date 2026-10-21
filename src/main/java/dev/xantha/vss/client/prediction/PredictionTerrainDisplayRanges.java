package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Shared exact feature ranges beside a terrain-only proxy; no coordinate copy. */
final class PredictionTerrainDisplayRanges {
    private final PredictionDrawRanges[] features;
    private final PredictionVegetationRanges sourceVegetation;
    private final PredictionDrawRanges[][] proxyRanges;
    private final PredictionVegetationRanges[] proxyVegetation;
    private final boolean ownsProxyVegetation;
    private final PredictionDrawRanges[][][] selected = new PredictionDrawRanges[4][4 * 4 * PredictionGroundCoverLod.TIERS][];
    private long retainedBytes;

    PredictionTerrainDisplayRanges(PredictionDrawRanges[] features, PredictionVegetationRanges sourceVegetation,
                                   PredictionDrawRanges[][] ranges, PredictionVegetationRanges[] proxyVegetation) {
        this(features, sourceVegetation, ranges, proxyVegetation, true);
    }

    PredictionTerrainDisplayRanges(PredictionDrawRanges[] features, PredictionVegetationRanges sourceVegetation,
                                           PredictionDrawRanges[][] ranges, PredictionVegetationRanges[] proxyVegetation,
                                           boolean ownsVegetation) {
        this.features = features;
        this.sourceVegetation = sourceVegetation;
        proxyRanges = ranges;
        this.proxyVegetation = proxyVegetation;
        ownsProxyVegetation = ownsVegetation;
        retainedBytes = 512L + selected.length * (16L + selected[0].length * 4L);
        for (var range : features) retainedBytes += 64L + range.first.length * 8L;
        if (!ownsVegetation) for (int tier = 1; tier < ranges.length; tier++)
            if (ranges[tier] != null && ranges[tier] != ranges[tier - 1])
                for (var range : ranges[tier]) retainedBytes += 64L + range.first.length * 8L;
    }

    PredictionDrawRanges range(int tier, int aquatic, int visible, int plantTier) {
        return range(tier, aquatic, visible, plantTier, plantTier);
    }
    PredictionDrawRanges range(int tier, int aquatic, int visible, int plantTier, int groundCoverTier) {
        if (proxyRanges[tier] == null || proxyRanges[tier][visible] == null) return null;
        int key = (plantTier * PredictionGroundCoverLod.TIERS + groundCoverTier) * 4 + aquatic;
        var cache = selected[tier][key];
        if (cache == null) {
            selected[tier][key] = cache = new PredictionDrawRanges[32];
            retainedBytes += 16L + cache.length * 4L;
        }
        var result = cache[visible];
        if (result != null) return result;
        var exact = intersect(features[visible], sourceVegetation.range(visible, plantTier, aquatic, groundCoverTier));
        var proxy = intersect(proxyRanges[tier][visible], proxyVegetation[tier].range(visible, plantTier, aquatic, groundCoverTier));
        int n = exact.first.length, m = proxy.first.length;
        int[] first = Arrays.copyOf(exact.first, n + m), count = Arrays.copyOf(exact.count, n + m);
        System.arraycopy(proxy.first, 0, first, n, m);
        System.arraycopy(proxy.count, 0, count, n, m);
        result = new PredictionDrawRanges(first, count);
        retainedBytes += 64L + result.first.length * 8L;
        return cache[visible] = result;
    }

    long retainedHeapBytes() {
        // PackedMesh already accounts for the shared source vegetation plan.
        long bytes = retainedBytes;
        if (ownsProxyVegetation) for (int tier = 1; tier < proxyVegetation.length; tier++)
            if (proxyVegetation[tier] != null && proxyVegetation[tier] != proxyVegetation[tier - 1])
                bytes += proxyVegetation[tier].retainedHeapBytes();
        return bytes;
    }

    PredictionDrawRanges[] cacheFeatures() { return features; }
    PredictionDrawRanges[][] cacheProxies() { return proxyRanges; }
    PredictionVegetationRanges[] cacheVegetation() { return proxyVegetation; }
    boolean ownsProxyVegetation() { return ownsProxyVegetation; }

    /** Reuse already allocated terrain faces while features keep exact source
     * positions during their separate ownership handoff. No extra GPU words. */
    static PredictionTerrainDisplayRanges shareExisting(int[] source, int[] first, int[] count,
            PredictionVegetationRanges sourceVegetation, PredictionDisplayGeometry.Result display) {
        boolean any = false;
        for (int g = 0; g < count.length && !any; g++) for (int q = first[g], end = q + count[g]; q < end; q++)
            if ((source[q * PredictionPackedMesh.STRIDE_INTS + 10] & PredictionPackedMesh.FEATURE) != 0) { any = true; break; }
        if (!any) return null;
        var featureRanges = features(source, first, count);
        if (featureRanges == null) return null;
        var terrain = new PredictionDrawRanges[4][];
        for (int tier = 1; tier < 4; tier++) {
            if (display.vegetation()[tier] == null) continue;
            if (display.vegetation()[tier] == display.vegetation()[tier - 1]) { terrain[tier] = terrain[tier - 1]; continue; }
            var groups = new PredictionDrawRanges[first.length];
            for (int g = 0; g < groups.length; g++) groups[g] = display.range(tier, 0, 1 << g, 0);
            terrain[tier] = extract(display.words(), groups, false);
            if (terrain[tier] == null) return null;
        }
        // Existing vegetation metadata is already counted by Result; only the
        // split selections and their small ground/feature runs are additional.
        return new PredictionTerrainDisplayRanges(featureRanges, sourceVegetation, terrain, display.vegetation(), false);
    }


    private static PredictionDrawRanges intersect(PredictionDrawRanges left, PredictionDrawRanges right) {
        int[] first = new int[left.first.length + right.first.length];
        int[] count = new int[first.length];
        int a = 0, b = 0, n = 0;
        while (a < left.first.length && b < right.first.length) {
            int start = Math.max(left.first[a], right.first[b]);
            int endA = left.first[a] + left.count[a], endB = right.first[b] + right.count[b];
            int end = Math.min(endA, endB);
            if (start < end) { first[n] = start; count[n++] = end - start; }
            if (endA <= endB) a++;
            if (endB <= endA) b++;
        }
        return new PredictionDrawRanges(Arrays.copyOf(first, n), Arrays.copyOf(count, n));
    }

    /** Build small per-face runs after the worker's exact-ground/feature ordering. */
    static PredictionDrawRanges[] features(int[] source, int[] first, int[] count) {
        var groups = new PredictionDrawRanges[first.length];
        for (int g = 0; g < first.length; g++) groups[g] = new PredictionDrawRanges(new int[]{first[g]}, new int[]{count[g]});
        return extract(source, groups, true);
    }

    private static PredictionDrawRanges[] extract(int[] source, PredictionDrawRanges[] groups, boolean feature) {
        int[][] starts = new int[groups.length][], lengths = new int[groups.length][];
        int runs = 0;
        for (int g = 0; g < groups.length; g++) {
            int[] f = new int[32], c = new int[32]; int n = 0;
            for (int r = 0; r < groups[g].first.length; r++) for (int q = groups[g].first[r], end = q + groups[g].count[r]; q < end; q++) {
                if (((source[q * PredictionPackedMesh.STRIDE_INTS + 10] & PredictionPackedMesh.FEATURE) != 0) != feature) continue;
                if (n > 0 && f[n - 1] + c[n - 1] == q) c[n - 1]++;
                else {
                    if (n == f.length) { f = Arrays.copyOf(f, n * 2); c = Arrays.copyOf(c, n * 2); }
                    f[n] = q; c[n++] = 1;
                }
            }
            runs += n;
            // Unordered legacy/fixture data must not create thousands of commands.
            if (runs > 128) return null;
            starts[g] = Arrays.copyOf(f, n); lengths[g] = Arrays.copyOf(c, n);
        }
        PredictionDrawRanges[] result = new PredictionDrawRanges[32];
        for (int visible = 0; visible < 32; visible++) {
            int[] f = new int[runs], c = new int[runs]; int n = 0;
            for (int g = 0; g < groups.length; g++) if ((visible & 1 << g) != 0)
                for (int r = 0; r < starts[g].length; r++) { f[n] = starts[g][r]; c[n++] = lengths[g][r]; }
            result[visible] = new PredictionDrawRanges(Arrays.copyOf(f, n), Arrays.copyOf(c, n));
        }
        return result;
    }
}
