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

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class FarTerrainSubmissionGpuTest {
    @Test void compareActualHistoricalMeshesUsingTheProductionUploadShaderAndMdiPath() throws Exception {
        var textures = new ArrayList<Integer>(); var rows = new ArrayList<String>();
        rows.add("mesh,view,round,mode,gpu_ns,cpu_ns,quads,commands,stored_bytes");
        try (var context = new CompletionGpuFixture(); var shader = PredictionTerrainProgram.createBatch()) {
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            // Repeatable replacement atlas. Material IDs and geometry are from the real capture;
            // the captured pack's textures were not retained, so this is a component test.
            float[] sprites = new float[256 * 20 * 4];
            for (int i = 0; i < 256; i++) { sprites[i * 4 + 2] = sprites[i * 4 + 3] = 1; Arrays.fill(sprites,(256 + i) * 4,(256 + i + 1) * 4,.65f); }
            PredictionVegetationRenderGpuTest.texture(textures,2,256,20,GL_RGBA32F,GL_RGBA,sprites);
            glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
            float[] clear = new float[256 * 256]; Arrays.fill(clear,1);
            int depth = PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,clear);
            var target = new TextureTarget(256,256,true,false);
            try {
                var fixtures = new FarTerrainFixture();
                for (var mesh : fixtures.meshes) {
                    var old = fixtures.before(mesh); var next = fixtures.after(mesh);
                    for (String view : new String[]{"near", "4096", "8192"})
                        compare(mesh,old,next,view,context,shader,target,depth,rows);
                }
            } finally { target.destroyBuffers(); }
            glFinish(); PredictionTerrainArena.SHARED.close(); PredictionTerrainArena.WATER.close();
            for (int texture : textures) glDeleteTextures(texture);
        }
        CompletionGpuFixture.save("far-terrain-submission.csv",rows);
    }
    private static void compare(FarTerrainFixture.Mesh m, FarBaselineDisplayGeometry.Result old,
            PredictionDisplayGeometry.Result next, String view, CompletionGpuFixture context,
            PredictionTerrainProgram shader, TextureTarget target, int depth, List<String> rows) throws Exception {
        int span = m.axis() * m.spacing(); int terrain = Arrays.stream(m.count()).sum();
        boolean distant = !view.equals("near");
        double top = top(m.words()), distance = distant ? Double.parseDouble(view) : span * 1.5;
        // Selection uses the distant screen-error curve. A fixed closer diagnostic
        // view makes pixel/index corruption visible, instead of testing subpixel blanks.
        // Timings isolate submission cost for those selected real-mesh ranges, not FPS.
        var camera = new Vec3(span / 2.0,top + span * 1.5,span / 2.0);
        var projection = new Matrix4f().perspective(1.2f,1,.05f,131072);
        var model = new Matrix4f().lookAlong(0,-1,0,0,0,-1);
        // Match the full-corpus replay's 900-pixel projection scale and normal
        // 2-pixel error budget, including the user's 4096-block horizon.
        double scale = 900;
        var a = new PredictionDisplayLod(); var b = new PredictionDisplayLod();
        if (distant) {
            a.update(old.errors(),distance,scale,256,false,2); b.update(next.errors(),distance,scale,256,false,2);
        }
        int oldTier = a.tier(), newTier = b.tier();
        var ranges = List.of(FarTerrainFixture.range(m,old.ranges(),oldTier,31),FarTerrainFixture.range(m,next.ranges(),newTier,31));
        var words = List.of(old.words(),next.words()); var tiles = new ArrayList<PredictionGpuTile>();
        var draws = new ArrayList<PredictionRenderer.Draw>(); var bytes = new long[2];
        try (var beforeBatch = new PredictionIndirectBatch(); var afterBatch = new PredictionIndirectBatch()) {
            var constructor = PredictionPackedMesh.class.getDeclaredConstructor(int[].class,int.class,int.class,int[].class,int[].class,
                    int[].class,int[].class,boolean.class,int.class,boolean.class); constructor.setAccessible(true);
            String spatial = System.getProperty("vss.disablePredictionSpatialOrder");
            try {
                System.setProperty("vss.disablePredictionSpatialOrder","true");
                for (int mode = 0; mode < 2; mode++) {
                    // Upload the exact explicit source/water/display ranges. Do not regenerate display copies.
                    int[] waterFirst = {terrain,terrain + m.water(),terrain + m.water(),terrain + m.water(),terrain + m.water()};
                    var packed = constructor.newInstance(words.get(mode).clone(),m.axis(),terrain,m.first().clone(),m.count().clone(),
                            waterFirst,new int[]{m.water(),0,0,0,0},false,0,false);
                    packed.prepareGpuStorage(); bytes[mode] = packed.gpuStorageBytes();
                    var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
                    var tile = new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                            PredictionMesh.restored(terrain * 6,0,packed,null),new PredictionDepthBound(47,(int)Math.ceil(top)),0,mode + 1,span,m.spacing());
                    var gpu = new PredictionGpuTile(key); tiles.add(gpu); assertTrue(gpu.ensureMesh(tile)); assertNotNull(gpu.arenaSlice());
                    boolean[] owned = new boolean[m.axis() * m.axis()]; Arrays.fill(owned,true); gpu.updateCoverage(owned);
                    byte[] boundary = new byte[owned.length]; Arrays.fill(boundary,(byte)192); gpu.updateBoundaryCoverage(boundary);
                    draws.add(new PredictionRenderer.Draw(tile,gpu,owned,false,31,0,new AABB(0,47,0,span,top,span)));
                }
            } finally { if (spatial == null) System.clearProperty("vss.disablePredictionSpatialOrder"); else System.setProperty("vss.disablePredictionSpatialOrder",spatial); }
            PredictionVegetationRenderGpuTest.setup(shader,projection,camera,false,false,depth);
            shader.setCamera(model,VssLodProjection.of(projection).matrix()); shader.setMorph(null,0);
            shader.setHorizon(131072); shader.setDetailDistance(0); shader.setRealRenderDistance(0); shader.setRealCoverage(0,0,0,0);
            int program = glGetInteger(GL_CURRENT_PROGRAM); glUniform1i(glGetUniformLocation(program,"ExactCoverage"),8);
            glUniform3f(glGetUniformLocation(program,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
            var batches = List.of(beforeBatch,afterBatch); long[][] cpu = new long[2][81], gpu = new long[2][81];
            long[] quads = new long[2], commands = new long[2]; byte[][] colors = new byte[2][]; float[][] depths = new float[2][];
            for (int round = -24; round < 81; round++) for (int order = 0; order < 2; order++) {
                int mode = Math.floorMod(round + order,2);
                target.bindWrite(true); shader.use(); glBindVertexArray(context.vao); clear(); glFinish();
                long start = System.nanoTime(); glBeginQuery(GL_TIME_ELAPSED,context.timer);
                var batch = batches.get(mode); var range = ranges.get(mode); batch.begin(shader);
                long replay = batch.replayPass(range,1,true,camera,Long.MAX_VALUE);
                if (replay < 0) for (int repeat = 0; repeat < 64; repeat++) assertTrue(batch.add(draws.get(mode),range,camera,false,true,Long.MAX_VALUE));
                batch.end(); glEndQuery(GL_TIME_ELAPSED); long elapsed = System.nanoTime() - start;
                long gpuTime = glGetQueryObjectui64(context.timer,GL_QUERY_RESULT);
                quads[mode] = counter(batch,"lastMdiQuads"); commands[mode] = counter(batch,"lastMdiCommands");
                assertEquals(range.quads * 64L,quads[mode]);
                if (round >= 0) { cpu[mode][round] = elapsed; gpu[mode][round] = gpuTime;
                    rows.add(m.id() + "," + view + "," + round + "," + mode + "," + gpuTime + "," + elapsed + "," + quads[mode] + "," + commands[mode] + "," + bytes[mode]); }
                if (round == 0) { colors[mode] = context.pixels(); depths[mode] = context.depths(); }
            }
            for (int mode = 0; mode < 2; mode++) {
                int visible = 0; for (int p = 0; p < colors[mode].length; p += 4) if (colors[mode][p] != 0 || colors[mode][p + 1] != 0 || colors[mode][p + 2] != 0) visible++;
                assertTrue(visible > 0,"nonblank historical mesh " + m.id() + "/" + view);
                target.bindWrite(true); shader.use(); clear(); shader.batch(false);
                var draw = draws.get(mode); draw.gpu().bindTerrain(shader); draw.gpu().bindYield(3);
                shader.setTile((float)-camera.x,(float)-camera.y,(float)-camera.z,m.spacing(),m.axis(),true); shader.setBoundaryReplacement(true);
                PredictionRenderer.submitRanges(ranges.get(mode),draw.gpu().packed(),false);
                assertArrayEquals(colors[mode],context.pixels(),"direct/MDI color " + m.id() + "/" + view);
                assertArrayEquals(depths[mode],context.depths(),"direct/MDI depth " + m.id() + "/" + view);
            }
            if (!distant || ranges.get(0).quads == ranges.get(1).quads) {
                assertArrayEquals(colors[0],colors[1],"identical representation must preserve pixels");
                assertArrayEquals(depths[0],depths[1],"identical representation must preserve depth");
            } else {
                assertTrue(ranges.get(1).quads < ranges.get(0).quads);
                assertTrue(next.errors()[newTier] * scale / (distance - next.errors()[newTier]) <= 2);
            }
            System.out.printf(Locale.ROOT,"FAR_TERRAIN_GPU id=%d view=%s tiers=%d/%d quads=%d->%d commands=%d->%d bytes=%d->%d CPU_ms=%.6f->%.6f GPU_ms=%.6f->%.6f%n",
                    m.id(),view,oldTier,newTier,quads[0],quads[1],commands[0],commands[1],bytes[0],bytes[1],
                    CompletionGpuFixture.median(cpu[0]) / 1e6,CompletionGpuFixture.median(cpu[1]) / 1e6,
                    CompletionGpuFixture.median(gpu[0]) / 1e6,CompletionGpuFixture.median(gpu[1]) / 1e6);
            assertEquals(GL_NO_ERROR,glGetError());
        } finally { for (var tile : tiles) tile.close(); }
    }
    private static void clear() {
        glViewport(0,0,256,256); glDisable(GL_BLEND); glDisable(GL_CULL_FACE); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GEQUAL); glDepthMask(true);
        glClearDepth(0); glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }
    private static long counter(PredictionIndirectBatch batch, String name) throws Exception {
        var field = PredictionIndirectBatch.class.getDeclaredField(name); field.setAccessible(true); return field.getLong(batch);
    }
    private static double top(int[] words) {
        double top = -1e9;
        for (int p = 0; p < words.length; p += 12) {
            int flags = words[p + 6];
            boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0
                    || (flags & (3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT)) != 0
                    && (flags & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0;
            double scale = fine ? 1.0 / 16 : .25;
            for (int c = 0; c < 4; c++) top = Math.max(top,((words[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535) - 32768) * scale);
        } return top;
    }
}
