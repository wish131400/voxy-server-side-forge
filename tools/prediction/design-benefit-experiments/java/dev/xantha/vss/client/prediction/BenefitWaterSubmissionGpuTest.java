package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class BenefitWaterSubmissionGpuTest {
    @Test void measurePrepareAndStaticPlanSubmissionWithExactWaterOrder() throws Exception {
        var csv=new ArrayList<String>();csv.add("path,round,mode,cpu_ns,gpu_ns,whole_hits");
        var textures=new ArrayList<Integer>();var tiles=new ArrayList<PredictionGpuTile>();
        try(var context=new CompletionGpuFixture();var shader=PredictionTerrainProgram.createBatch()) {
            PredictionVegetationRenderGpuTest.setupTextures(textures);glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
            float[] values=new float[256*256];Arrays.fill(values,1);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,values);
            var draws=new ArrayList<PredictionRenderer.Draw>();
            for(int i=0;i<16;i++) {
                var fixture=PredictionMeshCodecTest.fixture(16);var payload=fixture.gpuPayload();payload.prepareGpuStorage();
                var base=PredictionLodSeamsTest.tile(i%4,i/4,1,16);
                var tile=new PredictionTileManager.PredictionTile(base.key(),new int[0],new int[0],new ClientColumnSample[0],fixture,
                        new PredictionDepthBound(64,120),0,i+1,16,1);
                var gpu=new PredictionGpuTile(tile.key());tiles.add(gpu);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice(true));
                boolean[] all=new boolean[256];Arrays.fill(all,true);gpu.updateCoverage(all);
                byte[] boundary=new byte[256];Arrays.fill(boundary,(byte)192);gpu.updateBoundaryCoverage(boundary);
                for(int repeat=0;repeat<64;repeat++)draws.add(new PredictionRenderer.Draw(tile,gpu,all,false,31,0,
                        new AABB(tile.baseBlockX(),64,tile.baseBlockZ(),tile.baseBlockX()+16,120,tile.baseBlockZ()+16)));
            }
            var target=new TextureTarget(256,256,true,false);var projection=new Matrix4f().perspective(1.2f,1,.05f,8192);
            PredictionVegetationRenderGpuTest.setup(shader,projection,new Vec3(32,130,110),false,false,depth);
            glUniform1i(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM),"ExactCoverage"),8);
            shader.setHorizon(8192);
            try {
                for(String path:List.of("vertical","horizontal"))try(var oldBatch=new PredictionIndirectBatch();var newBatch=new PredictionIndirectBatch()) {
                    PredictionPreparedPassLocalTest.coverage().clear();BenefitBaselinePass.synchronizeRenderer();
                    var before=new BenefitBaselinePass.Pass(true);var after=new PredictionPreparedPassLocalTest.Pass(true);
                    var oldPrepare=BenefitBaselinePass.Pass.class.getDeclaredMethod("prepare",List.class,long.class,Vec3.class);oldPrepare.setAccessible(true);
                    var oldEntries=BenefitBaselinePass.Pass.class.getDeclaredMethod("entries");oldEntries.setAccessible(true);
                    var version=BenefitBaselinePass.Pass.class.getDeclaredField("revision");version.setAccessible(true);
                    var oldDraw=Class.forName(BenefitBaselinePass.class.getName()+"$PreparedDraw").getDeclaredMethod("draw");oldDraw.setAccessible(true);
                    var newDraw=Class.forName(PredictionRenderer.class.getName()+"$PreparedDraw").getDeclaredMethod("draw");newDraw.setAccessible(true);
                    var oldRanges=BenefitBaselinePass.Pass.class.getDeclaredMethod("ranges",PredictionRenderer.Draw.class);oldRanges.setAccessible(true);
                    long[][] times=new long[2][81],gpu=new long[2][81];byte[][] pixels=new byte[2][];float[][] depths=new float[2][];
                    for(int round=-24;round<81;round++)for(int order=0;order<2;order++) {
                        int mode=Math.floorMod(round+order,2);var camera=new Vec3(path.equals("horizontal")?32+(round+25)*.1:32,
                                path.equals("vertical")?130+(round+25)*.1:130,110);
                        target.bindWrite(true);shader.use();shader.setOpaqueAlpha(0);glBindVertexArray(context.vao);
                        glViewport(0,0,256,256);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);
                        glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);glClearDepth(0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDepthMask(false);glFinish();
                        long started=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,context.timer);
                        if(mode==0)oldPrepare.invoke(before,draws,1L,camera);else after.prepare(draws,1,camera);
                        var batch=mode==0?oldBatch:newBatch;batch.begin(shader,false);
                        long replay=batch.replayPass(mode==0?before:after.value,mode==0?version.getLong(before):after.counter("revision"),true,camera,Long.MAX_VALUE);
                        if(replay<0) {
                            var entries=mode==0?(List<?>)oldEntries.invoke(before):after.entries();
                            for(int i=entries.size()-1;i>=0;i--) {
                                var entry=entries.get(i);var method=mode==0?oldDraw:newDraw;
                                var draw=(PredictionRenderer.Draw)method.invoke(entry);var ranges=mode==0?(PredictionDrawRanges)oldRanges.invoke(before,draw):after.ranges(draw);
                                assertTrue(batch.add(draw,ranges,camera,true,true,Long.MAX_VALUE));
                            }
                        }
                        batch.end();glEndQuery(GL_TIME_ELAPSED);long elapsed=System.nanoTime()-started,time=glGetQueryObjectui64(context.timer,GL_QUERY_RESULT);
                        if(round>=0){times[mode][round]=elapsed;gpu[mode][round]=time;csv.add(path+","+round+","+mode+","+elapsed+","+time+","+counter(batch,"wholePlanHits"));}
                        if(round==0){pixels[mode]=context.pixels();depths[mode]=context.depths();}
                    }
                    assertArrayEquals(pixels[0],pixels[1]);assertArrayEquals(depths[0],depths[1]);
                    int visible=0;for(int p=0;p<pixels[0].length;p+=4)if(pixels[0][p]!=0||pixels[0][p+1]!=0||pixels[0][p+2]!=0)visible++;
                    assertTrue(visible>0,"water comparison must render real pixels");
                    if(path.equals("vertical")){assertEquals(0,counter(oldBatch,"wholePlanHits"));assertTrue(counter(newBatch,"wholePlanHits")>80);}
                    System.out.printf(Locale.ROOT,"BENEFIT_WATER_FULL path=%s draws=1024 CPU_ms=%.6f->%.6f GPU_ms=%.6f->%.6f wholeHits=%d->%d%n",
                            path,CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                            CompletionGpuFixture.median(gpu[0])/1e6,CompletionGpuFixture.median(gpu[1])/1e6,
                            counter(oldBatch,"wholePlanHits"),counter(newBatch,"wholePlanHits"));
                }
            }finally{target.destroyBuffers();}
            glDepthMask(true);for(var tile:tiles)tile.close();glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();
            for(int texture:textures)glDeleteTextures(texture);assertEquals(GL_NO_ERROR,glGetError());
        }
        CompletionGpuFixture.save("benefit-water-full.csv",csv);
    }
    private static long counter(PredictionIndirectBatch batch,String name)throws Exception{var f=PredictionIndirectBatch.class.getDeclaredField(name);f.setAccessible(true);return f.getLong(batch);}
}
