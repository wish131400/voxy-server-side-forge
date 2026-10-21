package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import java.lang.reflect.Field;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Frozen current baseline and candidate use the same geometry, shader and selection policy. */
class NextSubmissionGpuTest {
    private static final Field RANGES=field("byDraw");
    private static final Field REVISION=field("revision");
    private record Sample(long cpu,long gpu,long commands,long uploaded){}
    private static final class Path implements AutoCloseable {
        final boolean before;
        final NextBeforeGpuSelectionInput oldInput=new NextBeforeGpuSelectionInput();
        final PredictionGpuSelectionInput input=new PredictionGpuSelectionInput();
        final NextBeforePass oldPass;
        final PredictionPreparedPassLocalTest.Pass pass;
        final NextBeforeIndirectBatch oldBatch=new NextBeforeIndirectBatch();
        final PredictionIndirectBatch batch=new PredictionIndirectBatch();
        final PredictionOpaqueBatches groups=new PredictionOpaqueBatches();
        Path(boolean before)throws Exception {this.before=before;oldPass=new NextBeforePass(false);pass=new PredictionPreparedPassLocalTest.Pass(false);}
        Sample draw(CompletionGpuFixture f,PredictionTerrainProgram shader,List<PredictionRenderer.Draw> source,Vec3 camera,long residency)throws Exception {return draw(f,shader,source,camera,residency,true);}
        @SuppressWarnings("unchecked") Sample draw(CompletionGpuFixture f,PredictionTerrainProgram shader,List<PredictionRenderer.Draw> source,Vec3 camera,long residency,boolean timed)throws Exception {
            shader.use();f.clear(false);shader.setOpaqueAlpha(1);
            long start=System.nanoTime();if(timed)glBeginQuery(GL_TIME_ELAPSED,f.timer);
            List<PredictionRenderer.Draw> draws;IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges> ranges;
            long revision;Object token;
            if(before){oldInput.update(source,camera,16384);draws=oldInput.candidates();oldPass.prepare(draws,residency,camera);ranges=oldPass.ranges();revision=oldPass.revision();token=oldPass;oldBatch.begin(shader,true,residency,1);}
            else{input.update(source,camera,16384);draws=input.candidates();pass.prepare(draws,residency,camera);ranges=(IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>)RANGES.get(pass.value);revision=REVISION.getLong(pass.value);token=pass.value;batch.begin(shader,true,residency,1);}
            long replay=before?oldBatch.replayPass(token,revision,false,camera,Long.MAX_VALUE):batch.replayPass(token,revision,false,camera,Long.MAX_VALUE);
            if(replay<0){
                int buckets=groups.prepare(draws,revision,ranges,camera);
                for(int b=0;b<buckets;b++){
                    var bucket=groups.bucket(b);
                    for(var page:bucket.pages)for(var draw:page.draws){var selected=ranges.get(draw);if(selected!=null&&selected.quads>0)assertTrue(before?oldBatch.add(draw,selected,camera,false,false,Long.MAX_VALUE):batch.add(draw,selected,camera,false,false,Long.MAX_VALUE));}
                    assertTrue(bucket.fallback.isEmpty());
                }
            }
            long commands=before?oldBatch.end():batch.end();if(timed)glEndQuery(GL_TIME_ELAPSED);
            long cpu=System.nanoTime()-start,gpu=timed?glGetQueryObjectui64(f.timer,GL_QUERY_RESULT):0;
            return new Sample(cpu,gpu,commands,before?oldBatch.uploadedBytes():batch.uploadedBytes());
        }
        public void close(){oldBatch.close();batch.close();oldInput.clear();input.clear();oldPass.clear();try{pass.clear.invoke(pass.value);}catch(Exception e){throw new AssertionError(e);}groups.clear();}
    }
    @Test void compareCompleteSubmissionDuringUnrelatedSparseBulkAndViewUpdates()throws Exception {
        try(var f=new CompletionGpuFixture()){
            var textures=new ArrayList<Integer>();var allocated=new ArrayList<PredictionGpuTile>();
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,new float[256*256]);
            var rows=new ArrayList<String>();rows.add("tiles,case,round,mode,cpu_ns,gpu_ns,calls,uploaded_bytes");
            var through=new ArrayList<String>();through.add("tiles,case,round,mode,frames,elapsed_ns");
            try(var shader=PredictionTerrainProgram.createBatch()){
                for(int count:new int[]{128,4096,8192}){
                    var draws=new ArrayList<PredictionRenderer.Draw>();for(int i=0;i<count;i++)draws.add(NextIrisBatchGpuTest.upload(i,count,allocated));
                    int side=(int)Math.ceil(Math.sqrt(count));var camera=new Vec3(side*8,105,side*16+80);
                    var projection=VssLodProjection.of(new Matrix4f().perspective(1.2f,1,.05f,4096));
                    shader.use();shader.setSamplers(0,1,2,3,4);shader.setCamera(new Matrix4f().lookAlong(0,-.35f,-1,0,1,0),projection.matrix());shader.bindMainDepth(depth,projection);
                    shader.setFrame(new float[]{0,0,0,0},1e7f,2e7f,0,1e7f,false,1);shader.setRealCoverage(0,0,0,0);shader.setRealRenderDistance(0);shader.setVoxyOwnershipDistance(0);shader.setHorizon(131072);shader.setDetailDistance(0);
                    int id=glGetInteger(GL_CURRENT_PROGRAM);glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),2,8,2);glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
                    glUniform1i(glGetUniformLocation(id,"ExactCoverage"),8);glUniform3f(glGetUniformLocation(id,"ExactCoverageGrid"),1e6f,1e6f,1);
                    glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                    var unrelated=PredictionPreparedPassLocalTest.mesh();unrelated.trackSubmission();
                    for(String scenario:List.of("stable","unrelated","one-gpu","1pct-gpu","replace-all","move","turn")){
                        try(var old=new Path(true);var next=new Path(false)){
                            List<PredictionRenderer.Draw> source=List.copyOf(draws);long residency=1;
                            for(int round=-60;round<61;round++){
                                if(scenario.equals("unrelated"))unrelated.morph(null,0,0);
                                if(scenario.equals("one-gpu")||scenario.equals("1pct-gpu")){
                                    int changed=scenario.equals("one-gpu")?1:Math.max(1,count/100);
                                    for(int c=0;c<changed;c++)NextInvalidationTest.changed(draws.get(Math.floorMod(round*37+c,count)).gpu());residency++;
                                }
                                if(scenario.equals("replace-all")){for(int i=0;i<count;i++)draws.set(i,ResidentRecordIntegrationTest.replacement(draws.get(i)));source=List.copyOf(draws);residency++;}
                                Vec3 view=scenario.equals("move")?camera.add(round*.2,0,0):camera;
                                if(scenario.equals("turn")){shader.use();shader.setCamera(new Matrix4f().lookAlong((float)Math.sin(round*.012),-.35f,(float)-Math.cos(round*.012),0,1,0),projection.matrix());}
                                byte[] expected=null;float[] expectedDepth=null;
                                for(int order=0;order<2;order++){
                                    int mode=Math.floorMod(round+order,2);glFinish();var sample=(mode==0?old:next).draw(f,shader,source,view,residency);
                                    if(round>=0)rows.add(count+","+scenario+","+round+","+mode+","+sample.cpu+","+sample.gpu+","+sample.commands+","+sample.uploaded);
                                    if(round==0||round==60){byte[] pixels=f.pixels();float[] values=f.depths();if(expected==null){expected=pixels;expectedDepth=values;}else{assertArrayEquals(expected,pixels);assertArrayEquals(expectedDepth,values);}}
                                }
                            }
                            // Includes CPU preparation, submission, GPU completion and the same per-frame mutations; no per-frame query waits.
                            for(int round=-3;round<9;round++)for(int order=0;order<2;order++){
                                int mode=Math.floorMod(round+order,2);glFinish();long start=System.nanoTime();int frames=16;
                                for(int frame=0;frame<frames;frame++){
                                    if(scenario.equals("unrelated"))unrelated.morph(null,0,0);
                                    if(scenario.equals("one-gpu")||scenario.equals("1pct-gpu")){for(int c=0;c<(scenario.equals("one-gpu")?1:Math.max(1,count/100));c++)NextInvalidationTest.changed(draws.get((frame*37+c)%count).gpu());residency++;}
                                    if(scenario.equals("replace-all")){for(int i=0;i<count;i++)draws.set(i,ResidentRecordIntegrationTest.replacement(draws.get(i)));source=List.copyOf(draws);residency++;}
                                    if(scenario.equals("turn")){shader.use();shader.setCamera(new Matrix4f().lookAlong((float)Math.sin(frame*.012),-.35f,(float)-Math.cos(frame*.012),0,1,0),projection.matrix());}
                                    (mode==0?old:next).draw(f,shader,source,scenario.equals("move")?camera.add(frame*.2,0,0):camera,residency,false);
                                }
                                glFinish();if(round>=0)through.add(count+","+scenario+","+round+","+mode+","+frames+","+(System.nanoTime()-start));
                            }
                        }
                        assertEquals(GL_NO_ERROR,glGetError());
                    }
                    allocated.forEach(PredictionGpuTile::close);allocated.clear();glFinish();PredictionTerrainArena.SHARED.reap();
                }
            }finally{allocated.forEach(PredictionGpuTile::close);textures.forEach(i->glDeleteTextures(i));glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();}
            CompletionGpuFixture.save("next-submission.csv",rows);CompletionGpuFixture.save("next-submission-throughput.csv",through);
        }
    }
    private static Field field(String name){try{var f=Class.forName(PredictionRenderer.class.getName()+"$PreparedPass").getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new AssertionError(e);}}
}
