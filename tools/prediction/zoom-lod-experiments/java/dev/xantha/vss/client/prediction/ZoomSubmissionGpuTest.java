package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exact preceding worktree pass versus pixel selection; repeated fixtures, not game FPS. */
@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class ZoomSubmissionGpuTest {
    @Test void actualScopedProjectionSelectsReducedRangesAndZoomInRestoresThem() throws Exception {
        var csv = new ArrayList<String>();
        csv.add("content,scenario,round,mode,gpu_ns,cpu_ns,mdi_quads,mdi_commands,display_tier,vegetation_tier");
        var textures = new ArrayList<Integer>();
        try (var context = new CompletionGpuFixture(); var state = new PredictionZoomLodTest.State();
             var shader = PredictionTerrainProgram.createBatch()) {
            VssLodSpriteTable.close();
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            glClearTexImage(textures.get(3), 0, GL_RED, GL_FLOAT, new float[]{0});
            float[] depthValues = new float[256 * 256]; Arrays.fill(depthValues, 1);
            int depth = PredictionVegetationRenderGpuTest.texture(textures, 7, 256, 256, GL_R32F, GL_RED, depthValues);
            var target = new TextureTarget(256, 256, true, false);
            try (var contents = PredictionVegetationMaterialTest.contents("block/vss_zoom_grass")) {
                int row = PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents), Blocks.GRASS);
                for (String content : List.of("terrain", "plants")) {
                    int[] words = content.equals("terrain") ? PredictionZoomLodTest.words() : PredictionVegetationLodGpuTest.plants(row);
                    for (String scenario : List.of("normal", "zoom-near", "zoom-far")) {
                        var camera = scenario.equals("zoom-far") ? new Vec3(64, 7280, 9664) : new Vec3(64, 1040, 1344);
                        var projection = PredictionZoomLodTest.projection(scenario.equals("normal") ? 70 : 8.75);
                        state.view(projection, camera, !scenario.equals("normal"));
                        compare(content, scenario, words, camera, projection, context, shader, target, depth, state, csv);
                    }
                }
            } finally { target.destroyBuffers(); }
            for (int texture : textures) glDeleteTextures(texture);
            glFinish(); PredictionTerrainArena.SHARED.close(); VssLodSpriteTable.close();
            assertEquals(GL_NO_ERROR, glGetError());
        }
        CompletionGpuFixture.save("zoom-submission.csv", csv);
    }

    private static void compare(String content, String scenario, int[] words, Vec3 camera, Matrix4f projection,
                                CompletionGpuFixture context, PredictionTerrainProgram shader, TextureTarget target,
                                int depth, PredictionZoomLodTest.State state, List<String> csv) throws Exception {
        var tiles = new ArrayList<PredictionGpuTile>();
        var draws = new ArrayList<PredictionRenderer.Draw>();
        try (var oldBatch = new PredictionIndirectBatch(); var nextBatch = new PredictionIndirectBatch()) {
            for (int mode = 0; mode < 2; mode++) {
                var packed = PredictionPackedMesh.terrainRecords(words.clone(), 128); packed.prepareGpuStorage();
                var draw = PredictionZoomLodTest.draw(packed);
                // The CPU fixture installs a payload for reflection-only tests; real upload needs an empty GPU tile.
                PredictionPreparedPassLocalTest.payload(draw.gpu(), null);
                assertTrue(draw.gpu().ensureMesh(draw.tile())); assertNotNull(draw.gpu().arenaSlice());
                draw.gpu().updateCoverage(draw.allowed());
                byte[] boundary = new byte[draw.allowed().length]; Arrays.fill(boundary, (byte)192);
                draw.gpu().updateBoundaryCoverage(boundary);
                draws.add(draw); tiles.add(draw.gpu());
            }
            PredictionPreparedPassLocalTest.coverage().clear(); ZoomBaselinePass.synchronizeRenderer();
            var before = new ZoomBaselinePass.Pass(false);
            var after = new PredictionPreparedPassLocalTest.Pass(false);
            Method oldPrepare = ZoomBaselinePass.Pass.class.getDeclaredMethod("prepare", List.class, long.class, Vec3.class);
            oldPrepare.setAccessible(true);
            var version = ZoomBaselinePass.Pass.class.getDeclaredField("revision"); version.setAccessible(true);
            var nextVersion = after.type.getDeclaredField("revision"); nextVersion.setAccessible(true);
            var oldRanges = ZoomBaselinePass.Pass.class.getDeclaredMethod("ranges", PredictionRenderer.Draw.class);
            oldRanges.setAccessible(true);
            var prepares = new Method[]{oldPrepare, after.prepare};
            var selections = new Method[]{oldRanges, after.ranges};
            var versions = new java.lang.reflect.Field[]{version, nextVersion};
            var passes = new Object[]{before, after.value};
            var direction = new Vec3(64 - camera.x, 80 - camera.y, 64 - camera.z).normalize();
            var model = new Matrix4f().lookAlong((float)direction.x, (float)direction.y, (float)direction.z, 0, 1, 0);
            PredictionVegetationRenderGpuTest.setup(shader, projection, camera, false, false, depth);
            shader.setCamera(model, VssLodProjection.of(projection).matrix());
            shader.setHorizon(32768); shader.setDetailDistance(0); shader.setRealRenderDistance(0);
            int program = glGetInteger(GL_CURRENT_PROGRAM);
            glUniform1i(glGetUniformLocation(program, "ExactCoverage"), 8);
            glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), 1e6f, 1e6f, 1e6f);
            var batches = List.of(oldBatch, nextBatch);
            var lists = List.of(List.of(draws.get(0)), List.of(draws.get(1)));
            long[][] cpu = new long[2][81], gpu = new long[2][81];
            long[] quads = new long[2], commands = new long[2];
            byte[][] colors = new byte[2][]; float[][] depths = new float[2][];
            for (int round = -24; round < 81; round++) for (int order = 0; order < 2; order++) {
                int mode = Math.floorMod(round + order, 2);
                target.bindWrite(true); shader.use(); glBindVertexArray(context.vao);
                glViewport(0, 0, 256, 256); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
                glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GEQUAL); glDepthMask(true);
                glClearDepth(0); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); glFinish();
                long started = System.nanoTime(); glBeginQuery(GL_TIME_ELAPSED, context.timer);
                prepares[mode].invoke(passes[mode], lists.get(mode), 1L, camera);
                var draw = draws.get(mode);
                var ranges = (PredictionDrawRanges)selections[mode].invoke(passes[mode], draw);
                assertNotNull(ranges);
                var batch = batches.get(mode); batch.begin(shader, true);
                long replay = batch.replayPass(passes[mode], versions[mode].getLong(passes[mode]), true, camera, Long.MAX_VALUE);
                if (replay < 0) for (int repeat = 0; repeat < 64; repeat++)
                    assertTrue(batch.add(draw, ranges, camera, false, true, Long.MAX_VALUE));
                batch.end(); glEndQuery(GL_TIME_ELAPSED);
                long elapsedCpu = System.nanoTime() - started;
                long elapsedGpu = glGetQueryObjectui64(context.timer, GL_QUERY_RESULT);
                quads[mode] = counter(batch, "lastMdiQuads"); commands[mode] = counter(batch, "lastMdiCommands");
                assertEquals(ranges.quads * 64L, quads[mode]);
                if (round >= 0) {
                    cpu[mode][round] = elapsedCpu; gpu[mode][round] = elapsedGpu;
                    csv.add(content + "," + scenario + "," + round + "," + mode + "," + elapsedGpu + "," + elapsedCpu
                            + "," + quads[mode] + "," + commands[mode] + "," + draw.gpu().packed().displayLod.tier()
                            + "," + draw.gpu().packed().vegetationLod.tier());
                }
                if (round == 0) { colors[mode] = context.pixels(); depths[mode] = context.depths(); }
            }
            for (byte[] pixels : colors) {
                int visible = 0; for (int p = 0; p < pixels.length; p += 4)
                    if (pixels[p] != 0 || pixels[p + 1] != 0 || pixels[p + 2] != 0) visible++;
                assertTrue(visible > 0, "comparison cannot be blank: " + content + "/" + scenario);
            }
            if (!scenario.equals("zoom-far")) {
                assertEquals(quads[0], quads[1]); assertArrayEquals(colors[0], colors[1]); assertArrayEquals(depths[0], depths[1]);
            } else {
                assertTrue(quads[1] < quads[0]);
                var packed = draws.get(1).gpu().packed();
                double bound = screenBound(packed, content, camera, projection, model, draws.get(1).bounds());
                double limit = content.equals("terrain") ? PredictionDisplayLod.terrainPixelError("normal") : PredictionDisplayLod.PIXEL_ERROR;
                assertTrue(bound <= limit, "selected representation exceeded screen error: " + bound);
                System.out.printf(Locale.ROOT, "ZOOM_ERROR content=%s projected=%.6f limit=%.6f%n", content, bound, limit);
            }
            // Every selected range still agrees exactly with its direct rendering path.
            var draw = draws.get(1); var ranges = after.ranges(draw);
            target.bindWrite(true); shader.use(); shader.batch(false); draw.gpu().bindTerrain(shader); draw.gpu().bindYield(3);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            shader.setTile((float)-camera.x, (float)-camera.y, (float)-camera.z, 1, 128, false);
            PredictionRenderer.submitRanges(ranges, draw.gpu().packed(), false);
            assertArrayEquals(colors[1], context.pixels()); assertArrayEquals(depths[1], context.depths());
            System.out.printf(Locale.ROOT, "ZOOM_AB content=%s scenario=%s quads=%d->%d commands=%d->%d CPU_ms=%.6f->%.6f GPU_ms=%.6f->%.6f%n",
                    content, scenario, quads[0], quads[1], commands[0], commands[1], CompletionGpuFixture.median(cpu[0]) / 1e6,
                    CompletionGpuFixture.median(cpu[1]) / 1e6, CompletionGpuFixture.median(gpu[0]) / 1e6, CompletionGpuFixture.median(gpu[1]) / 1e6);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { for (var tile : tiles) tile.close(); }
    }
    private static long counter(PredictionIndirectBatch batch, String name) throws Exception {
        var field = PredictionIndirectBatch.class.getDeclaredField(name); field.setAccessible(true); return field.getLong(batch);
    }
    private static double screenBound(PredictionPackedMesh packed, String content, Vec3 camera, Matrix4f projection,
                                      Matrix4f model, net.minecraft.world.phys.AABB box) {
        var direction = new Vec3(64 - camera.x, 80 - camera.y, 64 - camera.z).normalize();
        double eye = PredictionWorkView.rendered(projection, model, camera, direction).nearestDepth(box);
        double x = Math.max(Math.abs(box.minX - camera.x), Math.abs(box.maxX - camera.x));
        double y = Math.max(Math.abs(box.minY - camera.y), Math.abs(box.maxY - camera.y));
        double z = Math.max(Math.abs(box.minZ - camera.z), Math.abs(box.maxZ - camera.z));
        double scale = VssLodProjection.selectionScale(projection, 256) * (1 + Math.sqrt(x*x + y*y + z*z) / eye);
        double error = content.equals("terrain") ? packed.displayErrors()[packed.displayLod.tier()]
                : PredictionVegetationRanges.ERRORS[packed.vegetationLod.tier()];
        return error * scale / (eye - error);
    }
}
