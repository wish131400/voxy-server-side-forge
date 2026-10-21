package dev.xantha.vss.client.prediction;

import java.util.*;

/** Four coherent, already uploaded representations. Commands store membership,
 * so the current projection changes GPU choices, never CPU range assembly. */
final class PredictionGpuRepresentations {
    record Segments(PredictionDrawRanges ranges, int[] masks, int[] kinds) { }
    final PredictionDrawRanges union;
    private final int[] first, end, masks;

    PredictionGpuRepresentations(PredictionDrawRanges[] tiers) {
        var events = new TreeMap<Integer, int[]>();
        for (int t = 0; t < tiers.length; t++) {
            var r = tiers[t];
            for (int i = 0; i < r.first.length; i++) if (r.count[i] > 0) {
                events.computeIfAbsent(r.first[i], unused -> new int[4])[t]++;
                events.computeIfAbsent(r.first[i] + r.count[i], unused -> new int[4])[t]--;
            }
        }
        var f = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var e = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var m = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int[] active = new int[4]; int previous = 0, mask = 0;
        for (var event : events.entrySet()) {
            int at = event.getKey();
            if (mask != 0 && at > previous) { f.add(previous); e.add(at); m.add(mask); }
            for (int t = 0; t < 4; t++) active[t] += event.getValue()[t];
            mask = 0; for (int t = 0; t < 4; t++) if (active[t] > 0) mask |= 1 << t;
            previous = at;
        }
        first = f.toIntArray(); end = e.toIntArray(); masks = m.toIntArray();
        int[] count = new int[end.length];
        for (int i = 0; i < count.length; i++) count[i] = end[i] - first[i];
        union = new PredictionDrawRanges(first, count);
    }

    Segments clip(PredictionDrawRanges selected, PredictionMeshletBounds bounds, boolean seam) {
        var starts = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var counts = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var member = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var kinds = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int run = 0;
        for (int r = 0; r < selected.first.length; r++) {
            int at = selected.first[r], stop = at + selected.count[r];
            while (run < end.length && end[run] <= at) run++;
            int i = run;
            while (i < end.length && first[i] < stop) {
                int p = Math.max(at, first[i]), limit = Math.min(stop, end[i]);
                while (p < limit) {
                    int group = Arrays.binarySearch(bounds.renderEnds, p + 1);
                    if (group < 0) group = -group - 1;
                    int next = Math.min(PredictionMeshletBounds.end(p, limit), bounds.renderEnds[group]);
                    starts.add(p); counts.add(next - p); member.add(masks[i]);
                    kinds.add(seam ? 2 : bounds.renderKinds[group]); p = next;
                }
                i++;
            }
        }
        return new Segments(new PredictionDrawRanges(starts.toIntArray(), counts.toIntArray()),
                member.toIntArray(), kinds.toIntArray());
    }
    long retainedBytes() { return 96L + first.length * 16L; }
}
