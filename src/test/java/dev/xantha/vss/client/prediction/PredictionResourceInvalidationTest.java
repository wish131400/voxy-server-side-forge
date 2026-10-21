package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Exercises invalidation while the visible list retains its identity. */
class PredictionResourceInvalidationTest {
    @Test void diskCoverageCannotDeleteDrawsAndMorphStillInvalidatesCachedSubmission() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        var coverage = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        var packed = PredictionPackedMesh.terrainRecords(quad(), 64);
        var draw = draw(0, packed);
        var draws = List.of(draw);
        var pass = new Pass(false);
        var camera = new Vec3(4, 68, 4);
        var index = new PredictionExactCoverageIndex();
        for (int z = 0; z <= 6; z++) for (int x = 0; x <= 6; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        var covered = index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS);
        coverage.clear();
        try {
            coverage.update(null, camera, 480);
            pass.prepare(draws, 1, camera);
            assertEquals(1, pass.ranges(draw).quads);
            coverage.update(covered, camera, 480);
            pass.prepare(draws, 2, camera);
            assertEquals(1, pass.ranges(draw).quads, "disk/upload metadata is not frame visibility");
            coverage.update(null, camera, 480);
            pass.prepare(draws, 3, camera);
            assertEquals(1, pass.ranges(draw).quads, "withdrawal restores the quad in the same preparation");
            coverage.update(covered, camera, 480);
            pass.prepare(draws, 4, camera);
            assertEquals(1, pass.ranges(draw).quads);
            packed.morph(new float[]{0}, -64, 1000);
            pass.prepare(draws, 4, camera);
            assertEquals(1, pass.ranges(draw).quads, "morph invalidates without changing residency or list identity");
        } finally {
            coverage.clear();
        }
    }

    @Test void aPreviouslyEmptyPayloadAppearsAndClearingReleasesItsReference() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        var draw = draw(0, null);
        var draws = List.of(draw);
        var pass = new Pass(false);
        var camera = new Vec3(0, 80, 0);
        pass.prepare(draws, 1, camera);
        assertNull(pass.ranges(draw));
        field(PredictionGpuTile.class, "packed").set(draw.gpu(), PredictionPackedMesh.terrainRecords(quad(), 64));
        pass.prepare(draws, 2, camera);
        assertEquals(1, pass.ranges(draw).quads, "null sources must participate in the geometry comparison");
        method(pass.type, "clear").invoke(pass.instance);
        assertTrue(((List<?>) field(pass.type, "packedSources").get(pass.instance)).isEmpty());
    }

    private static int[] quad() {
        int x = 33, z = 33, y = 32768 + 64 * 4;
        return new int[]{x | (x + 1) << 16, x | (x + 1) << 16, z | z << 16, z + 1 | (z + 1) << 16,
                y | y << 16, y | y << 16, 0, 0xffffff, 0, 0xffffff, 0xffffff, 0xffffff};
    }

    private static PredictionRenderer.Draw draw(int x, PredictionPackedMesh packed) throws Exception {
        var tile = PredictionLodSeamsTest.tile(x, 0, 1, 64);
        var gpu = new PredictionGpuTile(tile.key());
        field(PredictionGpuTile.class, "packed").set(gpu, packed);
        return new PredictionRenderer.Draw(tile, gpu, new boolean[4096], false,
                VssLodFaceGroup.ALL, 0, new AABB(x, 60, 0, x + 64, 80, 64));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static final class Pass {
        final Class<?> type = Class.forName(PredictionRenderer.class.getName() + "$PreparedPass");
        final Object instance;
        final Method prepare = method(type, "prepare", List.class, long.class, Vec3.class);
        final Method ranges = method(type, "ranges", PredictionRenderer.Draw.class);

        Pass(boolean water) throws Exception {
            Constructor<?> constructor = type.getDeclaredConstructor(boolean.class);
            constructor.setAccessible(true);
            instance = constructor.newInstance(water);
        }

        void prepare(List<PredictionRenderer.Draw> draws, long revision, Vec3 camera) throws Exception {
            prepare.invoke(instance, draws, revision, camera);
        }

        PredictionDrawRanges ranges(PredictionRenderer.Draw draw) throws Exception {
            return (PredictionDrawRanges) ranges.invoke(instance, draw);
        }
    }
}
