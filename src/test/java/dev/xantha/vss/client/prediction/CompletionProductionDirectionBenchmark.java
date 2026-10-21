package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;

/** Actual packed terrain shader, arena, MDI and meshlet bounds; repeated synthetic wall tiles. */
final class CompletionProductionDirectionBenchmark {
    static void run(PredictionTerrainProgram program, Vec3 camera, Matrix4f transform,
                    com.mojang.blaze3d.pipeline.TextureTarget target) throws Exception {
        var csv=new ArrayList<String>();csv.add("front,round,mode,cpu_ns,gpu_ns");
        String previousIndirect=System.getProperty("vss.disableIndirect");System.clearProperty("vss.disableIndirect");
        int timer=glGenQueries(),previousElements=glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING),elements=glCreateBuffers();
        int[] indices=new int[4096*6],corners={0,1,2,0,2,3};
        for(int q=0;q<4096;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,elements);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
        var tiles=new ArrayList<PredictionGpuTile>();
        try(var old=new CompletionLegacyDirectionBatch();var next=new PredictionIndirectBatch()) {
            for(boolean front:new boolean[]{false,true}) {
                var draws=new ArrayList<PredictionRenderer.Draw>();
                for(int t=0;t<16;t++) {
                    int quads=4096;int[] words=new int[quads*12];
                    for(int q=0;q<quads;q++) {
                        int p=q*12,x=q%64,y=32768+(q/64)*4,yy=y+4;
                        words[p]=x|(x+1)<<16;words[p+1]=(x+1)|x<<16;
                        words[p+2]=words[p+3]=32|32<<16;
                        words[p+4]=y|y<<16;words[p+5]=yy|yy<<16;
                        words[p+6]=2<<24|(front?0:PredictionPackedMesh.FLAG_FACE_POSITIVE);
                        words[p+7]=words[p+9]=words[p+10]=words[p+11]=0x608040;
                    }
                    var payload=PredictionPackedMesh.terrainRecords(words,64);payload.prepareGpuStorage();
                    var mesh=PredictionMesh.restored(quads*6,0,payload,null);
                    var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
                    var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,
                            new PredictionDepthBound(0,64),0,t+1,64,1);
                    var gpu=new PredictionGpuTile(key);tiles.add(gpu);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
                    boolean[] allowed=new boolean[4096];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
                    byte[] replacement=new byte[4096];Arrays.fill(replacement,(byte)128);gpu.updateBoundaryCoverage(replacement);
                    draws.add(new PredictionRenderer.Draw(tile,gpu,allowed,false,31,0,new AABB(0,0,32,64,64,32)));
                }
                var pass=new PredictionPreparedPassLocalTest.Pass(false);pass.prepare(draws,1,camera);
                long work=pass.counter("workloadRevision"),builds=pass.counter("rangeBuilds");
                boolean submitted = pass.ranges(draws.get(0)) != null;
                byte[] boundary=new byte[4096];Arrays.fill(boundary,(byte)255);
                draws.get(0).gpu().updateBoundaryCoverage(boundary);pass.prepare(draws,1,camera);
                assertEquals(work + (submitted ? 1 : 0),pass.counter("workloadRevision"),
                        "only submitted GPU mask changes restart timing comparison; wholly back-facing tiles stay outside its workload");
                assertEquals(builds,pass.counter("rangeBuilds"),"GPU masks do not invalidate CPU ownership ranges");
                Arrays.fill(boundary,(byte)128);draws.get(0).gpu().updateBoundaryCoverage(boundary);
                long[][] cpu=new long[2][61],gpu=new long[2][61];byte[][] pixels=new byte[2][];float[][] depths=new float[2][];
                for(int round=-20;round<61;round++)for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);
                    target.bindWrite(true);program.use();glViewport(0,0,128,128);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
                    glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,1);
                    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);program.setOpaqueAlpha(1);glFinish();
                    long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,timer);
                    if(mode==0)old.begin(program,target.getDepthTextureId(),128,128,transform);
                    else next.begin(program,target.getDepthTextureId(),128,128,transform);
                    if(mode==0)old.nextOcclusionBucket();else next.nextOcclusionBucket();
                    for(int repeat=0;repeat<8;repeat++)for(var draw:draws) {
                        var ranges=draw.gpu().packed().drawRanges(false,31);
                        boolean added=mode==0?old.add(draw,ranges,camera,false,true,Long.MAX_VALUE)
                                :next.add(draw,ranges,camera,false,true,Long.MAX_VALUE);assertTrue(added);
                    }
                    if(mode==0)old.end();else next.end();glEndQuery(GL_TIME_ELAPSED);
                    long elapsedCpu=System.nanoTime()-start,time=glGetQueryObjectui64(timer,GL_QUERY_RESULT);
                    if(round>=0){cpu[mode][round]=elapsedCpu;gpu[mode][round]=time;
                        csv.add(front+","+round+","+mode+","+elapsedCpu+","+time);}
                    if(round==0){var b=org.lwjgl.BufferUtils.createByteBuffer(128*128*4);glReadPixels(0,0,128,128,GL_RGBA,GL_UNSIGNED_BYTE,b);
                        pixels[mode]=new byte[b.remaining()];b.get(pixels[mode]);
                        var d=org.lwjgl.BufferUtils.createFloatBuffer(128*128);glReadPixels(0,0,128,128,GL_DEPTH_COMPONENT,GL_FLOAT,d);
                        depths[mode]=new float[d.remaining()];d.get(depths[mode]);}
                }
                assertArrayEquals(pixels[0],pixels[1]);assertArrayEquals(depths[0],depths[1]);
                if(front){int nonzero=0;for(int p=0;p<pixels[1].length;p+=4)if(pixels[1][p]!=0)nonzero++;assertTrue(nonzero>0);}
                System.out.printf(Locale.ROOT,"COMPLETION_PRODUCTION_DIRECTIONS front=%s quads=524288 GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",front,
                        CompletionGpuFixture.median(gpu[0])/1e6,CompletionGpuFixture.median(gpu[1])/1e6,
                        CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6);
                if(front) compareAdaptiveVersions(program,draws,camera,transform,target,timer,pass.counter("workloadRevision"));
            }
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {for(var tile:tiles)tile.close();glDeleteQueries(timer);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,previousElements);glDeleteBuffers(elements);
            if(previousIndirect==null)System.clearProperty("vss.disableIndirect");else System.setProperty("vss.disableIndirect",previousIndirect);}
        CompletionGpuFixture.save("production-command-direction.csv",csv);
    }
    private static void compareAdaptiveVersions(PredictionTerrainProgram program,List<PredictionRenderer.Draw> draws,
                    Vec3 camera,Matrix4f transform,com.mojang.blaze3d.pipeline.TextureTarget target,int timer,long visibleVersion) throws Exception {
        var csv=new ArrayList<String>();csv.add("frame,mode,gpu_ns");long[][] times=new long[2][61];
        byte[][] pixels=new byte[2][];float[][] depths=new float[2][];
        try(var legacy=new PredictionIndirectBatch();var local=new PredictionIndirectBatch()) {
            for(int frame=1;frame<=210;frame++)for(int order=0;order<2;order++) {
                int mode=(frame+order)%2;var batch=mode==0?legacy:local;
                target.bindWrite(true);program.use();glDepthFunc(GL_GREATER);glDepthMask(true);
                glClearColor(0,0,0,1);glClearDepth(0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glFinish();
                glBeginQuery(GL_TIME_ELAPSED,timer);
                if(mode==0)batch.begin(program,target.getDepthTextureId(),128,128,transform,camera,frame,frame);
                else batch.begin(program,target.getDepthTextureId(),128,128,transform,camera,frame,frame,visibleVersion);
                for(int bucket=0;bucket<8;bucket++) {
                    batch.nextOcclusionBucket();
                    for(var draw:draws)assertTrue(batch.add(draw,draw.gpu().packed().drawRanges(false,31),camera,false,true,Long.MAX_VALUE));
                }
                batch.end();glEndQuery(GL_TIME_ELAPSED);long time=glGetQueryObjectui64(timer,GL_QUERY_RESULT);
                if(frame>=150)times[mode][frame-150]=time;csv.add(frame+","+mode+","+time);
                if(frame==210){var b=org.lwjgl.BufferUtils.createByteBuffer(128*128*4);glReadPixels(0,0,128,128,GL_RGBA,GL_UNSIGNED_BYTE,b);
                    pixels[mode]=new byte[b.remaining()];b.get(pixels[mode]);
                    var d=org.lwjgl.BufferUtils.createFloatBuffer(128*128);glReadPixels(0,0,128,128,GL_DEPTH_COMPONENT,GL_FLOAT,d);
                    depths[mode]=new float[d.remaining()];d.get(depths[mode]);}
            }
            assertArrayEquals(pixels[0],pixels[1]);assertArrayEquals(depths[0],depths[1]);
            assertTrue(legacy.diagnostics().contains("occlusionComparisons=0"));
            assertTrue(local.diagnostics().contains("occlusionComparisons=1"),local.diagnostics());
            System.out.printf(Locale.ROOT,"COMPLETION_PRODUCTION_POLICY buckets=8 quads=524288 GPU_ms=%.6f->%.6f old=%s new=%s%n",
                    CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,legacy.diagnostics(),local.diagnostics());
        }
        CompletionGpuFixture.save("production-visible-workload-policy.csv",csv);
    }
}
