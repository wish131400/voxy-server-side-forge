package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class PredictionDisplayErrorTest {
    @Test void terrainBudgetsFollowDetailAndRemainIndependentOfPlantImportance() {
        assertEquals(3, PredictionDisplayLod.terrainPixelError("low"));
        assertEquals(2, PredictionDisplayLod.terrainPixelError("normal"));
        assertEquals(1.5, PredictionDisplayLod.terrainPixelError("high"));
        assertEquals(1, PredictionDisplayLod.terrainPixelError("extreme"));
        var terrain = new PredictionDisplayLod();
        float[] errors = {Float.NaN, 1, 2, 4};
        terrain.update(errors, 2000, 1475, 512, false, 1.5);
        assertEquals(1, terrain.tier());
        terrain.update(errors, 2000, 1475, 512, false, 2);
        assertEquals(2, terrain.tier());
        var plants = new PredictionDisplayLod();
        plants.update(PredictionVegetationRanges.ERRORS, 2000, 1475, 512, false);
        assertEquals(0, plants.tier());
        assertEquals(2, terrain.tier(), "plant importance cannot postpone terrain display detail");
        terrain.update(errors, 2000, 1475, 512, false, 1);
        assertEquals(1, terrain.tier(), "higher quality restores a finer representation immediately");
    }
    @Test void confirmedCanopyAndSnowFeaturesGetBoundedVariantsWhileUnknownModelsStayExact() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        for (boolean recognized : new boolean[]{false, true}) {
            int[] words = PredictionDisplayGeometryTest.grid(7);
            for (int q = 0; q < 1024; q++) {
                int p = q * 12, y = 32768 + 320 + (q % 2) * 4;
                words[p + 4] = words[p + 5] = y | y << 16;
                words[p + 10] |= PredictionPackedMesh.FEATURE;
            }
            int[][] retained = new int[4][5]; for (int[] r : retained) r[0] = 1024;
            boolean[] materials = new boolean[256]; materials[7] = recognized;
            var result = PredictionDisplayGeometry.build(words, new int[5], new int[]{1024,0,0,0,0}, retained, 32, materials);
            boolean moved = false;
            if (result.ranges() != null) for (int p = words.length + 10; p < result.words().length; p += 12) {
                assertNotEquals(0, result.words()[p] & PredictionPackedMesh.FEATURE);
                moved |= (result.words()[p] & PredictionPackedMesh.DISPLAY_APPROXIMATE) != 0;
            }
            assertEquals(recognized, moved, "unknown feature material cannot be approximated");
        }
    }

    @Test void eyeDepthIncludesCameraRotationAndPerspectiveScreenEdges() {
        var view = PredictionWorkView.rendered(new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1.7f,.1f,8192),
                new org.joml.Matrix4f(), Vec3.ZERO, new Vec3(0,0,-1),1475);
        assertEquals(900, view.nearestDepth(new AABB(1000,-20,-1000,1200,20,-900)), .001);
        var rotated = PredictionWorkView.rendered(new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1.7f,.1f,8192),
                new org.joml.Matrix4f().lookAlong(1,0,0,0,1,0), Vec3.ZERO, new Vec3(1,0,0),1475);
        assertEquals(1000, rotated.nearestDepth(new AABB(1000,-20,-1000,1200,20,-900)), .001);
        assertTrue(rotated.changedFrom(view));
    }
    @Test void productionPassRestoresExactGeometryForMixedCoverageNearViewAndTelescope() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var scale = PredictionRenderer.class.getDeclaredField("selectionScale"); scale.setAccessible(true);
        var scope = PredictionRenderer.class.getDeclaredField("selectionScoping"); scope.setAccessible(true);
        double oldScale = scale.getDouble(null); boolean oldScope = scope.getBoolean(null);
        try {
            scale.setDouble(null, 1475); scope.setBoolean(null, false);
            var tile = PredictionLodSeamsTest.tile(0, 0, 1, 64);
            var packed = PredictionPackedMesh.terrainRecords(PredictionDisplayGeometryTest.grid(0), 64);
            var gpu = new PredictionGpuTile(tile.key());
            var payload = PredictionGpuTile.class.getDeclaredField("packed"); payload.setAccessible(true); payload.set(gpu, packed);
            boolean[] all = new boolean[4096]; Arrays.fill(all, true);
            var full = new PredictionRenderer.Draw(tile, gpu, all, false, 31, 0, new AABB(0, 0, 0, 64, 100, 64));
            Class<?> type = Class.forName(PredictionRenderer.class.getName() + "$PreparedPass");
            Constructor<?> constructor = type.getDeclaredConstructor(boolean.class); constructor.setAccessible(true);
            Object pass = constructor.newInstance(false);
            Method prepare = type.getDeclaredMethod("prepare", List.class, long.class, Vec3.class); prepare.setAccessible(true);
            Method ranges = type.getDeclaredMethod("ranges", PredictionRenderer.Draw.class); ranges.setAccessible(true);
            var far = new Vec3(10000, 80, 0);
            prepare.invoke(pass, List.of(full), 1L, far);
            assertTrue(((PredictionDrawRanges)ranges.invoke(pass, full)).quads < 1024, "actual pass must use its uploaded coarse representation");
            boolean[] mixed = all.clone(); mixed[100] = false;
            var partial = new PredictionRenderer.Draw(tile, gpu, mixed, false, 31, 0, full.bounds());
            assertFalse(partial.completeOwnership());
            prepare.invoke(pass, List.of(partial), 1L, far);
            assertEquals(1024, ((PredictionDrawRanges)ranges.invoke(pass, partial)).quads);
            prepare.invoke(pass, List.of(full), 1L, new Vec3(32, 80, 32));
            assertEquals(1024, ((PredictionDrawRanges)ranges.invoke(pass, full)).quads);
            scope.setBoolean(null, true);
            prepare.invoke(pass, List.of(full), 1L, far);
            assertEquals(1024, ((PredictionDrawRanges)ranges.invoke(pass, full)).quads);
        } finally { scale.setDouble(null, oldScale); scope.setBoolean(null, oldScope); }
    }
    @Test void screenErrorHysteresisScopeNearAndUnavailableRepresentationsAreRespected() {
        var lod = new PredictionDisplayLod();
        float[] errors = {Float.NaN, .5f, 1, 2};
        assertTrue(lod.update(errors, 3000, 1475, 512, false)); assertEquals(3, lod.tier());
        assertFalse(lod.update(errors, 2100, 1475, 512, false));
        assertTrue(lod.update(errors, 1900, 1475, 512, false)); assertEquals(2, lod.tier());
        assertTrue(lod.update(errors, 3000, 1475, 512, true)); assertEquals(0, lod.tier());
        lod.update(errors, 3000, 1475, 512, false);
        assertTrue(lod.update(errors, 300, 1475, 512, false)); assertEquals(0, lod.tier());
        assertFalse(lod.update(new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN}, 1e9, 1475, 512, false));
        assertFalse(lod.update(errors, 3000, 0, 512, false));
    }

    @Test void steepSurfaceRemainsWhenOnlyItsHorizontalFootprintCollapses() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int[] words = PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE);
        int p = (10 * 32 + 11) * 12;
        words[p + 6] |= 7; // Distinguish this sloping ground face from the flat surroundings.
        int low = 32768 + 80 * 4, high = 32768 + 96 * 4;
        words[p + 4] = low | high << 16;
        words[p + 5] = high | low << 16;
        int[][] retained = new int[4][5];
        for (int[] r : retained) r[0] = 1024;
        var result = PredictionDisplayGeometry.build(words, new int[5], new int[]{1024,0,0,0,0}, retained, 32, new boolean[256]);
        assertNotNull(result.ranges());
        var range = result.range(1, 0, 1);
        assertNotNull(range);
        int[] expected = {12 | 12 << 16, 12 | 12 << 16, 10 | 10 << 16, 12 | 12 << 16,
                low | high << 16, high | low << 16};
        boolean found = false;
        for (int q = range.first[0], end = q + range.count[0]; q < end; q++) {
            int at = q * 12;
            if ((result.words()[at + 6] & 65535) != 7) continue;
            assertArrayEquals(expected, Arrays.copyOfRange(result.words(), at, at + 6),
                    "the snapped cliff has two valid 3D triangles despite its zero XZ footprint");
            found = true;
        }
        assertTrue(found, "a collapsed footprint must not remove a visible cliff");
    }

    @Test void terracedGroundAndSnowKeepSourceEdgeHeightsAndMeasuredError() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int[] words = PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE);
        for (int q = 0; q < 1024; q++) {
            int y = 32768 + 320 + (q % 2) * 4;
            words[q * 12 + 4] = words[q * 12 + 5] = y | y << 16;
        }
        int[] original = words.clone(), first = new int[5], counts = {1024,0,0,0,0};
        int[][] retained = new int[4][5]; for (int[] r : retained) r[0] = 1024;
        var result = PredictionDisplayGeometry.build(words, first, counts, retained, 32, new boolean[256]);
        assertNotNull(result.ranges(), "terraces must produce an actual geometric representation");
        assertArrayEquals(original, Arrays.copyOf(result.words(), original.length));
        assertTrue(result.words().length <= original.length * 1.5, "bounded extra residency");
        boolean moved = false;
        for (int tier = 1; tier <= 3; tier++) if (Float.isFinite(result.errors()[tier])) {
            assertTrue(result.errors()[tier] <= 6);
            var ranges = result.range(tier, 0, 1);
            assertTrue(ranges.quads < 1024);
            assertEquals(1, ranges.first.length, "full face group must remain contiguous");
            double area = 0;
            for (int q = ranges.first[0]; q < ranges.first[0] + ranges.count[0]; q++) {
                int p = q * 12;
                int[] x = new int[4], z = new int[4];
                for (int c = 0; c < 4; c++) {
                    x[c] = result.words()[p + c / 2] >>> ((c & 1) * 16) & 65535;
                    z[c] = result.words()[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535;
                    int y = (result.words()[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535) - 32768;
                    moved |= y != 320 && y != 324;
                    if (x[c] == 0 || x[c] == 32 || z[c] == 0 || z[c] == 32)
                        assertTrue(y == 320 || y == 324, "outer seam vertices must not move");
                }
                for (int c = 0; c < 4; c++) area += (x[c] * z[(c + 1) % 4] - x[(c + 1) % 4] * z[c]) / 2.0;
            }
            assertEquals(1024, area, "shared vertices must preserve the ground footprint");
        }
        assertTrue(moved);
    }
}
