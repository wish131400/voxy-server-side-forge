package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class BenefitDirectionGpuTest {
    @Test void actualDirectCommandsHaveIdenticalColorDepthAndCompleteTiming() throws Exception {
        var csv = new ArrayList<String>(); csv.add("scenario,round,mode,gpu_ns,cpu_ns,quads,commands");
        var textures = new ArrayList<Integer>();
        try (var context = new CompletionGpuFixture(); var shader = PredictionTerrainProgram.createBatch()) {
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
            float[] values = new float[256*256]; Arrays.fill(values, 1);
            int depth = PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,values);
            var camera = new Vec3(64,160,170);
            PredictionVegetationRenderGpuTest.setup(shader,new Matrix4f().perspective(1.2f,1,.05f,2048),camera,false,false,depth);
            int handle=glGetInteger(GL_CURRENT_PROGRAM);
            glUniform1i(glGetUniformLocation(handle,"ExactCoverage"),8);
            glUniform1i(glGetUniformLocation(handle,"VanillaMask"),6);
            glUniform3f(glGetUniformLocation(handle,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
            var target = new TextureTarget(256,256,true,false);
            try {
                for (int scenario = 0; scenario < 6; scenario++) {
                    int[] words = fixture(scenario);
                    var packed = PredictionPackedMesh.seamRecords(words,128); packed.prepareGpuStorage();
                    var mesh = PredictionMesh.restored(words.length/12*6,0,packed,null);
                    var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
                    var tile = new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,
                            new PredictionDepthBound(80,82),0,scenario+1,128,1);
                    try (var gpu = new PredictionGpuTile(key); var baseline = new PredictionIndirectBatch(); var candidate = new PredictionIndirectBatch()) {
                        assertTrue(gpu.ensureMesh(tile)); assertNotNull(gpu.arenaSlice());
                        boolean[] allowed = new boolean[128*128]; Arrays.fill(allowed,true); gpu.updateCoverage(allowed);
                        byte[] boundary = new byte[allowed.length]; Arrays.fill(boundary,(byte)192); gpu.updateBoundaryCoverage(boundary);
                        shader.use();shader.bindMainDepth(depth,VssLodProjection.of(new Matrix4f().perspective(1.2f,1,.05f,2048)));
                        if (scenario == 4) packed.morph(new float[129*129],80,82);
                        var draw = new PredictionRenderer.Draw(tile,gpu,allowed,false,31,0,new AABB(0,80,0,128,82,128));
                        var full = packed.drawRanges(false,31);
                        var filtered = PredictionCommandDirections.filter(packed.occlusionBounds,full,PredictionCommandDirections.mask(draw,camera));
                        var draws=List.of(draw);var beforePass=new BenefitBaselinePass.Pass(false);
                        var afterPass=new PredictionPreparedPassLocalTest.Pass(false);
                        var beforePrepare=BenefitBaselinePass.Pass.class.getDeclaredMethod("prepare",List.class,long.class,Vec3.class);
                        beforePrepare.setAccessible(true);
                        PredictionPreparedPassLocalTest.coverage().clear();BenefitBaselinePass.synchronizeRenderer();
                        if (scenario==0) assertEquals(0,filtered.quads);
                        if (scenario==1||scenario==3||scenario==4) assertSame(full,filtered);
                        if (scenario==2) assertEquals(full.quads/2,filtered.quads);
                        if (scenario==5) assertSame(full,filtered,"fragmented streams are not retained without benefit");
                        long[][] times=new long[2][81], cpu=new long[2][81]; byte[][] pixels=new byte[2][]; float[][] depths=new float[2][];
                        for (int round=-24;round<81;round++) for(int order=0;order<2;order++) {
                            int mode=Math.floorMod(round+order,2); target.bindWrite(true); shader.use(); glBindVertexArray(context.vao); glViewport(0,0,256,256);
                            glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
                            glClearDepth(0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glFinish();
                            long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,context.timer);
                            var batch=mode==0?baseline:candidate;
                            var frameCamera=camera.add((round+25)*.01,0,0);
                            if(mode==0)beforePrepare.invoke(beforePass,draws,1L,frameCamera);else afterPass.prepare(draws,1,frameCamera);
                            var ranges=mode==0?beforePass.ranges(draw):afterPass.ranges(draw);
                            if(ranges==null)ranges=new PredictionDrawRanges(new int[0],new int[0]);
                            assertEquals(mode==0?full.quads:filtered.quads,ranges.quads,"actual prepared selection");
                            batch.begin(shader,true);
                            long version=mode==0?passVersion(beforePass):afterPass.counter("revision");
                            long replay=batch.replayPass(mode==0?beforePass:afterPass.value,version,true,frameCamera,Long.MAX_VALUE);
                            if(replay<0&&ranges.quads>0)for(int repeat=0;repeat<64;repeat++)assertTrue(batch.add(draw,ranges,frameCamera,false,true,Long.MAX_VALUE));
                            batch.end();glEndQuery(GL_TIME_ELAPSED);long elapsedCpu=System.nanoTime()-start;
                            long elapsed=glGetQueryObjectui64(context.timer,GL_QUERY_RESULT);
                            if(round>=0){times[mode][round]=elapsed;cpu[mode][round]=elapsedCpu;
                                csv.add(scenario+","+round+","+mode+","+elapsed+","+elapsedCpu+","+ranges.quads*64L+","+ranges.first.length*64);}
                            if(round==0){var p=org.lwjgl.BufferUtils.createByteBuffer(256*256*4);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,p);
                                pixels[mode]=new byte[p.remaining()];p.get(pixels[mode]);var d=org.lwjgl.BufferUtils.createFloatBuffer(256*256);
                                glReadPixels(0,0,256,256,GL_DEPTH_COMPONENT,GL_FLOAT,d);depths[mode]=new float[d.remaining()];d.get(depths[mode]);}
                        }
                        assertArrayEquals(pixels[0],pixels[1],"scenario="+scenario);assertArrayEquals(depths[0],depths[1]);
                        if(scenario==1||scenario==2){int visible=0;for(int p=0;p<pixels[0].length;p+=4)if(pixels[0][p]!=0)visible++;assertTrue(visible>50);}
                        System.out.printf(Locale.ROOT,"BENEFIT_DIRECT scenario=%d quads=%d->%d commands=%d->%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",
                                scenario,full.quads*64L,filtered.quads*64L,full.first.length*64,filtered.first.length*64,
                                CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                                CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6);
                    }
                }
            } finally { target.destroyBuffers(); }
            glFinish();PredictionTerrainArena.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);
        }
        CompletionGpuFixture.save("benefit-direction.csv",csv);
    }
    private static long passVersion(BenefitBaselinePass.Pass pass) throws Exception {
        var field=BenefitBaselinePass.Pass.class.getDeclaredField("revision");field.setAccessible(true);return field.getLong(pass);
    }
    static int[] fixture(int scenario) {
        int[] words=new int[128*128*12];
        for(int q=0;q<128*128;q++) {
            int flags = 1 | (scenario==0 || scenario==2 && q%128<64 ? PredictionPackedMesh.FLAG_BANK : 0);
            if(scenario==3)flags|=PredictionPackedMesh.FLAG_UNSHADED;
            if(scenario==5&&((q%128/16+q/128/16)&1)==0)flags|=PredictionPackedMesh.FLAG_BANK;
            PredictionSpatialOrderTest.quad(words,q,q%128,q/128,80,flags);
            words[q*12+8]=q;
            for(int c:new int[]{7,9,10,11})words[q*12+c]=0x668844;
        }
        return words;
    }
}
