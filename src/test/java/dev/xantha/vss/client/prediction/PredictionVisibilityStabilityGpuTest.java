package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionVisibilityStabilityGpuTest {
    private static final int SIZE = 96;
    private static final String REFERENCE_VERTEX = """
            #version 150
            uniform mat4 Mvp;
            uniform vec3 Offset;
            void main() {
                int c = gl_VertexID & 3;
                vec3 p = vec3(c == 1 || c == 2 ? 16.0 : 0.0,
                              c == 1 || c == 2 ? 96.0 : 64.0, c >= 2 ? 16.0 : 0.0);
                gl_Position = Mvp * vec4(p + Offset, 1.0);
            }
            """;

    @Test void slopedCoarseSurfaceKeepsBothTrianglesAsTheEyeCrossesVertexHeights() {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(SIZE, SIZE, "VSS coarse slope visibility", 0, 0); assertNotEquals(0, window);
        var textures = new ArrayList<Integer>();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread(); AlgorithmBootstrap.bootstrapMinecraft();
            System.out.println("VISIBILITY_STABILITY_GPU " + glGetString(GL_RENDERER));
            var output = new TextureTarget(SIZE, SIZE, true, false);
            int vao = glGenVertexArrays(), indices = glGenBuffers(), payload = glGenBuffers(), records = glCreateBuffers();
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2,0,2,3}, GL_STATIC_DRAW);
            texture(textures, 0, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1,1,1,1});
            texture(textures, 1, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1,1,1,1});
            texture(textures, 2, GL_RGBA32F, 2, 2, GL_RGBA, new float[16]);
            texture(textures, 3, GL_R8, 1, 1, GL_RED, new float[]{1});
            float[] sky = new float[SIZE * SIZE]; Arrays.fill(sky, 1);
            int depth = texture(textures, 7, GL_R32F, SIZE, SIZE, GL_RED, sky);
            int quad = glCreateTextures(GL_TEXTURE_BUFFER); textures.add(quad);
            glBindBuffer(GL_TEXTURE_BUFFER, payload);
            int low = 32768 + 64 * 4, high = 32768 + 96 * 4;
            int[] words = {16 << 16,16,0,16 | 16 << 16,low | high << 16,high | low << 16,
                    0,0xff6633,0,0x1ff6633,0xff6633,0xff6633};
            glBufferData(GL_TEXTURE_BUFFER, words, GL_STATIC_DRAW);
            glTextureBuffer(quad, GL_RGBA32UI, payload); glBindTextureUnit(4, quad);
            var missing = new ArrayList<String>(); int cases = 0, expectedPixels = 0, actualPixels = 0;
            try (var reference = GlProgram.link("slope-reference", REFERENCE_VERTEX,
                    "#version 150\nout vec4 Color; void main(){ Color=vec4(1); }")) {
                for (int mode = 0; mode < 4; mode++) {
                    boolean iris = mode >= 2, reverse = mode == 3, batch = mode == 1;
                    var vanilla = reverse ? new Matrix4f().perspective(1.2f, 1, 65536, .05f, true)
                            : new Matrix4f().perspective(1.2f, 1, .05f, 65536);
                    var projection = VssLodProjection.of(vanilla);
                    glClipControl(GL_LOWER_LEFT, reverse ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                    if (reverse) { Arrays.fill(sky, 0); } else Arrays.fill(sky, 1);
                    glTextureSubImage2D(depth, 0, 0, 0, SIZE, SIZE, GL_RED, GL_FLOAT, sky);
                    try (var shader = iris ? PredictionTerrainProgram.createIris("vec2 vssTaaShift(){return vec2(0);}",
                            source -> source + "\nout vec4 TestColor; void voxy_emitFragment(VoxyFragmentParameters p){TestColor=p.sampledColour;}\n")
                            : batch ? PredictionTerrainProgram.createBatch() : PredictionTerrainProgram.create()) {
                        for (float eye : new float[]{72,80,88,104}) for (float yaw : new float[]{-.1f,0,.1f}) {
                            // y = 64 + 2*x; every view is above the plane at x=2.
                            float ox=-2, oy=-eye, oz=-40;
                            var view = new Matrix4f().lookAlong(yaw,-.15f,-1,0,1,0);
                            var raster = iris ? vanilla : projection.matrix();
                            output.bindWrite(true); glBindVertexArray(vao);
                            glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND);
                            clear(); reference.use(); matrix(reference.uniform("Mvp"),new Matrix4f(raster).mul(view));
                            glUniform3f(reference.uniform("Offset"),ox,oy,oz); glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
                            byte[] expected = pixels(); int n = count(expected); assertTrue(n > 40, "reference slope must cover pixels");
                            shader.use(); shader.setSamplers(0,1,2,3,4); shader.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                            shader.setOpaqueAlpha(1); shader.setCamera(view,raster); shader.setRealRenderDistance(-1);
                            shader.bindMainDepth(depth,projection); shader.setTile(ox,oy,oz,16,1,true);
                            shader.batch(batch); shader.setBatchCameraOffset(0,0,0); shader.setBatchTimeSeconds(0);
                            int program=glGetInteger(GL_CURRENT_PROGRAM);
                            glUniform1fv(glGetUniformLocation(program,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                            if (iris) shader.setIrisFrame(new Matrix4f(vanilla).invert(),SIZE,SIZE,reverse,new int[256],reverse?0:1);
                            if (batch) {
                                var data=BufferUtils.createByteBuffer(64);
                                data.putFloat(ox).putFloat(oy).putFloat(oz).putFloat(16);
                                data.putInt(1).putInt(1).putInt(0).putInt(0);
                                data.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                                data.putInt(2).putInt(0).putInt(0).putInt(0).flip();
                                glNamedBufferData(records,data,GL_STREAM_DRAW); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,records);
                            }
                            clear();
                            if(batch) glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES,6,GL_UNSIGNED_INT,0,1,0,0);
                            else glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
                            byte[] actual=pixels(); int got=count(actual); cases++; expectedPixels+=n; actualPixels+=got;
                            if(!Arrays.equals(expected,actual)) missing.add("mode="+mode+",eye="+eye+",yaw="+yaw+",pixels="+got+"/"+n);
                        }
                    }
                }
                assertEquals(GL_NO_ERROR,glGetError());
                System.out.println("SLOPE_VISIBILITY mismatches="+missing.size()+"/"+cases+" pixels="+actualPixels+"/"+expectedPixels+" "+missing.stream().limit(18).toList());
                assertTrue(missing.isEmpty(),"visible coarse triangles disappeared: "+missing.stream().limit(18).toList());
            } finally {
                glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE); output.destroyBuffers();
                glDeleteBuffers(indices); glDeleteBuffers(payload); glDeleteBuffers(records); glDeleteVertexArrays(vao);
            }
        } finally { for(int texture:textures)glDeleteTextures(texture); glfwDestroyWindow(window); glfwTerminate(); }
    }
    private static void clear(){glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);}
    private static byte[] pixels(){var bytes=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,bytes);byte[] result=new byte[SIZE*SIZE];for(int i=0;i<result.length;i++)result[i]=(byte)((bytes.get(i*4+3)&255)>0?1:0);return result;}
    private static int count(byte[] values){int n=0;for(byte v:values)n+=v;return n;}
    private static void matrix(int uniform,Matrix4f value){glUniformMatrix4fv(uniform,false,value.get(new float[16]));}
    private static int texture(List<Integer> textures,int unit,int format,int width,int height,int channels,float[] values){int id=glCreateTextures(GL_TEXTURE_2D);textures.add(id);glTextureStorage2D(id,1,format,width,height);glTextureSubImage2D(id,0,0,0,width,height,channels,GL_FLOAT,values);glTextureParameteri(id,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(id,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glBindTextureUnit(unit,id);return id;}
}
