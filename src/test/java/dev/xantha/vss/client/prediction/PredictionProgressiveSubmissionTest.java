package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;

class PredictionProgressiveSubmissionTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void boundedHistoryOnlyInvalidatesMembersAndFallsBackAfterOverflow() {
        var history = new PredictionSubmissionChanges(); var members = new LongOpenHashSet(new long[]{17, 22});
        long before = history.epoch(); history.publish(9);
        assertFalse(history.affects(before, history.epoch(), members));
        history.publish(17); assertTrue(history.affects(before, history.epoch(), members));
        assertFalse(history.affects(history.epoch(), history.epoch(), members));
        assertTrue(history.affects(-1, history.epoch(), members));
        assertTrue(history.affects(history.epoch(), history.epoch() + 1, members));
        before = history.epoch();
        for (int i = 0; i < PredictionSubmissionChanges.CAPACITY; i++) history.publish(9);
        assertFalse(history.affects(before, history.epoch(), members));
        history.publish(9); assertTrue(history.affects(before, history.epoch(), members));
    }

    @Test void offscreenMeshAndGpuUpdatesKeepPreparedPassReusableWhileLocalChangesInvalidate() throws Exception {
        var pass = new PredictionPreparedPassLocalTest.Pass(false);
        var mesh = PredictionPreparedPassLocalTest.mesh(); var draw = PredictionPreparedPassLocalTest.draw(0, 0, mesh);
        var draws = List.of(draw); var camera = new Vec3(32, 80, 32); pass.prepare(draws, 1, camera);
        var ranges = pass.ranges(draw); long misses = pass.counter("misses"), builds = pass.counter("rangeBuilds");
        var remote = PredictionPackedMesh.seamRecords(new int[12], 1); remote.trackSubmission();
        var remoteGpu = new PredictionGpuTile(draw.tile().key());
        var changed = PredictionGpuTile.class.getDeclaredMethod("submissionChanged"); changed.setAccessible(true);
        for (int i = 0; i < 50; i++) {
            remote.morph(null, 0, 0); changed.invoke(remoteGpu);
            pass.prepare(draws, 2 + i, camera);
            assertSame(ranges, pass.ranges(draw)); assertEquals(misses, pass.counter("misses"));
            assertEquals(builds, pass.counter("rangeBuilds"));
        }
        changed.invoke(draw.gpu()); pass.prepare(draws, 52, camera);
        assertEquals(misses + 1, pass.counter("misses")); assertEquals(builds, pass.counter("rangeBuilds"));
        mesh.morph(new float[]{0}, -64, 1000); pass.prepare(draws, 52, camera);
        assertEquals(misses + 2, pass.counter("misses")); assertEquals(builds + 1, pass.counter("rangeBuilds"));
    }

    @Test void localSelectionTokenTracksCameraCoverageWithdrawalMeshOwnerAndClear() {
        int[] words = PredictionMeshFixture.decorated(0);
        var mesh = PredictionPackedMesh.seamRecords(words, 64); var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        var selected = new LegacyResidentCoverage.Selection(); var camera = new Vec3(4, 80, 4);
        var index = new PredictionExactCoverageIndex();
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        state.update(index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS), camera, 480);
        assertVersion(state, mesh, selected, false);
        index.remove(Level.OVERWORLD, 2, 2);
        state.update(index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS), camera, 480);
        assertVersion(state, mesh, selected, false);
        state.update(index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS), new Vec3(4, 1000, 4), 480);
        assertVersion(state, mesh, selected, false);
        mesh.morph(new float[]{0}, -64, 1000); assertVersion(state, mesh, selected, false);
        assertVersion(state, mesh, selected, true);
        state.clear(); assertVersion(state, mesh, selected, false);
        assertVersion(new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()), mesh, selected, false);
        assertVersion(state, PredictionPackedMesh.seamRecords(words, 64), selected, false);
    }

    private static void assertVersion(LegacyResidentCoverage state, PredictionPackedMesh mesh,
                                      LegacyResidentCoverage.Selection selected, boolean water) {
        long expected = state.selectionVersion(mesh, 0, 0, water);
        assertEquals(expected, state.selectionVersion(mesh, 0, 0, water, selected));
        assertEquals(expected, state.selectionVersion(mesh, 0, 0, water, selected));
    }

    @Test void outsideHorizonRejectsBeforeAllocatingAndTestingFrustum() throws Exception {
        class CountingFrustum extends Frustum {
            int tested;
            CountingFrustum() { super(new Matrix4f(), new Matrix4f()); }
            @Override public boolean isVisible(AABB box) { tested++; return true; }
        }
        var frustum = new CountingFrustum();
        var method = PredictionRenderer.class.getDeclaredMethod("tileWithinHorizon", PredictionTileManager.PredictionTile.class, Vec3.class, Frustum.class);
        method.setAccessible(true);
        var tile = PredictionLodSeamsTest.tile(0, 0, 1, 64);
        assertFalse((boolean)method.invoke(null, tile, new Vec3(30_000_000, 80, 30_000_000), frustum));
        assertEquals(0, frustum.tested);
        assertTrue((boolean)method.invoke(null, tile, new Vec3(32, 80, 32), frustum));
        assertEquals(1, frustum.tested);
    }

    @Test void squaredWorldDistanceMatchesHypotForNegativeAndWorldBoundaryCoordinates() {
        var random = new Random(63017);
        for (int i = 0; i < 10000; i++) {
            double x = random.nextDouble(-30_000_000, 30_000_000), z = random.nextDouble(-30_000_000, 30_000_000);
            int bx = random.nextInt(60_000_001) - 30_000_000, bz = random.nextInt(60_000_001) - 30_000_000;
            int span = 64 << random.nextInt(10);
            double dx = Math.max(0, Math.max(bx - x, x - ((double)bx + span)));
            double dz = Math.max(0, Math.max(bz - z, z - ((double)bz + span)));
            assertEquals(Math.hypot(dx, dz), PredictionAquaticLod.distance(x, z, bx, bz, span), 1e-7);
        }
    }
}
