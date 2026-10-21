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

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class BenefitRepresentationsGpuTest {
    @Test void compareLocalLodAndCanopyAgainstWholeTileWithProductionPackedShader() throws Exception {
        var textures=new ArrayList<Integer>();var csv=new ArrayList<String>();csv.add("candidate,case,round,mode,gpu_ns,cpu_ns,quads,commands");
        try(var fixture=new CompletionGpuFixture();var shader=PredictionTerrainProgram.createBatch()) {
            PredictionVegetationRenderGpuTest.setupTextures(textures);glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
            float[] depthValues=new float[256*256];Arrays.fill(depthValues,1);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,depthValues);
            var target=new TextureTarget(256,256,true,false);
            var projection=new Matrix4f().perspective(1.2f,1,.05f,8192);
            try {
                for(int shape=0;shape<2;shape++) {
                    int[] source=BenefitLocalTerrainTest.fixture(shape);
                    var whole=PredictionDisplayGeometry.build(source,new int[5],new int[]{16384,0,0,0,0},new int[4][5],128,new boolean[256]);
                    var local=BenefitLocalTerrain.build(source,128);
                    var selectCamera=new Vec3(64,130,-2400);var lod=new PredictionDisplayLod();lod.update(whole.errors(),2400,1475,256,false,2);
                    var a=whole.range(lod.tier(),0,31,0);var b=BenefitLocalTerrain.select(local,selectCamera,1475,2,false);
                    compare("local-terrain",shape,whole.words(),a,local.words(),b,new Vec3(64,160,170),projection,depth,target,fixture,shader,csv);
                }
                for(int shape=0;shape<3;shape++) {
                    var source=BenefitCanopyProxy.forest(shape);long started=System.nanoTime();var fine=BenefitCanopyProxy.mesh(source,1);
                    long oldBuild=System.nanoTime()-started;started=System.nanoTime();var reduced=BenefitCanopyProxy.reduce(source,2);var coarse=BenefitCanopyProxy.mesh(reduced,2);
                    long newBuild=System.nanoTime()-started;
                    System.out.printf(Locale.ROOT,"BENEFIT_CANOPY_BUILD shape=%d voxels=%d->%d quads=%d->%d extra_bytes=%d worker_ms=%.6f/%.6f%n",
                            shape,source.size(),reduced.size(),fine.length/12,coarse.length/12,coarse.length*4L,oldBuild/1e6,newBuild/1e6);
                    for(int distance:new int[]{180,1800,4096})
                        compare("canopy-distance-"+distance,shape,fine,new PredictionDrawRanges(new int[]{0},new int[]{fine.length/12}),
                                coarse,new PredictionDrawRanges(new int[]{0},new int[]{coarse.length/12}),
                                new Vec3(64,95,distance),projection,depth,target,fixture,shader,csv);
                }
            }finally{target.destroyBuffers();}
            glFinish();PredictionTerrainArena.SHARED.close();for(int t:textures)glDeleteTextures(t);
        }
        CompletionGpuFixture.save("benefit-representations.csv",csv);
    }
    private static void compare(String name,int shape,int[] oldWords,PredictionDrawRanges oldRanges,int[] newWords,PredictionDrawRanges newRanges,
            Vec3 camera,Matrix4f projection,int depth,TextureTarget target,CompletionGpuFixture context,PredictionTerrainProgram shader,List<String> csv) throws Exception {
        var tiles=new ArrayList<PredictionGpuTile>();var payloads=new ArrayList<PredictionPackedMesh>();var draws=new ArrayList<PredictionRenderer.Draw>();
        try(var a=new PredictionIndirectBatch();var b=new PredictionIndirectBatch()) {
            for(int[] source:List.of(oldWords,newWords)) {
                // Explicit record ordering must survive; the source constructor normally reorders it.
                String old=System.getProperty("vss.disablePredictionSpatialOrder");System.setProperty("vss.disablePredictionSpatialOrder","true");
                PredictionPackedMesh packed;
                    try{packed=name.startsWith("local")?PredictionPackedMesh.seamRecords(source.clone(),128)
                            :PredictionPackedMesh.terrainRecords(source.clone(),128);}
                    finally{if(old==null)System.clearProperty("vss.disablePredictionSpatialOrder");else System.setProperty("vss.disablePredictionSpatialOrder",old);}
                packed.prepareGpuStorage();payloads.add(packed);
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
                var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                        PredictionMesh.restored(source.length/12*6,0,packed,null),new PredictionDepthBound(80,120),0,tiles.size()+1,128,1);
                var gpu=new PredictionGpuTile(key);tiles.add(gpu);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
                boolean[] cells=new boolean[128*128];Arrays.fill(cells,true);gpu.updateCoverage(cells);
                byte[] masks=new byte[cells.length];Arrays.fill(masks,(byte)192);gpu.updateBoundaryCoverage(masks);
                draws.add(new PredictionRenderer.Draw(tile,gpu,cells,false,31,0,new AABB(0,80,0,128,120,128)));
            }
            // The field partition in terrain patches is exact; canopy groups reorder by face.
            // Use a sorted canonical source for canopy, since the coarse ranges select the complete mesh.
            var ranges=name.startsWith("local")?List.of(oldRanges,newRanges)
                    :List.of(payloads.get(0).drawRanges(false,31),payloads.get(1).drawRanges(false,31));
            var batches=List.of(a,b);
            PredictionVegetationRenderGpuTest.setup(shader,projection,camera,false,false,depth);
            int handle=glGetInteger(GL_CURRENT_PROGRAM);glUniform1i(glGetUniformLocation(handle,"ExactCoverage"),8);
            glUniform3f(glGetUniformLocation(handle,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
            var model=name.startsWith("local")?new Matrix4f().lookAlong(0,-.55f,-1,0,1,0)
                    :new Matrix4f().lookAlong(0,5,(float)(64-camera.z),0,1,0);
            shader.setCamera(model,VssLodProjection.of(projection).matrix());shader.setHorizon(8192);shader.setRealRenderDistance(0);
            byte[][] pixels=new byte[2][];float[][] depths=new float[2][];long[][] gpu=new long[2][81],cpu=new long[2][81];
            for(int round=-24;round<81;round++)for(int order=0;order<2;order++) {
                int mode=Math.floorMod(round+order,2);target.bindWrite(true);shader.use();glBindVertexArray(context.vao);clear();
                glFinish();long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,context.timer);
                var batch=batches.get(mode);batch.begin(shader);var selection=ranges.get(mode);
                long replay=batch.replayPass(selection,1,true,camera,Long.MAX_VALUE);
                if(replay<0)for(int r=0;r<128;r++)assertTrue(batch.add(draws.get(mode),selection,camera,false,true,Long.MAX_VALUE));
                batch.end();glEndQuery(GL_TIME_ELAPSED);long timeCpu=System.nanoTime()-start,time=glGetQueryObjectui64(context.timer,GL_QUERY_RESULT);
                if(round>=0){gpu[mode][round]=time;cpu[mode][round]=timeCpu;csv.add(name+","+shape+","+round+","+mode+","+time+","+timeCpu+","+selection.quads*128L+","+selection.first.length*128);}
                if(round==0){pixels[mode]=context.pixels();depths[mode]=context.depths();}
            }
            int changed=0,sourceVisible=0,newVisible=0;
            for(int p=0;p<pixels[0].length;p+=4){boolean different=false;for(int c=0;c<4;c++)different|=pixels[0][p+c]!=pixels[1][p+c];if(different)changed++;
                if(pixels[0][p]!=0||pixels[0][p+1]!=0||pixels[0][p+2]!=0)sourceVisible++;
                if(pixels[1][p]!=0||pixels[1][p+1]!=0||pixels[1][p+2]!=0)newVisible++;}
            int depthChanged=0;for(int i=0;i<depths[0].length;i++)if(depths[0][i]!=depths[1][i])depthChanged++;
            System.out.printf(Locale.ROOT,"BENEFIT_REP name=%s shape=%d quads=%d->%d commands=%d->%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f pixels=%d/%d changed=%d depthChanged=%d%n",
                    name,shape,ranges.get(0).quads*128L,ranges.get(1).quads*128L,ranges.get(0).first.length*128,ranges.get(1).first.length*128,
                    CompletionGpuFixture.median(gpu[0])/1e6,CompletionGpuFixture.median(gpu[1])/1e6,
                    CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6,sourceVisible,newVisible,changed,depthChanged);
            assertTrue(sourceVisible>0,"source image must be visible");assertTrue(newVisible>0,"candidate image must be visible");
            assertEquals(GL_NO_ERROR,glGetError());
        }finally{for(var tile:tiles)tile.close();}
    }
    private static void clear(){glViewport(0,0,256,256);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);
        glClearDepth(0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);}
}
