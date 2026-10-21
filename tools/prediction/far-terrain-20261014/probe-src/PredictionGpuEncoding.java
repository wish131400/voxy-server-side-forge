package dev.xantha.vss.client.prediction;

/** Lossless GPU color/light dictionary. Coordinates, quad order and every flag remain exact.
 * Built on publishing/restore workers; no hash table construction in the draw loop.
 */
final class PredictionGpuEncoding {
    private PredictionGpuEncoding() { }
    record Encoded(int[] words, int paletteBaseTexel) { }
    private static final int CROSS_PLANE = 1 << 30;

    /** GPU-only 20-byte records: opposite corners with winding stored in the
     * unused normal coordinate, attributes, and a separate cell/palette word.
     * Vertical plant crosses use both endpoints and a tag in the normal word.
     * A negative palette base selects this layout. Exceptional faces reference
     * exact six-word coordinates after the palette. CPU/disk layout stays exact. */
    static Encoded rectangles(Encoded source, int quads) {
        if (source.paletteBaseTexel <= 0 || quads < 64 || Boolean.getBoolean("vss.disableRectangleGpu")) return source;
        int[] words = source.words;
        int exceptional = 0;
        for (int q = 0; q < quads; q++)
            if (rectangleWinding(words, q * 8) < 0 && !crossPlane(words, q * 8)) exceptional++;
        int recordWords = Math.addExact(Math.multiplyExact(quads, 5), 3) & ~3;
        int paletteWords = source.paletteBaseTexel * 4;
        int colors = words.length - paletteWords;
        int palette = recordWords;
        int length = Math.addExact(Math.addExact(palette, colors), Math.multiplyExact(exceptional, 8));
        if (length > words.length - Math.max(16, words.length / 16)) return source;
        int[] result = new int[length];
        int fallback = palette + colors;
        for (int q = 0; q < quads; q++) {
            int p = q * 8, at = q * 4;
            int winding = rectangleWinding(words, p);
            if (winding >= 0) {
                int normal = normal(words[p + 6]), u = (normal + 1) % 3, v = (normal + 2) % 3;
                result[at] = coordinate(words, p, 0, 0) | coordinate(words, p, 2, 0) << 16;
                result[at + 1] = coordinate(words, p, 0, 2) | coordinate(words, p, 2, 2) << 16;
                result[at + 2] = coordinate(words, p, 0, 1) | coordinate(words, p, 2, 1) << 16;
                int word = normal == 0 ? 0 : normal == 1 ? 2 : 1;
                result[at + word] = (result[at + word] & 65535) | (1 << (winding == 1 ? u : v)) << 16;
            } else if (crossPlane(words, p)) {
                result[at] = coordinate(words, p, 0, 0) | coordinate(words, p, 2, 0) << 16;
                result[at + 1] = coordinate(words, p, 0, 2) | coordinate(words, p, 2, 2) << 16;
                result[at + 2] = coordinate(words, p, 0, 1) | coordinate(words, p, 2, 1) << 16;
                result[at + (normal(words[p + 6]) == 0 ? 0 : 1)] |= CROSS_PLANE;
            } else {
                result[at] = fallback / 4;
                int normal = normal(words[p + 6]);
                result[at + (normal == 0 ? 0 : normal == 2 ? 1 : 2)] |= Integer.MIN_VALUE;
                System.arraycopy(words, p, result, fallback, 6);
                fallback += 8;
            }
            result[at + 3] = words[p + 6];
            result[quads * 4 + q] = words[p + 7];
        }
        System.arraycopy(words, paletteWords, result, palette, colors);
        return new Encoded(result, -palette / 4);
    }

    private static int normal(int flags) {
        return switch (flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) {
            case 0 -> 1; case 1 -> 0; case 2 -> 2; default -> -1;
        };
    }
    private static int coordinate(int[] words, int p, int corner, int axis) {
        return words[p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2] >>> ((corner & 1) * 16) & 65535;
    }
    static int rectangleWinding(int[] words, int p) {
        int n = normal(words[p + 6]);
        if (n < 0) return -1;
        int u = (n + 1) % 3, v = (n + 2) % 3;
        int plane = coordinate(words, p, 0, n);
        for (int c = 1; c < 4; c++) if (coordinate(words, p, c, n) != plane) return -1;
        int u0 = coordinate(words, p, 0, u), u2 = coordinate(words, p, 2, u);
        int v0 = coordinate(words, p, 0, v), v2 = coordinate(words, p, 2, v);
        if (u0 == u2 || v0 == v2) return -1;
        if (coordinate(words, p, 1, u) == u2 && coordinate(words, p, 1, v) == v0
                && coordinate(words, p, 3, u) == u0 && coordinate(words, p, 3, v) == v2) return 1;
        if (coordinate(words, p, 1, u) == u0 && coordinate(words, p, 1, v) == v2
                && coordinate(words, p, 3, u) == u2 && coordinate(words, p, 3, v) == v0) return 0;
        return -1;
    }

    /** Only exact vertical crosses participate. Reserve two high endpoint bits
     * for the cross/exception tags; wider or irregular models keep their coordinates. */
    private static boolean crossPlane(int[] words, int p) {
        int flags = words[p + 6], n = normal(flags);
        if ((n != 0 && n != 2) || (flags & PredictionPackedMesh.FLAG_UNSHADED) == 0
                || (flags & PredictionPackedMesh.FLAG_MODEL_UV) != 0
                || (flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) != 0
                || coordinate(words, p, 2, n) >= 16384) return false;
        for (int a = 0; a <= 2; a += 2) {
            if (coordinate(words, p, 0, a) == coordinate(words, p, 2, a)
                    || coordinate(words, p, 0, a) != coordinate(words, p, 3, a)
                    || coordinate(words, p, 1, a) != coordinate(words, p, 2, a)) return false;
        }
        return coordinate(words, p, 0, 1) != coordinate(words, p, 2, 1)
                && coordinate(words, p, 0, 1) == coordinate(words, p, 1, 1)
                && coordinate(words, p, 2, 1) == coordinate(words, p, 3, 1);
    }

    /** CPU reference decoder for GPU regression/offline validation only. */
    static int[] decodeGpu(Encoded stored, int quads) {
        if (stored.paletteBaseTexel >= 0) return decode(stored.words, stored.paletteBaseTexel, quads);
        int[] input = stored.words;
        int palette = -stored.paletteBaseTexel * 4, paletteEnd = input.length;
        for (int q = 0; q < quads; q++) if (exceptional(input, q * 4)) paletteEnd = Math.min(paletteEnd, (input[q * 4] & Integer.MAX_VALUE) * 4);
        int[] legacy = new int[quads * 8 + paletteEnd - palette];
        int[] corner = new int[12];
        for (int q = 0; q < quads; q++) {
            int p = q * 4, out = q * 8;
            if (exceptional(input, p)) System.arraycopy(input, (input[p] & Integer.MAX_VALUE) * 4, legacy, out, 6);
            else {
                int n = normal(input[p + 3]);
                int normalWord = n == 0 ? 0 : n == 2 ? 1 : 2;
                boolean cross = (input[p + normalWord] & CROSS_PLANE) != 0;
                corner[0] = input[p] & 65535; corner[1] = input[p + 2] & 65535; corner[2] = input[p + 1] & 65535;
                for (int c = 1; c < 4; c++) System.arraycopy(corner, 0, corner, c * 3, 3);
                corner[6] = input[p] >>> 16; corner[7] = input[p + 2] >>> 16; corner[8] = input[p + 1] >>> 16;
                if (cross) {
                    corner[6 + n] &= 16383;
                    corner[3] = corner[6]; corner[5] = corner[8]; corner[10] = corner[7];
                } else {
                    corner[6 + n] = corner[n];
                    int mask = input[p + normalWord] >>> 16;
                    for (int a = 0; a < 3; a++) {
                        corner[3 + a] = (mask & 1 << a) != 0 ? corner[6 + a] : corner[a];
                        corner[9 + a] = (mask & 1 << a) == 0 ? corner[6 + a] : corner[a];
                    }
                }
                for (int a = 0; a < 3; a++) for (int pair = 0; pair < 2; pair++)
                    legacy[out + (a == 0 ? 0 : a == 2 ? 2 : 4) + pair] = corner[pair * 6 + a] | corner[pair * 6 + 3 + a] << 16;
            }
            legacy[out + 6] = input[p + 3]; legacy[out + 7] = input[quads * 4 + q];
        }
        System.arraycopy(input, -stored.paletteBaseTexel * 4, legacy, quads * 8, legacy.length - quads * 8);
        return decode(legacy, quads * 2, quads);
    }
    private static boolean exceptional(int[] words, int p) {
        int n = normal(words[p + 3]);
        return words[p + (n == 0 ? 0 : n == 2 ? 1 : 2)] < 0;
    }

    /** Select one pass without decoding an already compact CPU payload. The two opaque
     * spans straddle canonical water; appended display geometry belongs to opaque. */
    static Encoded selectPass(int[] source, int paletteBase, int quads,
                              int waterFirst, int waterCount, boolean water, boolean compact) {
        if (waterFirst < 0 || waterCount < 0 || waterFirst + waterCount > quads)
            throw new IllegalArgumentException("Invalid water interval");
        int count = water ? waterCount : quads - waterCount;
        if (count == 0) return new Encoded(new int[0], 0);
        if (!water && waterCount == 0) return new Encoded(source, paletteBase);
        int stride = paletteBase == 0 ? 12 : 8;
        int[] records = new int[count * stride];
        if (water) System.arraycopy(source, waterFirst * stride, records, 0, records.length);
        else {
            System.arraycopy(source, 0, records, 0, waterFirst * stride);
            System.arraycopy(source, (waterFirst + waterCount) * stride, records,
                    waterFirst * stride, (quads - waterFirst - waterCount) * stride);
        }
        if (paletteBase == 0) return compact ? encode(records) : new Encoded(records, 0);
        int paletteStart = paletteBase * 4;
        int[] remap = new int[(source.length - paletteStart) / 4];
        java.util.Arrays.fill(remap, -1);
        int colors = 0;
        for (int p = 7; p < records.length; p += 8) {
            int id = records[p] >>> 16;
            if (id >= remap.length) throw new IllegalArgumentException("Invalid pass palette id");
            if (remap[id] < 0) remap[id] = colors++;
            records[p] = (records[p] & 65535) | (remap[id] << 16);
        }
        int[] result = java.util.Arrays.copyOf(records, records.length + colors * 4);
        for (int id = 0; id < remap.length; id++) if (remap[id] >= 0)
            System.arraycopy(source, paletteStart + id * 4, result, records.length + remap[id] * 4, 4);
        return new Encoded(result, records.length / 4);
    }

    static Encoded encode(int[] source) {
        if (source.length % 12 != 0) throw new IllegalArgumentException("Invalid quad words");
        int quads = source.length / 12;
        // Small seams retain the legacy layout. Require at least 12.5% payload savings.
        if (quads < 64 || source.length > PredictionMeshCompression.MAX_BYTES / 4)
            return new Encoded(source, 0);
        int maxColors = Math.min(65536, quads * 5 / 8);
        int capacity = 1;
        while (capacity < maxColors * 2) capacity <<= 1;
        int[] representatives = new int[capacity], paletteIds = new int[capacity];
        int colors = 0;
        for (int offset = 0; offset < source.length; offset += 12) {
            if ((source[offset + 8] & 0xffff0000) != 0) return new Encoded(source, 0);
            int slot = slot(source, offset, representatives);
            if (representatives[slot] == 0) {
                if (colors == maxColors) return new Encoded(source, 0);
                representatives[slot] = offset + 1;
                paletteIds[slot] = colors++;
            }
        }
        int paletteStart = quads * 8;
        int[] packed = new int[paletteStart + colors * 4];
        for (int offset = 0, target = 0; offset < source.length; offset += 12, target += 8) {
            int color = paletteIds[slot(source, offset, representatives)];
            System.arraycopy(source, offset, packed, target, 7);
            packed[target + 7] = source[offset + 8] | (color << 16);
        }
        for (int slot = 0; slot < capacity; slot++) if (representatives[slot] != 0) {
            int offset = representatives[slot] - 1, target = paletteStart + paletteIds[slot] * 4;
            packed[target] = source[offset + 7];
            System.arraycopy(source, offset + 9, packed, target + 1, 3);
        }
        return new Encoded(packed, paletteStart / 4);
    }

    private static int slot(int[] words, int offset, int[] table) {
        int hash = words[offset + 7];
        hash = hash * 31 + words[offset + 9];
        hash = hash * 31 + words[offset + 10];
        hash = hash * 31 + words[offset + 11];
        hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15;
        int slot = hash & (table.length - 1);
        while (table[slot] != 0) {
            int previous = table[slot] - 1;
            if (words[previous + 7] == words[offset + 7]
                    && words[previous + 9] == words[offset + 9]
                    && words[previous + 10] == words[offset + 10]
                    && words[previous + 11] == words[offset + 11]) return slot;
            slot = (slot + 1) & (table.length - 1);
        }
        return slot;
    }

    /** Canonical words for disk caches and CPU consumers; never used by the render loop. */
    static int[] decode(int[] stored, int paletteBaseTexel, int quads) {
        if (paletteBaseTexel < 0) return decodeGpu(new Encoded(stored, paletteBaseTexel), quads);
        if (paletteBaseTexel == 0) return stored;
        if (quads < 0 || (long)quads * 2 != paletteBaseTexel
                || (long)paletteBaseTexel * 4 >= stored.length || (stored.length & 3) != 0)
            throw new IllegalArgumentException("Invalid GPU palette layout");
        int[] words = new int[Math.multiplyExact(quads, 12)];
        for (int input = 0, output = 0; output < words.length; input += 8, output += 12) {
            int color = paletteBaseTexel * 4 + (stored[input + 7] >>> 16) * 4;
            if (color > stored.length - 4) throw new IllegalArgumentException("Invalid GPU palette index");
            System.arraycopy(stored, input, words, output, 7);
            words[output + 7] = stored[color];
            words[output + 8] = stored[input + 7] & 0xffff;
            System.arraycopy(stored, color + 1, words, output + 9, 3);
        }
        return words;
    }
}
