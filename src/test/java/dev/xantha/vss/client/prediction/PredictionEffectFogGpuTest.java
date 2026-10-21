package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static org.mockito.Mockito.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.xantha.vss.config.VSSClientConfig;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionEffectFogGpuTest {
    private static final float[] BLACK = {0, 0, 0, 1};
    private static final String FULLSCREEN = """
            #version 450 core
            out vec2 UV;
            void main() {
                vec2 p = vec2(gl_VertexID == 1 ? 3 : -1, gl_VertexID == 2 ? 3 : -1);
                UV = p * .5 + .5; gl_Position = vec4(p, 0, 1);
            }
            """;

    @Test void terrainWaterAndVoxyObeyFreshEnvironmentalFogAndRecover() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(32, 32, "VSS status fog regression", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            AlgorithmBootstrap.bootstrapMinecraft();
            System.out.println("Effect fog GPU: " + glGetString(GL_RENDERER));
            Camera camera = mock(Camera.class);
            when(camera.getFluidInCamera()).thenReturn(FogType.WATER);
            verifyCapture(camera);
            var target = new TextureTarget(32, 32, false, false);
            int vao = glGenVertexArrays(), indices = glGenBuffers(), payload = glGenBuffers(), records = glCreateBuffers();
            var textures = new ArrayList<Integer>();
            try (var terrain = PredictionTerrainProgram.create(); var batch = PredictionTerrainProgram.createBatch()) {
                target.bindWrite(true); glBindVertexArray(vao);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 0, 2, 3}, GL_STATIC_DRAW);
                texture(textures, 0, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1, 1, 1, .4f});
                texture(textures, 1, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1, 1, 1, 1});
                texture(textures, 2, GL_RGBA32F, 2, 2, GL_RGBA,
                        new float[]{0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1, .4f, 1, 1, 1, .4f});
                texture(textures, 3, GL_R8, 1, 1, GL_RED, new float[]{1});
                int quadTexture = glCreateTextures(GL_TEXTURE_BUFFER); textures.add(quadTexture);
                glBindTextureUnit(4, quadTexture); glBindBuffer(GL_TEXTURE_BUFFER, payload);
                glBufferData(GL_TEXTURE_BUFFER, new int[12], GL_STATIC_DRAW);
                glTextureBuffer(quadTexture, GL_RGBA32UI, payload);
                float[] farDepth = new float[32 * 32]; java.util.Arrays.fill(farDepth, 1);
                texture(textures, 7, GL_R32F, 32, 32, GL_RED, farDepth);
                for (var shader : new PredictionTerrainProgram[]{terrain, batch}) {
                    boolean indirect = shader == batch;
                    shader.use(); shader.setSamplers(0, 1, 2, 3, 4);
                    shader.batch(indirect);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, records);
                    glUniform1fv(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "DirectionalTint[0]"),
                            new float[]{1, 1, 1, 1, 1, 1, 1});
                    glUniform1f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "RealRenderDistance"), -1);
                    var projection = VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(55), 1, .05f, 65536));
                    shader.bindMainDepth(textures.get(5), projection);
                    shader.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection.matrix());
                    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
                    for (int kind = 0; kind < 3; kind++) {
                        boolean water = kind == 1;
                        upload(payload, kind);
                        shader.setOpaqueAlpha(water ? 0 : 1);
                        shader.setFrame(BLACK, 1e7f, 2e7f, 0, 1e7f, false, 1);
                        int[] clear = pixel(shader, 32, indirect, records);
                        assertTrue(clear[0] > 100, "the production mesh must actually render before applying fog");
                        // Negative control: the old horizontal-only rule leaves terrain visible below the camera.
                        shader.setFrame(BLACK, 1.25f, 5, 0, 0, false, 1);
                        assertArrayEquals(clear, pixel(shader, 32, indirect, records), "the old path must reproduce leaked distant LOD");
                        for (float end : new float[]{5, 15, 48, 256}) {
                            RenderSystem.setShaderFogStart(end * .25f); RenderSystem.setShaderFogEnd(end);
                            RenderSystem.setShaderFogShape(FogShape.SPHERE); RenderSystem.setShaderFogColor(0, 0, 0, 1);
                            PredictionFogBridge.captureTerrain(camera, false);
                            var fog = PredictionFogBridge.fog(null);
                            shader.setFrame(PredictionFogBridge.color(), fog.start(), fog.end(), fog.density(),
                                    fog.hazeStart(), false, 1, fog.shape());
                            for (float distance : new float[]{Math.max(1.1f, end * .1f), end * .55f, end, end * 2, 4096}) {
                                int[] actual = pixel(shader, distance, indirect, records);
                                double offset = Math.tan(Math.toRadians(55) / 2) / 32;
                                double length = distance * Math.sqrt(1 + 2 * offset * offset);
                                double t = Math.max(0, Math.min(1, (length - fog.start()) / (fog.end() - fog.start())));
                                double visibility = 1 - t * t * (3 - 2 * t);
                                for (int c = 0; c < 3; c++) assertEquals(clear[c] * visibility, actual[c], 2,
                                        "production fog: kind=" + kind + " batch=" + indirect + " end=" + end + " distance=" + distance);
                                assertEquals(clear[3], actual[3], 1, "fog must preserve water/cutout alpha");
                            }
                        }
                        shader.setFrame(BLACK, 1e7f, 2e7f, 0, 1e7f, false, 1);
                        assertArrayEquals(clear, pixel(shader, 32, indirect, records), "ending the effect resets shader shape and fog");
                    }
                }
                verifyVoxyComposite(camera, vao, target, textures.get(0));
                assertEquals(GL_NO_ERROR, glGetError());
                System.out.println("PASS: production direct/batch terrain, vegetation and water, 5/15/48/256-block effect ranges, near/fade/far, alpha and recovery");
            } finally {
                PredictionFogBridge.beginFrame(); target.destroyBuffers();
                glDeleteVertexArrays(vao); glDeleteBuffers(indices); glDeleteBuffers(payload); glDeleteBuffers(records);
                for (int texture : textures) com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(texture);
            }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static void verifyCapture(Camera camera) throws Exception {
        var handler = dev.xantha.vss.mixin.client.PredictionFogCaptureMixin.class.getDeclaredMethods()[0];
        handler.setAccessible(true);
        PredictionFogBridge.beginFrame();
        RenderSystem.setShaderFogColor(0, 0, 0, 1);
        RenderSystem.setShaderFogStart(1.25f); RenderSystem.setShaderFogEnd(5); RenderSystem.setShaderFogShape(FogShape.SPHERE);
        handler.invoke(null, camera, FogRenderer.FogMode.FOG_SKY, 512f, false, 0f, null);
        assertSame(RenderSystem.getShaderFogColor(), PredictionFogBridge.color(), "sky fog is never saved as terrain fog");
        handler.invoke(null, camera, FogRenderer.FogMode.FOG_TERRAIN, 512f, false, 0f, null);
        // Reproduce Voxy's range/color mutation after the capture point.
        RenderSystem.setShaderFogStart(1e9f); RenderSystem.setShaderFogEnd(1e9f); RenderSystem.setShaderFogColor(1, 1, 1, 1);
        boolean previous = VSSClientConfig.CONFIG.predictionFog;
        try {
            for (boolean haze : new boolean[]{false, true}) {
                VSSClientConfig.CONFIG.predictionFog = haze;
                assertEquals(1.25f, PredictionFogBridge.fog(null).start());
                assertEquals(5, PredictionFogBridge.fog(null).end());
                assertEquals(PredictionFogBridge.SPHERE, PredictionFogBridge.fog(null).shape());
                assertArrayEquals(BLACK, PredictionFogBridge.color());
            }
        } finally { VSSClientConfig.CONFIG.predictionFog = previous; }
        PredictionFogBridge.beginFrame();
        assertSame(RenderSystem.getShaderFogColor(), PredictionFogBridge.color(), "an old effect snapshot cannot survive a frame reset");
        when(camera.getFluidInCamera()).thenReturn(FogType.NONE);
        PredictionFogBridge.captureTerrain(camera, false);
        assertSame(RenderSystem.getShaderFogColor(), PredictionFogBridge.color(), "the ordinary path releases environmental fog");
        when(camera.getFluidInCamera()).thenReturn(FogType.WATER);
    }

    private static void verifyVoxyComposite(Camera camera, int vao, TextureTarget target, int colorTexture) throws Exception {
        String fragment = """
                #version 450 core
                uniform sampler2D colourTex;
                uniform vec3 SurfacePoint;
                in vec2 UV; out vec4 colour;
                vec3 rev3d(vec3 p) { return SurfacePoint; }
                void main() {
                    vec3 point = rev3d(vec3(UV, 0));
                    colour = texture(colourTex, UV.xy);
                    colour.rgb = vec3(1, 0, 1); // stand in for stale/disabled Voxy fog
                }
                """;
        try (var blit = GlProgram.link("effect_fog_voxy", FULLSCREEN,
                PredictionFogBridge.patch("voxy:post/blit_texture_depth_cutout.frag", fragment))) {
            target.bindWrite(true); blit.use(); glBindVertexArray(vao); glBindTextureUnit(0, colorTexture);
            Minecraft minecraft = mock(Minecraft.class); minecraft.level = mock(ClientLevel.class);
            boolean prediction = VSSClientConfig.CONFIG.enablePrediction;
            try (var singleton = mockStatic(Minecraft.class)) {
                singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                // Effects must work even with prediction and ordinary distance haze disabled.
                VSSClientConfig.CONFIG.enablePrediction = false;
                for (FogShape shape : FogShape.values()) {
                    RenderSystem.setShaderFogStart(1.25f); RenderSystem.setShaderFogEnd(5);
                    RenderSystem.setShaderFogShape(shape); RenderSystem.setShaderFogColor(0, 0, 0, 1);
                    PredictionFogBridge.captureTerrain(camera, false); PredictionFogBridge.bindVoxy();
                    glUniform3f(blit.uniform("SurfacePoint"), 3, 3, 0);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    int[] actual = readPixel();
                    double distance = shape == FogShape.SPHERE ? Math.sqrt(18) : 3;
                    double t = (distance - 1.25) / 3.75;
                    assertEquals(255 * (1 - t * t * (3 - 2 * t)), actual[0], 1);
                    assertEquals(102, actual[3], 1);
                    for (float height : new float[]{0, 4700, 20000}) {
                        glUniform3f(blit.uniform("SurfacePoint"), 500, height, 0);
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                        assertEquals(0, readPixel()[0], "distant Voxy terrain must not leak through effect fog");
                    }
                }
                PredictionFogBridge.beginFrame(); PredictionFogBridge.bindVoxy();
                glDrawArrays(GL_TRIANGLES, 0, 3);
                assertEquals(255, readPixel()[0]); assertEquals(255, readPixel()[2]);
                assertEquals(0, glGetUniformi(glGetInteger(GL_CURRENT_PROGRAM), blit.uniform("VssPredictionFogEnabled")));
            } finally { VSSClientConfig.CONFIG.enablePrediction = prediction; }
            // Ordinary horizontal haze remains independent of altitude.
            glUniform1i(blit.uniform("VssPredictionFogEnabled"), 1);
            PredictionFogBridge.bind(glGetInteger(GL_CURRENT_PROGRAM), 36044.8f, 65536, 512, 0,
                    new float[]{.65f, .75f, .9f, 1});
            for (float height : new float[]{0, 4700, 20000}) {
                glUniform3f(blit.uniform("SurfacePoint"), 500, height, 0); glDrawArrays(GL_TRIANGLES, 0, 3);
                assertEquals(255, readPixel()[0], "normal haze must keep the altitude fix");
            }
        }
        String jarPath = System.getProperty("vss.voxyJar");
        assertNotNull(jarPath, "provide the actual Voxy JAR to validate the final-blit shader contract");
        try (var jar = new ZipFile(jarPath)) {
            String installed = PredictionVoxyDepthConventionGpuTest.load(jar, "voxy:post/blit_texture_depth_cutout.frag");
            installed = installed.replaceFirst("\n", "\n#define EMIT_COLOUR\n");
            String changed = PredictionFogBridge.patch("voxy:post/blit_texture_depth_cutout.frag", installed);
            assertNotEquals(installed, changed);
            try (var linked = GlProgram.link("effect_fog_installed_voxy", FULLSCREEN, changed)) {
                assertTrue(linked.uniform("VssPredictionFogShape") >= 0);
            }
        }
        System.out.println("PASS: Voxy sphere/cylinder, stale fog override with prediction disabled, normal altitude preservation and installed GLSL link");
    }

    private static void upload(int payload, int kind) {
        int y = 32768, color = 0xCC6633;
        int flags = kind == 1 ? 1 | (1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT) : 0;
        int[] words = {32768 << 16, 32768, 0, 32768 | (32768 << 16), y | (y << 16), y | (y << 16),
                flags, color, 0, color | 0x01000000, color | (kind == 2 ? 0x01000000 : 0), color};
        glBindBuffer(GL_TEXTURE_BUFFER, payload); glBufferData(GL_TEXTURE_BUFFER, words, GL_STATIC_DRAW);
    }

    private static int[] pixel(PredictionTerrainProgram shader, float distance, boolean indirect, int records) {
        shader.setTile(-16384, -distance, -16384, 32768, 1, false);
        if (indirect) {
            ByteBuffer record = BufferUtils.createByteBuffer(64);
            record.putFloat(-16384).putFloat(-distance).putFloat(-16384).putFloat(32768);
            record.putInt(1).putInt(0).putInt(0).putInt(0);
            record.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            record.putInt(2).putInt(0).putInt(0).putInt(0).flip();
            glNamedBufferData(records, record, GL_STREAM_DRAW);
        }
        glClearColor(.05f, .02f, .9f, .1f); glClear(GL_COLOR_BUFFER_BIT);
        if (indirect) glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0, 1, 0, 0);
        else glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        assertEquals(GL_NO_ERROR, glGetError());
        return readPixel();
    }

    private static int[] readPixel() {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return new int[]{pixel.get(0) & 255, pixel.get(1) & 255, pixel.get(2) & 255, pixel.get(3) & 255};
    }

    private static void texture(ArrayList<Integer> textures, int unit, int internal, int width, int height,
                                int format, float[] values) {
        int texture = glGenTextures(); textures.add(texture);
        RenderSystem.activeTexture(GL_TEXTURE0 + unit); RenderSystem.bindTexture(texture);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL_FLOAT, values);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
}
