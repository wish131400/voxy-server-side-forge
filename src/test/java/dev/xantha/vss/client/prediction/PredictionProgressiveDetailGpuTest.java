package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionProgressiveDetailGpuTest {
    @Test void splitFeatureHandoffKeepsSourcePixelsAndMatchesDirectAndMdiSubmission() throws Exception {
        var textures = new ArrayList<Integer>(); var rows = new ArrayList<String>();
        rows.add("mesh,round,mode,gpu_ns,cpu_ns,quads,commands");
        try (var context = new CompletionGpuFixture(); var shader = PredictionTerrainProgram.createBatch()) {
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            float[] sprites = new float[256 * 20 * 4];
            for (int i = 0; i < 256; i++) {
                sprites[i * 4 + 2] = sprites[i * 4 + 3] = 1;
                Arrays.fill(sprites, (256 + i) * 4, (256 + i + 1) * 4, .65f);
            }
            PredictionVegetationRenderGpuTest.texture(textures, 2, 256, 20, GL_RGBA32F, GL_RGBA, sprites);
            glClearTexImage(textures.get(3), 0, GL_RED, GL_FLOAT, new float[]{0});
            float[] clearDepth = new float[256 * 256]; Arrays.fill(clearDepth, 1);
            int depth = PredictionVegetationRenderGpuTest.texture(textures, 7, 256, 256, GL_R32F, GL_RED, clearDepth);
            var target = new TextureTarget(256, 256, true, false);
            try {
                int tested = 0;
                for (var fixtures : List.of(new FarTerrainFixture(), new FarTerrainFixture("/progressive-detail/shared-source.bin.gz"))) {
                int groupTested = 0;
                for (var m : fixtures.meshes) {
                    int[] words = m.words().clone();
                    var sourcePlan = PredictionVegetationRanges.arrange(words, m.first(), m.count(), fixtures.plants, fixtures.aquatic);
                    var display = PredictionDisplayGeometry.build(words, m.first(), m.count(), m.retained(), m.axis(), fixtures.approximate, sourcePlan);
                    if (display.sharedFeatures() == null) continue;
                    var selector = new PredictionDisplayLod(); selector.update(display.errors(), 16384, 900, 256, false, 2);
                    if (selector.tier() == 0) continue;
                    var before = sourcePlan.range(31, 0, 0);
                    var after = display.sharedFeatures().range(selector.tier(), 0, 31, 0);
                    if (after.quads >= before.quads) continue;
                    verify(m, display.words(), before, after, context, shader, target, depth, rows);
                    tested++; if (++groupTested >= 3) break;
                }
                }
                assertEquals(6, tested, "existing and newly admitted proxies must exercise the partial-feature path");
                CompletionGpuFixture.save("progressive-detail-gpu-benefit.csv", rows);
            } finally { target.destroyBuffers(); }
        } finally { for (int texture : textures) glDeleteTextures(texture); }
    }

    private static void verify(FarTerrainFixture.Mesh m, int[] words, PredictionDrawRanges before, PredictionDrawRanges after,
            CompletionGpuFixture context, PredictionTerrainProgram shader, TextureTarget target, int depth, List<String> rows) throws Exception {
        int terrain = Arrays.stream(m.count()).sum(), span = m.axis() * m.spacing();
        var constructor = PredictionPackedMesh.class.getDeclaredConstructor(int[].class, int.class, int.class, int[].class, int[].class,
                int[].class, int[].class, boolean.class, int.class, boolean.class); constructor.setAccessible(true);
        String spatial = System.getProperty("vss.disablePredictionSpatialOrder");
        PredictionPackedMesh packed;
        try {
            System.setProperty("vss.disablePredictionSpatialOrder", "true");
            int[] wf = new int[5]; Arrays.fill(wf, terrain + m.water()); wf[0] = terrain;
            packed = constructor.newInstance(words.clone(), m.axis(), terrain, m.first().clone(), m.count().clone(),
                    wf, new int[]{m.water(), 0, 0, 0, 0}, false, 0, false);
        } finally {
            if (spatial == null) System.clearProperty("vss.disablePredictionSpatialOrder"); else System.setProperty("vss.disablePredictionSpatialOrder", spatial);
        }
        packed.prepareGpuStorage();
        double top = top(words), eye = top + span * 1.5;
        var camera = new Vec3(span / 2.0, eye, span / 2.0);
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
        var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                PredictionMesh.restored(terrain * 6, 0, packed, null), new PredictionDepthBound(47, (int)Math.ceil(top)), 0, 1, span, m.spacing());
        try (var gpu = new PredictionGpuTile(key); var oldBatch = new PredictionIndirectBatch(); var nextBatch = new PredictionIndirectBatch()) {
            assertTrue(gpu.ensureMesh(tile)); assertNotNull(gpu.arenaSlice());
            boolean[] owned = new boolean[m.axis() * m.axis()]; Arrays.fill(owned, true); gpu.updateCoverage(owned);
            var draw = new PredictionRenderer.Draw(tile, gpu, owned, false, 31, 0, new AABB(0, 47, 0, span, top, span));
            var projection = new Matrix4f().perspective(1.2f, 1, .05f, 131072);
            PredictionVegetationRenderGpuTest.setup(shader, projection, camera, false, false, depth);
            shader.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), VssLodProjection.of(projection).matrix());
            shader.setMorph(null, 0); shader.setHorizon(131072); shader.setDetailDistance(0);
            shader.setRealRenderDistance(0); shader.setRealCoverage(0, 0, 0, 0);
            int program = glGetInteger(GL_CURRENT_PROGRAM); glUniform1i(glGetUniformLocation(program, "ExactCoverage"), 8);
            glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), 1e6f, 1e6f, 1e6f);
            var selections = List.of(before, after); var batches = List.of(oldBatch, nextBatch);
            for (int mask : new int[]{64, 128, 192, 0}) {
                byte[] boundary = new byte[owned.length]; Arrays.fill(boundary, (byte)mask); gpu.updateBoundaryCoverage(boundary);
                byte[][] colors = new byte[2][]; float[][] depths = new float[2][];
                for (int mode = 0; mode < 2; mode++) {
                    clear(target, shader, context); var batch = batches.get(mode); batch.begin(shader);
                    assertTrue(batch.add(draw, selections.get(mode), camera, false, true, Long.MAX_VALUE)); batch.end();
                    colors[mode] = context.pixels(); depths[mode] = context.depths();
                    clear(target, shader, context); shader.batch(false); gpu.bindTerrain(shader); gpu.bindYield(3);
                    shader.setTile((float)-camera.x, (float)-camera.y, (float)-camera.z, m.spacing(), m.axis(), true);
                    shader.setBoundaryReplacement(true); PredictionRenderer.submitRanges(selections.get(mode), packed, false);
                    assertArrayEquals(colors[mode], context.pixels(), "MDI/direct color mesh=" + m.id() + " mask=" + mask);
                    assertArrayEquals(depths[mode], context.depths(), "MDI/direct depth mesh=" + m.id() + " mask=" + mask);
                }
                if (mask == 64 || mask == 0) {
                    assertArrayEquals(colors[0], colors[1], "exact feature handoff color mesh=" + m.id());
                    assertArrayEquals(depths[0], depths[1], "exact feature handoff depth mesh=" + m.id());
                }
            }
            byte[] boundary = new byte[owned.length]; Arrays.fill(boundary, (byte)192); gpu.updateBoundaryCoverage(boundary);
            long[][] times = new long[2][61];
            for (int round = -32; round < 61; round++) for (int order = 0; order < 2; order++) {
                int mode = Math.floorMod(round + order, 2); clear(target, shader, context); glFinish();
                long start = System.nanoTime(); glBeginQuery(GL_TIME_ELAPSED, context.timer);
                var batch = batches.get(mode); batch.begin(shader);
                if (batch.replayPass(selections.get(mode), 1, true, camera, Long.MAX_VALUE) < 0)
                    for (int repeat = 0; repeat < 32; repeat++) assertTrue(batch.add(draw, selections.get(mode), camera, false, true, Long.MAX_VALUE));
                batch.end(); glEndQuery(GL_TIME_ELAPSED); long cpu = System.nanoTime() - start;
                long ns = glGetQueryObjectui64(context.timer, GL_QUERY_RESULT);
                if (round >= 0) { times[mode][round] = ns; rows.add(m.id() + "," + round + "," + mode + "," + ns + "," + cpu + "," + selections.get(mode).quads * 32L + "," + selections.get(mode).first.length * 32L); }
            }
            System.out.printf(Locale.ROOT, "PROGRESSIVE_GPU mesh=%d quads=%d->%d commands=%d->%d GPU_ms=%.6f->%.6f%n",
                    m.id(), before.quads * 32L, after.quads * 32L, before.first.length * 32L, after.first.length * 32L,
                    CompletionGpuFixture.median(times[0]) / 1e6, CompletionGpuFixture.median(times[1]) / 1e6);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }
    private static void clear(TextureTarget target, PredictionTerrainProgram shader, CompletionGpuFixture context) {
        target.bindWrite(true); shader.use(); glBindVertexArray(context.vao); glViewport(0, 0, 256, 256);
        glDisable(GL_BLEND); glDisable(GL_CULL_FACE); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GEQUAL); glDepthMask(true);
        glClearDepth(0); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }
    private static double top(int[] words) {
        double top = -1e9;
        for (int p = 0; p < words.length; p += 12) {
            int flags = words[p + 6]; boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0
                    || (flags & (3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT)) != 0 && (flags & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0;
            for (int c = 0; c < 4; c++) top = Math.max(top, ((words[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535) - 32768) * (fine ? 1.0 / 16 : .25));
        }
        return top;
    }
}
