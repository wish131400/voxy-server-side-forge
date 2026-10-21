package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionScanMergeGpuTest {
    private static final int SIZE = 256;

    @Test void allPlanesCutoutTexturesAndMixedRevokedRestoredOwnersMatchDirectAndMdi() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(SIZE, SIZE, "scan meshing regression", 0, 0); assertNotEquals(0, window);
        var textures = new ArrayList<Integer>();
        String display = System.getProperty("vss.disablePredictionDisplayLod");
        System.setProperty("vss.disablePredictionDisplayLod", "true");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            PredictionMeshCodecTest.bootstrap();
            System.out.println("SCAN_GPU renderer=" + glGetString(GL_RENDERER));
            int vao = glGenVertexArrays(), indices = glGenBuffers(); glBindVertexArray(vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices); elements(65536);
            var target = new TextureTarget(SIZE, SIZE, true, false);
            int depth = textures(textures);
            try (var shader = PredictionTerrainProgram.createBatch(); var batch = new PredictionIndirectBatch()) {
                int[] opaquePixels = new int[3];
                for (int normal = 0; normal < 3; normal++) for (boolean cutout : new boolean[]{false, true}) {
                    int[] words = PredictionScanMergeTest.slice(normal, 32, true);
                    for (int p = 6; p < words.length; p += 12) words[p] |= 1 | (cutout ? PredictionPackedMesh.FLAG_CUTOUT : 0);
                    var merged = PredictionScanMergeTest.merge(words, 32, 1, true);
                    assertEquals(4, merged.words().length / 12);
                    Vec3 camera = normal == 1 ? new Vec3(16, 115, 16) : normal == 0 ? new Vec3(-35, 16, 16) : new Vec3(16, 16, -35);
                    Matrix4f view = normal == 1 ? new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1)
                            : normal == 0 ? new Matrix4f().lookAlong(1, 0, 0, 0, 1, 0) : new Matrix4f().lookAlong(0, 0, 1, 0, 1, 0);
                    setup(shader, view, depth);
                    try (var before = gpu(words, normal); var after = gpu(merged.words(), normal)) {
                        for (int mask : new int[]{0, 1, 2, 0}) {
                            byte[] owners = new byte[32 * 32];
                            for (int cell = 0; cell < owners.length; cell++)
                                owners[cell] = (byte)(mask == 1 ? 0 : mask == 0 || (cell % 32 / 4 + cell / 32 / 4) % 2 == 0 ? 192 : 128);
                            before.updateBoundaryCoverage(owners); after.updateBoundaryCoverage(owners);
                            byte[] reference = frame(before, normal, shader, batch, camera, target, depth, false);
                            assertTrue(mask == 1 || nonblank(reference) > 100, "fixture must produce pixels: normal=" + normal + " cutout=" + cutout + " mask=" + mask);
                            if (mask == 0) {
                                if (cutout) assertTrue(nonblank(reference) < opaquePixels[normal] * .95, "cutout fixture must contain actual alpha holes");
                                else opaquePixels[normal] = nonblank(reference);
                            }
                            byte[] mergedPixels = frame(after, normal, shader, batch, camera, target, depth, false);
                            assertFrame(reference, mergedPixels,
                                    "direct normal=" + normal + " cutout=" + cutout + " mask=" + mask, cutout);
                            assertFrame(reference, frame(before, normal, shader, batch, camera, target, depth, true), "baseline MDI", false);
                            assertFrame(reference, frame(after, normal, shader, batch, camera, target, depth, true),
                                    "MDI normal=" + normal + " cutout=" + cutout + " mask=" + mask, cutout);
                        }
                    }
                }
                benchmark(shader, depth, target);
                assertEquals(GL_NO_ERROR, glGetError());
                System.out.println("SCAN_GPU exact opaque coverage/alpha; RGB tolerance <=2/255; cutout boundary tolerance <=1 pixel and <=0.1% of target, no interior mismatches; XYZ planes, repeated atlas UV, full/mixed/revoked/restored owners and direct/MDI");
            } finally { target.destroyBuffers(); glDeleteBuffers(indices); glDeleteVertexArrays(vao); }
        } finally {
            if (display == null) System.clearProperty("vss.disablePredictionDisplayLod"); else System.setProperty("vss.disablePredictionDisplayLod", display);
            for (int texture : textures) glDeleteTextures(texture); glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static int nonblank(byte[] pixels) { int n = 0; for (int i = 0; i < pixels.length; i += 4) if (pixels[i] != 0) n++; return n; }
    private static void assertFrame(byte[] before, byte[] after, String description, boolean cutout) {
        assertEquals(before.length, after.length); int changed = 0, maximum = 0, edges = 0;
        for (int p = 0; p < before.length; p += 4) {
            boolean oldVisible = before[p] != 0 || before[p + 1] != 0 || before[p + 2] != 0;
            boolean newVisible = after[p] != 0 || after[p + 1] != 0 || after[p + 2] != 0;
            if (oldVisible != newVisible) {
                assertTrue(cutout, "opaque/ownership coverage at " + p / 4 + " " + description);
                assertTrue(boundary(before, p / 4) || boundary(after, p / 4), "alpha interior mismatch " + p / 4 + " " + description);
                edges++; continue;
            }
            assertEquals(before[p + 3], after[p + 3], "alpha " + description);
            for (int c = 0; c < 3; c++) {
                int difference = Math.abs((before[p + c] & 255) - (after[p + c] & 255));
                maximum = Math.max(maximum, difference); if (difference != 0) changed++;
                assertTrue(difference <= 2, "RGB at " + p / 4 + " difference=" + difference + " " + description);
            }
        }
        System.out.println("SCAN_PIXEL " + description + " changedComponents=" + changed + " maxRgbDelta=" + maximum + " alphaBoundaryPixels=" + edges);
        assertTrue(edges <= SIZE * SIZE / 1000, "cutout edge drift exceeds 0.1%: " + edges + " " + description);
    }
    private static boolean boundary(byte[] pixels, int cell) {
        boolean first = visible(pixels, cell); int x = cell % SIZE, y = cell / SIZE;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int xx = x + dx, yy = y + dy;
            if (xx >= 0 && xx < SIZE && yy >= 0 && yy < SIZE && visible(pixels, yy * SIZE + xx) != first) return true;
        }
        return false;
    }
    private static boolean visible(byte[] pixels, int cell) { int p = cell * 4; return pixels[p] != 0 || pixels[p + 1] != 0 || pixels[p + 2] != 0; }
    private static void elements(int count) {
        int[] indices = new int[count * 6], corners = {0, 1, 2, 0, 2, 3};
        for (int q = 0; q < count; q++) for (int c = 0; c < 6; c++) indices[q * 6 + c] = q * 4 + corners[c];
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
    }
    private static PredictionGpuTile gpu(int[] words, int normal) {
        int group = normal == 1 ? 0 : normal == 0 ? VssLodFaceGroup.WEST : VssLodFaceGroup.NORTH;
        int[] first = new int[5], count = new int[5]; count[group] = words.length / 12;
        var payload = new PredictionPackedMesh(words, 32, words.length / 12, first, count, new int[5], new int[5], false, words.length / 12);
        payload.morph(null, 0, 144); payload.prepareGpuStorage();
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
        var tile = new PredictionTileManager.PredictionTile(key, new int[1089], new int[1089], new ClientColumnSample[1089],
                PredictionMesh.restored(words.length / 12 * 6, 0, payload, null), new PredictionDepthBound(0, 144), 0, 1, 32, 1);
        var gpu = new PredictionGpuTile(key); assertTrue(gpu.ensureMesh(tile));
        boolean[] owns = new boolean[1024]; Arrays.fill(owns, true); gpu.updateCoverage(owns); return gpu;
    }
    private static void setup(PredictionTerrainProgram shader, Matrix4f view, int depth) {
        shader.use(); shader.setSamplers(0, 1, 2, 3, 4);
        shader.setFrame(new float[]{0, 0, 0, 1}, 1e7f, 2e7f, 0, 1e7f, false, 1);
        var projection = VssLodProjection.of(new Matrix4f().perspective(1.2f, 1, .05f, 65536));
        shader.setCamera(view, projection.matrix()); shader.bindMainDepth(depth, projection); shader.setOpaqueAlpha(1);
        shader.setHorizon(1e6f); shader.setDetailDistance(4096); shader.setRealRenderDistance(4096);
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        glUniform1i(glGetUniformLocation(program, "ExactCoverage"), 8);
        glUniform3f(glGetUniformLocation(program, "ExactCoverageGrid"), 0, 0, 1);
        glUniform1i(glGetUniformLocation(program, "VanillaMask"), 6);
        glUniform3i(glGetUniformLocation(program, "VanillaMaskSize"), 1, 1, 1);
        glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), 1e6f, 1e6f, 1e6f);
        glUniform1fv(glGetUniformLocation(program, "DirectionalTint[0]"), new float[]{1, 1, 1, 1, 1, 1, 1});
    }
    private static byte[] frame(PredictionGpuTile gpu, int normal, PredictionTerrainProgram shader,
                                PredictionIndirectBatch batch, Vec3 camera, TextureTarget target, int depth, boolean mdi) {
        target.bindWrite(true); shader.use(); glViewport(0, 0, SIZE, SIZE);
        shader.bindMainDepth(depth, VssLodProjection.of(new Matrix4f().perspective(1.2f, 1, .05f, 65536)));
        glDisable(GL_CULL_FACE); glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GEQUAL); glDepthMask(true);
        glClearColor(0, 0, 0, 1); glClearDepth(0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        var ranges = gpu.packed().drawRanges(false, VssLodFaceGroup.ALL);
        if (mdi) {
            boolean[] owns = new boolean[1024]; Arrays.fill(owns, true);
            var draw = new PredictionRenderer.Draw(gpuTile(gpu), gpu, owns, false, VssLodFaceGroup.ALL, 0, new AABB(0, 0, 0, 32, 144, 32));
            batch.begin(shader); assertTrue(batch.add(draw, ranges, camera, false, false, Long.MAX_VALUE)); batch.end();
        } else {
            shader.batch(false); gpu.bindTerrain(shader); gpu.bindYield(3);
            shader.setTile((float)-camera.x, (float)-camera.y, (float)-camera.z, 1, 32, false);
            PredictionRenderer.submitRanges(ranges, gpu.packed(), false);
        }
        var buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        byte[] pixels = new byte[buffer.remaining()]; buffer.get(pixels); return pixels;
    }
    private static PredictionTileManager.PredictionTile gpuTile(PredictionGpuTile gpu) {
        return new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0), new int[1089], new int[1089], new ClientColumnSample[1089],
                PredictionMesh.restored(gpu.packed().terrainQuadCount() * 6, 0, gpu.packed(), null),
                new PredictionDepthBound(0, 144), 0, 1, 32, 1);
    }
    private static int textures(ArrayList<Integer> textures) {
        float[] atlas = new float[32 * 32 * 4];
        for (int i = 0; i < 1024; i++) {
            atlas[i * 4] = .3f + (i % 5) * .1f; atlas[i * 4 + 1] = .8f; atlas[i * 4 + 2] = .3f;
            atlas[i * 4 + 3] = ((i % 32 / 8 + i / 32 / 8) & 1) == 0 ? 0 : 1;
        }
        texture(textures, 0, GL_RGBA32F, GL_RGBA, 32, 32, atlas); glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        texture(textures, 1, GL_RGBA8, GL_RGBA, 1, 1, new float[]{1, 1, 1, 1});
        float[] table = new float[16 * 20 * 4]; table[6] = table[7] = 1;
        Arrays.fill(table, (16 + 1) * 4, (16 + 2) * 4, .6f);
        texture(textures, 2, GL_RGBA32F, GL_RGBA, 16, 20, table);
        float[] clearDepth = new float[SIZE * SIZE]; Arrays.fill(clearDepth, 1);
        int depth = texture(textures, 7, GL_R32F, GL_RED, SIZE, SIZE, clearDepth);
        texture(textures, 8, GL_R8, GL_RED, 1, 1, new float[]{0});
        PredictionGlState.activeTexture(GL_TEXTURE6); int volume = glGenTextures(); textures.add(volume);
        glBindTexture(GL_TEXTURE_3D, volume); glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, 1, 1, 1, 0, GL_RED, GL_FLOAT, new float[]{0});
        return depth;
    }
    private static int texture(ArrayList<Integer> textures, int unit, int format, int type, int width, int height, float[] data) {
        PredictionGlState.activeTexture(GL_TEXTURE0 + unit); int id = glGenTextures(); textures.add(id); glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, type, GL_FLOAT, data); return id;
    }
    private static void benchmark(PredictionTerrainProgram shader, int depth, TextureTarget target) {
        int[] words = new int[65536 * 12];
        for (int layer = 0; layer < 64; layer++) {
            int[] slice = PredictionScanMergeTest.slice(1, 32, true);
            for (int p = 0; p < slice.length; p += 12) for (int c = 0; c < 4; c++)
                PredictionScanMerge.setCoordinate(slice, p, c, 1, (80 + layer) * 16);
            System.arraycopy(slice, 0, words, layer * slice.length, slice.length);
        }
        var merged = PredictionScanMergeTest.merge(words, 32, 1, true);
        setup(shader, new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), depth);
        try (var before = gpu(words, 1); var after = gpu(merged.words(), 1)) {
            var gpu = new PredictionGpuTile[]{before, after}; byte[] owners = new byte[1024]; Arrays.fill(owners, (byte)192);
            for (var tile : gpu) tile.updateBoundaryCoverage(owners);
            target.bindWrite(true); glViewport(0, 0, SIZE, SIZE); shader.batch(false);
            int query = glGenQueries();
            try {
                for (boolean discard : new boolean[]{true, false}) {
                    if (discard) glEnable(GL_RASTERIZER_DISCARD); else glDisable(GL_RASTERIZER_DISCARD);
                    long[][] times = new long[2][15];
                    for (int round = -8; round < 15; round++) for (int slot = 0; slot < 2; slot++) {
                        int mode = (round + 8 + slot) % 2; var tile = gpu[mode];
                        glClearDepth(0); glClear(GL_DEPTH_BUFFER_BIT); shader.use(); tile.bindTerrain(shader); tile.bindYield(3);
                        shader.setTile(-16, -200, -16, 1, 32, true);
                        glBeginQuery(GL_TIME_ELAPSED, query);
                        for (int i = 0; i < 4; i++) PredictionRenderer.submitRanges(tile.packed().drawRanges(false, VssLodFaceGroup.ALL), tile.packed(), false);
                        glEndQuery(GL_TIME_ELAPSED); long elapsed = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                        if (round >= 0) times[mode][round] = elapsed;
                    }
                    for (long[] values : times) Arrays.sort(values);
                    System.out.printf(Locale.ROOT, "SCAN_MICROBENCH synthetic=true rasterDiscard=%s draws=4 sourceQuads=%d mergedQuads=%d sourceBytes=%d mergedBytes=%d before_ms=%.6f after_ms=%.6f%n",
                            discard, words.length / 12, merged.words().length / 12, before.packed().opaqueUploadWords().length * 4,
                            after.packed().opaqueUploadWords().length * 4, times[0][7] / 1e6, times[1][7] / 1e6);
                }
            } finally { glDisable(GL_RASTERIZER_DISCARD); glDeleteQueries(query); }
        }
    }
}
