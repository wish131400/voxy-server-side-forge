package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Compare to the previously distributed shaders, not a reimplementation of the new shader. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionVegetationRenderGpuTest {
    private static final int SIZE = 256;
    private static final String TAA = "vec2 vssTaaShift() { return vec2(0.0); }";
    private static final String IRIS_OUTPUT = "\nout vec4 TestColor;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { TestColor = p.sampledColour; }\n";

    @Test void previousAndNewShadersMatchForCutoutCrossesLeavesMipmapsMasksAndDepthRetractions() throws Exception {
        Path baseline = Path.of(System.getProperty("vss.vegetationReferenceJar", "missing.jar"));
        assertTrue(Files.isRegularFile(baseline), "previous distributed JAR missing: " + baseline);
        String[] oldSources = previousSources(baseline);
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(SIZE, SIZE, "VSS cutout equivalence", 0, 0);
        assertNotEquals(0, window);
        var textures = new ArrayList<Integer>();
        var tiles = new ArrayList<PredictionGpuTile>();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            System.out.println("VEGETATION_GPU " + glGetString(GL_RENDERER));
            int vao = glGenVertexArrays(); glBindVertexArray(vao);
            int indices = glGenBuffers(); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            int[] words = fixture();
            int[] elements = new int[words.length / 12 * 6];
            int[] corners = {0, 1, 2, 0, 2, 3};
            for (int q = 0; q < words.length / 12; q++) for (int c = 0; c < 6; c++) elements[q * 6 + c] = q * 4 + corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STATIC_DRAW);
            var oldTile = upload(words, false, tiles);
            var compactTile = upload(words, true, tiles);
            assertTrue(oldTile.gpu().packed().opaquePaletteBaseTexel() > 0);
            assertTrue(compactTile.gpu().packed().opaquePaletteBaseTexel() < 0);
            setupTextures(textures);
            int vanillaMaskTexture = textures.get(textures.size() - 1);
            int mainDepth = texture(textures, 7, SIZE, SIZE, GL_R32F, GL_RED, new float[SIZE * SIZE]);
            int voxyDepth = texture(textures, 5, SIZE, SIZE, GL_R32F, GL_RED, new float[SIZE * SIZE]);
            var camera = new Vec3(8, 75, 22);
            var target = new TextureTarget(SIZE, SIZE, true, false);
            try (var batch = new PredictionIndirectBatch()) {
                for (int mode = 0; mode < 4; mode++) {
                    boolean mdi = mode == 1, iris = mode >= 2, reverse = mode == 3;
                    glClipControl(GL_LOWER_LEFT, reverse ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                    try (var oldProgram = program(oldSources[0], oldSources[1], mode);
                         var current = iris ? PredictionTerrainProgram.createIris(TAA, source -> source + IRIS_OUTPUT)
                                 : mdi ? PredictionTerrainProgram.createBatch() : PredictionTerrainProgram.create()) {
                        for (float fov : new float[]{1.2f, .08f}) {
                            var vanilla = reverse ? new Matrix4f().perspective(fov, 1, 2048, .05f, true)
                                    : new Matrix4f().perspective(fov, 1, .05f, 2048);
                            for (var pass : List.of(oldProgram, current)) setup(pass, vanilla, camera, iris, reverse, mainDepth);
                            for (int mask : new int[]{192, 128, 64, 0}) {
                                byte[] data = new byte[16]; Arrays.fill(data, (byte) mask); data[5] = 0;
                                oldTile.gpu().updateBoundaryCoverage(data); compactTile.gpu().updateBoundaryCoverage(data);
                                for (int depth : new int[]{0, 14, 50}) for (boolean vanillaMask : new boolean[]{false, true}) {
                                    setDepth(mainDepth, vanilla, depth, reverse);
                                    setVanillaMask(vanillaMaskTexture, vanillaMask);
                                    var before = render(oldProgram, oldTile, camera, target, batch, mdi, iris, reverse);
                                    var after = render(current, compactTile, camera, target, batch, mdi, iris, reverse);
                                    String context = "mode=" + mode + ",fov=" + fov + ",mask=" + mask + ",depth=" + depth + ",vanilla=" + vanillaMask;
                                    assertArrayEquals(before.colors(), after.colors(), context);
                                    assertArrayEquals(before.depths(), after.depths(), context);
                                    if (mask == 192 && depth == 0 && !vanillaMask && fov > 1) {
                                        int visible = 0, clear = 0;
                                        for (int i = 3; i < after.colors().length; i += 4) {
                                            if (after.colors()[i] != 0) visible++; else clear++;
                                        }
                                        assertTrue(visible > 100 && clear > 100, "fixture must contain visible vegetation and cutout holes");
                                    }
                                }
                            }
                            if (!iris) {
                                byte[] full = new byte[16]; Arrays.fill(full, (byte) 192);
                                oldTile.gpu().updateBoundaryCoverage(full); compactTile.gpu().updateBoundaryCoverage(full);
                                setVanillaMask(vanillaMaskTexture, false);
                                for (int rawDistance : new int[]{0, 14, 50}) {
                                    setDepth(voxyDepth, vanilla, rawDistance, false);
                                    glClearTexImage(mainDepth, 0, GL_RED, GL_FLOAT, new float[]{1 - 1f / 16777215});
                                    var frame = new PredictionVoxyDepth.Frame(voxyDepth, target.frameBufferId, SIZE, SIZE,
                                            camera, new Matrix4f(vanilla).invert(), false, false);
                                    for (var pass : List.of(oldProgram, current)) {
                                        pass.use(); pass.bindVoxyDepth(frame, vanilla);
                                    }
                                    glBindTextureUnit(7, mainDepth);
                                    var before = render(oldProgram, oldTile, camera, target, batch, mdi, false, false);
                                    var after = render(current, compactTile, camera, target, batch, mdi, false, false);
                                    assertArrayEquals(before.colors(), after.colors(), "saturated main depth, raw=" + rawDistance);
                                    assertArrayEquals(before.depths(), after.depths(), "saturated main depth, raw=" + rawDistance);
                                }
                            }
                        }
                    }
                }
                glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                setVanillaMask(vanillaMaskTexture, false);
                benchmark(oldSources, oldTile, compactTile, camera, target, mainDepth);
                benchmarkCrossVertices(oldSources, camera, indices, tiles, mainDepth);
            } finally {
                glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                target.destroyBuffers(); glDeleteBuffers(indices); glDeleteVertexArrays(vao);
            }
        } finally {
            for (var gpu : tiles) gpu.close();
            glFinish(); PredictionTerrainArena.SHARED.close(); PredictionQuadBufferPool.SHARED.close();
            for (int texture : textures) glDeleteTextures(texture);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    record Frame(byte[] colors, float[] depths) { }

    static Frame render(PredictionTerrainProgram program, PredictionRenderer.Draw draw, Vec3 camera,
                               TextureTarget target, PredictionIndirectBatch batch, boolean mdi, boolean iris, boolean reversed) {
        target.bindWrite(true); program.use(); glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_CULL_FACE); glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true);
        glDepthFunc(iris && !reversed ? GL_LESS : GL_GREATER);
        glClearDepth(iris && !reversed ? 1 : 0); glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        var gpu = draw.gpu(); var ranges = gpu.packed().drawRanges(false, VssLodFaceGroup.ALL);
        program.batch(false);
        if (mdi) {
            batch.begin(program);
            assertTrue(batch.add(draw, ranges, camera, false, false, Long.MAX_VALUE));
            assertTrue(batch.end() > 0);
        } else {
            gpu.bindTerrain(program); gpu.bindYield(3);
            program.setTile((float) -camera.x, (float) -camera.y, (float) -camera.z, 4, 4, false);
            PredictionRenderer.submitRanges(ranges, gpu.packed(), false);
        }
        var bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
        byte[] colors = new byte[bytes.remaining()]; bytes.get(colors);
        var values = BufferUtils.createFloatBuffer(SIZE * SIZE);
        glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, values);
        float[] depths = new float[values.remaining()]; values.get(depths);
        assertEquals(GL_NO_ERROR, glGetError());
        return new Frame(colors, depths);
    }

    static void setup(PredictionTerrainProgram program, Matrix4f vanilla, Vec3 camera,
                              boolean iris, boolean reverse, int depth) {
        program.use(); program.setSamplers(0, 1, 2, 3, 4);
        program.setFrame(new float[]{0, 0, 0, 0}, 1e7f, 2e7f, 0, 1e7f, true, 1);
        var projection = VssLodProjection.of(vanilla);
        program.setCamera(new Matrix4f().lookAlong(0, -.55f, -.835f, 0, 1, 0), iris ? vanilla : projection.matrix());
        program.bindMainDepth(depth, projection); program.setOpaqueAlpha(1);
        program.setRealRenderDistance(4096);
        program.setRealCoverage(0, 0, 0, 0); program.setHorizon(4096); program.setDetailDistance(1024);
        if (iris) program.setIrisFrame(new Matrix4f(vanilla).invert(), SIZE, SIZE, reverse, new int[256], reverse ? 0 : 1);
        int id = glGetInteger(GL_CURRENT_PROGRAM);
        glUniform1fv(glGetUniformLocation(id, "DirectionalTint[0]"), new float[]{1, 1, 1, 1, 1, 1, 1});
        glUniform3f(glGetUniformLocation(id, "ExactCoverageGrid"), (float) -camera.x, (float) -camera.z, 1);
        glUniform3i(glGetUniformLocation(id, "VanillaMaskSize"), 2, 8, 2);
        glUniform3f(glGetUniformLocation(id, "VanillaMaskOrigin"), (float) -camera.x, (float) -camera.y, (float) -camera.z);
        glUniform1f(glGetUniformLocation(id, "VanillaRenderDistance"), 4096);
    }

    static PredictionRenderer.Draw upload(int[] words, boolean compact, ArrayList<PredictionGpuTile> tiles) {
        String old = System.getProperty("vss.disableRectangleGpu");
        if (!compact) System.setProperty("vss.disableRectangleGpu", "true"); else System.clearProperty("vss.disableRectangleGpu");
        try {
            var payload = PredictionPackedMesh.terrainRecords(words.clone(), 4);
            payload.prepareGpuStorage();
            var mesh = PredictionMesh.restored(words.length / 12 * 6, 0, payload, null);
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var tile = new PredictionTileManager.PredictionTile(key, new int[25], new int[25], new ClientColumnSample[25],
                    mesh, new PredictionDepthBound(64, 68), 0, 1, 4, 4);
            var gpu = new PredictionGpuTile(key); tiles.add(gpu); assertTrue(gpu.ensureMesh(tile));
            boolean[] allowed = new boolean[16]; Arrays.fill(allowed, true); gpu.updateCoverage(allowed);
            return new PredictionRenderer.Draw(tile, gpu, allowed, false, VssLodFaceGroup.ALL, 0, new AABB(0, 64, 0, 16, 68, 16));
        } finally { if (old == null) System.clearProperty("vss.disableRectangleGpu"); else System.setProperty("vss.disableRectangleGpu", old); }
    }

    static int[] fixture() {
        int[] words = new int[384 * 12];
        for (int q = 0; q < 256; q++) {
            int x = q % 16, z = q / 16;
            PredictionCrossEncodingTest.cross(words, q, q % 2 == 0 ? x : x + 1, z, 33024,
                    q % 2 == 0 ? x + 1 : x, z + 1, 33028 + q % 3 * 4, q % 2 + 1);
            int p = q * 12;
            words[p + 6] = words[p + 6] & ~65535 | (q % 3 + 1);
            words[p + 8] = z / 4 * 4 + x / 4;
            words[p + 7] = 0x709020 | (q % 4) << 28;
            words[p + 9] = 0x689418 | (q % 4 + 1) << 28;
            words[p + 10] = 0x789428 | PredictionPackedMesh.FEATURE | (q % 4 + 2) << 28;
            words[p + 11] = 0x729824 | (q % 4 + 3) << 28;
            if (q % 4 == 0) words[p + 9] |= 1 << 24; // fixed-cell ownership
        }
        for (int q = 256; q < 384; q++) {
            int p = q * 12, x = q % 16, z = (q - 256) / 16;
            words[p] = x | (x + 1) << 16; words[p + 1] = x + 1 | x << 16;
            words[p + 2] = z | z << 16; words[p + 3] = z + 1 | (z + 1) << 16;
            int y = 33024 + q % 5 * 4;
            words[p + 4] = words[p + 5] = y | y << 16;
            words[p + 6] = PredictionPackedMesh.FLAG_CUTOUT | (q % 3 + 1);
            words[p + 7] = words[p + 9] = words[p + 11] = 0x709020;
            words[p + 8] = z / 4 * 4 + x / 4;
            words[p + 10] = 0x709020 | (q % 4 == 0 ? 0 : PredictionPackedMesh.FEATURE);
        }
        return words;
    }

    static void setupTextures(ArrayList<Integer> textures) {
        float[] atlas = new float[32 * 32 * 4];
        for (int i = 0; i < 32 * 32; i++) {
            atlas[i * 4] = .35f + i % 7 * .07f; atlas[i * 4 + 1] = .8f; atlas[i * 4 + 2] = .3f;
            atlas[i * 4 + 3] = new float[]{0, .09f, .1f, .11f, 1}[i % 5];
        }
        texture(textures, 0, 32, 32, GL_RGBA32F, GL_RGBA, atlas);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        float[] lightmap = new float[16 * 16 * 4];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int p = (y * 16 + x) * 4;
            lightmap[p] = .2f + y * .04f; lightmap[p + 1] = .25f + y * .035f;
            lightmap[p + 2] = .3f + y * .03f; lightmap[p + 3] = 1;
        }
        texture(textures, 1, 16, 16, GL_RGBA8, GL_RGBA, lightmap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        float[] sprite = new float[16 * 20 * 4];
        for (int id = 1; id <= 2; id++) {
            int p = id * 4; sprite[p + 2] = sprite[p + 3] = id == 1 ? 1 : .5f;
        }
        sprite[3 * 4] = -1;
        for (int id = 1; id < 4; id++) Arrays.fill(sprite, (16 + id) * 4, (16 + id + 1) * 4, .6f);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int p = ((y + 4) * 16 + x) * 4;
            sprite[p] = 1; sprite[p + 1] = .8f; sprite[p + 2] = .25f;
            sprite[p + 3] = y % 3 == 0 || x % 2 == 0 ? 0 : 1;
        }
        texture(textures, 2, 16, 20, GL_RGBA32F, GL_RGBA, sprite);
        texture(textures, 8, 1, 1, GL_R8, GL_RED, new float[]{1});
        glActiveTexture(GL_TEXTURE6);
        int mask = glGenTextures(); textures.add(mask); glBindTexture(GL_TEXTURE_3D, mask);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, 2, 8, 2, 0, GL_RED, GL_FLOAT, new float[32]);
    }

    static void setVanillaMask(int id, boolean active) {
        glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_3D, id);
        float[] data = new float[32]; if (active) Arrays.fill(data, 1);
        glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, 2, 8, 2, GL_RED, GL_FLOAT, data);
    }

    static void setDepth(int id, Matrix4f projection, int distance, boolean reverse) {
        float value = reverse ? 0 : 1;
        if (distance > 0) {
            Vector4f clip = projection.transform(new Vector4f(0, 0, -distance, 1));
            value = reverse ? clip.z / clip.w : clip.z / clip.w * .5f + .5f;
        }
        float[] data = new float[SIZE * SIZE]; Arrays.fill(data, value);
        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, id);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL_RED, GL_FLOAT, data);
    }

    static int texture(ArrayList<Integer> textures, int unit, int width, int height, int internal, int format, float[] data) {
        glActiveTexture(GL_TEXTURE0 + unit); int id = glGenTextures(); textures.add(id); glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL_FLOAT, data); return id;
    }

    private static PredictionTerrainProgram program(String vertex, String fragment, int mode) throws Exception {
        if (mode == 1) { vertex = PredictionTerrainProgram.batchVertex(vertex); fragment = PredictionTerrainProgram.batchFragment(fragment); }
        if (mode >= 2) {
            vertex = vertex.replace("#version 150", "#version 460 core\n#define VSS_IRIS")
                    .replace("uniform usamplerBuffer", TAA + "\nuniform usamplerBuffer");
            fragment = fragment.replace("#version 150", "#version 460 core\n#define VSS_IRIS") + IRIS_OUTPUT;
        }
        var constructor = PredictionTerrainProgram.class.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true); return constructor.newInstance(vertex, fragment);
    }

    private static String[] previousSources(Path jar) throws Exception {
        String name = PredictionTerrainProgram.class.getName();
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, PredictionTerrainProgram.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.equals(name)) return super.loadClass(requested, resolve);
                Class<?> found = findLoadedClass(requested); if (found == null) found = findClass(requested);
                if (resolve) resolveClass(found); return found;
            }
        }) {
            Class<?> type = loader.loadClass(name);
            var vertex = type.getDeclaredField("TERRAIN_VERTEX"); vertex.setAccessible(true);
            var fragment = type.getDeclaredField("TERRAIN_FRAGMENT"); fragment.setAccessible(true);
            return new String[]{(String) vertex.get(null), (String) fragment.get(null)};
        }
    }

    private static void benchmarkCrossVertices(String[] old, Vec3 camera, int indices,
                                              ArrayList<PredictionGpuTile> tiles, int depth) throws Exception {
        int quads = 262144;
        int[] sample = fixture(), words = new int[quads * 12], elements = new int[quads * 6];
        int[] corners = {0, 1, 2, 0, 2, 3};
        for (int q = 0; q < quads; q++) {
            System.arraycopy(sample, q % 256 * 12, words, q * 12, 12);
            for (int c = 0; c < 6; c++) elements[q * 6 + c] = q * 4 + corners[c];
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices); glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STATIC_DRAW);
        var oldTile = upload(words, false, tiles); var compactTile = upload(words, true, tiles);
        byte[] mask = new byte[16]; Arrays.fill(mask, (byte) 192);
        for (var draw : List.of(oldTile, compactTile)) draw.gpu().updateBoundaryCoverage(mask);
        var vertex = PredictionTerrainProgram.class.getDeclaredField("TERRAIN_VERTEX"); vertex.setAccessible(true);
        var fragment = PredictionTerrainProgram.class.getDeclaredField("TERRAIN_FRAGMENT"); fragment.setAccessible(true);
        try (var before = program(old[0], old[1], 0);
             var current32 = program((String) vertex.get(null), (String) fragment.get(null), 0);
             var current20 = PredictionTerrainProgram.create()) {
            var programs = List.of(before, current32, current20);
            for (var pass : programs) setup(pass, new Matrix4f().perspective(1.2f, 1, .05f, 2048), camera, false, false, depth);
            glEnable(GL_RASTERIZER_DISCARD); long[][] times = new long[3][15]; int query = glGenQueries();
            try {
                for (int round = -8; round < 15; round++) for (int slot = 0; slot < 3; slot++) {
                    int mode = (round + 9 + slot) % 3; var pass = programs.get(mode);
                    var draw = mode == 2 ? compactTile : oldTile;
                    pass.use(); draw.gpu().bindTerrain(pass); draw.gpu().bindYield(3);
                    pass.setTile((float) -camera.x, (float) -camera.y, (float) -camera.z, 4, 4, false);
                    glBeginQuery(GL_TIME_ELAPSED, query);
                    for (int repeat = 0; repeat < 4; repeat++) PredictionRenderer.submitRanges(
                            draw.gpu().packed().drawRanges(false, VssLodFaceGroup.ALL), draw.gpu().packed(), false);
                    glEndQuery(GL_TIME_ELAPSED); long elapsed = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                    if (round >= 0) times[mode][round] = elapsed;
                }
                for (long[] values : times) Arrays.sort(values);
                System.out.printf(Locale.ROOT,
                        "VEGETATION_VERTEX_MICROBENCH draws=4 quads=%d beforeBytes=%d currentBytes=%d before32_ms=%.6f current32_ms=%.6f current20_ms=%.6f%n",
                        quads, oldTile.gpu().packed().opaqueUploadWords().length * 4,
                        compactTile.gpu().packed().opaqueUploadWords().length * 4,
                        times[0][7] / 1e6, times[1][7] / 1e6, times[2][7] / 1e6);
                assertEquals(GL_NO_ERROR, glGetError());
            } finally { glDisable(GL_RASTERIZER_DISCARD); glDeleteQueries(query); }
        }
    }

    private static void benchmark(String[] old, PredictionRenderer.Draw oldTile, PredictionRenderer.Draw compactTile,
                                  Vec3 camera, TextureTarget target, int depth) throws Exception {
        var vertex = PredictionTerrainProgram.class.getDeclaredField("TERRAIN_VERTEX"); vertex.setAccessible(true);
        var fragment = PredictionTerrainProgram.class.getDeclaredField("TERRAIN_FRAGMENT"); fragment.setAccessible(true);
        try (var before = program(old[0], old[1], 0);
             var early32 = program((String) vertex.get(null), (String) fragment.get(null), 0);
             var late20 = program((String) vertex.get(null), old[1], 0);
             var current = PredictionTerrainProgram.create()) {
            var programs = List.of(before, early32, late20, current);
            var matrix = new Matrix4f().perspective(1.2f, 1, .05f, 2048);
            for (var pass : programs) setup(pass, matrix, camera, false, false, depth);
            setDepth(depth, matrix, 0, false);
            byte[] mask = new byte[16]; Arrays.fill(mask, (byte) 192);
            oldTile.gpu().updateBoundaryCoverage(mask); compactTile.gpu().updateBoundaryCoverage(mask);
            for (int benchmarkSize : new int[]{256, 1024}) {
              var benchmarkTarget = new TextureTarget(benchmarkSize, benchmarkSize, true, false);
              glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, depth);
              glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, benchmarkSize, benchmarkSize, 0, GL_RED, GL_FLOAT,
                      (java.nio.FloatBuffer) null);
              glClearTexImage(depth, 0, GL_RED, GL_FLOAT, new float[]{1});
              benchmarkTarget.bindWrite(true); glViewport(0, 0, benchmarkSize, benchmarkSize);
              glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
              long[][] times = new long[programs.size()][15]; int query = glGenQueries();
              try {
                for (int round = -10; round < 15; round++) for (int slot = 0; slot < programs.size(); slot++) {
                    int mode = (round + 14 + slot) % programs.size(); var pass = programs.get(mode);
                    var draw = mode < 2 ? oldTile : compactTile;
                    pass.use(); draw.gpu().bindTerrain(pass); draw.gpu().bindYield(3);
                    pass.setTile((float) -camera.x, (float) -camera.y, (float) -camera.z, 4, 4, false);
                    glBeginQuery(GL_TIME_ELAPSED, query);
                    for (int repeat = 0; repeat < 16; repeat++) PredictionRenderer.submitRanges(
                            draw.gpu().packed().drawRanges(false, VssLodFaceGroup.ALL), draw.gpu().packed(), false);
                    glEndQuery(GL_TIME_ELAPSED);
                    long elapsed = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                    if (round >= 0) times[mode][round] = elapsed;
                }
                for (long[] values : times) Arrays.sort(values);
                System.out.printf(Locale.ROOT,
                        "VEGETATION_MICROBENCH draws=16 size=%d quads=%d before32_ms=%.6f current32_ms=%.6f vertex20_oldFragment_ms=%.6f current20_ms=%.6f%n",
                        benchmarkSize, oldTile.gpu().quadCount(), times[0][7] / 1e6, times[1][7] / 1e6, times[2][7] / 1e6, times[3][7] / 1e6);
                assertEquals(GL_NO_ERROR, glGetError());
              } finally { glDeleteQueries(query); benchmarkTarget.destroyBuffers(); }
            }
        }
    }
}
