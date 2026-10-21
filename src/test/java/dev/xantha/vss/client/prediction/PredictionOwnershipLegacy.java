package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-built runs with one column footprint. Does not reorder transparent geometry. */
final class PredictionOwnershipLegacy {
    final int[] ends;
    final float[] boxes;
    private final byte[] ownership;
    final boolean hasFeatures;
    final boolean hasPreviews;
    private final float minX, minY, minZ, maxX, maxY, maxZ;

    PredictionOwnershipLegacy(int[] words) {
        int count = words.length / PredictionPackedMesh.STRIDE_INTS;
        var previewBoxes = previewBoxes(words);
        int capacity = countRuns(words, previewBoxes), size = 0;
        int[] ends = new int[capacity];
        float[] boxes = new float[capacity * 6];
        ownership = new byte[capacity];
        boolean anyFeatures = false;
        int lastPreviewCell = -1;
        for (int q = 0; q < count; q++) {
            int p = q * PredictionPackedMesh.STRIDE_INTS, flags = words[p + 6];
            byte kind = kind(words[p + 10]);
            anyFeatures |= kind != 0;
            boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
            int fluid = flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3;
            float xzScale = fine ? 1f / 16 : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
            float yScale = fine || fluid != 0 && (flags & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0 ? 1f / 16 : 1f / 4;
            float x = Float.POSITIVE_INFINITY, y = x, z = x, xx = -x, yy = -x, zz = -x;
            for (int c = 0; c < 4; c++) {
                float a = (words[p + c / 2] >>> ((c & 1) * 16) & 65535) * xzScale;
                float b = ((words[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535) - 32768) * yScale;
                float d = (words[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535) * xzScale;
                x = Math.min(x, a); xx = Math.max(xx, a);
                y = Math.min(y, b); yy = Math.max(yy, b);
                z = Math.min(z, d); zz = Math.max(zz, d);
            }
            // Closed geometry at a chunk edge needs both neighbours; never clip a quad in half.
            x = (float) Math.floor((x - 0.0625) / 16) * 16;
            z = (float) Math.floor((z - 0.0625) / 16) * 16;
            xx = (float) (Math.floor((xx + 0.0625) / 16) + 1) * 16;
            zz = (float) (Math.floor((zz + 0.0625) / 16) + 1) * 16;
            if (kind == 3) {
                var box = previewBoxes.get(words[p + 8]);
                x = box[0]; y = box[1]; z = box[2]; xx = box[3]; yy = box[4]; zz = box[5];
            }
            int i = (size - 1) * 6;
            if (size > 0 && ownership[size - 1] == kind && (kind != 3 || lastPreviewCell == words[p + 8])
                    && boxes[i] == x && boxes[i + 2] == z && boxes[i + 3] == xx && boxes[i + 5] == zz) {
                boxes[i + 1] = Math.min(boxes[i + 1], y); boxes[i + 4] = Math.max(boxes[i + 4], yy);
                ends[size - 1] = q + 1;
            } else {
                i = size * 6;
                boxes[i] = x; boxes[i + 1] = y; boxes[i + 2] = z;
                boxes[i + 3] = xx; boxes[i + 4] = yy; boxes[i + 5] = zz;
                ownership[size] = kind;
                ends[size++] = q + 1;
            }
            lastPreviewCell = words[p + 8];
        }
        this.ends = ends; this.boxes = boxes;
        this.hasFeatures = anyFeatures;
        this.hasPreviews = previewBoxes != null;
        float loX = Float.POSITIVE_INFINITY, loY = Float.POSITIVE_INFINITY, loZ = Float.POSITIVE_INFINITY;
        float hiX = Float.NEGATIVE_INFINITY, hiY = Float.NEGATIVE_INFINITY, hiZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            int b = i * 6;
            loX = Math.min(loX, boxes[b]); loY = Math.min(loY, boxes[b + 1]); loZ = Math.min(loZ, boxes[b + 2]);
            hiX = Math.max(hiX, boxes[b + 3]); hiY = Math.max(hiY, boxes[b + 4]); hiZ = Math.max(hiZ, boxes[b + 5]);
        }
        this.minX = size == 0 ? 0 : loX; this.minY = size == 0 ? 0 : loY; this.minZ = size == 0 ? 0 : loZ;
        this.maxX = size == 0 ? 0 : hiX; this.maxY = size == 0 ? 0 : hiY; this.maxZ = size == 0 ? 0 : hiZ;
    }

    // Count XZ footprints first so workers allocate only final arrays, without
    // growing/copying a potentially large index or allocating per-quad objects.
    private static int countRuns(int[] words, it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<float[]> previewBoxes) {
        int count = 0;
        float lastX = Float.NaN, lastZ = Float.NaN, lastXX = Float.NaN, lastZZ = Float.NaN;
        byte lastKind = 0;
        int lastPreviewCell = -1;
        for (int p = 0; p < words.length; p += PredictionPackedMesh.STRIDE_INTS) {
            int flags = words[p + 6];
            byte kind = kind(words[p + 10]);
            float scale = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0
                    ? 1f / 16 : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
            float x = Float.POSITIVE_INFINITY, z = x, xx = -x, zz = -x;
            for (int c = 0; c < 4; c++) {
                float a = (words[p + c / 2] >>> ((c & 1) * 16) & 65535) * scale;
                float b = (words[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535) * scale;
                x = Math.min(x, a); xx = Math.max(xx, a);
                z = Math.min(z, b); zz = Math.max(zz, b);
            }
            x = (float) Math.floor((x - 0.0625) / 16) * 16;
            z = (float) Math.floor((z - 0.0625) / 16) * 16;
            xx = (float) (Math.floor((xx + 0.0625) / 16) + 1) * 16;
            zz = (float) (Math.floor((zz + 0.0625) / 16) + 1) * 16;
            if (kind == 3) {
                var box = previewBoxes.get(words[p + 8]);
                x = box[0]; z = box[2]; xx = box[3]; zz = box[5];
            }
            if (x != lastX || z != lastZ || xx != lastXX || zz != lastZZ || kind != lastKind
                    || kind == 3 && lastPreviewCell != words[p + 8]) {
                count++; lastX = x; lastZ = z; lastXX = xx; lastZZ = zz;
                lastKind = kind;
            }
            lastPreviewCell = words[p + 8];
        }
        return count;
    }

    private static byte kind(int flags) {
        return (flags & PredictionPackedMesh.FEATURE) == 0 ? (byte) 0
                : (flags & PredictionPackedMesh.PREVIEW) == 0 ? (byte) 1 : (byte) 3;
    }

    /** Workers join every representative's trunk/crown across face groups into one proof. */
    private static it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<float[]> previewBoxes(int[] words) {
        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<float[]> result = null;
        for (int p = 0; p < words.length; p += PredictionPackedMesh.STRIDE_INTS) {
            if (kind(words[p + 10]) != 3) continue;
            if (result == null) result = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
            int cell = words[p + 8];
            float[] box = result.get(cell);
            if (box == null) {
                box = new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                result.put(cell, box);
            }
            int flags = words[p + 6];
            boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
            float scale = fine ? 1f / 16 : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
            float yScale = fine ? 1f / 16 : 1f / 4;
            for (int c = 0; c < 4; c++) {
                float x = (words[p + c / 2] >>> ((c & 1) * 16) & 65535) * scale;
                float y = ((words[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535) - 32768) * yScale;
                float z = (words[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535) * scale;
                box[0] = Math.min(box[0], x); box[3] = Math.max(box[3], x);
                box[1] = Math.min(box[1], y); box[4] = Math.max(box[4], y);
                box[2] = Math.min(box[2], z); box[5] = Math.max(box[5], z);
            }
        }
        if (result != null) for (var box : result.values()) {
            box[0] = (float) Math.floor((box[0] - 0.0625) / 16) * 16;
            box[2] = (float) Math.floor((box[2] - 0.0625) / 16) * 16;
            box[3] = (float) (Math.floor((box[3] + 0.0625) / 16) + 1) * 16;
            box[5] = (float) (Math.floor((box[5] + 0.0625) / 16) + 1) * 16;
        }
        return result;
    }

    @FunctionalInterface interface UploadedCoverage {
        boolean covers(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    }

    /** Scratch retained by a submission-cache entry so filtering never grows per frame. */
    static final class Scratch {
        private int[] first = new int[0];
        private int[] counts = new int[0];
        private byte[] proof;
        private int[] coveredRuns = new int[0];

        private void index(byte[] coverage) {
            if (proof == coverage) return;
            int size = 0;
            for (byte value : coverage) if (value != 0) size++;
            coveredRuns = new int[size];
            int i = 0;
            for (int run = 0; run < coverage.length; run++) if (coverage[run] != 0) coveredRuns[i++] = run;
            proof = coverage;
        }

        private int append(int size, int start, int end) {
            if (start == end) return size;
            if (size > 0 && first[size - 1] + counts[size - 1] == start) counts[size - 1] += end - start;
            else {
                ensure(size + 1);
                first[size] = start; counts[size++] = end - start;
            }
            return size;
        }

        private void ensure(int capacity) {
            if (first.length >= capacity) return;
            int next = Math.max(capacity, Math.max(16, first.length * 2));
            first = Arrays.copyOf(first, next);
            counts = Arrays.copyOf(counts, next);
        }
    }

    /** Compute the camera-independent Voxy coverage proof for each run. */
    byte[] staticCoverage(PredictionExactCoverageMask.Snapshot index, double baseX, double baseZ) {
        return staticCoverage(index, baseX, baseZ, null);
    }

    byte[] staticCoverage(PredictionExactCoverageMask.Snapshot index, double baseX, double baseZ,
                          UploadedCoverage uploaded) {
        byte[] covered = new byte[ends.length];
        if (index == null) return covered;
        for (int run = 0; run < ends.length; run++) {
            // Complete decoration reaches the actual depth test. Only temporary
            // representatives may retire on settled data AND uploaded regional ownership.
            if (ownership[run] == 1 || ownership[run] == 3 && uploaded == null) continue;
            int b = run * 6;
            double x = baseX + boxes[b], z = baseZ + boxes[b + 2];
            double xx = baseX + boxes[b + 3], zz = baseZ + boxes[b + 5];
            int cx = (int) Math.floor(x / 16), cz = (int) Math.floor(z / 16);
            int cxx = (int) Math.ceil(xx / 16) - 1, czz = (int) Math.ceil(zz / 16) - 1;
            covered[run] = (byte) (index.interior(cx, cz, cxx, czz)
                    && (ownership[run] != 3 || uploaded.covers(cx, (int) Math.floor(boxes[b + 1]), cz,
                            cxx, (int) Math.ceil(boxes[b + 4]), czz)) ? 1 : 0);
        }
        return covered;
    }

    static boolean allStaticCoverage(byte[] covered) {
        for (byte value : covered) if (value == 0) return false;
        return true;
    }

    static boolean anyStaticCoverage(byte[] covered) {
        for (byte value : covered) if (value != 0) return true;
        return false;
    }

    boolean intersectsRange(double baseX, double baseZ, double x, double y, double z, double radius,
                            float morphMin, float morphMax) {
        return intersectsSphere(x, y, z, radius, baseX + minX - 4, Math.min(minY, morphMin) - 4,
                baseZ + minZ - 4, baseX + maxX + 4, Math.max(maxY, morphMax) + 4, baseZ + maxZ + 4);
    }

    boolean fullyInside(double baseX, double baseZ, double x, double y, double z, double radius,
                        float morphMin, float morphMax) {
        return !hasFeatures && ends.length > 0 && radius > 0 && PredictionVoxyOwnership.containsBox(x, y, z, radius,
                baseX + minX - 4, Math.min(minY, morphMin) - 4, baseZ + minZ - 4,
                baseX + maxX + 4, Math.max(maxY, morphMax) + 4, baseZ + maxZ + 4);
    }
    boolean wholeStaticCoverage(PredictionExactCoverageMask.Snapshot index, double baseX, double baseZ) {
        if (hasFeatures) return false;
        return index.interior((int) Math.floor((baseX + minX) / 16), (int) Math.floor((baseZ + minZ) / 16),
                (int) Math.ceil((baseX + maxX) / 16) - 1, (int) Math.ceil((baseZ + maxZ) / 16) - 1);
    }

    /** Conservative footprint test for local settled-column invalidations. */
    boolean intersectsColumns(double baseX, double baseZ, long x, long z, long xx, long zz) {
        return ends.length > 0 && baseX + maxX >= x * 16.0 && baseZ + maxZ >= z * 16.0
                && baseX + minX <= (xx + 1.0) * 16 && baseZ + minZ <= (zz + 1.0) * 16;
    }

    boolean intersectsWindow(double baseX, double baseZ, dev.xantha.vss.compat.StrictVoxyCoverageChanges.WindowMovement window) {
        return window.affects((long) Math.floor((baseX + minX) / 16), (long) Math.floor((baseZ + minZ) / 16),
                (long) Math.ceil((baseX + maxX) / 16), (long) Math.ceil((baseZ + maxZ) / 16));
    }

    PredictionDrawRanges filter(PredictionDrawRanges input, PredictionExactCoverageMask.Snapshot index,
                               double baseX, double baseZ, double cameraX, double cameraY, double cameraZ,
                               double radius, float morphMin, float morphMax) {
        return filter(input, index, baseX, baseZ, cameraX, cameraY, cameraZ, radius, morphMin, morphMax,
                null, false, new Scratch(), null);
    }

    /**
     * Filter a draw plan using a camera-independent coverage proof and reusable output scratch.
     * The previous result is returned when its exact ranges still match, avoiding a new plan on
     * every camera-cell crossing.
     */
    PredictionDrawRanges filter(PredictionDrawRanges input, PredictionExactCoverageMask.Snapshot index,
                               double baseX, double baseZ, double cameraX, double cameraY, double cameraZ,
                               double radius, float morphMin, float morphMax, byte[] staticCoverage,
                               boolean allStaticCoverage, Scratch scratch, PredictionDrawRanges previous) {
        if (index == null || radius <= 0 || input.quads == 0) return input;

        double lowY = Math.min(minY, morphMin), highY = Math.max(maxY, morphMax);
        double lowX = baseX + minX - 4, lowZ = baseZ + minZ - 4;
        double highX = baseX + maxX + 4, highZ = baseZ + maxZ + 4;
        // A whole-mesh outside test avoids touching every run when the camera leaves the tile.
        if (!intersectsSphere(cameraX, cameraY, cameraZ, radius, lowX, lowY - 4, lowZ,
                highX, highY + 4, highZ)) return input;
        boolean fullInside = PredictionVoxyOwnership.containsBox(cameraX, cameraY, cameraZ, radius,
                lowX, lowY - 4, lowZ, highX, highY + 4, highZ);

        if (fullInside && allStaticCoverage) {
            if (previous != null && previous.quads == 0) return previous;
            return new PredictionDrawRanges(new int[0], new int[0]);
        }

        if (staticCoverage == null) staticCoverage = staticCoverage(index, baseX, baseZ);
        scratch.index(staticCoverage);
        if (scratch.coveredRuns.length == 0) return input;
        int size = 0, total = 0;
        for (int r = 0; r < input.first.length; r++) {
            int start = input.first[r], end = start + input.count[r];
            int run = Arrays.binarySearch(ends, start + 1); if (run < 0) run = -run - 1;
            int covered = Arrays.binarySearch(scratch.coveredRuns, run);
            if (covered < 0) covered = -covered - 1;
            // Subtract only runs with a settled-column proof. Far terrain and
            // features between them remain one span, without per-run box work.
            for (; covered < scratch.coveredRuns.length; covered++) {
                run = scratch.coveredRuns[covered];
                int runStart = run == 0 ? 0 : ends[run - 1];
                if (runStart >= end) break;
                int next = Math.min(end, ends[run]), b = run * 6;
                double x = baseX + boxes[b], z = baseZ + boxes[b + 2];
                double xx = baseX + boxes[b + 3], zz = baseZ + boxes[b + 5];
                double y = Math.min(boxes[b + 1], morphMin), yy = Math.max(boxes[b + 4], morphMax);
                boolean owned = fullInside || PredictionVoxyOwnership.containsBox(cameraX, cameraY, cameraZ,
                        radius, x - 4, y - 4, z - 4, xx + 4, yy + 4, zz + 4);
                if (owned && ownership[run] != 1) {
                    int keepEnd = Math.max(start, runStart);
                    size = scratch.append(size, start, keepEnd);
                    total += keepEnd - start;
                    start = next;
                }
            }
            size = scratch.append(size, start, end);
            total += end - start;
        }
        if (total == input.quads) return input;
        if (size == 0) {
            if (previous != null && previous.quads == 0) return previous;
            return new PredictionDrawRanges(new int[0], new int[0]);
        }
        if (previous != null && previous.quads == total && previous.first.length == size
                && sameRanges(previous, scratch.first, scratch.counts, size)) return previous;
        return new PredictionDrawRanges(Arrays.copyOf(scratch.first, size), Arrays.copyOf(scratch.counts, size));
    }

    private static boolean sameRanges(PredictionDrawRanges previous, int[] first, int[] counts, int size) {
        for (int i = 0; i < size; i++) {
            if (previous.first[i] != first[i] || previous.count[i] != counts[i]) return false;
        }
        return true;
    }

    private static boolean intersectsSphere(double cameraX, double cameraY, double cameraZ, double radius,
                                            double minX, double minY, double minZ,
                                            double maxX, double maxY, double maxZ) {
        double dx = cameraX < minX ? minX - cameraX : cameraX > maxX ? cameraX - maxX : 0;
        double dy = cameraY < minY ? minY - cameraY : cameraY > maxY ? cameraY - maxY : 0;
        double dz = cameraZ < minZ ? minZ - cameraZ : cameraZ > maxZ ? cameraZ - maxZ : 0;
        return dx * dx + dy * dy + dz * dz < radius * radius;
    }
}
