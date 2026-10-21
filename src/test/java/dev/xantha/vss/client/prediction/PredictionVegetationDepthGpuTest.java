package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Coplanar foliage must not oscillate as vanilla D24 depth crosses quantization bins. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionVegetationDepthGpuTest {
    private static final int SIZE=64;
    private static final float D24=16777215;

    @Test void realGrassAndLeavesKeepCoplanarOwnershipWhileCloserPredictedPlantsRemainVisible() {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"VSS vegetation depth ties",0,0); assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            AlgorithmBootstrap.bootstrapMinecraft();
            System.out.println("VEGETATION_DEPTH_GPU "+glGetString(GL_RENDERER));
            var main=new TextureTarget(SIZE,SIZE,true,false);
            int vao=glGenVertexArrays(), indices=glGenBuffers(), payload=glGenBuffers(), records=glCreateBuffers();
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,0,2,3},GL_STATIC_DRAW);
            int atlas=texture(textures,0,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            int lightmap=texture(textures,1,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            int sprites=texture(textures,2,GL_RGBA32F,2,2,GL_RGBA,
                    new float[]{0,0,1,1,0,0,1,1,1,1,1,1,1,1,1,1});
            int yield=texture(textures,3,GL_R8,1,1,GL_RED,new float[]{1});
            int quad=glCreateTextures(GL_TEXTURE_BUFFER); textures.add(quad);
            glBindBuffer(GL_TEXTURE_BUFFER,payload); glBufferData(GL_TEXTURE_BUFFER,new int[12],GL_STATIC_DRAW);
            glTextureBuffer(quad,GL_RGBA32UI,payload); glBindTextureUnit(4,quad);
            int depth=texture(textures,7,GL_R32F,SIZE,SIZE,GL_RED,new float[SIZE*SIZE]);
            int original=texture(textures,5,GL_R32F,SIZE,SIZE,GL_RED,new float[SIZE*SIZE]);
            try (var target=new PredictionRenderTarget()) {
                target.ensure(main);
                var failures=new ArrayList<String>();
                var groups=new TreeMap<String,Integer>();
                int cases=0;
                for(float mainFar:new float[]{512,65536}) for(boolean batch:new boolean[]{false,true})
                        for(boolean zero:new boolean[]{false,true}) for(boolean reverse:new boolean[]{false,true}) {
                    var vanilla=new Matrix4f().perspective(.001f,1,.05f,mainFar);
                    var projection=VssLodProjection.of(vanilla);
                    var voxy=new Matrix4f().perspective(.001f,1,reverse?48000:16,reverse?16:48000,zero);
                    var frame=new PredictionVoxyDepth.Frame(original,main.frameBufferId,SIZE,SIZE,Vec3.ZERO,
                            new Matrix4f(voxy).invert(),zero,reverse);
                    try(var shader=batch?PredictionTerrainProgram.createBatch():PredictionTerrainProgram.create()) {
                        shader.use(); shader.setSamplers(0,1,2,3,4); shader.batch(batch);
                        shader.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                        shader.setOpaqueAlpha(1); shader.setCamera(new Matrix4f(),projection.matrix());
                        shader.setRealRenderDistance(-1); shader.bindMainDepth(depth,projection); shader.bindVoxyDepth(frame,vanilla);
                        shader.setBatchCameraOffset(0,0,0); shader.setBatchTimeSeconds(0);
                        glUniform1fv(shaderUniform("DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                        int[] leaves=new int[8]; leaves[0]=1<<1;
                        for(int kind=0;kind<3;kind++) {
                            // Crossed grass uses the short feature path; fast leaves and
                            // solid trunks exercise the general fragment path.
                            shader.setLeaves(kind==1,leaves);
                            int flags=1|(2<<24)|PredictionPackedMesh.FLAG_FACE_POSITIVE;
                            if(kind<2) flags|=PredictionPackedMesh.FLAG_CUTOUT;
                            if(kind==0) flags|=PredictionPackedMesh.FLAG_UNSHADED;
                            int y0=32768,y1=32768+16*4,z=8;
                            int[] words={16<<16,16,z|z<<16,z|z<<16,y0|y0<<16,y1|y1<<16,
                                    flags,0x80c040,0,0x80c040|0x01000000,0x80c040|PredictionPackedMesh.FEATURE,0x80c040};
                            glBindBuffer(GL_TEXTURE_BUFFER,payload); glBufferData(GL_TEXTURE_BUFFER,words,GL_STATIC_DRAW);
                            for(float center:new float[]{64,128,256,512,1024,4096}) for(int step=0;step<33;step++) {
                                float jitter=(step-16)*.0625f;
                                float realDistance=center+jitter;
                                float mainValue=Math.min(1-1/D24,windowDepth(vanilla,realDistance,false));
                                fill(depth,mainValue);
                                fill(original,windowDepth(voxy,realDistance,zero));
                                for(float separation:new float[]{0,-1,1}) for(boolean seeded:new boolean[]{false,true}) {
                                    float predicted=realDistance+separation;
                                    float zOffset=-predicted-8;
                                    shader.use(); shader.setTile(-8,-8,zOffset,16,1,false);
                                    if(batch) {
                                        var bytes=BufferUtils.createByteBuffer(64);
                                        bytes.putFloat(-8).putFloat(-8).putFloat(zOffset).putFloat(16);
                                        bytes.putInt(1).putInt(0).putInt(0).putInt(0);
                                        bytes.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                                        bytes.putInt(2).putInt(0).putInt(0).putInt(0).flip();
                                        glNamedBufferData(records,bytes,GL_STREAM_DRAW); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,records);
                                    }
                                    glBindFramebuffer(GL_FRAMEBUFFER,main.frameBufferId);
                                    glDepthMask(true); glClearDepth(seeded?mainValue:1); glClear(GL_DEPTH_BUFFER_BIT);
                                    target.beginOpaque(main,projection,seeded?frame:null,vanilla); shader.use();
                                    glDisable(GL_BLEND); glDisable(GL_CULL_FACE); glBindVertexArray(vao);
                                    // The target's depth seeding uses texture unit zero.
                                    glBindTextureUnit(0,atlas); glBindTextureUnit(1,lightmap);
                                    glBindTextureUnit(2,sprites); glBindTextureUnit(3,yield);
                                    glBindTextureUnit(4,quad); glBindTextureUnit(5,original); glBindTextureUnit(7,depth);
                                    glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT);
                                    if(batch) glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES,6,GL_UNSIGNED_INT,0,1,0,0);
                                    else glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
                                    var pixel=BufferUtils.createByteBuffer(4); glReadPixels(SIZE/2,SIZE/2,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
                                    cases++;
                                    if ((separation<0)!=((pixel.get(0)&255)>0)) {
                                        failures.add("mainFar="+mainFar+",batch="+batch+",zero="+zero+",reverse="+reverse
                                                +",kind="+kind+",distance="+realDistance+",separation="+separation+",seeded="+seeded);
                                        groups.merge("distance="+center+",separation="+separation+",seeded="+seeded,1,Integer::sum);
                                    }
                                }
                            }
                        }
                    }
                }
                assertEquals(GL_NO_ERROR,glGetError());
                System.out.println("VEGETATION_DEPTH mismatches="+failures.size()+"/"+cases+" groups="+groups+" "+failures.stream().limit(30).toList());
                assertTrue(failures.isEmpty(),"unstable depth ownership: "+failures.size()+" failures, "+failures.stream().limit(30).toList());
            } finally {
                main.destroyBuffers(); glDeleteBuffers(indices); glDeleteBuffers(payload); glDeleteBuffers(records); glDeleteVertexArrays(vao);
            }
        } finally {
            for(int texture:textures) glDeleteTextures(texture);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static int shaderUniform(String name) { return glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM),name); }
    private static float windowDepth(Matrix4f projection,float distance,boolean zero) {
        var clip=projection.transform(new Vector4f(0,0,-distance,1));
        float depth=zero?clip.z/clip.w:clip.z/clip.w*.5f+.5f;
        return Math.round((double)depth*D24)/D24;
    }
    private static void fill(int texture,float value) {
        float[] values=new float[SIZE*SIZE]; Arrays.fill(values,value);
        glTextureSubImage2D(texture,0,0,0,SIZE,SIZE,GL_RED,GL_FLOAT,values);
    }
    private static int texture(List<Integer> textures,int unit,int format,int w,int h,int channels,float[] values) {
        int id=glCreateTextures(GL_TEXTURE_2D); textures.add(id); glTextureStorage2D(id,1,format,w,h);
        glTextureSubImage2D(id,0,0,0,w,h,channels,GL_FLOAT,values);
        glTextureParameteri(id,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTextureParameteri(id,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glBindTextureUnit(unit,id); return id;
    }
}
