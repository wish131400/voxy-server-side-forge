package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Covers generated refinement carrying a retained, already finished morph field. */
class PredictionZoomReturnTest {
    @Test void animationDeadlineInvalidatesAStationaryPlanExactlyOnce() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try (var state = new PredictionZoomLodTest.State()) {
            var packed = PredictionZoomLodTest.terrain();
            packed.morph(new float[129 * 129], 80, 81);
            var original = PredictionZoomLodTest.draw(packed);
            var draw = new PredictionRenderer.Draw(original.tile(), original.gpu(), original.allowed(), false,
                    original.faces(), 1, original.bounds());
            var draws = List.of(draw);
            var pass = new PredictionPreparedPassLocalTest.Pass(false);
            var timed = pass.method("prepare", List.class, long.class, Vec3.class, long.class);
            var camera = new Vec3(64, 1040, 1344);
            state.view(PredictionZoomLodTest.projection(70), camera, false);
            long start = 1_000_000_000L;
            uploadedAt(draw.gpu(), start);
            timed.invoke(pass.value, draws, 1L, camera, start);
            assertEquals(16384, pass.ranges(draw).quads, "active transitions keep original vertex positions");
            long builds = pass.counter("rangeBuilds");
            timed.invoke(pass.value, draws, 1L, camera, start + PredictionMorph.DURATION_NANOS - 1);
            assertEquals(builds, pass.counter("rangeBuilds"));
            assertEquals(16384, pass.ranges(draw).quads);
            timed.invoke(pass.value, draws, 1L, camera, start + PredictionMorph.DURATION_NANOS);
            assertTrue(pass.ranges(draw).quads < 16384,
                    "an unchanged camera, projection and snapshot still notice that morph has finished");
            assertEquals(builds + 1, pass.counter("rangeBuilds"));
            timed.invoke(pass.value, draws, 1L, camera, start + 2 * PredictionMorph.DURATION_NANOS);
            assertEquals(builds + 1, pass.counter("rangeBuilds"), "finished animations cannot cause a per-frame rebuild");
        }
    }

    @Test void completedRefinementReturnsToPixelLodAfterZoomOut() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try (var state = new PredictionZoomLodTest.State()) {
            var packed = PredictionZoomLodTest.terrain();
            packed.morph(new float[129 * 129], 80, 81);
            var draw = PredictionZoomLodTest.draw(packed);
            var pass = new PredictionPreparedPassLocalTest.Pass(false);
            var camera = new Vec3(64, 1040, 1344);
            uploadedAt(draw.gpu(), System.nanoTime() - 2 * PredictionMorph.DURATION_NANOS);
            state.view(PredictionZoomLodTest.projection(8.75), camera, true);
            pass.prepare(List.of(draw), 1, camera);
            assertEquals(16384, pass.ranges(draw).quads);
            state.view(PredictionZoomLodTest.projection(70), camera, false);
            pass.prepare(List.of(draw), 1, camera);
            assertTrue(pass.ranges(draw).quads < 16384,
                    "retained transition data must not permanently lock the refined payload at source detail");
            assertNotNull(packed.morph(), "selection must leave immutable upload and cached geometry intact");
            assertSame(packed, draw.gpu().packed());
        }
    }

    static void uploadedAt(PredictionGpuTile gpu, long value) throws Exception {
        var field = PredictionGpuTile.class.getDeclaredField("uploadedAt");
        field.setAccessible(true); field.setLong(gpu, value);
    }
}
