package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import java.nio.file.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraft.client.renderer.culling.Frustum;
import org.junit.jupiter.api.Test;

/** Real production arena/program/pass/MDI, alternating identical camera inputs. */
class PredictionGpuTraversalExperimentTest {
    @Test void rotatingResidentCommandsMatchCpuVisibilityAndMeasureWholePass() throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(512,512,"GPU traversal comparison",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();var allocated=new ArrayList<PredictionGpuTile>();
        Map<String,String> properties=new HashMap<>();
        for(String name:List.of("vss.gpuPredictionSelection","vss.gpuPredictionSelectionMode","vss.disablePredictionOcclusion","vss.forcePredictionOcclusion"))
            properties.put(name,System.getProperty(name));
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            System.out.println("GPU_TRAVERSAL_DEVICE "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            assertTrue(TraversalGpuCommandSelector.supported());
            int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int quadCapacity=4096;int[] indices=new int[quadCapacity*6],corners={0,1,2,0,2,3};
            for(int q=0;q<quadCapacity;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            tex(textures,0,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
            tex(textures,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
            tex(textures,2,1,GL_RGBA32F,GL_RGBA,new float[4]);
            int exact=tex(textures,8,1,GL_R8,GL_RED,new float[]{0});
            float[] clear=new float[512*512];Arrays.fill(clear,1);
            int mainDepth=tex(textures,7,512,GL_R32F,GL_RED,clear);
            glActiveTexture(GL_TEXTURE6);int volume=glGenTextures();textures.add(volume);glBindTexture(GL_TEXTURE_3D,volume);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage3D(GL_TEXTURE_3D,0,GL_R8,1,1,1,0,GL_RED,GL_FLOAT,new float[]{0});
            var target=new TextureTarget(512,512,true,false);
            int query=glGenQueries();
            var rows=new ArrayList<String>();rows.add("tiles,case,round,mode,cpu_ns,gpu_ns,visible_tiles,encoded_items,uploaded_bytes");
            var throughputRows=new ArrayList<String>();throughputRows.add("tiles,case,round,mode,frames,elapsed_ns");
            try(var program=PredictionTerrainProgram.createBatch()) {
                program.use();program.setSamplers(0,1,2,3,4);
                program.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                int id=glGetInteger(GL_CURRENT_PROGRAM);
                glUniform1i(glGetUniformLocation(id,"ExactCoverage"),8);
                glUniform3f(glGetUniformLocation(id,"ExactCoverageGrid"),0,0,1);
                glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);
                glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),1,1,1);
                glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
                glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});

                for(int count:new int[]{128,1024,1025}) {
                    var resident=new ArrayList<PredictionRenderer.Draw>();
                    for(int t=0;t<count;t++)resident.add(tile(t,count,allocated));
                    for(int scenario=0;scenario<3;scenario++) {
                        var plans=new ArrayList<TraversalExperimentVisiblePlan<Integer,PredictionRenderer.Draw>>();
                        var passes=new ArrayList<PredictionPreparedPassLocalTest.Pass>();
                        var batches=new ArrayList<TraversalExperimentBatch>();
                        var groupings=new ArrayList<PredictionOpaqueBatches>();
                        for(int mode=0;mode<4;mode++) {
                            var plan=new TraversalExperimentVisiblePlan<Integer,PredictionRenderer.Draw>(mode == 0,PredictionRenderer.Draw::bounds);
                            for(int t=0;t<count;t++)plan.put(t,resident.get(t));
                            plans.add(plan);passes.add(new PredictionPreparedPassLocalTest.Pass(false));
                            batches.add(new TraversalExperimentBatch(mode == 0));groupings.add(new PredictionOpaqueBatches());
                        }
                        long[][] cpu=new long[4][41],gpu=new long[4][41];
                        try {
                            for(int round=-20;round<41;round++) {
                                double yaw=scenario==0?0:round*.037;
                                var camera=new Vec3(0,110,0);
                                var view=new Matrix4f().lookAlong((float)Math.sin(yaw),-.13f,(float)-Math.cos(yaw),0,1,0);
                                var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096));
                                var transform=new Matrix4f(projection.matrix()).mul(view);
                                var frustum=new Frustum(view,projection.culling());frustum.prepare(camera.x,camera.y,camera.z);
                                byte[] referencePixels=null;float[] referenceDepth=null;
                                for(int order=0;order<4;order++) {
                                    int mode=round>=0 && round%2!=0?3-order:order;
                                    System.setProperty("vss.gpuPredictionSelection",mode==0?"false":"true");
                                    System.setProperty("vss.gpuPredictionSelectionMode",Integer.toString(mode-1));
                                    if(scenario==2){System.clearProperty("vss.disablePredictionOcclusion");System.setProperty("vss.forcePredictionOcclusion","true");}
                                    else {System.setProperty("vss.disablePredictionOcclusion","true");System.clearProperty("vss.forcePredictionOcclusion");}
                                    target.bindWrite(true);glBindVertexArray(vao);program.use();program.setCamera(view,projection.matrix());
                                    program.bindMainDepth(mainDepth,projection);glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D,exact);
                                    glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
                                    glClearDepth(scenario==2?.001:0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                                    program.setOpaqueAlpha(1);glFinish();
                                    var batch=batches.get(mode);var pass=passes.get(mode);var groups=groupings.get(mode);
                                    long encodedBefore=batch.encodedItems(),start=System.nanoTime();
                                    glBeginQuery(GL_TIME_ELAPSED,query);
                                    List<PredictionRenderer.Draw> draws=mode==0?plans.get(mode).selectStable(camera,view,projection.matrix(),frustum):plans.get(mode).residents();
                                    pass.prepare(draws,1,camera);
                                    batch.begin(program,target.getDepthTextureId(),512,512,transform,camera,1,1,pass.counter("workloadRevision"));
                                    long replay=batch.replayPass(pass.value,pass.counter("revision"),true,camera,Long.MAX_VALUE);
                                    if(replay<0) {
                                        int bucketCount=groups.prepare(draws,1,ranges(pass),camera);
                                        for(int b=0;b<bucketCount;b++) {
                                            batch.nextOcclusionBucket();
                                            for(var page:groups.bucket(b).pages) {
                                                batch.nextRegion(page.region);
                                                for(var draw:page.draws)assertTrue(batch.add(draw,pass.ranges(draw),camera,false,true,Long.MAX_VALUE));
                                            }
                                            fallbacks(groups.bucket(b),batch,pass,program,camera);
                                        }
                                    }
                                    batch.end();glEndQuery(GL_TIME_ELAPSED);
                                    long elapsed=System.nanoTime()-start,gpuTime=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                                    if(round>=0){cpu[mode][round]=elapsed;gpu[mode][round]=gpuTime;
                                        rows.add(count+","+scenario+","+round+","+mode+","+elapsed+","+gpuTime+","+draws.size()+","+(batch.encodedItems()-encodedBefore)+","+batch.uploadedBytes());}
                                    if(round==0 || round==17 || round==40) {
                                        var bytes=BufferUtils.createByteBuffer(512*512*4);glReadPixels(0,0,512,512,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
                                        byte[] pixels=new byte[bytes.remaining()];bytes.get(pixels);
                                        if(round==0 && scenario<2 && mode==0) {
                                            int colored=0;for(int p=0;p<pixels.length;p+=4)if(pixels[p]!=0 || pixels[p+1]!=0 || pixels[p+2]!=0)colored++;
                                            assertTrue(colored>128,"the correctness comparison must contain visible terrain");
                                        }
                                        var depth=BufferUtils.createFloatBuffer(512*512);glReadPixels(0,0,512,512,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
                                        float[] depths=new float[depth.remaining()];depth.get(depths);
                                        if(referencePixels==null){referencePixels=pixels;referenceDepth=depths;}
                                        else {assertArrayEquals(referencePixels,pixels,"pixels count="+count+" case="+scenario+" round="+round+" mode="+mode);
                                            assertArrayEquals(referenceDepth,depths,"depth count="+count+" case="+scenario+" round="+round+" mode="+mode);}
                                    }
                                    assertEquals(GL_NO_ERROR,glGetError(),"mode="+mode+" "+batch.diagnostics());
                                }
                            }
                            for(int mode=0;mode<4;mode++)System.out.printf(Locale.ROOT,"GPU_TRAVERSAL tiles=%d case=%d mode=%d CPU_ms=%.6f GPU_ms=%.6f%n",
                                    count,scenario,mode,CompletionGpuFixture.median(cpu[mode])/1e6,CompletionGpuFixture.median(gpu[mode])/1e6);
                        } finally {batches.forEach(TraversalExperimentBatch::close);}
                    }
                    throughput(program,resident,count,target,vao,mainDepth,exact,throughputRows);
                    allocated.forEach(PredictionGpuTile::close);allocated.clear();glFinish();PredictionTerrainArena.SHARED.reap();
                }
            } finally {
                glDeleteQueries(query);target.destroyBuffers();glBindVertexArray(0);glDeleteBuffers(ebo);glDeleteVertexArrays(vao);
            }
            Path output=Path.of(System.getProperty("vss.gpuTraversalResults","build/gpu-traversal-results"));Files.createDirectories(output);
            Files.write(output.resolve("production-gpu-traversal.csv"),rows);
            Files.write(output.resolve("production-gpu-traversal-throughput.csv"),throughputRows);
        } finally {
            for(var e:properties.entrySet())if(e.getValue()==null)System.clearProperty(e.getKey());else System.setProperty(e.getKey(),e.getValue());
            allocated.forEach(PredictionGpuTile::close);glFinish();PredictionTerrainArena.SHARED.reap();PredictionTerrainArena.SHARED.close();
            PredictionTerrainArena.WATER.reap();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static void throughput(PredictionTerrainProgram program,List<PredictionRenderer.Draw> residents,int count,
            TextureTarget target,int vao,int mainDepth,int exact,List<String> rows) throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            var plans=new ArrayList<TraversalExperimentVisiblePlan<Integer,PredictionRenderer.Draw>>();
            var passes=new ArrayList<PredictionPreparedPassLocalTest.Pass>();
            var batches=new ArrayList<TraversalExperimentBatch>();
            var groupings=new ArrayList<PredictionOpaqueBatches>();
            for(int mode=0;mode<4;mode++) {
                var plan=new TraversalExperimentVisiblePlan<Integer,PredictionRenderer.Draw>(mode == 0,PredictionRenderer.Draw::bounds);
                for(int t=0;t<count;t++)plan.put(t,residents.get(t));
                plans.add(plan);passes.add(new PredictionPreparedPassLocalTest.Pass(false));
                batches.add(new TraversalExperimentBatch(mode == 0));groupings.add(new PredictionOpaqueBatches());
            }
            int frames=32;long[][] elapsed=new long[4][21];
            try {
                for(int round=-8;round<21;round++) for(int order=0;order<4;order++) {
                    int mode=Math.floorMod(round+order,4);
                    System.setProperty("vss.gpuPredictionSelection",mode==0?"false":"true");
                    System.setProperty("vss.gpuPredictionSelectionMode",Integer.toString(mode-1));
                    if(scenario==2){System.clearProperty("vss.disablePredictionOcclusion");System.setProperty("vss.forcePredictionOcclusion","true");}
                    else {System.setProperty("vss.disablePredictionOcclusion","true");System.clearProperty("vss.forcePredictionOcclusion");}
                    glFinish();long start=System.nanoTime();
                    for(int f=0;f<frames;f++) {
                        double yaw=scenario==0?0:(round*frames+f)*.007;
                        var camera=new Vec3(0,110,0);
                        var view=new Matrix4f().lookAlong((float)Math.sin(yaw),-.13f,(float)-Math.cos(yaw),0,1,0);
                        var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096));
                        var transform=new Matrix4f(projection.matrix()).mul(view);
                        var frustum=new Frustum(view,projection.culling());frustum.prepare(camera.x,camera.y,camera.z);
                        target.bindWrite(true);glBindVertexArray(vao);program.use();program.setCamera(view,projection.matrix());
                        program.bindMainDepth(mainDepth,projection);glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D,exact);
                        glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
                        glClearDepth(scenario==2?.001:0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        program.setOpaqueAlpha(1);
                        var batch=batches.get(mode);var pass=passes.get(mode);var groups=groupings.get(mode);
                        List<PredictionRenderer.Draw> draws=mode==0?plans.get(mode).selectStable(camera,view,projection.matrix(),frustum):plans.get(mode).residents();
                        pass.prepare(draws,1,camera);
                        batch.begin(program,target.getDepthTextureId(),512,512,transform,camera,1,1,pass.counter("workloadRevision"));
                        long replay=batch.replayPass(pass.value,pass.counter("revision"),true,camera,Long.MAX_VALUE);
                        if(replay<0) {
                            int buckets=groups.prepare(draws,1,ranges(pass),camera);
                            for(int b=0;b<buckets;b++) {
                                batch.nextOcclusionBucket();
                                for(var page:groups.bucket(b).pages) {
                                    batch.nextRegion(page.region);
                                    for(var draw:page.draws)assertTrue(batch.add(draw,pass.ranges(draw),camera,false,true,Long.MAX_VALUE));
                                }
                                fallbacks(groups.bucket(b),batch,pass,program,camera);
                            }
                        }
                        batch.end();
                    }
                    glFinish();long time=System.nanoTime()-start;
                    if(round>=0){elapsed[mode][round]=time;rows.add(count+","+scenario+","+round+","+mode+","+frames+","+time);}
                    assertEquals(GL_NO_ERROR,glGetError());
                }
                for(int mode=0;mode<4;mode++)System.out.printf(Locale.ROOT,"GPU_TRAVERSAL_PIPELINED tiles=%d case=%d mode=%d ms_per_pass=%.6f%n",
                        count,scenario,mode,CompletionGpuFixture.median(elapsed[mode])/1e6/frames);
            } finally{batches.forEach(TraversalExperimentBatch::close);}
        }
    }
    private static void fallbacks(PredictionOpaqueBatches.Bucket group,TraversalExperimentBatch batch,
            PredictionPreparedPassLocalTest.Pass pass,PredictionTerrainProgram program,Vec3 camera)throws Exception {
        if(!group.fallback.isEmpty())batch.flush();
        // drawSingle uses the renderer's active program; use its exact body here
        // so the experimental program is bound even outside a Minecraft frame.
        for(var draw:group.fallback) {
            var ranges=pass.ranges(draw);if(ranges==null || ranges.quads==0)continue;
            batch.fallback(draw,ranges,camera);program.batch(false);
            var packed=draw.gpu().packed();draw.gpu().bindTerrain(program,false);draw.gpu().bindYield(3);
            program.setTile((float)(draw.tile().baseBlockX()-camera.x),(float)-camera.y,
                    (float)(draw.tile().baseBlockZ()-camera.z),draw.tile().spacingBlocks(),packed.cellAxis(),true);
            program.setMorph(packed,0);program.setBoundaryReplacement(!draw.seam());
            PredictionRenderer.submitRanges(ranges,packed,false);
        }
    }
    @SuppressWarnings("unchecked") private static IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges> ranges(PredictionPreparedPassLocalTest.Pass pass)throws Exception {
        var field=pass.type.getDeclaredField("byDraw");field.setAccessible(true);return (IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>)field.get(pass.value);
    }
    private static PredictionRenderer.Draw tile(int index,int count,List<PredictionGpuTile> allocated) {
        int side=(int)Math.ceil(Math.sqrt(count));int x=index%side-side/2,z=index/side-side/2;
        int[] words=PredictionDisplayGeometryTest.grid(0);
        boolean seam=count==1025 && index%3!=0;
        if(seam)words=Arrays.copyOf(words,64*12);
        var payload=PredictionPackedMesh.seamRecords(words,64);payload.prepareGpuStorage();
        var mesh=PredictionMesh.restored(words.length/12*6,0,payload,null);
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x,z,0);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,new PredictionDepthBound(80,80),0,index+1,64,1);
        var gpu=new PredictionGpuTile(key);allocated.add(gpu);
        if(seam){assertTrue(gpu.ensureSeams(payload)>0);assertNotNull(gpu.arenaSlice());}
        else {assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());}
        boolean[] allowed=new boolean[4096];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
        byte[] mask=new byte[4096];Arrays.fill(mask,(byte)128);gpu.updateBoundaryCoverage(mask);
        return new PredictionRenderer.Draw(tile,gpu,allowed,seam,VssLodFaceGroup.ALL,0,
                new AABB(tile.baseBlockX(),78,tile.baseBlockZ(),tile.baseBlockX()+64,82,tile.baseBlockZ()+64));
    }
    private static int tex(List<Integer> textures,int unit,int size,int format,int type,float[] data) {
        glActiveTexture(GL_TEXTURE0+unit);int texture=glGenTextures();textures.add(texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D,0,format,size,size,0,type,GL_FLOAT,data);return texture;
    }
}
