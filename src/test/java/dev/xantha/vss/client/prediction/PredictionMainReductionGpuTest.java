package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionMainReductionGpuTest {
    @SuppressWarnings("unchecked")
    @Test void productionResidencyMaintenanceVisitsAtMost64TilesAndEventuallyFinishes() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(32, 32, "bounded residency regression", 0, 0); assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            var gpu = (Map<PredictionTileKey, PredictionGpuTile>) get(PredictionRenderer.class, null, "gpuTiles");
            var policy = (PredictionGpuResidency<PredictionTileKey>) get(PredictionRenderer.class, null, "gpuResidency");
            var residency = (PredictionRenderResidency) get(PredictionRenderer.class, null, "renderResidency");
            var layout = VssLodLayout.of(8192, 6, true, false);
            var tiles = new HashMap<PredictionTileKey, PredictionTileManager.PredictionTile>();
            var maintenance = PredictionRenderer.class.getDeclaredMethod("maintainGpuResidency",
                    PredictionTileManager.RenderSnapshot.class, Vec3.class, Frustum.class); maintenance.setAccessible(true);
            try {
                for (int j = 0; j < 130; j++) {
                    var key = new PredictionTileKey(Level.OVERWORLD, j - 65, 0, 0);
                    var mesh = PredictionMeshCodecTest.fixture(64);
                    var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                            mesh, new PredictionDepthBound(64, 80), 0, 1, 64, layout.sampleSpacing(0));
                    var buffer = new PredictionGpuTile(key); assertTrue(buffer.ensureMesh(tile));
                    gpu.put(key, buffer); tiles.put(key, tile); policy.uploaded(key, 1, buffer.residentBytes()); residency.uploaded(tile);
                }
                var source = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, tiles, Map.of());
                var frustum = new Frustum(new Matrix4f(), new Matrix4f()); frustum.prepare(0, 80, 0);
                long previous = 0;
                for (int frame = 0; frame < 256 && policy.snapshot().get("observationChecks") < 130; frame++) {
                    maintenance.invoke(null, source, new Vec3(0, 80, 0), frustum);
                    long checked = policy.snapshot().get("observationChecks");
                    assertTrue(checked - previous <= 64, "a production render invocation is bounded"); previous = checked;
                }
                assertEquals(130, policy.snapshot().get("observationChecks"));
                assertEquals(0, policy.snapshot().get("observationRemaining"));
                assertEquals(130, gpu.size(), "no fallback means maintenance cannot open coverage holes");
                assertEquals(GL_NO_ERROR, glGetError());
            } finally {
                gpu.values().forEach(PredictionGpuTile::close); gpu.clear(); residency.clear(); policy.clear();
                glFinish(); PredictionTerrainArena.SHARED.reap(); PredictionTerrainArena.SHARED.close();
                PredictionTerrainArena.WATER.reap(); PredictionTerrainArena.WATER.close(); PredictionQuadBufferPool.SHARED.close();
            }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }
    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
}
