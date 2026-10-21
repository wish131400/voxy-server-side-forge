package dev.xantha.vss.client.prediction;

import static dev.xantha.vss.client.prediction.PredictionVegetationRenderGpuTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionGraphicsQualityGpuTest {
    private static final int SIZE = 256;
    private static final String TAA = "vec2 vssTaaShift() { return vec2(0.0); }";
    private static final String IRIS_OUTPUT = "\nout vec4 TestColor;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { TestColor = p.sampledColour; }\n";

    @Test void appliedLeavesSwitchResidentMeshesWithoutChangingPlantsOwnershipOrDepthAcrossAllPasses() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(SIZE, SIZE, "VSS graphics quality", 0, 0); assertNotEquals(0, window);
        var textures = new ArrayList<Integer>(); var tiles = new ArrayList<PredictionGpuTile>();
        String scan = System.getProperty("vss.disableVoxyScanMerge"), display = System.getProperty("vss.disablePredictionDisplayLod");
        System.setProperty("vss.disableVoxyScanMerge", "true"); System.setProperty("vss.disablePredictionDisplayLod", "true");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            System.out.println("QUALITY_GPU " + glGetString(GL_RENDERER));
            int vao = glGenVertexArrays(), indices = glGenBuffers(); glBindVertexArray(vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            int[] words = fixture(), solid = words.clone(), elements = new int[words.length / 12 * 6];
            int[] corners = {0, 1, 2, 0, 2, 3};
            int[] leaves = new int[8]; leaves[0] = 1 << 1;
            int changed = 0;
            for (int q = 0; q < words.length / 12; q++) {
                for (int c = 0; c < 6; c++) elements[q * 6 + c] = q * 4 + corners[c];
                int p = q * 12, attr = words[p + 6];
                if ((attr & 65535) == 1 && (attr & PredictionPackedMesh.FLAG_UNSHADED) == 0) {
                    solid[p + 6] &= ~PredictionPackedMesh.FLAG_CUTOUT; changed++;
                }
            }
            assertTrue(changed > 10); glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STATIC_DRAW);
            var current = upload(words, true, tiles);
            var oracle = upload(solid, true, tiles);
            setupTextures(textures); int vanillaMask = textures.get(textures.size() - 1);
            // Broad holes survive the mip levels at this view distance. The
            // old five-texel alpha pattern averages above the discard threshold.
            float[] atlas = new float[32 * 32 * 4];
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int p = (y * 32 + x) * 4;
                atlas[p] = .4f; atlas[p + 1] = .8f; atlas[p + 2] = .3f;
                atlas[p + 3] = ((x / 8 + y / 8) & 1) == 0 ? 0 : 1;
            }
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, textures.get(0));
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 32, 32, GL_RGBA, GL_FLOAT, atlas);
            glGenerateMipmap(GL_TEXTURE_2D);
            int depth = texture(textures, 7, SIZE, SIZE, GL_R32F, GL_RED, new float[SIZE * SIZE]);
            var camera = new Vec3(8, 75, 22); var target = new TextureTarget(SIZE, SIZE, true, false);
            try (var batch = new PredictionIndirectBatch()) {
                for (int mode = 0; mode < 4; mode++) {
                    boolean mdi = mode == 1, iris = mode >= 2, reverse = mode == 3;
                    glClipControl(GL_LOWER_LEFT, reverse ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                    try (var shader = iris ? PredictionTerrainProgram.createIris(TAA, s -> s + IRIS_OUTPUT)
                            : mdi ? PredictionTerrainProgram.createBatch() : PredictionTerrainProgram.create()) {
                        for (float fov : new float[]{1.2f, .08f}) {
                            var projection = reverse ? new Matrix4f().perspective(fov, 1, 2048, .05f, true)
                                    : new Matrix4f().perspective(fov, 1, .05f, 2048);
                            setup(shader, projection, camera, iris, reverse, depth);
                            for (int mask : new int[]{192, 128, 64, 0, 192}) {
                                byte[] owners = new byte[16]; Arrays.fill(owners, (byte)mask); owners[5] = 0;
                                current.gpu().updateBoundaryCoverage(owners); oracle.gpu().updateBoundaryCoverage(owners);
                                for (int distance : new int[]{0, 14, 50}) for (boolean vanilla : new boolean[]{false, true}) {
                                    setDepth(depth, projection, distance, reverse); setVanillaMask(vanillaMask, vanilla);
                                    shader.use(); shader.setLeaves(false, leaves);
                                    var fancy = render(shader, current, camera, target, batch, mdi, iris, reverse);
                                    var expected = render(shader, oracle, camera, target, batch, mdi, iris, reverse);
                                    shader.use(); shader.setLeaves(true, leaves);
                                    var fast = render(shader, current, camera, target, batch, mdi, iris, reverse);
                                    assertArrayEquals(expected.colors(), fast.colors(), "only leaves may lose alpha discard");
                                    assertArrayEquals(expected.depths(), fast.depths(), "opaque leaf depth matches solid material");
                                    shader.use(); shader.setLeaves(false, leaves);
                                    var restored = render(shader, current, camera, target, batch, mdi, iris, reverse);
                                    assertArrayEquals(fancy.colors(), restored.colors(), "fancy restoration on the same GPU mesh");
                                    assertArrayEquals(fancy.depths(), restored.depths());
                                    if (fov > 1 && mask == 192 && distance == 0 && !vanilla) {
                                        boolean visible = false;
                                        for (float value : fancy.depths()) visible |= value > 0 && value < 1;
                                        assertTrue(visible, "fixture must render visible geometry");
                                        assertFalse(Arrays.equals(fancy.colors(), fast.colors()), "fixture must exercise leaf transparency in mode " + mode);
                                        shader.use(); shader.setLeaves(true, new int[8]);
                                        assertArrayEquals(fancy.colors(), render(shader, current, camera, target, batch, mdi, iris, reverse).colors(),
                                                "a new material snapshot must invalidate the uniform mask");
                                    }
                                }
                            }
                        }
                    }
                }
                benchmark("mixed-plants", current, camera, target, depth, vanillaMask);
                int[] canopy = layeredCanopy();
                int[] canopyElements = new int[canopy.length / 12 * 6];
                for (int q = 0; q < canopy.length / 12; q++)
                    for (int c = 0; c < 6; c++) canopyElements[q * 6 + c] = q * 4 + corners[c];
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, canopyElements, GL_STATIC_DRAW);
                var layered = upload(canopy, true, tiles);
                benchmark("layered-canopy", layered, new Vec3(8, 90, 22), target, depth, vanillaMask);
            } finally { target.destroyBuffers(); glDeleteBuffers(indices); glDeleteVertexArrays(vao); }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            restore("vss.disableVoxyScanMerge", scan); restore("vss.disablePredictionDisplayLod", display);
            tiles.forEach(PredictionGpuTile::close); for (int texture : textures) glDeleteTextures(texture);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static void benchmark(String fixture, PredictionRenderer.Draw draw, Vec3 camera, TextureTarget target, int depth, int vanillaMask) {
        glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
        byte[] owners = new byte[16]; Arrays.fill(owners, (byte)192); draw.gpu().updateBoundaryCoverage(owners);
        var projection = new Matrix4f().perspective(1.2f, 1, .05f, 2048);
        setDepth(depth, projection, 0, false); setVanillaMask(vanillaMask, false);
        try (var shader = PredictionTerrainProgram.create()) {
            setup(shader, projection, camera, false, false, depth);
            int[] mask = new int[8]; mask[0] = 1 << 1;
            target.bindWrite(true); glViewport(0, 0, SIZE, SIZE); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            for (boolean depthEnabled : new boolean[]{false, true}) {
                if (depthEnabled) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
                glDepthFunc(GL_GREATER); glDepthMask(true);
                long[][] times = new long[2][15]; int query = glGenQueries();
                try {
                    for (int round = -8; round < 15; round++) for (int slot = 0; slot < 2; slot++) {
                        int mode = (round + 8 + slot) & 1;
                        glClearDepth(0); glClear(GL_DEPTH_BUFFER_BIT); shader.use(); shader.setLeaves(mode == 1, mask);
                        draw.gpu().bindTerrain(shader); draw.gpu().bindYield(3);
                        shader.setTile((float)-camera.x, (float)-camera.y, (float)-camera.z, 4, 4, false);
                        glBeginQuery(GL_TIME_ELAPSED, query);
                        for (int repeat = 0; repeat < 32; repeat++) PredictionRenderer.submitRanges(
                                draw.gpu().packed().drawRanges(false, VssLodFaceGroup.ALL), draw.gpu().packed(), false);
                        glEndQuery(GL_TIME_ELAPSED); long elapsed = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                        if (round >= 0) times[mode][round] = elapsed;
                    }
                    for (long[] values : times) Arrays.sort(values);
                    System.out.printf(Locale.ROOT, "QUALITY_MICROBENCH synthetic=true fixture=%s depth=%s size=%d draws=32 quads=%d fancy_ms=%.6f fast_ms=%.6f%n",
                            fixture, depthEnabled, SIZE, draw.gpu().quadCount(), times[0][7] / 1e6, times[1][7] / 1e6);
                } finally { glDeleteQueries(query); }
            }
        }
    }

    private static int[] layeredCanopy() {
        int[] words = new int[16 * 16 * 16 * 12];
        // Separate one-block leaves on stacked half-block planes. This isolates
        // alpha overdraw; it deliberately exaggerates depth complexity and is
        // not a generated tree, world replay or game frame-rate measurement.
        for (int q = 0; q < words.length / 12; q++) {
            int p = q * 12, x = q % 16, z = q / 16 % 16, y = 33024 + q / 256 * 2;
            words[p] = x | (x + 1) << 16; words[p + 1] = x + 1 | x << 16;
            words[p + 2] = z | z << 16; words[p + 3] = z + 1 | (z + 1) << 16;
            words[p + 4] = words[p + 5] = y | y << 16;
            words[p + 6] = PredictionPackedMesh.FLAG_CUTOUT | 1;
            words[p + 7] = words[p + 9] = words[p + 11] = 0x709020;
            words[p + 8] = z / 4 * 4 + x / 4;
            words[p + 10] = 0x709020 | PredictionPackedMesh.FEATURE;
        }
        return words;
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
