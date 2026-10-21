package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionRetainedGeometryTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void retainedAdmissionKeepsTheDiskCodecLimitSeparate() {
        assertEquals(16L * 1024 * 1024, PredictionMeshCodec.MAX_BYTES);
        assertEquals(64L * 1024 * 1024, PredictionMemoryBudget.RETAINED_MESH_BYTES);
        assertEquals(192L * 1024 * 1024, PredictionMemoryBudget.BUILD_BYTES);
    }

    @Test void projectionMatchesActualCompactAndExpandedStreams() throws Exception {
        for (boolean terrainCompact : new boolean[]{false, true}) {
            for (boolean waterCompact : new boolean[]{false, true}) {
                var mesh = quads(3, 2, terrainCompact, waterCompact);
                assertEquals(mesh.retainedHeapBytes(), PredictionQuadMesh.projectedRetainedBytes(
                        1, 3, 2, terrainCompact, waterCompact));
            }
        }
    }

    @Test void projectionRetainsTheExactBoundaryAndRejectsInvalidOrOverflowedInputs() {
        long limit = PredictionMemoryBudget.RETAINED_MESH_BYTES;
        long variable = limit - 768L - 10L * 64 * 64;
        int water = 0;
        while ((variable - 84L * water) % 89L != 0) water++;
        int terrain = Math.toIntExact((variable - 84L * water) / 89L);
        assertEquals(limit, PredictionQuadMesh.projectedRetainedBytes(64, terrain, water, true, true));
        assertTrue(PredictionQuadMesh.projectedRetainedBytes(64, terrain + 1, water, true, true) > limit);
        assertEquals(Long.MAX_VALUE, PredictionQuadMesh.projectedRetainedBytes(0, 0, 0, true, true));
        assertEquals(Long.MAX_VALUE, PredictionQuadMesh.projectedRetainedBytes(-1, 0, 0, true, true));
        assertEquals(Long.MAX_VALUE, PredictionQuadMesh.projectedRetainedBytes(1, -1, 0, true, true));
        assertEquals(Long.MAX_VALUE, PredictionQuadMesh.projectedRetainedBytes(1, 0, -1, true, true));
        assertEquals(Long.MAX_VALUE, PredictionQuadMesh.projectedRetainedBytes(Integer.MAX_VALUE, 0, 0, true, true));
    }

    @Test void detachedSegmentsAdmitExactly64MiBAndRejectTheNextByte() throws Exception {
        var type = Class.forName(PredictionMeshBuilder.class.getName() + "$MeshSegments");
        var constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        var bytes = type.getDeclaredField("bytes");
        bytes.setAccessible(true);
        var add = type.getDeclaredMethod("add", PredictionMesh.class);
        add.setAccessible(true);
        var part = quads(1, 0, true, true);
        var mesh = PredictionMesh.fromQuads(part, 6, 0, 1);
        var exact = constructor.newInstance(1);
        bytes.setLong(exact, PredictionMemoryBudget.RETAINED_MESH_BYTES - part.retainedHeapBytes());
        assertDoesNotThrow(() -> add.invoke(exact, mesh));
        assertEquals(PredictionMemoryBudget.RETAINED_MESH_BYTES, bytes.getLong(exact));
        var above = constructor.newInstance(1);
        bytes.setLong(above, PredictionMemoryBudget.RETAINED_MESH_BYTES - part.retainedHeapBytes() + 1);
        var failure = assertThrows(InvocationTargetException.class, () -> add.invoke(above, mesh));
        assertInstanceOf(PredictionMemoryBudget.MeshLimitException.class, failure.getCause());
    }

    @Test void combinedGuardRejectsMixedNormalExpansionBeforeAllocation() throws Exception {
        for (boolean water : new boolean[]{false, true}) {
            var compact = quads(water ? 0 : 600_000, water ? 600_000 : 0, true, true);
            var smooth = quads(water ? 0 : 1, water ? 1 : 0, water, !water);
            assertTrue(compact.retainedHeapBytes() + smooth.retainedHeapBytes()
                    < PredictionMemoryBudget.RETAINED_MESH_BYTES);
            assertThrows(PredictionMemoryBudget.MeshLimitException.class,
                    () -> PredictionQuadMesh.combine(List.of(compact, smooth)));
        }
    }

    @Test void combinedMixedNormalsRemainLosslessForTerrainAndWater() throws Exception {
        var first = quads(1, 1, true, false);
        var second = quads(1, 1, false, true);
        var combined = PredictionQuadMesh.combine(List.of(first, second));
        assertEquals(2, combined.quadCount());
        assertEquals(2, combined.waterQuadCount());
        assertEquals(PredictionQuadMesh.projectedRetainedBytes(1, 2, 2, false, false), combined.retainedHeapBytes());
        for (int q = 0; q < 2; q++) {
            var part = q == 0 ? first : second;
            for (int corner = 0; corner < 4; corner++) {
                assertEquals(part.normalX(0, corner), combined.normalX(q, corner));
                assertEquals(part.normalY(0, corner), combined.normalY(q, corner));
                assertEquals(part.normalZ(0, corner), combined.normalZ(q, corner));
                assertEquals(part.waterNormalX(0, corner), combined.waterNormalX(q, corner));
                assertEquals(part.waterNormalY(0, corner), combined.waterNormalY(q, corner));
                assertEquals(part.waterNormalZ(0, corner), combined.waterNormalZ(q, corner));
            }
        }
    }

    @Test void combinedLiveGeometryCanExceedTheUnchangedDiskRecordLimit() throws Exception {
        var combined = PredictionQuadMesh.combine(List.of(
                quads(200_000, 0, true, true), quads(1, 0, true, true)));
        assertEquals(200_001, combined.quadCount());
        assertTrue(combined.retainedHeapBytes() > PredictionMeshCodec.MAX_BYTES);
        assertTrue(combined.retainedHeapBytes() < PredictionMemoryBudget.RETAINED_MESH_BYTES);
    }

    @Test void combineKeepsItsSinglePartAndTopologyContracts() throws Exception {
        var part = quads(1, 0, true, true);
        assertSame(part, PredictionQuadMesh.combine(List.of(part)));
        assertThrows(IllegalArgumentException.class, () -> PredictionQuadMesh.combine(List.of()));
        var axis = PredictionQuadMesh.class.getDeclaredField("cellAxis");
        axis.setAccessible(true);
        var invalid = quads(1, 0, true, true);
        axis.setInt(invalid, Integer.MAX_VALUE);
        assertThrows(PredictionMemoryBudget.MeshLimitException.class,
                () -> PredictionQuadMesh.combine(List.of(invalid, invalid)));
        assertThrows(IllegalArgumentException.class,
                () -> PredictionQuadMesh.combine(List.of(part, invalid)));
    }

    @Test void idleRefinementKeeps256MiBBeyondThe192MiBPromise() {
        long mib = PredictionMemoryBudget.MIB;
        var budget = new PredictionMemoryBudget(448L * mib, 0, () -> Long.MAX_VALUE, () -> 1);
        assertTrue(budget.allowsIdleRefinement());
        assertEquals(2, budget.availableBuildSlots());
        try (var claim = budget.tryReserve(1)) {
            assertNotNull(claim);
            assertFalse(budget.allowsIdleRefinement());
        }
        assertTrue(budget.allowsIdleRefinement());
        try (var claim = budget.tryReserveBuild()) {
            assertNotNull(claim);
            assertEquals(192L * mib, budget.usedBytes());
            assertEquals(1, budget.availableBuildSlots());
        }
        assertEquals(0, budget.usedBytes());
    }

    private static PredictionQuadMesh quads(int terrain, int water, boolean compact, boolean waterCompact)
            throws Exception {
        var constructor = PredictionQuadMesh.class.getDeclaredConstructor(float[].class, float[].class,
                int[].class, int[].class, byte[].class, int[].class, short[].class, short[].class,
                int.class, float[].class, float[].class, int[].class, int[].class,
                short[].class, short[].class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new float[terrain * 12], normals(terrain, compact),
                new int[terrain * 4], new int[terrain], new byte[terrain], new int[terrain],
                new short[terrain], new short[terrain], terrain,
                new float[water * 12], normals(water, waterCompact), new int[water * 4], new int[water],
                new short[water], new short[water], water, 1);
    }

    private static float[] normals(int quads, boolean compact) {
        float[] values = new float[quads * (compact ? 3 : 12)];
        for (int q = 0; q < quads; q++) {
            if (compact) values[q * 3 + 1] = 1;
            else for (int corner = 0; corner < 4; corner++) values[q * 12 + corner * 3 + 1] = corner + 1;
        }
        return values;
    }
}
