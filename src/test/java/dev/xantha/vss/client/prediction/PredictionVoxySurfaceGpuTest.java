package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

import dev.xantha.vss.mixin.voxy.PredictionFogShaderMixin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** VSS must preserve ordinary Voxy surfaces even when a predicted tile exists nearby. */
@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionVoxySurfaceGpuTest {
    static String patch(String path, String source) throws Exception {
        var callback = new CallbackInfoReturnable<String>("parse", true, source);
        var hook = PredictionFogShaderMixin.class.getDeclaredMethod("vss$sharedPredictionFog",
                String.class, CallbackInfoReturnable.class);
        hook.setAccessible(true); hook.invoke(null, path, callback);
        return callback.getReturnValue();
    }

    @Test void treesCliffsAndTranslucentSidesAreNotClassifiedAsReplaceableByRadius() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(16,16,"VSS real surface preservation",0,0); assertNotEquals(0,window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            String vertex=patch("voxy:lod/gl46/quads3.vert", """
                    #version 460 core
                    layout(location=0) out flat uvec4 interData;
                    uniform vec3 cameraSubPos, WorldPosition;
                    uniform uint Face;
                    struct QuadData { vec3 basePoint; vec2 quadSizeAddin; float lodScale; uint axis; };
                    uint quadData[1]=uint[1](0u);
                    vec3 swizzelDataAxis(uint axis,vec3 data) { return data; }
                    uint extractFace(uint data) { return Face; }
                    void setupQuad(out QuadData quad,uint data,uvec2 pos,bool attributes) {
                        quad.basePoint=WorldPosition; quad.quadSizeAddin=vec2(1); quad.lodScale=1; quad.axis=0u;
                    }
                    void main() {
                        QuadData quad; uvec2 pos=uvec2(0);
                        setupQuad(quad, quadData[uint(gl_VertexID)>>2], pos, (gl_VertexID&3)==1);
                        interData=uvec4(0);
                        gl_Position=vec4(gl_VertexID==1?3:-1,gl_VertexID==2?3:-1,0,1);
                    }
                    """);
            String fragment=patch("voxy:lod/gl46/quads.frag", """
                    #version 460 core
                    layout(location=0) in flat uvec4 interData;
                    layout(location=0) out vec4 color;
                    void main() { color=vec4(.25,.5,.75,1); }
                    """);
            int vao=glGenVertexArrays(); glBindVertexArray(vao); glViewport(0,0,16,16);
            try(var program=GlProgram.link("Voxy ordinary surfaces",vertex,fragment)) {
                program.use();
                // Exercise a fully covered angular sector under the old patch as well.
                // A ready prediction tile is deliberately not proof of replacement pixels.
                glUniform1i(program.uniform("VssBoundaryEnabled"),1);
                glUniform4ui(program.uniform("VssBoundaryCoverage"),-1,-1,-1,-1);
                var missing=new java.util.ArrayList<String>();
                for(float radius:new float[]{256,512,1024}) {
                    glUniform1f(program.uniform("VssBoundaryRadius"),radius);
                    for(float distance:new float[]{radius-16,radius,radius+16,radius+128})
                            for(int face=0;face<6;face++) {
                        glUniform3f(program.uniform("WorldPosition"),distance,0,0);
                        glUniform1ui(program.uniform("Face"),face);
                        glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT); glDrawArrays(GL_TRIANGLES,0,3);
                        var pixel=BufferUtils.createByteBuffer(4); glReadPixels(8,8,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
                        if ((pixel.get(3)&255)!=255) missing.add("radius="+radius+",distance="+distance+",face="+face);
                    }
                }
                assertEquals(GL_NO_ERROR,glGetError());
                System.out.println("VOXY_SURFACE missing="+missing.size()+"/72 "+missing);
                assertTrue(missing.isEmpty(),"ordinary real surfaces were removed: "+missing);
            } finally { glDeleteVertexArrays(vao); }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }
}
