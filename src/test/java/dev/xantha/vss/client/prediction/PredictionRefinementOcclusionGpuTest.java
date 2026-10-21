package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionRefinementOcclusionGpuTest {
    @Test void asynchronousProbeLowersHiddenTerrainAndRecoversWithCurrentDepthWithoutChangingGlState() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "VSS refinement visibility", 0, 0); assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            int depth = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(depth, 1, GL_DEPTH_COMPONENT32F, 64, 64);
            glTextureParameteri(depth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(depth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            int sentinel = glCreateBuffers(); glNamedBufferData(sentinel, 2048, GL_STATIC_DRAW);
            int alignment = glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            for (int i = 0; i < 5; i++) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, i, sentinel, alignment, 128);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, sentinel);
            glActiveTexture(GL_TEXTURE5);
            var layout = VssLodLayout.of(8192, 2, true, false);
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, -4, 0);
            var mesh = new PredictionMesh(new float[0], new float[0], new int[0], new float[0], new float[0],
                    new int[0], new boolean[64], 0, 0, 64, null, null, null, null, 8, 8);
            var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                    mesh, new PredictionDepthBound(70, 70), 0, 7, 8, 8);
            var source = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(key, tile), Map.of(key, 3L));
            var projection = new Matrix4f().perspective((float)Math.toRadians(70), 1, .1F, 4096);
            var camera = new Vec3(32, 80, 0);
            var view = PredictionWorkView.rendered(projection, new Matrix4f(), camera, new Vec3(0, 0, -1), 500);
            var transform = VssLodProjection.of(projection).matrix();
            long now = System.nanoTime();
            try (var probe = new PredictionRefinementOcclusion()) {
                glClearTexImage(depth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{.1F});
                var first = probe.sample(source, view, transform, camera, depth, 64, 64, now);
                assertTrue(first.proofs().isEmpty(), "submission must return without waiting for visibility");
                assertState(sentinel, alignment);
                glFinish(); // Test only: production polls with zero timeout.
                var hidden = probe.sample(source, view, transform, camera, depth, 64, 64, now + 1);
                assertTrue(hidden.hidden(key, layout, view, now + 1, source.tiles(), source.epochs(), Set.of()));
                assertState(sentinel, alignment);
                glClearTexImage(depth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{0});
                probe.sample(source, view, transform, camera, depth, 64, 64, now + PredictionRefinementOcclusion.INTERVAL_NANOS);
                glFinish();
                var exposed = probe.sample(source, view, transform, camera, depth, 64, 64,
                        now + PredictionRefinementOcclusion.INTERVAL_NANOS + 1);
                assertFalse(exposed.hidden(key, layout, view, now + PredictionRefinementOcclusion.INTERVAL_NANOS + 1,
                        source.tiles(), source.epochs(), Set.of()), "clear current depth must restore ordinary priority");
                assertState(sentinel, alignment);
                System.out.println("REFINEMENT_VISIBILITY_GPU " + glGetString(GL_RENDERER) + " " + probe.diagnostics());
            }
            for (int i = 0; i < 5; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
            glDeleteBuffers(sentinel); glDeleteTextures(depth);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static void assertState(int sentinel, int alignment) {
        assertEquals(GL_TEXTURE5, glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(sentinel, glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING));
        for (int i = 0; i < 5; i++) {
            assertEquals(sentinel, glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i));
            assertEquals(alignment, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, i));
            assertEquals(128, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, i));
        }
        assertEquals(GL_NO_ERROR, glGetError(), "probe must restore compute state before terrain/water draws");
    }
}
