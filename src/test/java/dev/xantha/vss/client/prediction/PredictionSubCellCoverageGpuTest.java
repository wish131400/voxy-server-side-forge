package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Pixel/depth checks use the real arena uploads, mask transaction and MDI encoder. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionSubCellCoverageGpuTest {
    private static final int SIZE=512;
    @Test void directMdiIrisWaterAndFeaturesClipOnlyTheChildAndRestoreTheParent() {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"VSS sub-cell ownership",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();
        String indirect=System.getProperty("vss.disableIndirect");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            System.out.println("SUB_CELL_GPU "+glGetString(GL_RENDERER));
            int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            texture(textures,0,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            texture(textures,1,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            texture(textures,2,GL_RGBA32F,2,2,GL_RGBA,new float[16]);
            float[] clearDepth=new float[SIZE*SIZE];Arrays.fill(clearDepth,1);
            int depth=texture(textures,7,GL_R32F,SIZE,SIZE,GL_RED,clearDepth);
            texture(textures,8,GL_R8,1,1,GL_RED,new float[]{0});
            var target=new TextureTarget(SIZE,SIZE,true,false);
            int cases=0;
            try {
                for(boolean standalone:new boolean[]{false,true}) for(int kind=0;kind<3;kind++) {
                    if(standalone) System.setProperty("vss.disableIndirect","true");else System.clearProperty("vss.disableIndirect");
                    boolean water=kind==1;
                    var parent=PredictionSubCellCoverageTest.tile(-1,-1,7,8,1024,160,true,water);
                    var fine=PredictionSubCellCoverageTest.tile(-63,-30,0,64,1,96,true);
                    var partial=PredictionSubCellCoverageTest.coverage(parent,PredictionSubCellCoverageTest.snapshot(parent,fine));
                    var full=PredictionSubCellCoverageTest.coverage(parent,PredictionSubCellCoverageTest.snapshot(parent));
                    var camera=new Vec3(parent.baseBlockX()+4608,500,parent.baseBlockZ()+6656);
                    try(var gpu=new PredictionGpuTile(parent.key());var batch=new PredictionIndirectBatch()) {
                        if(kind==2) {
                            int[] words=parent.mesh().gpuPayload().cacheWords().clone();
                            for(int q=0;q<words.length;q+=12) words[q+10]|=PredictionPackedMesh.FEATURE;
                            gpu.ensureSeams(PredictionPackedMesh.seamRecords(words,8));
                        } else assertTrue(gpu.ensureMesh(parent));
                        var packed=gpu.packed();var ranges=packed.drawRanges(water,VssLodFaceGroup.ALL);assertTrue(ranges.quads>0);
                        int[] indices=new int[packed.quadCount()*6],corners={0,1,2,0,2,3};
                        for(int q=0;q<packed.quadCount();q++) for(int c=0;c<6;c++) indices[q*6+c]=q*4+corners[c];
                        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
                        for(int mode=0;mode<4;mode++) {
                            if(standalone && mode%2==1) continue;
                            boolean iris=mode>=2,mdi=mode%2==1;
                            String taa="vec2 vssTaaShift() { return vec2(0); }";
                            java.util.function.UnaryOperator<String> emit=s->s+"\nout vec4 TestColor;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { TestColor=p.sampledColour; }\n";
                            try(var shader=iris?(mdi?PredictionTerrainProgram.createIrisBatch(taa,emit):PredictionTerrainProgram.createIris(taa,emit))
                                    :mdi?PredictionTerrainProgram.createBatch():PredictionTerrainProgram.create()) {
                                shader.use();shader.setSamplers(0,1,2,3,4);
                                shader.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                                var vanilla=new Matrix4f().perspective(2*(float)Math.atan(512D/340D),1,.05f,65536);
                                var projection=VssLodProjection.of(vanilla);
                                shader.setCamera(new Matrix4f().lookAlong(0,-1,0,0,0,-1),iris?vanilla:projection.matrix());
                                shader.bindMainDepth(depth,projection);shader.setOpaqueAlpha(1);
                                if(iris) shader.setIrisFrame(new Matrix4f(vanilla).invert(),SIZE,SIZE,false,new int[256],1);
                                int id=glGetInteger(GL_CURRENT_PROGRAM);
                                glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),0,0,0);
                                glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                                byte[][] masks=new byte[3][];
                                for(int phase=0;phase<3;phase++) {
                                    var coverage=phase==1?partial:full;
                                    gpu.updatePublishedCoverage(coverage.allowed(),coverage.grid());
                                    if(coverage.grid()!=null) gpu.updateBoundaryCoverage(coverage.grid().mask(null));
                                    else gpu.updateBoundaryCoverage(PredictionFeatureHandoff.mask(coverage.allowed(),coverage.features(),null));
                                    // Production commits masks before binding frame textures; mirror that order.
                                    shader.bindMainDepth(depth,projection);
                                    var draw=new PredictionRenderer.Draw(parent,gpu,coverage.allowed(),coverage.features(),false,
                                            VssLodFaceGroup.ALL,0,new AABB(parent.baseBlockX(),160,parent.baseBlockZ(),0,162,0),false,false,false,coverage.grid());
                                    target.bindWrite(true);glBindVertexArray(vao);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
                                    glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(iris?GL_LESS:GL_GREATER);
                                    glClearDepth(iris?1:0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                                    shader.batch(false);
                                    if(mdi) {
                                        batch.begin(shader);assertTrue(batch.add(draw,ranges,camera,water,true,Long.MAX_VALUE));assertTrue(batch.end()>0);
                                    } else {
                                        gpu.bindTerrain(shader,water);gpu.bindYield(3);
                                        shader.setTile(-4608,-500,-6656,1024,8,true);shader.setBoundaryReplacement(false);
                                        PredictionRenderer.submitRanges(ranges,packed,water);
                                    }
                                    var pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                                    var depths=BufferUtils.createFloatBuffer(SIZE*SIZE);glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
                                    masks[phase]=new byte[SIZE*SIZE];int drawn=0;
                                    for(int i=0;i<masks[phase].length;i++) {
                                        boolean visible=(pixels.get(i*4+3)&255)>0;
                                        masks[phase][i]=(byte)(visible?1:0);if(visible) drawn++;
                                        assertEquals(visible,iris?depths.get(i)<1:depths.get(i)>0,"depth agrees with ownership clipping");
                                    }
                                    assertTrue(drawn>200000,"coarse surroundings remain visible kind="+kind+" mode="+mode+" phase="+phase+" drawn="+drawn);
                                    assertEquals(GL_NO_ERROR,glGetError());cases++;
                                }
                                assertArrayEquals(masks[0],masks[2],"retraction restores exactly the original pixels");
                                int removed=0;
                                for(int i=0;i<masks[0].length;i++) if(masks[0][i]!=masks[1][i]) { assertEquals(0,masks[1][i]);removed++; }
                                assertTrue(removed>=900 && removed<=1156,"only the 64x64 child replaces a 1024x1024 source cell: kind="+kind+" mode="+mode+" removed="+removed);
                                assertEquals(0,masks[1][432*SIZE+48],"off-centre child cannot remain underneath the coarse slab");
                                assertEquals(masks[0][240*SIZE+240],masks[1][240*SIZE+240],"the source-cell centre keeps its original coverage kind="+kind+" mode="+mode);
                                assertEquals(1,masks[1][240*SIZE+240],"coarse fallback around the centre stays drawn kind="+kind+" mode="+mode);
                            }
                        }
                    }
                }
                verifyPartialWall(target,vao,ebo,textures,depth);
                System.out.println("SUB_CELL_GPU cases="+cases);
            } finally { target.destroyBuffers();glDeleteBuffers(ebo);glDeleteVertexArrays(vao); }
        } finally {
            if(indirect==null) System.clearProperty("vss.disableIndirect");else System.setProperty("vss.disableIndirect",indirect);
            PredictionTerrainArena.SHARED.close();for(int texture:textures) glDeleteTextures(texture);
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static void verifyPartialWall(TextureTarget target,int vao,int ebo,List<Integer> textures,int depth) {
        System.clearProperty("vss.disableIndirect");
        var parent=PredictionSubCellCoverageTest.tile(-1,-1,7,8,1024,160,true);
        var child=PredictionSubCellCoverageTest.tile(-64,-30,0,64,1,96,true);
        var snapshot=PredictionSubCellCoverageTest.snapshot(parent,child);
        var partial=PredictionSubCellCoverageTest.coverage(parent,snapshot);
        var full=PredictionSubCellCoverageTest.coverage(parent,PredictionSubCellCoverageTest.snapshot(parent));
        var seams=new PredictionLodSeams();
        seams.update(List.of(new PredictionLodSeams.Surface(parent,partial.allowed(),partial.grid()),
                new PredictionLodSeams.Surface(child,PredictionSubCellCoverageTest.coverage(child,snapshot).allowed())));
        byte[] boundary=partial.grid().mask(seams.boundaryMask(parent.key()));
        int top=32768+160*4,bottom=32768+64*4;
        int[] words={4096|4096<<16,4096|4096<<16,6144|7168<<16,7168|6144<<16,
                top|top<<16,bottom|bottom<<16,1<<24|1<<30,0xbf4d13,6*8+3,
                0xbf4d13|1<<24|1<<27,0xbf4d13,0xbf4d13};
        try(var gpu=new PredictionGpuTile(parent.key());var batch=new PredictionIndirectBatch()) {
            gpu.ensureSeams(PredictionPackedMesh.seamRecords(words,8));
            glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,0,2,3},GL_STATIC_DRAW);
            for(boolean mdi:new boolean[]{false,true}) try(var shader=mdi?PredictionTerrainProgram.createBatch():PredictionTerrainProgram.create()) {
                shader.use();shader.setSamplers(0,1,2,3,4);shader.setOpaqueAlpha(1);
                shader.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                var projection=VssLodProjection.of(new Matrix4f().perspective(2*(float)Math.atan(512D/904D),1,.05f,65536));
                shader.setCamera(new Matrix4f().lookAlong(-1,0,0,0,1,0),projection.matrix());
                int id=glGetInteger(GL_CURRENT_PROGRAM);
                glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),0,0,0);
                glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                for(int phase=0;phase<3;phase++) {
                    var mask=phase==1?partial:full;gpu.updatePublishedCoverage(mask.allowed(),mask.grid());
                    gpu.updateBoundaryCoverage(phase==1?boundary:PredictionFeatureHandoff.mask(mask.allowed(),mask.features(),null));
                    shader.bindMainDepth(depth,projection);
                    target.bindWrite(true);glBindVertexArray(vao);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);
                    glDepthFunc(GL_GREATER);glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    shader.batch(false);var camera=new Vec3(parent.baseBlockX()+5000,112,parent.baseBlockZ()+6656);
                    if(mdi) {
                        var draw=new PredictionRenderer.Draw(parent,gpu,mask.allowed(),mask.features(),false,VssLodFaceGroup.ALL,0,
                                new AABB(parent.baseBlockX(),64,parent.baseBlockZ(),0,160,0),false,false,false,mask.grid());
                        batch.begin(shader);assertTrue(batch.add(draw,gpu.packed().drawRanges(false,VssLodFaceGroup.ALL),camera,false,true,Long.MAX_VALUE));batch.end();
                    } else {
                        gpu.bindTerrain(shader,false);gpu.bindYield(3);shader.setTile(-5000,-112,-6656,1024,8,true);shader.setBoundaryReplacement(true);
                        PredictionRenderer.submitRanges(gpu.packed().drawRanges(false,VssLodFaceGroup.ALL),gpu.packed(),false);
                    }
                    var pixel=BufferUtils.createByteBuffer(4);glReadPixels(432,256,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
                    assertEquals(phase==1?0:255,pixel.get(3)&255,"only the stale wall segment beside the child exits, mdi="+mdi);
                    glReadPixels(240,256,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
                    assertEquals(255,pixel.get(3)&255,"the rest of the same source-cell wall remains as fallback");
                    assertEquals(GL_NO_ERROR,glGetError());
                }
            }
        }
        System.out.println("SUB_CELL_WALL_GPU cases=6");
    }
    private static int texture(List<Integer> textures,int unit,int format,int width,int height,int channels,float[] values) {
        int id=glCreateTextures(GL_TEXTURE_2D);textures.add(id);glTextureStorage2D(id,1,format,width,height);
        glTextureSubImage2D(id,0,0,0,width,height,channels,GL_FLOAT,values);
        glTextureParameteri(id,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(id,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glBindTextureUnit(unit,id);return id;
    }
}
