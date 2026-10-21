package dev.xantha.vss.client.prediction;

/** Tight bounds for consecutive packed quads; built with the mesh, never decoded in a frame loop. */
final class PredictionMeshletBounds {
    static final int QUADS = 256;
    final float[] values;
    /** Six axis directions; bit 6 keeps unknown or double-sided geometry. */
    final int[] directions;
    final int[] renderEnds;
    final byte[] renderKinds;
    /** Includes source, display variants and feature geometry; tile samples can miss tall models. */
    final float[] extent = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};

    PredictionMeshletBounds(int[] words) {
        this(words, VssLodSpriteTable.vegetationSprites(), VssLodSpriteTable.groundCoverSprites());
    }

    PredictionMeshletBounds(int[] words, boolean[] vegetation) {
        this(words, vegetation, new boolean[0]);
    }

    PredictionMeshletBounds(int[] words, boolean[] vegetation, boolean[] groundCover) {
        int count = words.length / PredictionPackedMesh.STRIDE_INTS;
        values = new float[((count + QUADS - 1) / QUADS) * 6];
        directions = new int[values.length / 6];
        var ends = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var kinds = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        int previousKind = -1;
        boolean selectGroundCover = !Boolean.getBoolean("vss.disablePredictionAquaticLod")
                && !Boolean.getBoolean("vss.disablePredictionVegetationLod");
        for (int start = 0; start < count; start += QUADS) {
            int b = start / QUADS * 6;
            int kind = 0;
            for (int a = 0; a < 3; a++) { values[b + a] = Float.POSITIVE_INFINITY; values[b + 3 + a] = Float.NEGATIVE_INFINITY; }
            for (int q = start; q < Math.min(count, start + QUADS); q++) {
                int p = q * PredictionPackedMesh.STRIDE_INTS, flags = words[p + 6];
                int sprite = flags & 65535;
                if (sprite < vegetation.length && vegetation[sprite]) kind = 1;
                boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
                int fluid = flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3;
                float xz = fine ? 1f / 16 : (1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
                float y = fine || fluid != 0 && (flags & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0 ? 1f / 16 : 1f / 4;
                directions[start / QUADS] |= direction(words, p, flags);
                for (int c = 0; c < 4; c++) {
                    float xx = half(words[p + c / 2], c) * xz;
                    float zz = half(words[p + 2 + c / 2], c) * xz;
                    float yy = (half(words[p + 4 + c / 2], c) - 32768) * y;
                    values[b] = Math.min(values[b], xx); values[b + 3] = Math.max(values[b + 3], xx);
                    values[b + 1] = Math.min(values[b + 1], yy); values[b + 4] = Math.max(values[b + 4], yy);
                    values[b + 2] = Math.min(values[b + 2], zz); values[b + 5] = Math.max(values[b + 5], zz);
                }
            }
            for (int a = 0; a < 3; a++) {
                extent[a] = Math.min(extent[a],values[b+a]);
                extent[a+3] = Math.max(extent[a+3],values[b+a+3]);
            }
            // A mixed meshlet is charged to vegetation as a whole. Splitting at
            // every trunk/leaf transition can turn millions of forest faces
            // into hundreds of thousands of tiny indirect commands.
            // Small-plant cohorts are already contiguous in worker order. Carry
            // their keep-through tier beside the timing lane; never classify a
            // mixed terrain/leaf meshlet as disposable ground cover.
            for (int q = start; q < Math.min(count, start + QUADS); q++) {
                int p = q * PredictionPackedMesh.STRIDE_INTS;
                int selectedKind = selectGroundCover && PredictionGroundCoverLod.eligible(words, p, groundCover)
                        ? 1 | 4 | PredictionVegetationRanges.through(words, p) << 3 : kind;
                if (selectedKind != previousKind) {
                    if (previousKind >= 0) ends.add(q);
                    kinds.add((byte) selectedKind); previousKind = selectedKind;
                }
            }
        }
        if (previousKind >= 0) ends.add(count);
        renderEnds = ends.toIntArray(); renderKinds = kinds.toByteArray();
    }

    private static int direction(int[] words, int p, int flags) {
        if ((flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) != 0) return 64;
        if ((flags & PredictionPackedMesh.FLAG_UNSHADED) != 0
                && (flags & PredictionPackedMesh.FLAG_MODEL_UV) == 0) return 64;
        int axis = flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3;
        if (axis > 2) return 64;
        int offset = axis == 1 ? 0 : axis == 2 ? 2 : 4;
        int pair = words[p + offset];
        if ((pair & 65535) != (pair >>> 16) || words[p + offset + 1] != pair) return 64;
        boolean positive = axis == 0 ? (flags & PredictionPackedMesh.FLAG_BANK) == 0
                : (flags & PredictionPackedMesh.FLAG_FACE_POSITIVE) != 0;
        int dimension = axis == 1 ? 0 : axis == 0 ? 1 : 2;
        return 1 << (dimension * 2 + (positive ? 1 : 0));
    }

    private static int half(int word, int corner) { return word >>> ((corner & 1) * 16) & 65535; }
    static int end(int first, int end) { return Math.min(end, (first / QUADS + 1) * QUADS); }
    static int count(PredictionDrawRanges ranges) {
        int count = 0;
        for (int i = 0; i < ranges.first.length; i++)
            count += (ranges.first[i] + ranges.count[i] - 1) / QUADS - ranges.first[i] / QUADS + 1;
        return count;
    }
}
