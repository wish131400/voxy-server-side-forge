package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/** Worker-only plane meshing. The scanline strategy is implemented independently;
 * canonical material, light, winding and ownership semantics remain VSS's own. */
final class PredictionScanMerge {
    private static final int STRIDE = PredictionPackedMesh.STRIDE_INTS;
    private static final int FIXED_CELL = 1 << 24;
    private static final int EXCLUDED = PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_MODEL_UV
            | (3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
    private static final int MAX_CELLS = 1 << 20, MAX_PAGES = 2048;
    private static final int MAX_SPAN = 256 * 16;

    record Result(int[] words, int[] first, int[] count) { }
    private record Key(int flags, int color, int tags1, int tags2, int plane, int layout, int owner) { }
    private static final class Plane {
        final Key key;
        int[] offsets = new int[8];
        int size, minU, minV, stepU, stepV, referenceU, referenceV;
        int[] replacement;
        Plane(Key key) { this.key = key; }
        void add(int offset, int u0, int u1, int v0, int v1) {
            if (size == offsets.length) offsets = Arrays.copyOf(offsets, size * 2);
            offsets[size++] = offset;
            if (size == 1) {
                referenceU = minU = u0; referenceV = minV = v0;
                stepU = u1 - u0; stepV = v1 - v0;
            } else {
                minU = Math.min(minU, u0); minV = Math.min(minV, v0);
                stepU = gcd(gcd(stepU, u1 - u0), Math.abs(u0 - referenceU));
                stepV = gcd(gcd(stepV, v1 - v0), Math.abs(v0 - referenceV));
            }
        }
    }

    private PredictionScanMerge() { }

    /** Exact mode only joins static features. A known spacing permits fixed-cell
     * faces wholly inside their cell to switch safely to pixel ownership. With
     * unknown spacing, fixed source cells remain separate. Display mode already
     * requires complete tile ownership and no active terrain morph. */
    static Result merge(int[] source, int[] first, int[] count, int axis, int spacing,
                        boolean featuresOnly, int latticeCap) {
        return merge(source, first, count, axis, spacing, featuresOnly, latticeCap, false);
    }

    /** Display workers may supply a larger reusable array; only its opaque prefix is valid. */
    static Result mergeDisplay(int[] source, int[] first, int[] count, int axis, int latticeCap) {
        return merge(source, first, count, axis, 0, false, latticeCap, true);
    }

    private static Result merge(int[] source, int[] first, int[] count, int axis, int spacing,
                                boolean featuresOnly, int latticeCap, boolean opaqueOnly) {
        if (Boolean.getBoolean("vss.disableVoxyScanMerge") || axis <= 0)
            return new Result(source, first, count);
        int total = Arrays.stream(count).sum();
        if (total < 2) return new Result(source, first, count);
        var lookup = new HashMap<Key, Plane>();
        var planes = new ArrayList<Plane>();
        Plane[] owners = null;
        int[] xyz = new int[12];
        for (int q = 0; q < total; q++) {
            int p = q * STRIDE, flags = source[p + 6];
            if ((flags & EXCLUDED) != 0 || (flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) == 3
                    || (source[p + 9] & 0x06000000) != 0
                    || (source[p + 10] & PredictionPackedMesh.PREVIEW) != 0
                    || featuresOnly && ((source[p + 10] & PredictionPackedMesh.FEATURE) == 0
                    || (source[p + 10] & PredictionPackedMesh.DISPLAY_APPROXIMATE) != 0)
                    || source[p + 7] != source[p + 11]
                    || source[p + 7] != (source[p + 9] & 0xf0ffffff)
                    || source[p + 7] != (source[p + 10] & 0xf0ffffff)
                    || !decode(source, p, xyz)) continue;
            int n = normal(flags), u = (n + 1) % 3, v = (n + 2) % 3;
            int u0 = Math.min(xyz[u], xyz[6 + u]), u1 = Math.max(xyz[u], xyz[6 + u]);
            int v0 = Math.min(xyz[v], xyz[6 + v]), v1 = Math.max(xyz[v], xyz[6 + v]);
            if (u0 == u1 || v0 == v1) continue;
            int layout = 0, corners = 0;
            for (int c = 0; c < 4; c++) {
                int a = xyz[c * 3 + u], b = xyz[c * 3 + v];
                if (xyz[c * 3 + n] != xyz[n] || a != u0 && a != u1 || b != v0 && b != v1) {
                    corners = 0; break;
                }
                int corner = (a == u1 ? 1 : 0) | (b == v1 ? 2 : 0);
                layout |= corner << (c * 2); corners |= 1 << corner;
            }
            if (corners != 15) continue;
            int cell = source[p + 8];
            if (cell < 0 || cell >= axis * axis) continue;
            boolean fixed = (source[p + 9] & FIXED_CELL) != 0;
            if (fixed && insideCell(xyz, n, cell, axis, spacing > 0 ? spacing : featuresOnly ? 0 : latticeCap / 16)) fixed = false;
            int owner = fixed ? cell : n == 0 ? cell % axis : n == 2 ? cell / axis : -1;
            int tags1 = source[p + 9] & 0x0f000000;
            if (!fixed) tags1 &= ~FIXED_CELL;
            var key = new Key(flags, source[p + 7], tags1, source[p + 10] & 0x0f000000, xyz[n], layout, owner);
            Plane plane = lookup.get(key);
            if (plane == null) { plane = new Plane(key); lookup.put(key, plane); planes.add(plane); }
            if (owners == null) owners = new Plane[total];
            plane.add(p, u0, u1, v0, v1); owners[q] = plane;
        }
        int budget = MAX_CELLS, removed = 0;
        for (Plane plane : planes) if (plane.size > 1) {
            int spent = build(source, plane, Math.max(16, latticeCap), budget);
            budget -= spent;
            if (plane.replacement != null) removed += plane.size - plane.replacement.length / STRIDE;
        }
        if (removed == 0) return new Result(source, first, count);
        int wordLength = opaqueOnly ? total * STRIDE : source.length;
        int[] words = new int[wordLength - removed * STRIDE];
        int[] starts = new int[count.length], counts = new int[count.length];
        int cursor = 0;
        for (int g = 0; g < count.length; g++) {
            starts[g] = cursor;
            for (int q = first[g], end = q + count[g]; q < end; q++) {
                Plane plane = owners[q];
                if (plane == null || plane.replacement == null) {
                    System.arraycopy(source, q * STRIDE, words, cursor++ * STRIDE, STRIDE);
                } else if (q * STRIDE == plane.offsets[0]) {
                    System.arraycopy(plane.replacement, 0, words, cursor * STRIDE, plane.replacement.length);
                    cursor += plane.replacement.length / STRIDE;
                }
            }
            counts[g] = cursor - starts[g];
            if (counts[g] == 0) starts[g] = 0;
        }
        // Transparent records are never scanned or reordered here.
        System.arraycopy(source, total * STRIDE, words, cursor * STRIDE, wordLength - total * STRIDE);
        return new Result(words, starts, counts);
    }

    private static boolean insideCell(int[] xyz, int normal, int cell, int axis, int spacing) {
        if (spacing <= 0) return false;
        long step = spacing * 16L, x0 = cell % axis * step, z0 = cell / axis * step;
        for (int c = 0; c < 4; c++) {
            if (normal != 0 && (xyz[c * 3] < x0 || xyz[c * 3] > x0 + step)) return false;
            if (normal != 2 && (xyz[c * 3 + 2] < z0 || xyz[c * 3 + 2] > z0 + step)) return false;
        }
        return true;
    }

    private static int build(int[] source, Plane plane, int latticeCap, int budget) {
        // A fixed lattice cap prevents a cache restore from repeatedly enlarging
        // the raster unit after its smaller rectangles have been merged away.
        int du = gcd(plane.stepU, latticeCap), dv = gcd(plane.stepV, latticeCap);
        int n = normal(plane.key.flags), u = (n + 1) % 3, v = (n + 2) % 3;
        long work = 0;
        for (int i = 0; i < plane.size; i++) {
            int p = plane.offsets[i];
            long width = Math.abs(coordinate(source, p, 2, u) - coordinate(source, p, 0, u)) / du;
            long height = Math.abs(coordinate(source, p, 2, v) - coordinate(source, p, 0, v)) / dv;
            work += width * height;
            if (work > budget) return 0;
        }
        var pages = new HashMap<Long, int[]>();
        for (int i = 0; i < plane.size; i++) {
            int p = plane.offsets[i];
            int a = coordinate(source, p, 0, u), b = coordinate(source, p, 2, u);
            int c = coordinate(source, p, 0, v), d = coordinate(source, p, 2, v);
            int x0 = (Math.min(a, b) - plane.minU) / du, x1 = (Math.max(a, b) - plane.minU) / du;
            int y0 = (Math.min(c, d) - plane.minV) / dv, y1 = (Math.max(c, d) - plane.minV) / dv;
            for (int py = y0 / 32; py <= (y1 - 1) / 32; py++) for (int px = x0 / 32; px <= (x1 - 1) / 32; px++) {
                long pageKey = (long)py << 32 | Integer.toUnsignedLong(px);
                int[] rows = pages.get(pageKey);
                if (rows == null) {
                    if (pages.size() >= MAX_PAGES) return (int)work;
                    pages.put(pageKey, rows = new int[32]);
                }
                int lo = Math.max(x0, px * 32) - px * 32, hi = Math.min(x1, px * 32 + 32) - px * 32;
                int mask = (int)(((1L << (hi - lo)) - 1) << lo);
                for (int y = Math.max(y0, py * 32); y < Math.min(y1, py * 32 + 32); y++) {
                    if ((rows[y & 31] & mask) != 0) return (int)work; // Preserve overlapping originals.
                    rows[y & 31] |= mask;
                }
            }
        }
        if ((long)pages.size() * 1024 > budget) return (int)work;
        int maxWidth = Math.max(1, Math.min(16, MAX_SPAN / du));
        int maxHeight = Math.max(1, Math.min(16, MAX_SPAN / dv));
        int[] output = new int[plane.size * STRIDE], cursor = {0};
        long[] keys = new long[pages.size()]; int index = 0;
        for (long key : pages.keySet()) keys[index++] = key;
        Arrays.sort(keys);
        for (long key : keys) {
            int[] rows = pages.get(key);
            int pageX = (int)key * 32, pageY = (int)(key >>> 32) * 32;
            PredictionScanMesher2D.scan(32, 32, maxWidth, maxHeight,
                    (x, y) -> (rows[y] >>> x & 1),
                    (x, y, width, height, ignored) -> {
                        if (cursor[0] >= output.length) { cursor[0] += STRIDE; return; }
                        int at = cursor[0]; cursor[0] += STRIDE;
                        System.arraycopy(source, plane.offsets[0], output, at, STRIDE);
                        output[at + 9] = plane.key.color | plane.key.tags1;
                        int lowU = plane.minU + (pageX + x) * du, highU = lowU + width * du;
                        int lowV = plane.minV + (pageY + y) * dv, highV = lowV + height * dv;
                        for (int corner = 0; corner < 4; corner++) {
                            int layout = plane.key.layout >>> (corner * 2);
                            setCoordinate(output, at, corner, u, (layout & 1) == 0 ? lowU : highU);
                            setCoordinate(output, at, corner, v, (layout & 2) == 0 ? lowV : highV);
                        }
                    });
            if (cursor[0] >= output.length) return pages.size() * 1024;
        }
        plane.replacement = Arrays.copyOf(output, cursor[0]);
        return pages.size() * 1024;
    }

    static int normal(int flags) {
        return switch (flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) { case 1 -> 0; case 2 -> 2; default -> 1; };
    }
    static int coordinate(int[] words, int p, int corner, int axis) {
        int at = p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2;
        int raw = words[at] >>> ((corner & 1) * 16) & 65535;
        boolean fine = (words[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        return axis == 1 ? (raw - 32768) * (fine ? 1 : 4)
                : raw * (fine ? 1 : 16 << (words[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
    }
    private static boolean decode(int[] words, int p, int[] xyz) {
        boolean fine = (words[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        long scale = fine ? 1 : 16L << (words[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
        for (int c = 0; c < 4; c++) {
            for (int axis = 0; axis < 3; axis++) {
                int at = p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + c / 2;
                int raw = words[at] >>> ((c & 1) * 16) & 65535;
                long value = axis == 1 ? (raw - 32768) * (fine ? 1L : 4L) : raw * scale;
                if (value > Integer.MAX_VALUE) return false;
                xyz[c * 3 + axis] = (int)value;
            }
        }
        return true;
    }
    static void setCoordinate(int[] words, int p, int corner, int axis, int value) {
        boolean fine = (words[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        int encoded = axis == 1 ? value / (fine ? 1 : 4) + 32768
                : value / (fine ? 1 : 16 << (words[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
        int at = p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2, shift = (corner & 1) * 16;
        words[at] = words[at] & ~(65535 << shift) | (encoded & 65535) << shift;
    }
    private static int gcd(int a, int b) { while (b != 0) { int next = a % b; a = b; b = next; } return a; }
}
