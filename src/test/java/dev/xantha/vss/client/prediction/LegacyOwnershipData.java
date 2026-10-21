package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/** Exact random-access ownership records. X/Z footprints are integral chunk boundaries. */
final class LegacyOwnershipData {
    private static final int END_MASK = 0x3fffffff, MAGIC = 0x56534f31, HEADER = 9;
    // At most 65,536 entries per publishing worker; no per-mesh dictionary growth churn.
    private static final ThreadLocal<Long2IntOpenHashMap> DICTIONARIES = ThreadLocal.withInitial(() -> {
        var map = new Long2IntOpenHashMap(1024); map.defaultReturnValue(-1); return map;
    });
    private final int[] records;
    private final long[] footprints;
    private final int mode, stride, baseX, baseZ, baseY;
    private final boolean wideY;

    LegacyOwnershipData(int[] ends, byte[] kinds, float[] boxes) {
        int count = ends.length;
        if (kinds.length != count || boxes.length != count * 6) throw new IllegalArgumentException("ownership dimensions");
        int lowX = Integer.MAX_VALUE, lowZ = lowX, lowY = lowX;
        int highX = Integer.MIN_VALUE, highZ = highX, highY = highX;
        boolean exact = true;
        for (int i = 0; i < boxes.length; i++) {
            int axis = i % 6;
            float value = boxes[i];
            int units = Math.round(value * (axis == 1 || axis == 4 ? 16f : 1f / 16));
            exact &= Float.isFinite(value) && Float.floatToRawIntBits(value)
                    == Float.floatToRawIntBits(units * (axis == 1 || axis == 4 ? 1f / 16 : 16f));
            switch (axis) {
                case 0, 3 -> { lowX = Math.min(lowX, units); highX = Math.max(highX, units); }
                case 2, 5 -> { lowZ = Math.min(lowZ, units); highZ = Math.max(highZ, units); }
                default -> { lowY = Math.min(lowY, units); highY = Math.max(highY, units); }
            }
        }
        baseX = count == 0 ? 0 : lowX; baseZ = count == 0 ? 0 : lowZ; baseY = count == 0 ? 0 : lowY;
        boolean compact = exact && (long) highX - lowX <= 65535 && (long) highZ - lowZ <= 65535;
        wideY = compact && (long) highY - lowY > 65535;
        var ids = DICTIONARIES.get(); ids.clear();
        boolean dictionary = compact && count >= 12;
        // Bound the transient dictionary and require savings over direct packed footprints.
        int limit = Math.min(65536, count / 3);
        if (dictionary) for (int run = 0; run < count; run++) {
            long key = footprint(boxes, run * 6, baseX, baseZ);
            if (ids.get(key) < 0) {
                if (ids.size() == limit) { dictionary = false; break; }
                ids.put(key, ids.size());
            }
        }
        mode = !compact ? 0 : dictionary ? 2 : 1;
        stride = mode == 0 ? 7 : (dictionary ? 3 : 4) + (wideY ? 1 : 0);
        records = new int[count * stride];
        footprints = dictionary ? new long[ids.size()] : new long[0];
        if (dictionary) {
            var iterator = ids.long2IntEntrySet().fastIterator();
            while (iterator.hasNext()) { var entry = iterator.next(); footprints[entry.getIntValue()] = entry.getLongKey(); }
        }
        for (int run = 0; run < count; run++) {
            int at = run * stride, b = run * 6;
            if (ends[run] <= 0 || ends[run] > END_MASK || (run > 0 && ends[run] <= ends[run - 1])
                    || kinds[run] < 0 || kinds[run] > 3) throw new IllegalArgumentException("ownership range");
            records[at] = ends[run] | kinds[run] << 30;
            if (mode == 0) {
                for (int axis = 0; axis < 6; axis++) records[at + 1 + axis] = Float.floatToRawIntBits(boxes[b + axis]);
                continue;
            }
            long key = footprint(boxes, b, baseX, baseZ);
            if (dictionary) records[at + 1] = ids.get(key);
            else { records[at + 1] = (int) key; records[at + 2] = (int) (key >>> 32); }
            int y = at + stride - (wideY ? 2 : 1);
            if (wideY) {
                records[y] = Float.floatToRawIntBits(boxes[b + 1]); records[y + 1] = Float.floatToRawIntBits(boxes[b + 4]);
            } else records[y] = Math.round(boxes[b + 1] * 16) - baseY
                    | (Math.round(boxes[b + 4] * 16) - baseY) << 16;
        }
        ids.clear();
    }

    private LegacyOwnershipData(int mode, int stride, int baseX, int baseZ, int baseY,
                                    boolean wideY, long[] footprints, int[] records) {
        this.mode = mode; this.stride = stride; this.baseX = baseX; this.baseZ = baseZ; this.baseY = baseY;
        this.wideY = wideY; this.footprints = footprints; this.records = records;
    }
    private static long footprint(float[] boxes, int b, int x, int z) {
        return (Math.round(boxes[b] / 16) - x) & 65535L
                | ((Math.round(boxes[b + 2] / 16) - z) & 65535L) << 16
                | ((Math.round(boxes[b + 3] / 16) - x) & 65535L) << 32
                | ((Math.round(boxes[b + 5] / 16) - z) & 65535L) << 48;
    }
    int size() { return records.length / stride; }
    int end(int run) { return records[run * stride] & END_MASK; }
    int kind(int run) { return records[run * stride] >>> 30; }
    int firstRun(int quad) {
        int low = 0, high = size();
        while (low < high) { int mid = (low + high) >>> 1; if (end(mid) <= quad) low = mid + 1; else high = mid; }
        return low;
    }
    float coordinate(int run, int axis) {
        int at = run * stride;
        if (mode == 0) return Float.intBitsToFloat(records[at + 1 + axis]);
        if (axis == 1 || axis == 4) {
            int y = at + stride - (wideY ? 2 : 1);
            if (wideY) return Float.intBitsToFloat(records[y + (axis == 4 ? 1 : 0)]);
            return (baseY + (axis == 1 ? records[y] & 65535 : records[y] >>> 16)) / 16f;
        }
        long key = mode == 2 ? footprints[records[at + 1]]
                : Integer.toUnsignedLong(records[at + 1]) | (long) records[at + 2] << 32;
        int shift = switch (axis) { case 0 -> 0; case 2 -> 16; case 3 -> 32; case 5 -> 48; default -> throw new IllegalArgumentException("axis"); };
        return ((axis == 0 || axis == 3 ? baseX : baseZ) + (int) (key >>> shift & 65535)) * 16f;
    }
    long retainedBytes() { return 112L + records.length * 4L + footprints.length * 8L; }
    long encodedBytes() { return (HEADER + (long) records.length + footprints.length * 2L) * 4; }
    int[] encode() {
        int[] out = new int[Math.toIntExact(encodedBytes() / 4)];
        out[0] = MAGIC; out[1] = mode; out[2] = stride; out[3] = baseX; out[4] = baseZ;
        out[5] = baseY; out[6] = wideY ? 1 : 0; out[7] = size(); out[8] = footprints.length;
        int at = HEADER;
        for (long value : footprints) { out[at++] = (int) value; out[at++] = (int) (value >>> 32); }
        System.arraycopy(records, 0, out, at, records.length);
        return out;
    }
    static LegacyOwnershipData decode(int[] input) {
        if (input.length < HEADER || input[0] != MAGIC) throw new IllegalStateException("ownership header");
        int mode = input[1], stride = input[2], count = input[7], paletteCount = input[8];
        boolean wide = input[6] == 1;
        if (mode < 0 || mode > 2 || count < 0 || paletteCount < 0 || input[6] < 0 || input[6] > 1
                || stride != (mode == 0 ? 7 : (mode == 2 ? 3 : 4) + (wide ? 1 : 0))
                || (mode != 2 && paletteCount != 0)
                || HEADER + paletteCount * 2L + count * (long) stride != input.length)
            throw new IllegalStateException("ownership dimensions");
        long[] palette = new long[paletteCount]; int at = HEADER;
        for (int i = 0; i < paletteCount; i++) { palette[i] = Integer.toUnsignedLong(input[at]) | (long) input[at + 1] << 32; at += 2; }
        int[] records = Arrays.copyOfRange(input, at, input.length);
        var result = new LegacyOwnershipData(mode, stride, input[3], input[4], input[5], wide, palette, records);
        for (int run = 0; run < count; run++) {
            if (result.end(run) <= 0 || run > 0 && result.end(run) <= result.end(run - 1)
                    || mode == 2 && (records[run * stride + 1] < 0 || records[run * stride + 1] >= paletteCount))
                throw new IllegalStateException("ownership records");
        }
        return result;
    }
}
