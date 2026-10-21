package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-built, bounded-error display meshes. Near geometry and disk records stay exact. */
final class FarBaselineDisplayGeometry {
    record Result(int[] words, PredictionDrawRanges[][] ranges, float[] errors,
                  PredictionVegetationRanges[] vegetation) {
        Result(int[] words, PredictionDrawRanges[][] ranges) { this(words, ranges, unavailable(), null); }
        private static float[] unavailable() { float[] errors = new float[4]; Arrays.fill(errors, Float.NaN); return errors; }
        /** Coordinates move into PackedMesh storage; the retained plan needs only ranges and errors. */
        Result metadataOnly() { return new Result(null, ranges, errors, vegetation); }
        PredictionDrawRanges range(int display, int aquatic, int visible) { return range(display, aquatic, visible, aquatic); }
        PredictionDrawRanges range(int display, int aquatic, int visible, int plantTier) {
            return vegetation == null || vegetation[display] == null ? null : vegetation[display].range(visible, plantTier, aquatic);
        }
        long vegetationBytes() {
            long bytes = 0;
            if (vegetation != null) for (int tier = 1; tier < vegetation.length; tier++)
                if (vegetation[tier] != null && vegetation[tier] != vegetation[tier - 1]) bytes += vegetation[tier].retainedHeapBytes();
            return bytes;
        }
    }
    private static final int STRIDE = PredictionPackedMesh.STRIDE_INTS;
    private static final int[] COLOR_WORDS = {7, 9, 10, 11};
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);
    private static final class Workspace {
        final int[][] words = new int[4][], roots = new int[4][], members = new int[4][];
        final int[] recent = new int[64];
        void ensure(int size) {
            for (int tier = 1; tier <= 3; tier++) if (roots[tier] == null || roots[tier].length < size) {
                words[tier] = new int[size * STRIDE]; roots[tier] = new int[size]; members[tier] = new int[size];
            }
        }
    }
    private FarBaselineDisplayGeometry() { }

    static Result build(int[] source, int[] first, int[] count, int[][] retained, int axis) {
        if (Boolean.getBoolean("vss.disablePredictionDisplayLod")) return new Result(source, null);
        int total = Arrays.stream(count).sum();
        if (total < 128 || total > 131072 || axis <= 0) return new Result(source, null);
        boolean[] approximate = VssLodSpriteTable.displayApproximationSprites();
        return build(source, first, count, retained, axis, approximate);
    }

    // A common lattice welds shared vertices. The outer row and water/model
    // columns stay exact. Approximate representations are only submitted for
    // complete ownership; mixed parent/child coverage retains original geometry.
    static Result build(int[] source, int[] first, int[] count, int[][] retained, int axis, boolean[] approximate) {
        int total = Arrays.stream(count).sum();
        if (total < 128 || total > 131072 || axis <= 0) return new Result(source, null);
        Workspace workspace = WORKSPACE.get(); workspace.ensure(total);
        boolean legacyMerge = Boolean.getBoolean("vss.disableVoxyScanMerge");
        int span = 0;
        for (int q = 0; q < total; q++) if ((source[q * STRIDE + 10] & PredictionPackedMesh.FEATURE) == 0)
            for (int c = 0; c < 4; c++) span = Math.max(span, Math.max(coordinate(source, q * STRIDE, c, 0), coordinate(source, q * STRIDE, c, 2)));
        if (span == 0) for (int q = 0; q < total; q++) for (int c = 0; c < 4; c++)
            span = Math.max(span, Math.max(coordinate(source, q * STRIDE, c, 0), coordinate(source, q * STRIDE, c, 2)));
        int spacing = Math.max(16, span / axis);
        boolean[] protectedCells = new boolean[axis * axis];
        boolean hasProtectedCells = false;
        for (int p = 0; p < source.length; p += STRIDE) {
            int flags = source[p + 6];
            if ((flags & PredictionPackedMesh.FLAG_MODEL_UV) != 0 || (flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) != 0
                    || (source[p + 9] & 0x06000000) != 0) {
                hasProtectedCells = true;
                int x0 = axis - 1, z0 = axis - 1, x1 = 0, z1 = 0;
                for (int c = 0; c < 4; c++) {
                    int x = Math.max(0, Math.min(axis - 1, coordinate(source, p, c, 0) / spacing));
                    int z = Math.max(0, Math.min(axis - 1, coordinate(source, p, c, 2) / spacing));
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
                }
                for (int z = z0; z <= z1; z++) Arrays.fill(protectedCells, z * axis + x0, z * axis + x1 + 1, true);
            }
        }
        int[][] variants = new int[4][];
        PredictionVegetationRanges[] vegetation = new PredictionVegetationRanges[4];
        float[] errors = new float[4];
        Arrays.fill(errors, Float.NaN);
        int extras = 0;
        boolean[] plants = VssLodSpriteTable.aquaticSprites(), land = VssLodSpriteTable.landPlantSprites();
        for (int i = 0; i < plants.length; i++) plants[i] |= land[i];
        for (int tier = 1; tier <= 3; tier++) {
            int[] words = workspace.words[tier], roots = workspace.roots[tier], members = workspace.members[tier];
            System.arraycopy(source, 0, words, 0, total * STRIDE);
            float error = 0;
            for (int q = 0; q < total; q++) {
                roots[q] = q; members[q] = 1;
                int p = q * STRIDE, flags = words[p + 6], sprite = flags & 65535;
                boolean feature = (words[p + 10] & PredictionPackedMesh.FEATURE) != 0;
                boolean allowed = (flags & (PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_MODEL_UV
                        | PredictionPackedMesh.FLAG_CUTOUT | (3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT))) == 0
                        && (words[p + 10] & PredictionPackedMesh.PREVIEW) == 0
                        && (words[p + 9] & 0x06000000) == 0
                        && (!feature || sprite < approximate.length && approximate[sprite]);
                if (allowed) for (int corner = 0; corner < 4; corner++) {
                    int x = coordinate(words, p, corner, 0), z = coordinate(words, p, corner, 2);
                    if (x <= spacing || z <= spacing || x >= span - spacing || z >= span - spacing
                            || hasProtectedCells && protectedVertex(protectedCells, axis, spacing, x, z)) continue;
                    int y = coordinate(words, p, corner, 1), step = 8 << tier;
                    int snapped = Math.floorDiv(y + step / 2, step) * step;
                    int resolution = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0 ? 1 : 4;
                    snapped = Math.max(-32768 * resolution, Math.min(32767 * resolution, snapped));
                    int horizontalStep = spacing << tier;
                    int snappedX = Math.floorDiv(x + horizontalStep / 2, horizontalStep) * horizontalStep;
                    int snappedZ = Math.floorDiv(z + horizontalStep / 2, horizontalStep) * horizontalStep;
                    snappedX = Math.max(spacing, Math.min(span - spacing, snappedX));
                    snappedZ = Math.max(spacing, Math.min(span - spacing, snappedZ));
                    if (hasProtectedCells && crossesProtection(protectedCells, axis, spacing, x, z, snappedX, snappedZ)) {
                        snappedX = x; snappedZ = z;
                    }
                    setCoordinate(words, p, corner, 0, snappedX);
                    setCoordinate(words, p, corner, 2, snappedZ);
                    setCoordinate(words, p, corner, 1, snapped);
                    double dx = (coordinate(words, p, corner, 0) - x) / 16.0;
                    double dy = (coordinate(words, p, corner, 1) - y) / 16.0;
                    double dz = (coordinate(words, p, corner, 2) - z) / 16.0;
                    error = Math.max(error, (float)Math.sqrt(dx * dx + dy * dy + dz * dz));
                    if (dx != 0 || dy != 0 || dz != 0) {
                        words[p + 10] |= PredictionPackedMesh.DISPLAY_APPROXIMATE;
                        words[p + 9] &= ~0x01000000;
                    }
                }
                if (allowed && degenerate(words, p)) { roots[q] = -1; members[q] = 0; }
            }
            for (int group = 0; group < count.length; group++) {
                int seen = 0;
                for (int q = first[group], end = q + count[group]; q < end; q++) {
                    if (roots[q] < 0 || !eligible(words, q)
                            || (words[q * STRIDE + 10] & PredictionPackedMesh.PREVIEW) != 0) continue;
                    int p = q * STRIDE;
                    boolean quantize = (words[p + 6] & PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE) != 0
                            || (words[p + 10] & PredictionPackedMesh.FEATURE) != 0
                            && (words[p + 6] & 65535) < approximate.length && approximate[words[p + 6] & 65535];
                    flattenColor(words, q, quantize);
                    if (!legacyMerge) continue;
                    for (int i = 0; i < Math.min(seen, 64); i++) {
                        int previous = workspace.recent[(seen - 1 - i) & 63];
                        if (roots[previous] != previous) continue;
                        if (join(words, previous, q, axis, tier)) {
                            roots[q] = previous; members[previous]++;
                            int current = previous;
                            boolean again;
                            do {
                                again = false;
                                for (int j = 0; j < Math.min(seen, 64); j++) {
                                    int other = workspace.recent[(seen - 1 - j) & 63];
                                    if (other == current || roots[other] != other) continue;
                                    if (join(words, other, current, axis, tier)) {
                                        roots[current] = other; members[other] += members[current];
                                        current = other; again = true; break;
                                    }
                                }
                            } while (again);
                            break;
                        }
                    }
                    if (roots[q] == q) workspace.recent[seen++ & 63] = q;
                }
            }
            int[] counts = new int[count.length], starts = new int[count.length];
            int n = 0;
            for (int g = 0; g < count.length; g++) {
                starts[g] = n;
                for (int q = first[g], end = q + count[g]; q < end; q++) if (roots[q] == q) counts[g]++;
                n += counts[g];
            }
            // Compact forward inside worker scratch. Allocate a published array
            // only after meshing and admission, so rejected levels leave no copy.
            int[] variant = words;
            int cursor = 0;
            for (int g = 0; g < count.length; g++) for (int q = first[g], end = q + count[g]; q < end; q++)
                if (roots[q] == q) System.arraycopy(words, q * STRIDE, variant, cursor++ * STRIDE, STRIDE);
            if (!legacyMerge) {
                var merged = PredictionScanMerge.mergeDisplay(variant, starts, counts, axis, spacing);
                variant = merged.words(); starts = merged.first(); counts = merged.count();
                n = Arrays.stream(counts).sum();
            }
            // Full contiguous representations avoid hundreds of tiny alternating
            // original/proxy commands. Bound extra residency to half the source.
            if (total - n < 64 || n > total * .65 || extras + n > total / 2) continue;
            if (variant == words) variant = Arrays.copyOf(variant,n * STRIDE);
            PredictionSpatialOrder.arrange(variant, starts, counts, plants);
            vegetation[tier] = PredictionVegetationRanges.arrange(variant, starts, counts,
                    VssLodSpriteTable.vegetationSprites(), VssLodSpriteTable.aquaticSprites());
            variants[tier] = variant; errors[tier] = error; extras += n;
        }
        if (extras == 0) return new Result(source, null);
        int[] combined = Arrays.copyOf(source, source.length + extras * STRIDE);
        PredictionDrawRanges[][] ranges = new PredictionDrawRanges[4][32];
        int cursor = source.length / STRIDE;
        for (int tier = 1; tier <= 3; tier++) if (variants[tier] != null) {
            System.arraycopy(variants[tier], 0, combined, cursor * STRIDE, variants[tier].length);
            vegetation[tier].offset(cursor);
            for (int visible = 0; visible < 32; visible++) ranges[tier][visible] =
                    vegetation[tier].range(visible, 0, 0);
            cursor += variants[tier].length / STRIDE;
        }
        // A residency cap can leave a deeper level without its own allocation.
        // Reuse the best shallower representation instead of returning to the
        // original dense mesh. Its measured error and all aquatic prefixes stay valid.
        if (!legacyMerge) for (int tier = 2; tier <= 3; tier++) if (vegetation[tier] == null && vegetation[tier - 1] != null) {
            errors[tier] = errors[tier - 1];
            vegetation[tier] = vegetation[tier - 1];
            for (int visible = 0; visible < 32; visible++) ranges[tier][visible] =
                    vegetation[tier].range(visible, 0, 0);
        }
        return new Result(combined, ranges, errors, vegetation);
    }

    private static boolean protectedVertex(boolean[] protectedCells, int axis, int spacing, int x, int z) {
        int cx = Math.floorDiv(x, spacing), cz = Math.floorDiv(z, spacing);
        for (int dz = -1; dz <= 0; dz++) for (int dx = -1; dx <= 0; dx++) {
            int a = cx + dx, b = cz + dz;
            if (a >= 0 && b >= 0 && a < axis && b < axis && protectedCells[b * axis + a]) return true;
        }
        return false;
    }
    private static boolean degenerate(int[] w, int p) {
        // A top whose XZ footprint collapsed can still be a valid steep face
        // in 3D. Drop only when both actual indexed triangles have zero area.
        long x = coordinate(w, p, 0, 0), y = coordinate(w, p, 0, 1), z = coordinate(w, p, 0, 2);
        long bx = coordinate(w, p, 2, 0) - x, by = coordinate(w, p, 2, 1) - y, bz = coordinate(w, p, 2, 2) - z;
        for (int c = 1; c <= 3; c += 2) {
            long ax = coordinate(w, p, c, 0) - x, ay = coordinate(w, p, c, 1) - y, az = coordinate(w, p, c, 2) - z;
            if (ay * bz != az * by || az * bx != ax * bz || ax * by != ay * bx) return false;
        }
        return true;
    }

    private static boolean crossesProtection(boolean[] cells, int axis, int spacing, int x, int z, int xx, int zz) {
        int minX = Math.max(0, Math.min(x, xx) / spacing), maxX = Math.min(axis - 1, Math.max(x, xx) / spacing);
        int minZ = Math.max(0, Math.min(z, zz) / spacing), maxZ = Math.min(axis - 1, Math.max(z, zz) / spacing);
        for (int row = minZ; row <= maxZ; row++) for (int col = minX; col <= maxX; col++)
            if (cells[row * axis + col]) return true;
        return false;
    }

    private static boolean eligible(int[] w, int q) {
        int p = q * STRIDE, flags = w[p + 6];
        int excluded = PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_MODEL_UV
                | (3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
        if ((flags & excluded) != 0 || (w[p + 9] & 0x06000000) != 0) return false;
        int normal = normal(flags), u = (normal + 1) % 3, v = (normal + 2) % 3;
        int plane = coordinate(w, p, 0, normal);
        int u0 = coordinate(w, p, 0, u), u1 = coordinate(w, p, 2, u);
        int v0 = coordinate(w, p, 0, v), v1 = coordinate(w, p, 2, v);
        if (u0 == u1 || v0 == v1) return false;
        for (int corner = 0; corner < 4; corner++) {
            if (coordinate(w, p, corner, normal) != plane) return false;
            int a = coordinate(w, p, corner, u), b = coordinate(w, p, corner, v);
            if (a != u0 && a != u1 || b != v0 && b != v1) return false;
        }
        return coordinate(w, p, 1, u) != coordinate(w, p, 3, u)
                && coordinate(w, p, 1, v) != coordinate(w, p, 3, v);
    }

    private static void flattenColor(int[] w, int q, boolean quantize) {
        int p = q * STRIDE, first = w[p + 7], flags = w[p + 9] & 0x0f000000;
        int red = 0, green = 0, blue = 0;
        int lowR = 255, highR = 0, lowG = 255, highG = 0, lowB = 255, highB = 0;
        for (int offset : COLOR_WORDS) {
            int color = w[p + offset];
            if ((color & 0xf0000000) != (first & 0xf0000000)) return;
            red += color >>> 16 & 255; green += color >>> 8 & 255; blue += color & 255;
            lowR = Math.min(lowR, color >>> 16 & 255); highR = Math.max(highR, color >>> 16 & 255);
            lowG = Math.min(lowG, color >>> 8 & 255); highG = Math.max(highG, color >>> 8 & 255);
            lowB = Math.min(lowB, color & 255); highB = Math.max(highB, color & 255);
        }
        if (quantize && (highR - lowR > 24 || highG - lowG > 24 || highB - lowB > 24)) return;
        red /= 4; green /= 4; blue /= 4;
        if (quantize) {
            red = Math.min(255, (red + 4) / 8 * 8);
            green = Math.min(255, (green + 4) / 8 * 8);
            blue = Math.min(255, (blue + 4) / 8 * 8);
        }
        int color = (first & 0xf0000000) | red << 16 | green << 8 | blue;
        int feature = w[p + 10] & (PredictionPackedMesh.FEATURE | PredictionPackedMesh.DISPLAY_APPROXIMATE | PredictionPackedMesh.PREVIEW);
        w[p + 7] = w[p + 11] = color; w[p + 9] = color | flags; w[p + 10] = color | feature;
    }

    private static boolean join(int[] w, int a, int b, int cellAxis, int tier) {
        int p = a * STRIDE, q = b * STRIDE;
        if (w[p + 6] != w[q + 6] || w[p + 7] != w[q + 7]
                || (w[p + 9] & ~0x01000000) != (w[q + 9] & ~0x01000000)
                || w[p + 10] != w[q + 10] || w[p + 11] != w[q + 11]
                || w[p + 7] != (w[p + 10] & ~(PredictionPackedMesh.FEATURE | PredictionPackedMesh.DISPLAY_APPROXIMATE | PredictionPackedMesh.PREVIEW))
                || w[q + 7] != (w[q + 10] & ~(PredictionPackedMesh.FEATURE | PredictionPackedMesh.DISPLAY_APPROXIMATE | PredictionPackedMesh.PREVIEW))
                || w[p + 7] != w[p + 11] || w[q + 7] != w[q + 11]) return false;
        int normal = normal(w[p + 6]), u = (normal + 1) % 3, v = (normal + 2) % 3;
        if (coordinate(w, p, 0, normal) != coordinate(w, q, 0, normal)) return false;
        // A side's perpendicular ownership coordinate is fixed by the existing shader.
        if (normal == 0 && w[p + 8] % cellAxis != w[q + 8] % cellAxis
                || normal == 2 && w[p + 8] / cellAxis != w[q + 8] / cellAxis) return false;
        int a0 = Math.min(coordinate(w, p, 0, u), coordinate(w, p, 2, u));
        int a1 = Math.max(coordinate(w, p, 0, u), coordinate(w, p, 2, u));
        int b0 = Math.min(coordinate(w, p, 0, v), coordinate(w, p, 2, v));
        int b1 = Math.max(coordinate(w, p, 0, v), coordinate(w, p, 2, v));
        int c0 = Math.min(coordinate(w, q, 0, u), coordinate(w, q, 2, u));
        int c1 = Math.max(coordinate(w, q, 0, u), coordinate(w, q, 2, u));
        int d0 = Math.min(coordinate(w, q, 0, v), coordinate(w, q, 2, v));
        int d1 = Math.max(coordinate(w, q, 0, v), coordinate(w, q, 2, v));
        boolean alongU = b0 == d0 && b1 == d1 && (a0 == c1 || a1 == c0);
        boolean alongV = a0 == c0 && a1 == c1 && (b0 == d1 || b1 == d0);
        if (!alongU && !alongV) return false;
        int dimension = alongU ? u : v, low = alongU ? a0 : b0, high = alongU ? a1 : b1;
        int minimum = Math.min(low, alongU ? c0 : d0), maximum = Math.max(high, alongU ? c1 : d1);
        int limit = ((w[p + 6] & PredictionPackedMesh.FLAG_CUTOUT) != 0 ? 1 << tier : 4 << tier) * 16;
        if (maximum - minimum > limit) return false;
        for (int corner = 0; corner < 4; corner++)
            setCoordinate(w, p, corner, dimension, coordinate(w, p, corner, dimension) == low ? minimum : maximum);
        w[p + 9] &= ~0x01000000; // Tangential ownership remains per fragment for the merged surface.
        return true;
    }

    private static int normal(int flags) { return switch (flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) { case 1 -> 0; case 2 -> 2; default -> 1; }; }

    private static int coordinate(int[] w, int p, int corner, int axis) {
        int word = w[p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2] >>> ((corner & 1) * 16) & 65535;
        boolean fine = (w[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        return axis == 1 ? (word - 32768) * (fine ? 1 : 4)
                : word * (fine ? 1 : 16 << (w[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
    }

    private static void setCoordinate(int[] w, int p, int corner, int axis, int value) {
        boolean fine = (w[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        int encoded = axis == 1 ? value / (fine ? 1 : 4) + 32768
                : value / (fine ? 1 : 16 << (w[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
        int at = p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2, shift = (corner & 1) * 16;
        w[at] = w[at] & ~(65535 << shift) | (encoded & 65535) << shift;
    }
}
