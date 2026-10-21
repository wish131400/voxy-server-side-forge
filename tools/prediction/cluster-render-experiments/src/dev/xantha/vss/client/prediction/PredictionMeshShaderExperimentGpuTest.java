package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import java.util.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.NVMeshShader;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionMeshShaderExperimentGpuTest {
    @Test void nvBackendUsesProductionMaterialsAndIsMeasuredAgainstMdi()throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();VssLodSpriteTable.close();
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(256,256,"VSS NV mesh experiment",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();var rows=new ArrayList<String>();rows.add("kind,raster,round,mode,gpu_ns,cpu_ns,quads");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();assertTrue(GL.getCapabilities().GL_NV_mesh_shader,"NV extension required for this experiment");
            if(!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            System.out.println("MESH_EXPERIMENT_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            PredictionVegetationRenderGpuTest.setupTextures(textures);glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
            float[] allDepth=new float[256*256];Arrays.fill(allDepth,1);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,allDepth);
            try(var contents=PredictionVegetationMaterialTest.contents("block/vss_lod_grass")) {
                int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
                measure("plants",PredictionVegetationLodGpuTest.plants(row),depth,rows);
                int[] words=new int[16384*12];
                for(int q=0;q<16384;q++) {
                    int x=q%128,z=q/128,p=q*12,y=33088;
                    words[p]=x|(x+1)<<16;words[p+1]=(x+1)|x<<16;
                    words[p+2]=z|z<<16;words[p+3]=(z+1)|(z+1)<<16;
                    words[p+4]=words[p+5]=y|y<<16;
                    words[p+6]=1;words[p+7]=words[p+9]=words[p+10]=words[p+11]=0x608030;words[p+8]=q;
                }
                measure("terrain",words,depth,rows);
            }
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            glFinish();PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);VssLodSpriteTable.close();glfwDestroyWindow(window);glfwTerminate();
        }
        CompletionGpuFixture.save("mesh-shader-experiment.csv",rows);
    }
    private static void measure(String kind,int[] words,int depth,List<String> rows)throws Exception {
        var payload=PredictionPackedMesh.terrainRecords(words.clone(),128);int quads=words.length/12;payload.prepareGpuStorage();
        var mesh=PredictionMesh.restored(quads*6,0,payload,null);var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
        var tile=new PredictionTileManager.PredictionTile(key,new int[129*129],new int[129*129],new ClientColumnSample[129*129],mesh,
                new PredictionDepthBound(80,82),0,1,128,1);
        int vao=glGenVertexArrays(),ebo=glCreateBuffers(),indirect=glCreateBuffers(),sentinel=glCreateBuffers(),query=glGenQueries();
        glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
        int[] indices=new int[payload.quadCount()*6],corners={0,1,2,0,2,3};
        for(int q=0;q<payload.quadCount();q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];glNamedBufferData(ebo,indices,GL_STATIC_DRAW);
        int[] commands=new int[64*2];for(int c=0;c<64;c++){commands[c*2]=(quads+ClusterMeshProgram.QUADS-1)/ClusterMeshProgram.QUADS;commands[c*2+1]=c*commands[c*2];}
        glNamedBufferData(indirect,commands,GL_STATIC_DRAW);glNamedBufferData(sentinel,64,GL_STATIC_DRAW);
        var target=new TextureTarget(256,256,true,false);var camera=new Vec3(64,130,170);
        try(var gpu=new PredictionGpuTile(key);var vertex=PredictionTerrainProgram.createBatch();var nv=ClusterMeshProgram.create();var batch=new PredictionIndirectBatch()) {
            assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());boolean[] all=new boolean[128*128];Arrays.fill(all,true);gpu.updateCoverage(all);
            byte[] coverage=new byte[all.length];Arrays.fill(coverage,(byte)192);gpu.updateBoundaryCoverage(coverage);
            var draw=new PredictionRenderer.Draw(tile,gpu,all,false,31,0,new AABB(0,80,0,128,82,128));
            var ranges=payload.drawRanges(false,31);assertEquals(quads,ranges.quads);
            for(var program:List.of(vertex,nv))PredictionVegetationRenderGpuTest.setup(program,new Matrix4f().perspective(1.2f,1,.05f,2048),camera,false,false,depth);
            byte[][] pixels=new byte[2][];float[][] depths=new float[2][];
            for(int mode=0;mode<2;mode++) {
                clear(target);render(mode,vertex,nv,batch,draw,ranges,camera,indirect,sentinel,quads,1);
                var b=BufferUtils.createByteBuffer(256*256*4);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,b);pixels[mode]=new byte[b.remaining()];b.get(pixels[mode]);
                var d=BufferUtils.createFloatBuffer(256*256);glReadPixels(0,0,256,256,GL_DEPTH_COMPONENT,GL_FLOAT,d);depths[mode]=new float[d.remaining()];d.get(depths[mode]);
            }
            assertArrayEquals(pixels[0],pixels[1],kind+" colors");assertArrayEquals(depths[0],depths[1],kind+" depths");
            int colored=0;for(int p=0;p<pixels[0].length;p+=4)if(pixels[0][p]!=0||pixels[0][p+1]!=0||pixels[0][p+2]!=0)colored++;assertTrue(colored>50);
            for(boolean raster:new boolean[]{false,true}) {
                if(raster)glDisable(GL_RASTERIZER_DISCARD);else glEnable(GL_RASTERIZER_DISCARD);
                long[][] times=new long[2][81],cpu=new long[2][81];
                for(int round=-24;round<81;round++)for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);clear(target);glFinish();long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                    render(mode,vertex,nv,batch,draw,ranges,camera,indirect,sentinel,quads,64);glEndQuery(GL_TIME_ELAPSED);
                    long cpuTime=System.nanoTime()-start,time=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                    if(round>=0){times[mode][round]=time;cpu[mode][round]=cpuTime;rows.add(kind+","+raster+","+round+","+mode+","+time+","+cpuTime+","+quads*64L);}
                }
                System.out.printf(Locale.ROOT,"MESH_SHADER_AB kind=%s raster=%s quads=%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",kind,raster,quads*64L,
                        CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6);
            }
            glDisable(GL_RASTERIZER_DISCARD);assertEquals(GL_NO_ERROR,glGetError());
        } finally {target.destroyBuffers();glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,0);glBindFramebuffer(GL_FRAMEBUFFER,0);glBindVertexArray(0);
            for(int b:new int[]{ebo,indirect,sentinel})glDeleteBuffers(b);glDeleteQueries(query);glDeleteVertexArrays(vao);}
    }
    private static void render(int mode,PredictionTerrainProgram vertex,PredictionTerrainProgram nv,PredictionIndirectBatch batch,
            PredictionRenderer.Draw draw,PredictionDrawRanges ranges,Vec3 camera,int indirect,int sentinel,int quads,int repeats) {
        if(mode==0){vertex.use();batch.begin(vertex);for(int r=0;r<repeats;r++)assertTrue(batch.add(draw,ranges,camera,false,false,Long.MAX_VALUE));batch.end();}
        else {nv.use();nv.batch(true);nv.setBatchCameraOffset((float)-camera.x,(float)-camera.y,(float)-camera.z);nv.setBatchTimeSeconds(0);
            glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,draw.gpu().arenaSlice().page.texture);
            int program=glGetInteger(GL_CURRENT_PROGRAM);glUniform1i(glGetUniformLocation(program,"MeshFirstQuad"),0);glUniform1i(glGetUniformLocation(program,"MeshQuadCount"),quads);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,metadata(batch));glBindBuffer(GL_DRAW_INDIRECT_BUFFER,indirect);
            NVMeshShader.glMultiDrawMeshTasksIndirectNV(0L,repeats,8);}
    }
    private static int metadata(PredictionIndirectBatch batch) {
        try {var f=PredictionIndirectBatch.class.getDeclaredField("opaqueSlots");f.setAccessible(true);
            var slot=((java.util.List<?>)f.get(batch)).get(0);var b=slot.getClass().getDeclaredField("metadata");b.setAccessible(true);return b.getInt(slot);
        } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void clear(TextureTarget target){target.bindWrite(true);glViewport(0,0,256,256);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearColor(0,0,0,0);glClearDepth(0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);}
}
