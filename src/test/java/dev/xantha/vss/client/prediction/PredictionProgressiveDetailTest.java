package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;

class PredictionProgressiveDetailTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static int[] decorated(int featureCount) {
        return PredictionMeshFixture.decorated(featureCount);
    }

    static PredictionDisplayGeometry.Result shared(int[] words, int[] first, int[] count,
            int[][] retained, int axis, boolean[] materials, boolean[] plants, boolean[] aquatic) {
        var vegetation = PredictionVegetationRanges.arrange(words, first, count, plants, aquatic);
        return PredictionDisplayGeometry.build(words, first, count, retained, axis, materials, vegetation);
    }

    @Test void completingDecorationsAndCrossingTotalFaceLimitCannotRemoveGroundDisplayLod() {
        for (int features : new int[]{0, 8192, 132000}) {
            int[] words = decorated(features), first = new int[5], count = {1024 + features, 0, 0, 0, 0};
            int[][] retained = new int[4][5]; for (var c : retained) c[0] = count[0];
            var next = shared(words, first, count, retained, 64, new boolean[256], new boolean[256], new boolean[256]);
            assertNotNull(next.ranges(), "ground display LOD after " + features + " decorations");
            assertArrayEquals(words, Arrays.copyOf(next.words(), words.length));
            if (features > 0) {
                assertNotNull(next.sharedFeatures());
                assertNull(ProgressiveBaselineDisplayGeometry.build(words, first, count, retained, 64, new boolean[256]).ranges());
                assertTrue(next.words().length - words.length <= 1024 * 12 / 2, "features must not be duplicated for each tier");
                for (int tier = 1; tier < 4; tier++) if (Float.isFinite(next.errors()[tier])) {
                    var ranges = next.range(tier, 0, 31, 0);
                    assertEquals(features, sourceFeatures(next, words.length / 12, ranges).size());
                    assertTrue(ranges.quads - features < 1024 * .65);
                    assertTrue(ranges.first.length <= 6);
                }
            }
            assertNull(next.metadataOnly().words(), "metadata must never retain full coordinate arrays");
        }
    }

    @Test void everyMaskAndPlantTierPreserveExactSharedFeaturesAndSourceOrder() {
        int[] words = decorated(8192), first = new int[5], count = {words.length / 12, 0, 0, 0, 0};
        boolean[] plants = new boolean[256], aquatic = new boolean[256]; plants[3] = plants[4] = aquatic[4] = true;
        for (int q = 1024; q < 2048; q++) words[q * 12 + 6] = PredictionPackedMesh.FLAG_UNSHADED | (q % 2 == 0 ? 3 : 4);
        var plan = PredictionVegetationRanges.arrange(words, first, count, plants, aquatic);
        var next = PredictionDisplayGeometry.build(words, first, count, null, 64, new boolean[256], plan);
        assertNotNull(next.sharedFeatures());
        for (int tier = 1; tier < 4; tier++) if (Float.isFinite(next.errors()[tier]))
            for (int mask = 0; mask < 32; mask++) for (int land = 0; land < 4; land++) for (int water = 0; water < 4; water++) {
                var expected = featureIndices(words, plan.range(mask, land, water));
                var actual = next.range(tier, water, mask, land);
                assertEquals(expected, sourceFeatures(next, words.length / 12, actual));
                int end = 0;
                for (int r = 0; r < actual.first.length; r++) {
                    assertTrue(actual.first[r] >= end);
                    end = actual.first[r] + actual.count[r];
                    assertTrue(end <= next.words().length / 12);
                }
                assertSame(actual, next.range(tier, water, mask, land));
            }
    }

    @Test void mixedFeatureOwnershipCanUseGroundProxyButMixedTerrainNearScopeAndMorphStayExact() throws Exception {
        var packed = PredictionPackedMesh.terrainRecords(decorated(8192), 64);
        assertTrue(packed.hasTerrainOnlyDisplayLod());
        var original = PredictionPreparedPassLocalTest.draw(0, 0, packed);
        boolean[] all = original.allowed(), features = all.clone(); features[100] = false;
        var fullGround = new PredictionRenderer.Draw(original.tile(), original.gpu(), all, features, false, 31, 0, original.bounds());
        assertFalse(fullGround.completeOwnership()); assertTrue(fullGround.completeTerrainOwnership());
        var scale = PredictionRenderer.class.getDeclaredField("selectionScale"); scale.setAccessible(true);
        var scope = PredictionRenderer.class.getDeclaredField("selectionScoping"); scope.setAccessible(true);
        double oldScale = scale.getDouble(null); boolean oldScope = scope.getBoolean(null);
        try {
            scale.setDouble(null, 900); scope.setBoolean(null, false);
            var pass = new PredictionPreparedPassLocalTest.Pass(false); var far = new Vec3(20000, 80, 0);
            pass.prepare(List.of(fullGround), 1, far);
            int exact = packed.drawRanges(false, 31).quads;
            assertTrue(pass.ranges(fullGround).quads < exact);
            boolean[] partial = all.clone(); partial[100] = false;
            var mixedGround = new PredictionRenderer.Draw(original.tile(), original.gpu(), partial, features, false, 31, 0, original.bounds());
            pass.prepare(List.of(mixedGround), 1, far); assertEquals(exact, pass.ranges(mixedGround).quads);
            pass.prepare(List.of(fullGround), 1, new Vec3(32, 80, 32)); assertEquals(exact, pass.ranges(fullGround).quads);
            scope.setBoolean(null, true); pass.prepare(List.of(fullGround), 1, far); assertEquals(exact, pass.ranges(fullGround).quads);
            scope.setBoolean(null, false);
            packed.morph(new float[4096], 0, 200);
            var uploaded = PredictionGpuTile.class.getDeclaredField("uploadedAt"); uploaded.setAccessible(true); uploaded.setLong(original.gpu(), System.nanoTime());
            var morph = new PredictionRenderer.Draw(original.tile(), original.gpu(), all, features, false, 31, 1, original.bounds());
            pass.prepare(List.of(morph), 1, far); assertEquals(exact, pass.ranges(morph).quads);
        } finally { scale.setDouble(null, oldScale); scope.setBoolean(null, oldScope); }
    }

    @Test void compressedUploadAndColdRestoreKeepSharedRangesWithoutRetainingCoordinates() throws Exception {
        var mesh = PredictionPackedMesh.terrainRecords(decorated(8192), 64);
        int[] original = mesh.quads().clone();
        var ranges = mesh.drawRanges(false, 31, 0, 1, 0);
        mesh.prepareGpuStorage(); mesh.prepareStorage(); assertTrue(mesh.compressed()); mesh.uploaded();
        assertSame(ranges, mesh.drawRanges(false, 31, 0, 1, 0));
        var geometry = PredictionPackedMesh.class.getDeclaredField("displayGeometry"); geometry.setAccessible(true);
        assertNull(((PredictionDisplayGeometry.Result)geometry.get(mesh)).words());
        var coordinates = PredictionPackedMesh.class.getDeclaredField("quads"); coordinates.setAccessible(true); assertNull(coordinates.get(mesh));
        assertArrayEquals(original, mesh.restoreWords());
        assertSame(ranges, mesh.drawRanges(false, 31, 0, 1, 0));
    }

    @Test void waterAndModelContactsRetainTheirOriginalProtectionEnvelope() {
        int[] words = decorated(8192);
        // Model feet touch the same-height ground throughout the tile.
        for (int q = 1024; q < words.length / 12; q++) {
            int y = 32768 + 80 * 4; words[q * 12 + 4] = words[q * 12 + 5] = y | y << 16;
        }
        int[] first = new int[5], count = {words.length / 12, 0, 0, 0, 0};
        var next = shared(words, first, count, null, 64, new boolean[256], new boolean[256], new boolean[256]);
        assertArrayEquals(words, Arrays.copyOf(next.words(), words.length));
        // Coplanar protected faces may still merge losslessly. Their contact
        // heights must stay exact even if a representation is admitted.
        for (int p = words.length; p < next.words().length; p += 12)
            for (int c = 0; c < 4; c++) {
                int y = next.words()[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535;
                assertTrue(y == 32768 + 80 * 4 || y == 32768 + 81 * 4);
                assertEquals(0, next.words()[p + 10] & PredictionPackedMesh.DISPLAY_APPROXIMATE);
            }
    }

    @Test void cachedRetainedBytesExactlyMatchThePreviousAccountingAndDoNotGrowOnReuse() throws Exception {
        int[] words = decorated(8192), first = new int[5], count = {words.length / 12, 0, 0, 0, 0};
        var plan = PredictionVegetationRanges.arrange(words, first, count, new boolean[256], new boolean[256]);
        assertEquals(recount(plan), plan.retainedHeapBytes());
        for (int mask = 0; mask < 32; mask++) for (int land = 0; land < 4; land++) for (int water = 0; water < 4; water++) {
            plan.range(mask, land, water); assertEquals(recount(plan), plan.retainedHeapBytes());
            long bytes = plan.retainedHeapBytes(); plan.range(mask, land, water); assertEquals(bytes, plan.retainedHeapBytes());
        }
    }

    @Test void explicitDisplayDisableSkipsBothOrdinaryAndSharedRepresentations() {
        String previous = System.getProperty("vss.disablePredictionDisplayLod");
        try {
            System.setProperty("vss.disablePredictionDisplayLod", "true");
            var mesh = PredictionPackedMesh.terrainRecords(decorated(8192), 64);
            assertFalse(mesh.hasTerrainDisplayLod()); assertFalse(mesh.hasTerrainOnlyDisplayLod());
            assertEquals(mesh.cacheWords().length, mesh.quads().length);
            assertSame(mesh.drawRanges(false, 31), mesh.drawRanges(false, 31, 0, 3, 0, true));
        } finally {
            if (previous == null) System.clearProperty("vss.disablePredictionDisplayLod");
            else System.setProperty("vss.disablePredictionDisplayLod", previous);
        }
    }

    private static long recount(PredictionVegetationRanges plan) throws Exception {
        var first = PredictionVegetationRanges.class.getDeclaredField("first"); first.setAccessible(true);
        var count = PredictionVegetationRanges.class.getDeclaredField("count"); count.setAccessible(true);
        var selected = PredictionVegetationRanges.class.getDeclaredField("selected"); selected.setAccessible(true);
        long bytes = 256L + ((int[])first.get(plan)).length * 4L;
        for (int[] c : (int[][])count.get(plan)) bytes += 16L + c.length * 4L;
        for (var tiers : (PredictionDrawRanges[][][])selected.get(plan)) for (var slots : tiers) if (slots != null) {
            bytes += 16L + slots.length * 4L;
            for (var r : slots) if (r != null) bytes += 64L + r.first.length * 8L;
        }
        return bytes;
    }

    private static List<Integer> featureIndices(int[] words, PredictionDrawRanges ranges) {
        var indices = new ArrayList<Integer>();
        for (int r = 0; r < ranges.first.length; r++) for (int q = ranges.first[r]; q < ranges.first[r] + ranges.count[r]; q++)
            if ((words[q * 12 + 10] & PredictionPackedMesh.FEATURE) != 0) indices.add(q);
        return indices;
    }
    private static List<Integer> sourceFeatures(PredictionDisplayGeometry.Result result, int sourceCount, PredictionDrawRanges ranges) {
        var indices = featureIndices(result.words(), ranges);
        for (int q : indices) assertTrue(q < sourceCount, "shared feature must reference the original source");
        return indices;
    }
}
