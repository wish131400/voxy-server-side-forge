package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Production shader and production MDI commands; repeated synthetic plant geometry, not a game FPS test. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionVegetationLodGpuTest {
    @Test void compactPlantRangesReachMdiPreserveFallbackAndReduceGpuWork() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft(); VssLodSpriteTable.close();
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(256,256,"VSS vegetation LOD",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();
        var csv=new ArrayList<String>();csv.add("scenario,round,mode,gpu_ns,cpu_ns,mdi_quads,mdi_commands");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();if(!RenderSystem.isOnRenderThread())RenderSystem.initRenderThread();
            System.out.println("VEGETATION_LOD_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            try(var contents=PredictionVegetationMaterialTest.contents("block/vss_lod_grass")) {
                int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
                int[] words=plants(row);var packed=PredictionPackedMesh.terrainRecords(words.clone(),128);
                assertNotNull(packed.displayErrors());
                var near=packed.drawRanges(false,31,0,0,0);var far=packed.drawRanges(false,31,0,0,1);
                assertEquals(32768,near.quads);assertEquals(8192,far.quads);
                assertEquals(far.quads,packed.drawRanges(false,31,3,0,1).quads,"aquatic level must not affect these land plants");
                assertEquals(near.quads,packed.quadCount(),"independent plant selection needs no appended geometry");
                packed.prepareGpuStorage();var mesh=PredictionMesh.restored(32768*6,0,packed,null);
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
                var tile=new PredictionTileManager.PredictionTile(key,new int[129*129],new int[129*129],
                        new ClientColumnSample[129*129],mesh,new PredictionDepthBound(80,82),0,1,128,1);
                int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
                int[] indices=new int[packed.quadCount()*6],corners={0,1,2,0,2,3};
                for(int q=0;q<packed.quadCount();q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
                PredictionVegetationRenderGpuTest.setupTextures(textures);
                glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
                float[] depthValues=new float[256*256];Arrays.fill(depthValues,1);
                int depth=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,depthValues);
                var camera=new Vec3(64,130,170);var target=new TextureTarget(256,256,true,false);
                try(var gpu=new PredictionGpuTile(key);var shader=PredictionTerrainProgram.createBatch();
                    var fullBatch=new PredictionIndirectBatch();var reducedBatch=new PredictionIndirectBatch()) {
                    assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
                    boolean[] all=new boolean[128*128];Arrays.fill(all,true);gpu.updateCoverage(all);
                    byte[] coverage=new byte[all.length];Arrays.fill(coverage,(byte)192);gpu.updateBoundaryCoverage(coverage);
                    var draw=new PredictionRenderer.Draw(tile,gpu,all,false,31,0,new AABB(0,80,0,128,82,128));
                    PredictionVegetationRenderGpuTest.setup(shader,new Matrix4f().perspective(1.2f,1,.05f,2048),camera,false,false,depth);
                    // Each level has different intentional detail, but its direct
                    // and MDI paths must agree exactly, including pass-local offsets.
                    for(var ranges:List.of(near,far)) {
                        render(shader,draw,ranges,camera,target,fullBatch,false,1);byte[] direct=pixels();
                        render(shader,draw,ranges,camera,target,fullBatch,true,1);assertArrayEquals(direct,pixels());
                        int colored=0;for(int p=3;p<direct.length;p+=4)if(direct[p]!=0)colored++;
                        assertTrue(colored>50,"nonblank plant geometry");
                    }
                    // Production preparation must restore source detail for near
                    // views, telescope and partial parent/child ownership.
                    double scale=staticDouble("selectionScale");
                    try {
                        setStatic("selectionScale",1475.0);
                        var pass=new PredictionPreparedPassLocalTest.Pass(false);
                        pass.prepare(List.of(draw),1,new Vec3(10000,130,0));
                        var distant=pass.ranges(draw);
                        assertTrue(distant==null || distant.quads<near.quads,
                                "a pure small-plant tile may disappear entirely at the farthest tier");
                        pass.prepare(List.of(draw),1,camera);assertEquals(near.quads,pass.ranges(draw).quads);
                        boolean[] mixed=all.clone();mixed[3]=false;
                        var partial=new PredictionRenderer.Draw(tile,gpu,mixed,false,31,0,draw.bounds());
                        pass.prepare(List.of(partial),1,new Vec3(10000,130,0));assertEquals(near.quads,pass.ranges(partial).quads);
                        setStatic("selectionScoping",true);pass.prepare(List.of(draw),1,new Vec3(10000,130,0));
                        assertEquals(near.quads,pass.ranges(draw).quads);
                    } finally {setStatic("selectionScale",scale);setStatic("selectionScoping",false);}
                    var batches=List.of(fullBatch,reducedBatch);var ranges=List.of(near,far);
                    int query=glGenQueries();
                    try {
                        for(int scenario=0;scenario<2;scenario++) {
                            boolean vertexOnly=scenario==0;
                            if(vertexOnly)glEnable(GL_RASTERIZER_DISCARD);else glDisable(GL_RASTERIZER_DISCARD);
                            long[][] times=new long[2][81],cpu=new long[2][81];long[] actual=new long[2],commands=new long[2];
                            for(int round=-24;round<81;round++)for(int order=0;order<2;order++) {
                                int mode=Math.floorMod(round+order,2);target.bindWrite(true);clear();glFinish();
                                long started=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                                render(shader,draw,ranges.get(mode),camera,target,batches.get(mode),true,64);
                                glEndQuery(GL_TIME_ELAPSED);long elapsedCpu=System.nanoTime()-started;
                                long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                                actual[mode]=counter(batches.get(mode),"lastMdiQuads");commands[mode]=counter(batches.get(mode),"lastMdiCommands");
                                assertEquals(ranges.get(mode).quads*64L,actual[mode]);
                                if(round>=0){times[mode][round]=elapsed;cpu[mode][round]=elapsedCpu;
                                    csv.add(scenario+","+round+","+mode+","+elapsed+","+elapsedCpu+","+actual[mode]+","+commands[mode]);}
                            }
                            System.out.printf(Locale.ROOT,"VEGETATION_LOD_AB scenario=%s quads=%d->%d commands=%d->%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",
                                    vertexOnly?"vertex":"raster",actual[0],actual[1],commands[0],commands[1],
                                    CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                                    CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6);
                        }
                    } finally {glDisable(GL_RASTERIZER_DISCARD);glDeleteQueries(query);}
                    assertEquals(GL_NO_ERROR,glGetError());
                } finally {target.destroyBuffers();glDeleteBuffers(ebo);glDeleteVertexArrays(vao);}
            }
        } finally {
            glFinish();PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);
            VssLodSpriteTable.close();glfwDestroyWindow(window);glfwTerminate();
        }
        CompletionGpuFixture.save("vegetation-lod.csv",csv);
    }

    @Test void terrainPixelBudgetChangesActualMdiRangeAndGpuWorkWithoutAddingGeometry() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft(); VssLodSpriteTable.close();
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(256,256,"VSS terrain LOD",0,0); assertNotEquals(0,window);
        var textures = new ArrayList<Integer>();
        var csv = new ArrayList<String>(); csv.add("scenario,round,mode,gpu_ns,cpu_ns,mdi_quads,mdi_commands");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            int[] words = new int[128 * 128 * 12];
            for (int z=0;z<128;z++) for(int x=0;x<128;x++) {
                int q=z*128+x,p=q*12;
                PredictionSpatialOrderTest.quad(words,q,x,z,80,PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE);
                int height=33088+((x+z)&1);
                words[p+4]=words[p+5]=height|height<<16;
                words[p+8]=q;
                for(int c:new int[]{7,9,10,11}) words[p+c]=0x668844;
            }
            var packed=PredictionPackedMesh.terrainRecords(words,128);
            int uploadedQuads=packed.quadCount();
            float error=packed.displayErrors()[1];
            assertTrue(error>0&&Float.isFinite(error),"terrain error="+Arrays.toString(packed.displayErrors()));
            double eyeDepth=error+error*1475/1.4;
            var original=new PredictionDisplayLod();var normal=new PredictionDisplayLod();
            original.update(packed.displayErrors(),eyeDepth,1475,512,false,1.5);
            normal.update(packed.displayErrors(),eyeDepth,1475,512,false,PredictionDisplayLod.terrainPixelError("normal"));
            assertEquals(0,original.tier());assertTrue(normal.tier()>0);
            var exact=packed.drawRanges(false,31,0,original.tier(),0);
            var reduced=packed.drawRanges(false,31,0,normal.tier(),0);
            assertEquals(16384,exact.quads);assertTrue(reduced.quads<exact.quads);
            assertEquals(uploadedQuads,packed.quadCount(),"selection must reuse the existing terrain allocation");
            packed.prepareGpuStorage();var mesh=PredictionMesh.restored(16384*6,0,packed,null);
            var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
            var tile=new PredictionTileManager.PredictionTile(key,new int[129*129],new int[129*129],
                    new ClientColumnSample[129*129],mesh,new PredictionDepthBound(80,81),0,1,128,1);
            int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int[] indices=new int[packed.quadCount()*6],corners={0,1,2,0,2,3};
            for(int q=0;q<packed.quadCount();q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
            float[] depthValues=new float[256*256];Arrays.fill(depthValues,1);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,depthValues);
            var camera=new Vec3(64,130,170);var target=new TextureTarget(256,256,true,false);
            try(var gpu=new PredictionGpuTile(key);var shader=PredictionTerrainProgram.createBatch();
                var fullBatch=new PredictionIndirectBatch();var reducedBatch=new PredictionIndirectBatch()) {
                assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
                boolean[] all=new boolean[128*128];Arrays.fill(all,true);gpu.updateCoverage(all);
                byte[] coverage=new byte[all.length];Arrays.fill(coverage,(byte)192);gpu.updateBoundaryCoverage(coverage);
                var draw=new PredictionRenderer.Draw(tile,gpu,all,false,31,0,new AABB(0,80,0,128,81,128));
                PredictionVegetationRenderGpuTest.setup(shader,new Matrix4f().perspective(1.2f,1,.05f,2048),camera,false,false,depth);
                for(var ranges:List.of(exact,reduced)) {
                    render(shader,draw,ranges,camera,target,fullBatch,false,1);byte[] direct=pixels();
                    render(shader,draw,ranges,camera,target,fullBatch,true,1);assertArrayEquals(direct,pixels());
                    int colored=0;for(int p=3;p<direct.length;p+=4)if(direct[p]!=0)colored++;
                    assertTrue(colored>50,"nonblank terrain geometry");
                }
                var batches=List.of(fullBatch,reducedBatch);var ranges=List.of(exact,reduced);int query=glGenQueries();
                try {
                    for(int scenario=0;scenario<2;scenario++) {
                        if(scenario==0)glEnable(GL_RASTERIZER_DISCARD);else glDisable(GL_RASTERIZER_DISCARD);
                        long[][] times=new long[2][81],cpu=new long[2][81];long[] actual=new long[2],commands=new long[2];
                        for(int round=-24;round<81;round++)for(int order=0;order<2;order++) {
                            int mode=Math.floorMod(round+order,2);target.bindWrite(true);clear();glFinish();
                            long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                            render(shader,draw,ranges.get(mode),camera,target,batches.get(mode),true,64);
                            glEndQuery(GL_TIME_ELAPSED);long cpuTime=System.nanoTime()-start;
                            long time=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                            actual[mode]=counter(batches.get(mode),"lastMdiQuads");commands[mode]=counter(batches.get(mode),"lastMdiCommands");
                            assertEquals(ranges.get(mode).quads*64L,actual[mode]);
                            if(round>=0){times[mode][round]=time;cpu[mode][round]=cpuTime;
                                csv.add(scenario+","+round+","+mode+","+time+","+cpuTime+","+actual[mode]+","+commands[mode]);}
                        }
                        System.out.printf(Locale.ROOT,"TERRAIN_LOD_AB scenario=%s quads=%d->%d commands=%d->%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",
                                scenario==0?"vertex":"raster",actual[0],actual[1],commands[0],commands[1],
                                CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                                CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6);
                    }
                } finally {glDisable(GL_RASTERIZER_DISCARD);glDeleteQueries(query);}
                assertEquals(GL_NO_ERROR,glGetError());
            } finally {target.destroyBuffers();glDeleteBuffers(ebo);glDeleteVertexArrays(vao);}
        } finally {
            glFinish();PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);
            VssLodSpriteTable.close();glfwDestroyWindow(window);glfwTerminate();
        }
        CompletionGpuFixture.save("terrain-lod.csv",csv);
    }

    static int[] plants(int row) {
        int[] words=new int[32768*12];
        for(int z=0;z<128;z++)for(int x=0;x<128;x++)for(int face=0;face<2;face++) {
            int q=(z*128+x)*2+face;
            PredictionCrossEncodingTest.cross(words,q,face==0?x:x+1,z,33088,face==0?x+1:x,z+1,33096,face+1);
            words[q*12+6]=words[q*12+6]&~65535|row;words[q*12+8]=z*128+x;
        }
        return words;
    }
    private static void render(PredictionTerrainProgram shader,PredictionRenderer.Draw draw,PredictionDrawRanges ranges,
                               Vec3 camera,TextureTarget target,PredictionIndirectBatch batch,boolean mdi,int repeats) {
        target.bindWrite(true);clear();shader.use();shader.setOpaqueAlpha(1);
        if(mdi) {
            batch.begin(shader);
            for(int r=0;r<repeats;r++)assertTrue(batch.add(draw,ranges,camera,false,false,Long.MAX_VALUE));
            assertTrue(batch.end()>0);
        } else {
            shader.batch(false);draw.gpu().bindTerrain(shader);draw.gpu().bindYield(3);
            shader.setTile((float)-camera.x,(float)-camera.y,(float)-camera.z,1,128,false);
            for(int r=0;r<repeats;r++)PredictionRenderer.submitRanges(ranges,draw.gpu().packed(),false);
        }
    }
    private static void clear() {
        glViewport(0,0,256,256);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);
        glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static byte[] pixels() {
        var buffer=BufferUtils.createByteBuffer(256*256*4);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,buffer);
        byte[] result=new byte[buffer.remaining()];buffer.get(result);return result;
    }
    private static long counter(PredictionIndirectBatch batch,String name) throws Exception {
        var field=PredictionIndirectBatch.class.getDeclaredField(name);field.setAccessible(true);return field.getLong(batch);
    }
    private static double staticDouble(String name) throws Exception {
        var field=PredictionRenderer.class.getDeclaredField(name);field.setAccessible(true);return field.getDouble(null);
    }
    private static void setStatic(String name,Object value) throws Exception {
        var field=PredictionRenderer.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
}
