package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Rasterize the production ordinary/Iris shaders with independent ground/feature masks. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionFeatureHandoffGpuTest {
    @Test void completeFeaturesKeepDepthFallbackWhileUploadedRegionsClearPreviewsInBothDepthConventions() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(32, 32, "VSS feature handoff", 0, 0);
        assertNotEquals(0, window);
        var textures = new ArrayList<Integer>();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            System.out.println("FEATURE_HANDOFF_GPU " + glGetString(GL_RENDERER));
            int vao = glGenVertexArrays(); glBindVertexArray(vao);
            int indices = glGenBuffers(); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 0, 2, 3}, GL_STATIC_DRAW);
            var target = new TextureTarget(32, 32, true, false);
            try {
                texture(textures, 0, 1, 1, GL_RGBA8, GL_RGBA, new float[]{1, 1, 1, 1});
                texture(textures, 1, 1, 1, GL_RGBA8, GL_RGBA, new float[]{1, 1, 1, 1});
                texture(textures, 2, 2, 2, GL_RGBA32F, GL_RGBA, new float[16]);
                texture(textures, 8, 1, 1, GL_R8, GL_RED, new float[]{1});
                int depth = texture(textures, 7, 32, 32, GL_R32F, GL_RED, new float[32 * 32]);
                glActiveTexture(GL_TEXTURE6);
                int volume = glGenTextures(); textures.add(volume); glBindTexture(GL_TEXTURE_3D, volume);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, 1, 1, 1, 0, GL_RED, GL_FLOAT, new float[]{0});
                for (int mode = 0; mode < 4; mode++) {
                    boolean iris = mode == 1 || mode == 2, reversed = mode == 2, mdi = mode == 3;
                    var matrix = reversed ? new Matrix4f().perspective(1.2f, 1, 2048, .05f, true)
                            : new Matrix4f().perspective(1.2f, 1, .05f, 2048);
                    var projection = VssLodProjection.of(matrix);
                    glClipControl(GL_LOWER_LEFT, reversed ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                    try (var program = iris ? PredictionTerrainProgram.createIris("vec2 vssTaaShift() { return vec2(0.0); }",
                            source -> source + "\nout vec4 TestColor;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { TestColor = p.sampledColour; }\n")
                            : mdi ? PredictionTerrainProgram.createBatch() : PredictionTerrainProgram.create();
                         var batch = new PredictionIndirectBatch()) {
                        program.use(); program.setSamplers(0, 1, 2, 3, 4);
                        program.setFrame(new float[]{0, 0, 0, 1}, 1e7f, 2e7f, 0, 1e7f, false, 1);
                        program.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), matrix);
                        program.setHorizon(4096); program.setRealRenderDistance(4096);
                        program.setRealCoverage(0, 0, 0, 0); program.bindMainDepth(depth, projection);
                        if (iris) program.setIrisFrame(new Matrix4f(matrix).invert(), 32, 32, reversed, new int[256], reversed ? 0 : 1);
                        int id = glGetInteger(GL_CURRENT_PROGRAM);
                        glUniform1i(glGetUniformLocation(id, "ExactCoverage"), 8);
                        glUniform3f(glGetUniformLocation(id, "ExactCoverageGrid"), -8, -8, 1);
                        glUniform1i(glGetUniformLocation(id, "VanillaMask"), 6);
                        glUniform3i(glGetUniformLocation(id, "VanillaMaskSize"), 0, 0, 0);
                        glUniform1fv(glGetUniformLocation(id, "DirectionalTint[0]"), new float[]{1, 1, 1, 1, 1, 1, 1});
                        for (boolean preview : new boolean[]{false, true})
                                for (boolean local : new boolean[]{false, true}) for (boolean feature : preview ? new boolean[]{true} : new boolean[]{false, true})
                                for (boolean uploaded : preview ? new boolean[]{false, true} : new boolean[]{false}) {
                            var tile = fixture(feature, local);
                            if (preview) {
                                int[] words = tile.mesh().gpuPayload().cacheWords().clone();
                                words[10] |= PredictionPackedMesh.PREVIEW;
                                var payload = PredictionPackedMesh.terrainRecords(words, 1); payload.morph(null, 64, 64);
                                tile = new PredictionTileManager.PredictionTile(tile.key(), tile.heights(), tile.groundHeights(), tile.samples(),
                                        PredictionMesh.restored(6, 0, payload, null), tile.depthBound(), 0, 2, 1, 16);
                            }
                            try (var gpu = new PredictionGpuTile(tile.key())) {
                                assertTrue(gpu.ensureMesh(tile));
                                gpu.updateCoverage(new boolean[]{true});
                                gpu.bindTerrain(program); gpu.bindYield(3);
                                program.setTile(-8, -100, -8, 16, 1, true);
                                var draw = new PredictionRenderer.Draw(tile, gpu, new boolean[]{true}, new boolean[]{feature},
                                        false, VssLodFaceGroup.ALL, 0, new AABB(0, 64, 0, 16, 64, 16));
                                var ranges = gpu.packed().drawRanges(false, VssLodFaceGroup.ALL);
                                for (int mask : new int[]{128, 64, 192, 0}) {
                                    gpu.updateBoundaryCoverage(new byte[]{(byte) mask}); gpu.bindYield(3);
                                    boolean owns = (mask & (feature ? 64 : 128)) != 0;
                                    for (int real : new int[]{0, 34, 36, 38, 200}) {
                                        float value = real == 0 ? (reversed ? 0 : 1) : windowDepth(matrix, real, reversed);
                                        float[] data = new float[32 * 32]; Arrays.fill(data, value);
                                        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, depth);
                                        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 32, 32, GL_RED, GL_FLOAT, data);
                                        target.bindWrite(true); glBindVertexArray(vao);
                                        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND);
                                        glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
                                        if (ranges.quads > 0 && mdi) {
                                            assertNotNull(gpu.arenaSlice());
                                            batch.begin(program);
                                            assertTrue(batch.add(draw, ranges, new Vec3(8, 100, 8), false, true, Long.MAX_VALUE));
                                            assertTrue(batch.end() > 0);
                                        } else if (ranges.quads > 0) PredictionRenderer.submitRanges(ranges, gpu.packed(), false);
                                        var pixel = BufferUtils.createByteBuffer(4);
                                        glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                                        boolean visible = (pixel.get(0) & 255) > 0;
                                        boolean expected = owns && (real == 0 || feature && real > 36);
                                        assertEquals(expected, visible, "mode=" + mode + ",feature=" + feature + ",local=" + local
                                                + ",mask=" + mask + ",realDistance=" + real + ",preview=" + preview + ",uploaded=" + uploaded);
                                        assertEquals(GL_NO_ERROR, glGetError());
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                target.destroyBuffers(); glDeleteBuffers(indices); glDeleteVertexArrays(vao);
            }
        } finally {
            for (int texture : textures) glDeleteTextures(texture);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static PredictionTileManager.PredictionTile fixture(boolean feature, boolean local) {
        int y = 32768 + 64 * 4;
        int[] words = {16 << 16, 16, 0, 16 | 16 << 16, y | y << 16, y | y << 16,
                0, 0xff2020, 0, 0xff2020 | (local ? 0 : 1 << 24), 0xff2020 | (feature ? PredictionPackedMesh.FEATURE : 0), 0xff2020};
        var payload = PredictionPackedMesh.terrainRecords(words, 1); payload.morph(null, 64, 64);
        var mesh = PredictionMesh.restored(6, 0, payload, null);
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
        return new PredictionTileManager.PredictionTile(key, new int[]{64, 64, 64, 64}, new int[4], new ClientColumnSample[4],
                mesh, new PredictionDepthBound(64, 64), 0, 1, 1, 16);
    }

    private static float windowDepth(Matrix4f matrix, float distance, boolean zeroToOne) {
        var clip = matrix.transform(new Vector4f(0, 0, -distance, 1));
        return zeroToOne ? clip.z / clip.w : clip.z / clip.w * .5f + .5f;
    }

    private static int texture(ArrayList<Integer> textures, int unit, int width, int height, int internal, int format, float[] data) {
        glActiveTexture(GL_TEXTURE0 + unit);
        int id = glGenTextures(); textures.add(id); glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL_FLOAT, data);
        return id;
    }
}
