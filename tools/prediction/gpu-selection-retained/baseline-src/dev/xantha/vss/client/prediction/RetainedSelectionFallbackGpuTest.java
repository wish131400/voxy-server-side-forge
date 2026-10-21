package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.Arrays;
import java.util.Collections;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class RetainedSelectionFallbackGpuTest {
    @Test void unsupportedClipModeRejectsPlannedSelectionBeforeAnyResidentDraw() {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(512,512,"GPU selection fallback",0,0); assertNotEquals(0,window);
        String previous=System.getProperty("vss.forcePredictionOcclusion");
        int depth=0;
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            System.setProperty("vss.forcePredictionOcclusion","true");
            depth=glCreateTextures(GL_TEXTURE_2D); glTextureStorage2D(depth,1,GL_DEPTH_COMPONENT32F,512,512);
            var payload=PredictionPackedMesh.seamRecords(PredictionDisplayGeometryTest.grid(0),64); payload.prepareGpuStorage();
            var mesh=PredictionMesh.restored(payload.quadCount()*6,0,payload,null);
            var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
            var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    mesh,new PredictionDepthBound(80,80),0,1,64,1);
            try(var gpu=new PredictionGpuTile(key); var program=PredictionTerrainProgram.createBatch(); var batch=new PredictionIndirectBatch()) {
                assertTrue(gpu.ensureMesh(tile)); boolean[] cells=new boolean[4096]; Arrays.fill(cells,true);
                var draw=new PredictionRenderer.Draw(tile,gpu,cells,false,VssLodFaceGroup.ALL,0,new AABB(0,78,0,64,82,64));
                var residents=Collections.nCopies(3072,draw); var input=new PredictionGpuSelectionInput(); var camera=new Vec3(0,110,0);
                var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096)).matrix();
                batch.workload(1_048_576);
                assertFalse(batch.prepareGpuSelection(depth,512,512,projection,camera,1,1,input,residents,4096));
                var rotated=new Matrix4f(projection).rotateY(.01f);
                assertTrue(batch.prepareGpuSelection(depth,512,512,rotated,camera,1,1,input,residents,4096));
                glClipControl(GL_LOWER_LEFT,GL_ZERO_TO_ONE); program.use();
                batch.begin(program,depth,512,512,rotated,camera,1,1,1);
                assertFalse(batch.gpuSelection(),"the renderer must now switch to the ordinary visible input");
                assertEquals(0,batch.end()); assertEquals(GL_NO_ERROR,glGetError());
                glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE); glUseProgram(0);
            }
        } finally {
            if(previous==null)System.clearProperty("vss.forcePredictionOcclusion"); else System.setProperty("vss.forcePredictionOcclusion",previous);
            glFinish(); PredictionTerrainArena.SHARED.reap(); PredictionTerrainArena.SHARED.close();
            PredictionTerrainArena.WATER.close(); PredictionQuadBufferPool.SHARED.close();
            if(depth!=0)glDeleteTextures(depth); glfwDestroyWindow(window); glfwTerminate();
        }
    }
}
