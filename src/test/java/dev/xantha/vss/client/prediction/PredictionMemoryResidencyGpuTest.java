package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionMemoryResidencyGpuTest {
    @Test void uploadIgnoresObsoleteOwnershipMetadataButWaitsForActualGeometryWithoutRemovingOldTerrain() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(64, 64, "metadata handoff", 0, 0); assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0);
            var oldPayload = PredictionOwnershipMemoryTest.mesh(); var nextPayload = PredictionOwnershipMemoryTest.mesh();
            var oldTile = tile(key, oldPayload, 1); var nextTile = tile(key, nextPayload, 2);
            var queue = new ArrayDeque<Runnable>();
            var cache = new LegacyMetadataCache(64L << 20, 16, queue::add, () -> Long.MAX_VALUE);
            var oldMetadata = LegacyOwnershipRuns.of(oldPayload);
            var nextMetadata = LegacyOwnershipRuns.of(nextPayload);
            oldMetadata.cool(cache); queue.remove().run();
            nextMetadata.cool(cache); queue.remove().run();
            assertFalse(oldMetadata.ready()); assertFalse(nextMetadata.ready());
            try (var gpu = new PredictionGpuTile(key)) {
                assertTrue(oldPayload.uploadReady(), "ready words do not need the removed metadata");
                assertTrue(gpu.ensureMesh(oldTile)); assertTrue(gpu.hasMesh(oldTile));
                assertFalse(oldMetadata.ready(), "GPU upload must not restore obsolete ownership proofs");
                nextPayload.prepareGpuStorage(); nextPayload.prepareStorage();
                assertTrue(nextPayload.compressed(), "exercise real compressed geometry restoration");
                synchronized (PredictionMeshRestore.class) {
                    PredictionMeshRestore.clear();
                    // Hold publication until the render-thread miss is checked, without timing races.
                    assertFalse(nextPayload.uploadReady());
                    assertFalse(gpu.ensureMesh(nextTile));
                    assertTrue(gpu.hasMesh(oldTile)); assertFalse(gpu.hasMesh(nextTile));
                }
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (!nextPayload.uploadReady() && System.nanoTime() < deadline) Thread.sleep(1);
                assertTrue(nextPayload.uploadReady()); assertTrue(gpu.ensureMesh(nextTile)); assertTrue(gpu.hasMesh(nextTile));
                assertFalse(oldMetadata.ready()); assertFalse(nextMetadata.ready());
                assertTrue(queue.isEmpty(), "only actual geometry needs restoration");
            }
            PredictionTerrainArena.SHARED.close(); PredictionTerrainArena.WATER.close(); PredictionQuadBufferPool.SHARED.close();
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { PredictionMeshRestore.clear(); glfwDestroyWindow(window); glfwTerminate(); }
    }
    private static PredictionTileManager.PredictionTile tile(PredictionTileManager.PredictionTileKey key,
                                                             PredictionPackedMesh payload, long revision) {
        var mesh = PredictionMesh.restored(6 * payload.quadCount(), 0, payload, null);
        return new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                mesh, new PredictionDepthBound(64, 80), 0, revision, 64, 1);
    }
}
