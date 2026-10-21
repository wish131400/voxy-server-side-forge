package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionTurnMainGpuTest {
    @Test void asyncHandoffKeepsOldGpuAndCompleteCoverageUntilTheReplacementIsReady() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(128, 128, "boundary handoff regression", 0, 0); assertNotEquals(0, window);
        var jobs = new ArrayDeque<Runnable>(); var scene = new PredictionRenderer.Scene(jobs::add);
        var owned = new ArrayList<PredictionGpuTile>();
        @SuppressWarnings("unchecked") var gpu = (Map<PredictionTileKey, PredictionGpuTile>) field(PredictionRenderer.class, null, "gpuTiles");
        var previous = new HashMap<>(gpu); gpu.clear();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            var a = PredictionLodSeamsTest.tile(-1, -2, 1, 64);
            var b = PredictionLodSeamsTest.tile(0, -2, 1, 67);
            for (var tile : List.of(a, b)) {
                var resident = new PredictionGpuTile(tile.key()); owned.add(resident);
                assertTrue(resident.ensureMesh(tile)); gpu.put(tile.key(), resident);
            }
            var layout = VssLodLayout.of(4096, 6, true, true);
            var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(a.key(), a, b.key(), b), Map.of());
            var view = new PredictionRenderer.CoverageView(0, 0, 1300, null);
            var camera = new Vec3(0, 100, 0); var matrix = new Matrix4f();
            var first = scene.prepare(snapshot, snapshot.tiles().keySet(), view, camera, 4096, 32, matrix, matrix, null);
            assertTrue(first.isEmpty()); assertTrue(scene.boundaryPending()); assertEquals(1, jobs.size());
            jobs.removeFirst().run();
            first = scene.prepare(snapshot, Set.of(), view, camera, 4096, 32, matrix, matrix, null);
            assertEquals(2, first.stream().filter(draw -> !draw.seam()).count());
            assertTrue(first.stream().anyMatch(PredictionRenderer.Draw::seam)); assertFalse(scene.boundaryPending());
            var oldB = first.stream().filter(draw -> !draw.seam() && draw.tile() == b).findFirst().orElseThrow();
            var replacement = PredictionLodSeamsTest.tile(0, -2, 1, 69);
            var replacementGpu = new PredictionGpuTile(replacement.key()); owned.add(replacementGpu);
            assertTrue(replacementGpu.ensureMesh(replacement)); gpu.put(replacement.key(), replacementGpu);
            var next = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout,
                    Map.of(a.key(), a, replacement.key(), replacement), Map.of(replacement.key(), 1L));
            var waiting = scene.prepare(next, Set.of(replacement.key()), view, camera, 4096, 32, matrix, matrix, null);
            assertTrue(waiting.stream().anyMatch(draw -> draw == oldB), "old geometry remains until masks and connectors are ready");
            assertSame(b.mesh().gpuPayload(), oldB.gpu().packed()); assertTrue(scene.references(oldB.gpu()));
            assertTrue(scene.boundaryPending()); assertEquals(1, jobs.size());
            jobs.removeFirst().run();
            var ready = scene.prepare(next, Set.of(), view, camera, 4096, 32, matrix, matrix, null);
            assertTrue(ready.stream().anyMatch(draw -> !draw.seam() && draw.tile() == replacement));
            assertFalse(ready.stream().anyMatch(draw -> !draw.seam() && draw.tile() == b));
            assertFalse(scene.references(oldB.gpu())); assertFalse(scene.boundaryPending());
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            scene.clear();
            var drain = PredictionRenderer.class.getDeclaredMethod("drainRetiredTiles"); drain.setAccessible(true);
            for (int i = 0; i < 8; i++) drain.invoke(null);
            for (var tile : owned) tile.close(); gpu.clear(); gpu.putAll(previous);
            glFinish(); PredictionTerrainArena.SHARED.reap(); PredictionTerrainArena.SHARED.close();
            PredictionTerrainArena.WATER.reap(); PredictionTerrainArena.WATER.close();
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    @Test void maskUpdatesOnlyChangedTexelsAndPreservesOverlapWhenTheWindowMoves() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(32, 32, "coverage texture regression", 0, 0); assertNotEquals(0, window);
        PredictionTerrainProgram program = null;
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            program = PredictionTerrainProgram.createBatch(); program.use();
            int handle = glGetInteger(GL_CURRENT_PROGRAM);
            int origin = glGetUniformLocation(handle, "VanillaMaskOrigin"), size = glGetUniformLocation(handle, "VanillaMaskSize");
            assertTrue(origin >= 0 && size >= 0);
            var mask = new PredictionVanillaMask();
            call(mask, "reanchor", new Class<?>[]{int.class, int.class, int.class, int.class, int.class}, 5, 3, -2, -2, -4);
            call(mask, "setState", new Class<?>[]{int.class, int.class, int.class, byte.class}, 2, 1, 2, (byte) 1);
            mask.bind(6, Vec3.ZERO, origin, size);
            int texture = (int) field(PredictionVanillaMask.class, mask, "texture");
            assertEquals(255, texels(75)[(2 * 3 + 1) * 5 + 2] & 255);
            long bytes = (long) field(PredictionVanillaMask.class, mask, "textureBytes");
            call(mask, "setState", new Class<?>[]{int.class, int.class, int.class, byte.class}, 2, 1, 2, (byte) 0);
            mask.bind(6, Vec3.ZERO, origin, size);
            assertEquals(bytes + 1, field(PredictionVanillaMask.class, mask, "textureBytes"));
            assertEquals(0, texels(75)[(2 * 3 + 1) * 5 + 2]);
            call(mask, "setState", new Class<?>[]{int.class, int.class, int.class, byte.class}, 3, 1, 2, (byte) 1);
            mask.bind(6, Vec3.ZERO, origin, size);
            call(mask, "reanchor", new Class<?>[]{int.class, int.class, int.class, int.class, int.class}, 5, 3, -1, -2, -4);
            mask.bind(6, Vec3.ZERO, origin, size);
            assertEquals(texture, field(PredictionVanillaMask.class, mask, "texture"));
            assertEquals(255, texels(75)[(2 * 3 + 1) * 5 + 2] & 255, "the same world section moves one texture cell");
            assertEquals(GL_NO_ERROR, glGetError()); glDeleteTextures(texture);
            net.minecraft.client.renderer.LevelRenderer.class.getDeclaredMethod("setSectionDirty", int.class, int.class, int.class, boolean.class);
        } finally { if (program != null) program.close(); glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static byte[] texels(int length) {
        var buffer = org.lwjgl.BufferUtils.createByteBuffer(length);
        glPixelStorei(GL_PACK_ALIGNMENT, 1); glGetTexImage(GL_TEXTURE_3D, 0, GL_RED, GL_UNSIGNED_BYTE, buffer);
        byte[] result = new byte[length]; buffer.get(result); return result;
    }
    private static Object field(Class<?> type, Object owner, String name) throws Exception {
        var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static Object call(Object owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, arguments);
    }
}
