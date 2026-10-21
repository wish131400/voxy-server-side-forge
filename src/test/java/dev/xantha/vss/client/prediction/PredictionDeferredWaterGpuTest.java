package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import net.minecraft.world.phys.Vec3;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionDeferredWaterGpuTest {
    @Test void exportedPredictionRiverbedCannotEraseItsOwnDeferredWater() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "VSS deferred water", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window); GL.createCapabilities();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        int vao=glGenVertexArrays(), indices=glGenBuffers(), payload=glGenBuffers();
        int[] textures = new int[5];
        TextureTarget main = new TextureTarget(64, 64, true, false);
        TextureTarget voxy = new TextureTarget(64, 64, true, false);
        try (PredictionRenderTarget target = new PredictionRenderTarget(); PredictionTerrainProgram terrain = PredictionTerrainProgram.create(); GlProgram real = realSurfaceProgram()) {
            target.ensure(main);
            terrain.use(); terrain.setSamplers(0,1,2,3,4);
            terrain.setFrame(new float[]{0,0,0,1},1e7F,2e7F,0,1e7F,false,1);
            terrain.setOpaqueAlpha(1);
            glUniform1fv(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "DirectionalTint[0]"), new float[]{1,1,1,1,1,1,1});
            glUniform1f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "RealRenderDistance"), -1);
            textures[0]=texture(0,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            terrainAtlas=textures[0];
            textures[1]=texture(1,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            textures[2]=texture(2,GL_RGBA32F,2,2,GL_RGBA,new float[16]);
            textures[3]=texture(3,GL_R8,1,1,GL_RED,new float[]{1});
            RenderSystem.activeTexture(GL_TEXTURE4); textures[4]=glGenTextures(); glBindTexture(GL_TEXTURE_BUFFER,textures[4]);
            glBindBuffer(GL_TEXTURE_BUFFER,payload); glBufferData(GL_TEXTURE_BUFFER,new int[12],GL_STATIC_DRAW);
            glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32UI,payload);
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,0,2,3},GL_STATIC_DRAW);
            for(float height : new float[]{128,997,2048}) for(float fov : new float[]{70,7}) {
                var projection=VssLodProjection.of(new Matrix4f().setPerspective((float)Math.toRadians(fov),1,.05F,2048));
                Matrix4f view=new Matrix4f().lookAlong(0,-.5F,-.8660254F,0,1,0);
                byte[] expected=render(main,voxy,target,terrain,real,vao,payload,projection,view,height,fov,false,false,null,false);
                byte[] actual=render(main,voxy,target,terrain,real,vao,payload,projection,view,height,fov,true,true,null,false);
                int wet=0,missing=0;
                for(int y=20;y<44;y++)for(int x=20;x<44;x++) {
                    int at=(y*64+x)*4;
                    if((expected[at+2]&255)>100) {wet++;if((actual[at+2]&255)<100)missing++;}
                }
                System.out.println("DEFERRED_WATER height="+height+" fov="+fov+" wet="+wet+" missingAfterExport="+missing);
                assertTrue(wet>500,"fixture must cover the central field with water");
                assertEquals(0,missing,"exported prediction riverbed is not real water; height="+height+" fov="+fov);
                for (boolean before : new boolean[]{false, true}) {
                    byte[] realWater=render(main,voxy,target,terrain,real,vao,payload,projection,view,height,fov,true,true,62.875F,before);
                    assertEquals(0,waterPixels(realWater),"real coplanar water keeps ownership; before="+before+" height="+height+" fov="+fov);
                    byte[] realBed=render(main,voxy,target,terrain,real,vao,payload,projection,view,height,fov,true,true,58F,before);
                    assertEquals(576,waterPixels(realBed),"a deeper real riverbed cannot erase closer prediction water; before="+before+" height="+height+" fov="+fov);
                }
                byte[] near=render(main,voxy,target,terrain,real,vao,payload,projection,view,height,fov,true,true,80F,false);
                assertEquals(0,waterPixels(near),"a later nearer real surface must occlude water");
            }
            var ordinary=VssLodProjection.of(new Matrix4f().setPerspective((float)Math.toRadians(70),1,.05F,2048));
            Matrix4f view=new Matrix4f().lookAlong(0,-.5F,-.8660254F,0,1,0);
            byte[] withoutHistory=render(main,voxy,target,terrain,real,vao,payload,ordinary,view,997,70,true,false,null,false);
            System.out.println("DEFERRED_WATER_NEGATIVE height=997 fov=70 visible=" + waterPixels(withoutHistory) + "/576");
            assertTrue(waterPixels(withoutHistory)<500,"negative control must reproduce the old missing water");
            target.beginOpaque(main, ordinary);
            assertFalse(target.waterDepthHistory().available(),"history never survives into a new opaque frame");
            int oldReal=target.waterDepthHistory().realTexture(), oldExported=target.waterDepthHistory().exportedTexture();
            TextureTarget resized=new TextureTarget(32,32,true,false);
            try {
                target.ensure(resized);
                // Deleted names may immediately be reused by the new target's
                // depth allocation. Verify the history itself was released.
                assertEquals(-1,target.waterDepthHistory().realTexture());
                assertEquals(-1,target.waterDepthHistory().exportedTexture());
                assertFalse(target.waterDepthHistory().available());
                target.beginOpaque(resized,ordinary);target.writeDepth(resized,ordinary,true);
                assertTrue(target.waterDepthHistory().available());
                int realTexture=target.waterDepthHistory().realTexture(), exportedTexture=target.waterDepthHistory().exportedTexture();
                glBindTexture(GL_TEXTURE_2D,realTexture);
                assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH));
                target.close();
                assertFalse(glIsTexture(realTexture));assertFalse(glIsTexture(exportedTexture));
            } finally { resized.destroyBuffers(); }
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            main.destroyBuffers();voxy.destroyBuffers();glDeleteBuffers(payload);glDeleteBuffers(indices);glDeleteVertexArrays(vao);
            for(int t:textures)if(t!=0)glDeleteTextures(t);
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static byte[] render(TextureTarget main, TextureTarget voxy, PredictionRenderTarget target, PredictionTerrainProgram terrain,
            GlProgram real, int vao,int payload,VssLodProjection.MatrixData projection,Matrix4f view,float height,float fov,
            boolean export,boolean history,Float realY,boolean realBefore) {
        main.bindWrite(true);RenderSystem.disableBlend();RenderSystem.disableCull();RenderSystem.colorMask(true,true,true,true);
        RenderSystem.depthMask(true);glClearColor(0,0,0,1);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        PredictionVoxyDepth.Frame frame = null;
        if (realY != null && realBefore) frame = drawReal(main,voxy,real,vao,projection,view,height,fov,realY);
        Matrix4f mainMvp = new Matrix4f(projection.matrix());
        // Frame reconstruction needs the vanilla destination projection, not
        // prediction's infinite reversed projection.
        mainMvp.setPerspective((float)Math.toRadians(fov),1,.05F,2048).mul(view);
        target.beginOpaque(main,projection,frame,mainMvp);
        terrain.use();terrain.setCamera(view,projection.matrix());terrain.setTile(-8192,-height,-16000,16384,1,true);
        terrain.bindMainDepth(main.getDepthTextureId(),projection);terrain.setOpaqueAlpha(1);
        if (frame != null) terrain.bindVoxyDepth(frame,mainMvp);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,terrainAtlas);
        upload(payload,61,false);glBindVertexArray(vao);glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
        if(export) {target.restore(main);target.writeDepth(main,projection,true);}
        if (realY != null && !realBefore) frame = drawReal(main,voxy,real,vao,projection,view,height,fov,realY);
        target.beginWater();PredictionGlState.depthFunc(GL_GEQUAL);terrain.use();terrain.bindMainDepth(main.getDepthTextureId(),projection);terrain.setOpaqueAlpha(0);
        if (export && history) terrain.bindWaterDepthHistory(target.waterDepthHistory());
        if (frame != null) terrain.bindVoxyDepth(frame,mainMvp);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,terrainAtlas);
        RenderSystem.enableBlend();RenderSystem.blendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);RenderSystem.depthMask(false);
        upload(payload,62.875F,true);glBindVertexArray(vao);glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
        ByteBuffer pixels=BufferUtils.createByteBuffer(64*64*4);glReadPixels(0,0,64,64,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        byte[] result=new byte[pixels.remaining()];pixels.get(result);RenderSystem.depthMask(true);
        assertEquals(GL_NO_ERROR,glGetError(),"production water render");return result;
    }
    private static int terrainAtlas;
    private static int waterPixels(byte[] pixels) {
        int water=0;
        for(int y=20;y<44;y++)for(int x=20;x<44;x++)if((pixels[(y*64+x)*4+2]&255)>100)water++;
        return water;
    }
    private static PredictionVoxyDepth.Frame drawReal(TextureTarget main, TextureTarget voxy, GlProgram fill, int vao,
            VssLodProjection.MatrixData projection,Matrix4f view,float height,float fov,float y) {
        var voxyProjection=VssLodProjection.of(new Matrix4f().setPerspective((float)Math.toRadians(fov),1,16,65536));
        voxy.bindWrite(true);PredictionGlState.depthMask(true);glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);
        fill.use();glUniformMatrix4fv(fill.uniform("RayInverseMvp"),false,new Matrix4f(projection.matrix()).mul(view).invert().get(new float[16]));
        glUniform1f(fill.uniform("PlaneY"),y-height);
        PredictionGlState.depthFunc(GL_ALWAYS);PredictionGlState.disableBlend();glBindVertexArray(vao);
        glUniform2f(fill.uniform("Planes"),voxyProjection.vanillaA(),voxyProjection.vanillaB());
        glDrawArrays(GL_TRIANGLES,0,3);
        main.bindWrite(true);PredictionGlState.depthFunc(GL_LEQUAL);
        glUniform2f(fill.uniform("Planes"),projection.vanillaA(),projection.vanillaB());
        glDrawArrays(GL_TRIANGLES,0,3);
        return new PredictionVoxyDepth.Frame(voxy.getDepthTextureId(),main.frameBufferId,64,64,Vec3.ZERO,
                new Matrix4f().setPerspective((float)Math.toRadians(fov),1,16,65536).mul(view).invert());
    }
    private static GlProgram realSurfaceProgram() {
        return GlProgram.link("deferred_real_water", """
                #version 150
                void main() {
                    gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0,
                                       gl_VertexID == 2 ? 3.0 : -1.0,0.0,1.0);
                }
                """, """
                #version 150
                uniform mat4 RayInverseMvp;
                uniform vec2 Planes;
                uniform float PlaneY;
                out vec4 fragColor;
                void main() {
                    vec2 ndc=gl_FragCoord.xy/vec2(64.0)*2.0-1.0;
                    vec4 a=RayInverseMvp*vec4(ndc,1.0,1.0),b=RayInverseMvp*vec4(ndc,-1.0,1.0);
                    float da=a.y-PlaneY*a.w,db=b.y-PlaneY*b.w;
                    float distance=(db-da)/(a.w*db-b.w*da);
                    if(distance<=0.0)discard;
                    gl_FragDepth=clamp((-Planes.x+Planes.y/distance)*0.5+0.5,0.0,1.0-1.0/16777215.0);
                    fragColor=vec4(0.0,1.0,0.0,1.0);
                }
                """);
    }
    private static void upload(int buffer,float height,boolean fluid) {
        int y=32768+Math.round(height*(fluid?16:4));int color=fluid?0xFFFFFF:0xA06010;
        int flags=fluid?PredictionPackedMesh.FLAG_FLUID_FINE_Y | (1<<PredictionPackedMesh.FLAGS_FLUID_SHIFT):0;
        int[] words={16384<<16,16384,0,16384|(16384<<16),y|(y<<16),y|(y<<16),flags,color,0,color|0x01000000,color,color};
        glBindBuffer(GL_TEXTURE_BUFFER,buffer);glBufferData(GL_TEXTURE_BUFFER,words,GL_STATIC_DRAW);
    }
    private static int texture(int unit,int internal,int w,int h,int format,float[] values) {
        RenderSystem.activeTexture(GL_TEXTURE0+unit);int texture=glGenTextures();RenderSystem.bindTexture(texture);
        glTexImage2D(GL_TEXTURE_2D,0,internal,w,h,0,format,GL_FLOAT,values);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return texture;
    }
}
