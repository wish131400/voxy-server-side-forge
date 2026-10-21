package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** A stored column cannot turn a rear cut face into a replacement for coarse foreground. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionCoarseHandoffGpuTest {
    private static final int SIZE = 32;

    @Test void indexedRearWallsKeepCoarseForegroundDuringPartialRealLoading() {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(SIZE, SIZE, "VSS coarse foreground handoff", 0, 0);
        assertNotEquals(0, window);
        var textures = new ArrayList<Integer>();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            AlgorithmBootstrap.bootstrapMinecraft();
            System.out.println("COARSE_HANDOFF_GPU " + glGetString(GL_RENDERER));
            var output = new TextureTarget(SIZE, SIZE, true, false);
            int vao = glGenVertexArrays(), indices = glGenBuffers(), payload = glGenBuffers(), records = glCreateBuffers();
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 0, 2, 3}, GL_STATIC_DRAW);
            texture(textures, 0, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1, 1, 1, 1});
            texture(textures, 1, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1, 1, 1, 1});
            texture(textures, 2, GL_RGBA32F, 2, 2, GL_RGBA, new float[16]);
            texture(textures, 3, GL_R8, 1, 1, GL_RED, new float[]{1});
            int depth = texture(textures, 7, GL_R32F, SIZE, SIZE, GL_RED, new float[SIZE * SIZE]);
            texture(textures, 8, GL_R8, 1, 1, GL_RED, new float[]{1});
            int quad = glCreateTextures(GL_TEXTURE_BUFFER); textures.add(quad);
            glBindBuffer(GL_TEXTURE_BUFFER, payload);
            int y = 32768 + 80 * 4;
            glBufferData(GL_TEXTURE_BUFFER, new int[]{16384 << 16, 16384, 0, 16384 | 16384 << 16,
                    y | y << 16, y | y << 16, 1, 0xbf4d13, 0, 0x1bf4d13, 0xbf4d13, 0xbf4d13}, GL_STATIC_DRAW);
            glTextureBuffer(quad, GL_RGBA32UI, payload); glBindTextureUnit(4, quad);
            var failures = new ArrayList<String>(); int cases = 0;
            try {
                for (int mode = 0; mode < 4; mode++) {
                    boolean iris = mode >= 2, reverse = mode == 3, batch = mode == 1;
                    var vanilla = reverse ? new Matrix4f().perspective(1.2f, 1, 65536, .05f, true)
                            : new Matrix4f().perspective(1.2f, 1, .05f, 65536);
                    var projection = VssLodProjection.of(vanilla);
                    glClipControl(GL_LOWER_LEFT, reverse ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                    try (var shader = iris ? PredictionTerrainProgram.createIris("vec2 vssTaaShift() { return vec2(0); }",
                            source -> source + "\nout vec4 TestColor;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { TestColor = p.sampledColour; }\n")
                            : batch ? PredictionTerrainProgram.createBatch() : PredictionTerrainProgram.create()) {
                        shader.use(); shader.setSamplers(0, 1, 2, 3, 4);
                        shader.setFrame(new float[]{0, 0, 0, 1}, 1e7f, 2e7f, 0, 1e7f, false, 1);
                        shader.setOpaqueAlpha(1); shader.setRealRenderDistance(4096);
                        shader.setRealCoverage(-10000, -10000, 10000, 10000);
                        shader.bindMainDepth(depth, projection);
                        int id = glGetInteger(GL_CURRENT_PROGRAM);
                        glUniform3i(glGetUniformLocation(id, "VanillaMaskSize"), 0, 0, 0);
                        glUniform1fv(glGetUniformLocation(id, "DirectionalTint[0]"), new float[]{1, 1, 1, 1, 1, 1, 1});
                        for (int direction : new int[]{-1, 1}) for (int spacing : new int[]{8, 16, 32}) {
                            var view = new Matrix4f().lookAlong(0, -1, direction, 0, 1, 0);
                            shader.setCamera(view, iris ? vanilla : projection.matrix());
                            if (iris) shader.setIrisFrame(new Matrix4f(vanilla).invert(), SIZE, SIZE, reverse,
                                    new int[256], reverse ? 0 : 1);
                            shader.setTile(-8192, -181, -8192, spacing, 1, true);
                            shader.batch(batch); shader.setBatchCameraOffset(0, 0, 0); shader.setBatchTimeSeconds(0);
                            if (batch) {
                                var data = BufferUtils.createByteBuffer(64);
                                data.putFloat(-8192).putFloat(-181).putFloat(-8192).putFloat(spacing);
                                data.putInt(1).putInt(1).putInt(0).putInt(0);
                                data.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                                data.putInt(2).putInt(0).putInt(0).putInt(0).flip();
                                glNamedBufferData(records, data, GL_STREAM_DRAW); glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, records);
                            }
                            var inverse = new Matrix4d(iris ? vanilla : projection.matrix()).mul(new Matrix4d(view)).invert();
                            var mvp = new Matrix4d(vanilla).mul(new Matrix4d(view));
                            for (int surface = 0; surface < 6; surface++) {
                                float[] values = new float[SIZE * SIZE];
                                for (int py = 0; py < SIZE; py++) for (int px = 0; px < SIZE; px++) {
                                    boolean clear = surface == 4 || surface == 5 && px >= SIZE / 2;
                                    if (clear) { values[py * SIZE + px] = reverse ? 0 : 1; continue; }
                                    var a = inverse.transformProject(new Vector3d((px + .5) / 16 - 1, (py + .5) / 16 - 1, reverse ? .5 : 0));
                                    var ray = inverse.transformProject(new Vector3d((px + .5) / 16 - 1, (py + .5) / 16 - 1, reverse ? .75 : .5)).sub(a);
                                    // Rear wall, lower actual ground, closer cliff, coplanar ground, clear, partial ground.
                                    if (surface == 1 || surface == 3 || surface == 5)
                                        ray.mul(((surface == 3 ? 80 : 64) - 181 - a.y) / ray.y);
                                    else ray.mul((direction * (surface == 2 ? 97 : 109) - a.z) / ray.z);
                                    var point = ray.add(a);
                                    double z = mvp.transformProject(point).z;
                                    values[py * SIZE + px] = reverse ? (float) z : (float) (Math.rint((z * .5 + .5) * 16777215) / 16777215);
                                }
                                glTextureSubImage2D(depth, 0, 0, 0, SIZE, SIZE, GL_RED, GL_FLOAT, values);
                                for (boolean indexed : new boolean[]{false, true}) {
                                    glUniform3f(glGetUniformLocation(id, "ExactCoverageGrid"), -8, direction > 0 ? 96 : -112, indexed ? 1 : 0);
                                    output.bindWrite(true); glBindVertexArray(vao);
                                    glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND);
                                    glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
                                    if (batch) glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0, 1, 0, 0);
                                    else glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
                                    var pixel = BufferUtils.createByteBuffer(4); glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                                    boolean expected = surface == 0 || surface == 4 || surface == 5;
                                    cases++;
                                    if (expected != ((pixel.get(0) & 255) > 100)) failures.add("mode=" + mode + ",spacing=" + spacing
                                            + ",direction=" + direction + ",surface=" + surface + ",indexed=" + indexed);
                                }
                            }
                        }
                    }
                }
                assertEquals(GL_NO_ERROR, glGetError());
                System.out.println("COARSE_HANDOFF mismatches=" + failures.size() + "/" + cases + " " + failures.stream().limit(30).toList());
                assertTrue(failures.isEmpty(), "incorrect coarse foreground ownership: " + failures.stream().limit(30).toList());
            } finally {
                glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE); output.destroyBuffers();
                glDeleteBuffers(indices); glDeleteBuffers(payload); glDeleteBuffers(records); glDeleteVertexArrays(vao);
            }
        } finally {
            for (int texture : textures) glDeleteTextures(texture);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static int texture(ArrayList<Integer> textures, int unit, int format, int width, int height, int channels, float[] values) {
        int id = glCreateTextures(GL_TEXTURE_2D); textures.add(id); glTextureStorage2D(id, 1, format, width, height);
        glTextureSubImage2D(id, 0, 0, 0, width, height, channels, GL_FLOAT, values);
        glTextureParameteri(id, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTextureParameteri(id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTextureUnit(unit, id); return id;
    }
}
