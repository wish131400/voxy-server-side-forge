package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PredictionScanMergeTest {
    @Test void wholeSlicesRestoreAllDirectionsMaterialsLightHolesAndPixelOwnership() {
        for (int normal = 0; normal < 3; normal++) {
            int[] words = slice(normal, 32, true);
            var merged = merge(words, 32, 1, true);
            assertEquals(4, merged.words().length / 12);
            assertEquals(raster(words, 32, 1), raster(merged.words(), 32, 1));
            var second = merge(merged.words(), 32, 1, true);
            assertArrayEquals(merged.words(), second.words(), "cache restore must be idempotent");
            for (int q = 0; q < 1024; q++) {
                int p = q * 12;
                if ((q % 32 + q / 32) % 5 == 0) words[p + 7] = words[p + 9] = words[p + 11] = 0x334466;
                else if (q % 7 == 0) words[p + 6] |= PredictionPackedMesh.FLAG_CUTOUT;
                else if (q % 11 == 0) for (int c : new int[]{7, 9, 10, 11}) words[p + c] |= 3 << 28;
                words[p + 10] = words[p + 7] | PredictionPackedMesh.FEATURE;
                if (q % 9 == 0) words[p + 6] |= PredictionPackedMesh.FLAG_MODEL_UV;
            }
            assertEquals(raster(words, 32, 1), raster(merge(words, 32, 1, true).words(), 32, 1));
        }
    }

    @Test void fixedOverhangingCrownsAndUnknownSpacingKeepTheirOriginalOwner() {
        int[] words = slice(1, 32, true);
        for (int q = 0; q < 1024; q++) words[q * 12 + 8] = 0;
        var sameCell = merge(words, 32, 0, true);
        assertEquals(4, sameCell.words().length / 12);
        for (int p = 0; p < sameCell.words().length; p += 12) {
            assertEquals(0, sameCell.words()[p + 8]); assertNotEquals(0, sameCell.words()[p + 9] & (1 << 24));
        }
        assertEquals(raster(words, 32, 1), raster(merge(words, 32, 1, true).words(), 32, 1));
        words = slice(1, 32, true);
        assertSame(words, merge(words, 32, 0, true).words(), "unknown cell spacing cannot relax fixed ownership");
    }

    @Test void exactGroundMorphWaterModelsCrossesAndBoundaryFacesStayUnchanged() {
        for (int excluded : new int[]{PredictionPackedMesh.FLAG_MODEL_UV, PredictionPackedMesh.FLAG_UNSHADED,
                1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT}) {
            int[] words = slice(1, 32, true);
            for (int p = 0; p < words.length; p += 12) words[p + 6] |= excluded;
            assertSame(words, merge(words, 32, 1, true).words());
        }
        for (int tag : new int[]{1 << 25, 1 << 26}) {
            int[] words = slice(1, 32, true);
            for (int p = 0; p < words.length; p += 12) words[p + 9] |= tag;
            assertSame(words, merge(words, 32, 1, true).words());
        }
        int[] ground = slice(1, 32, true);
        for (int p = 10; p < ground.length; p += 12) ground[p] &= ~PredictionPackedMesh.FEATURE;
        assertSame(ground, merge(ground, 32, 1, true).words());
        assertEquals(4, merge(ground, 32, 1, false).words().length / 12);
        int[] preview = slice(1, 32, true);
        for (int p = 10; p < preview.length; p += 12) preview[p] |= PredictionPackedMesh.PREVIEW;
        assertSame(preview, merge(preview, 32, 1, true).words());
        int[] words = Arrays.copyOf(slice(1, 32, true), (1024 + 2) * 12);
        for (int p = 1024 * 12; p < words.length; p++) words[p] = p * 37;
        var merged = PredictionScanMerge.merge(words, new int[5], new int[]{1024, 0, 0, 0, 0}, 32, 1, true, 16);
        assertArrayEquals(Arrays.copyOfRange(words, 1024 * 12, words.length), Arrays.copyOfRange(merged.words(), 4 * 12, merged.words().length));
    }

    @Test void overlapGradientsAndOppositeWindingCannotBeLostByRasterizing() {
        int[] overlap = Arrays.copyOf(slice(1, 32, true), 1025 * 12);
        System.arraycopy(overlap, 0, overlap, 1024 * 12, 12);
        assertSame(overlap, merge(overlap, 32, 1, true).words());
        int[] gradient = slice(1, 32, true);
        for (int p = 0; p < gradient.length; p += 12) gradient[p + 11]++;
        assertSame(gradient, merge(gradient, 32, 1, true).words());
        int[] words = Arrays.copyOf(slice(1, 32, false), 2 * 12);
        int[] xyz = new int[12];
        for (int c = 0; c < 4; c++) for (int axis = 0; axis < 3; axis++) xyz[c * 3 + axis] = PredictionScanMerge.coordinate(words, 12, c, axis);
        for (int c = 0; c < 4; c++) for (int axis = 0; axis < 3; axis++) PredictionScanMerge.setCoordinate(words, 12, c, axis, xyz[((4 - c) % 4) * 3 + axis]);
        assertSame(words, merge(words, 32, 1, true).words());
    }

    @Test void nonuniformGridCapsAndSparseWorkBudgetNeverIncreaseOrDropGeometry() {
        int[] words = slice(1, 32, false);
        // Use fully representable fractional quads instead of silently rounding coordinates.
        for (int q = 0; q < 1024; q++) quad(words, q, 1, q % 32 * 4, q / 32 * 4, 4, 4, true, false);
        var merged = merge(words, 32, 1, true);
        assertTrue(merged.words().length < words.length);
        assertEquals(rasterUnits(words, 32, 1, 4), rasterUnits(merged.words(), 32, 1, 4));
        int[] large = new int[2 * 12];
        quad(large, 0, 1, 0, 0, 4000 * 16, 4000 * 16, false, false);
        quad(large, 1, 1, 4000 * 16, 0, 4000 * 16, 4000 * 16, false, false);
        assertSame(large, merge(large, 32, 1, true).words(), "large raster work is bounded before allocation");
    }

    @Test void mergedCanonicalStorageReleasesScratchAndColdRestorePreservesAllRanges() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        int[] words = slice(1, 32, false);
        var payload = PredictionPackedMesh.terrainRecords(words, 32);
        assertEquals(4, payload.terrainQuadCount()); assertEquals(4 * 12, payload.cacheWords().length);
        int[] original = payload.quads().clone();
        payload.prepareGpuStorage(); payload.prepareStorage(); assertArrayEquals(original, payload.restoreWords());
        var mesh = PredictionMesh.restored(1024 * 6, 0, payload, PredictionMeshCodecTest.fixture(32).seamMesh());
        byte[] signature = new byte[32];
        var restored = PredictionMeshCodec.decode(PredictionMeshCodec.encode(mesh, signature), signature, 32);
        assertArrayEquals(payload.cacheWords(), restored.gpuPayload().cacheWords());
        assertEquals(payload.drawRanges(false, VssLodFaceGroup.ALL).quads, restored.gpuPayload().drawRanges(false, VssLodFaceGroup.ALL).quads);
    }

    @Test void existingFixedCellDiskMeshesGainCrossCellMergingUsingTheCallersKnownSpacing() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        int[] words = slice(1, 32, true);
        var payload = new PredictionPackedMesh(words, 32, 1024, new int[5], new int[]{1024, 0, 0, 0, 0}, new int[5], new int[5], false, 0);
        var mesh = PredictionMesh.restored(1024 * 6, 0, payload, PredictionMeshCodecTest.fixture(32).seamMesh());
        byte[] signature = new byte[32], bytes = PredictionMeshCodec.encode(mesh, signature);
        var unknown = PredictionMeshCodec.decode(bytes, signature, 32);
        assertEquals(1024, unknown.gpuPayload().terrainQuadCount());
        var known = PredictionMeshCodec.decode(bytes, signature, 32, 1);
        assertEquals(4, known.gpuPayload().terrainQuadCount());
        assertEquals(raster(words, 32, 1), raster(known.gpuPayload().cacheWords(), 32, 1));
    }

    @Test void deeperDisplayTiersReuseAResidentRepresentationWhenTheirAllocationDoesNotFit() {
        int[] words = PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_CUTOUT);
        for (int q = 0; q < 1024; q++) if (q % 4 == 0) for (int c : new int[]{7, 9, 10, 11}) words[q * 12 + c] = q + 1;
        int[][] retained = new int[4][5]; for (int[] tier : retained) tier[0] = 1024;
        var result = PredictionDisplayGeometry.build(words, new int[5], new int[]{1024, 0, 0, 0, 0}, retained, 64);
        assertNotNull(result.ranges());
        assertSame(result.vegetation()[1], result.vegetation()[2]); assertSame(result.vegetation()[1], result.vegetation()[3]);
        assertEquals(result.errors()[1], result.errors()[3]);
        assertTrue(result.words().length <= words.length * 1.5);
        for (int tier = 1; tier <= 3; tier++) assertTrue(result.range(tier, 0, 31).quads < 400);
    }

    static PredictionScanMerge.Result merge(int[] words, int axis, int spacing, boolean features) {
        return PredictionScanMerge.merge(words, new int[1], new int[]{words.length / 12}, axis, spacing, features, 16);
    }

    static int[] slice(int normal, int size, boolean fixed) {
        int[] words = new int[size * size * 12];
        for (int q = 0; q < size * size; q++) {
            quad(words, q, normal, q % size * 16, q / size * 16, 16, 16, false, fixed);
            int x = normal == 1 ? q / size : normal == 2 ? q % size : 0;
            int z = normal == 1 ? q % size : normal == 0 ? q / size : 0;
            words[q * 12 + 8] = x + z * size;
        }
        return words;
    }

    static void quad(int[] words, int q, int normal, int u0, int v0, int width, int height, boolean fine, boolean fixed) {
        int p = q * 12, u = (normal + 1) % 3, v = (normal + 2) % 3;
        words[p + 6] = (normal == 0 ? 1 : normal == 2 ? 2 : 0) << 24 | (fine ? 1 << 20 : 0);
        int[] corners = {0, 1, 3, 2};
        for (int c = 0; c < 4; c++) {
            PredictionScanMerge.setCoordinate(words, p, c, normal, normal == 1 ? 80 * 16 : 0);
            PredictionScanMerge.setCoordinate(words, p, c, u, u0 + ((corners[c] & 1) != 0 ? width : 0));
            PredictionScanMerge.setCoordinate(words, p, c, v, v0 + ((corners[c] & 2) != 0 ? height : 0));
        }
        words[p + 7] = words[p + 11] = 0x556633;
        words[p + 9] = 0x556633 | (fixed ? 1 << 24 : 0); words[p + 10] = 0x556633 | PredictionPackedMesh.FEATURE;
    }

    static Map<String, Integer> raster(int[] words, int axis, int spacing) { return rasterUnits(words, axis, spacing, 16); }
    private static Map<String, Integer> rasterUnits(int[] words, int axis, int spacing, int unit) {
        var result = new HashMap<String, Integer>();
        for (int p = 0; p < words.length; p += 12) {
            int n = PredictionScanMerge.normal(words[p + 6]), u = (n + 1) % 3, v = (n + 2) % 3;
            int a = PredictionScanMerge.coordinate(words, p, 0, u), b = PredictionScanMerge.coordinate(words, p, 2, u);
            int c = PredictionScanMerge.coordinate(words, p, 0, v), d = PredictionScanMerge.coordinate(words, p, 2, v);
            int plane = PredictionScanMerge.coordinate(words, p, 0, n);
            for (int y = Math.min(c, d); y < Math.max(c, d); y += unit) for (int x = Math.min(a, b); x < Math.max(a, b); x += unit) {
                int[] point = new int[3]; point[n] = plane; point[u] = x + unit / 2; point[v] = y + unit / 2;
                int cell = words[p + 8];
                if ((words[p + 9] & (1 << 24)) == 0) {
                    int cx = n == 0 ? cell % axis : point[0] / (spacing * 16);
                    int cz = n == 2 ? cell / axis : point[2] / (spacing * 16);
                    cell = cx + cz * axis;
                }
                String key = words[p + 6] + "/" + words[p + 7] + "/" + (words[p + 9] & ~(1 << 24)) + "/"
                        + words[p + 10] + "/" + words[p + 11] + "/" + plane + "/" + x + "/" + y + "/" + cell;
                result.merge(key, 1, Integer::sum);
            }
        }
        return result;
    }
}
