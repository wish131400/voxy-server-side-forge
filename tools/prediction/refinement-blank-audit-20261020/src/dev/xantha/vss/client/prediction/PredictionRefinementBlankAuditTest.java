package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

/** Audit-only invariants. Failures intentionally preserve evidence against unmodified production code. */
class PredictionRefinementBlankAuditTest {
    @Test void replacementWithIdenticalSeamsMustInitializeTheNewGpuMask() throws Exception {
        try (var fixture = new Fixture()) {
            var first = PredictionLodSeamsTest.wetTile(0, -1, 1, 64, 0, 96);
            var second = PredictionLodSeamsTest.wetTile(0, -1, 1, 64, 0, 96);
            second = revision(second, first.revision() + 1);
            fixture.replace(first);
            fixture.prepare(first);
            fixture.jobs.removeFirst().run();
            var published = fixture.prepare(first);
            assertEquals(1, published.stream().filter(d -> !d.seam()).count());
            var oldGpu = fixture.gpu.get(first.key());
            byte[] before = mask(oldGpu, first.cellAxis());
            int beforePixel = wallPixel(first, oldGpu);
            assertEquals(255, beforePixel, "fixture must see the original source wall");
            assertEquals(128, before[0] & 255);
            assertFalse(PredictionBoundaryWalls.replaced(before, 63, 64, 1, 64, true));
            assertTrue(first.mesh().seamMesh().sameContent(second.mesh().seamMesh()));
            var replacementGpu = fixture.replace(second);
            var waiting = fixture.prepare(second);
            assertSame(oldGpu, waiting.stream().filter(d -> !d.seam()).findFirst().orElseThrow().gpu());
            assertArrayEquals(before, mask(oldGpu, first.cellAxis()), "the waiting frame retains the old GPU state");
            fixture.jobs.removeFirst().run();
            var ready = fixture.prepare(second);
            assertSame(replacementGpu, ready.stream().filter(d -> !d.seam()).findFirst().orElseThrow().gpu());
            assertFalse(fixture.scene.boundaryPending());
            byte[] after = mask(replacementGpu, second.cellAxis());
            int afterPixel = wallPixel(second, replacementGpu);
            var boundaries = (PredictionSeamPreparation) field(PredictionRenderer.Scene.class, fixture.scene, "boundaries");
            var result = (PredictionSeamPreparation.Result) field(PredictionSeamPreparation.class, boundaries, "published");
            System.out.println("AUDIT_SAME_SEAMS oldMask=" + (before[0] & 255) + " newMask=" + (after[0] & 255)
                    + " intended=" + (result.masks().get(second.key())[0] & 255)
                    + " changedMasks=" + result.maskChanges().size()
                    + " removedEastWall=" + PredictionBoundaryWalls.replaced(after, 63, 64, 1, 64, true));
            System.out.println("AUDIT_WALL_PIXEL alphaBefore=" + beforePixel + " alphaAfter=" + afterPixel);
            for (int frame=0;frame<20;frame++) fixture.prepare(second);
            assertEquals(afterPixel, wallPixel(second,replacementGpu), "unchanged frames do not repair the missing mask");
            var changed = revision(PredictionLodSeamsTest.wetTile(0,-1,1,64,0,97),second.revision()+1);
            var changedGpu=fixture.replace(changed);fixture.prepare(changed);fixture.jobs.removeFirst().run();fixture.prepare(changed);
            int recoveredPixel=wallPixel(changed,changedGpu);
            assertEquals(255,recoveredPixel,"a later real seam change uploads the complete mask and restores the wall");
            System.out.println("AUDIT_RECOVERY unchangedFrames=20 alpha="+afterPixel+" laterSeamChangeAlpha="+recoveredPixel);
            assertEquals(GL_NO_ERROR, glGetError());
            assertAll(
                    () -> assertEquals(beforePixel, afterPixel, "replacement must not expose clear sky through an unchanged wall"),
                    () -> assertArrayEquals(before, after, "new GPU allocation must receive the complete existing ownership/boundary mask"));
        }
    }

    @Test void actualGridRefinementRetainsOldDrawUntilBoundaryCommit() throws Exception {
        try (var fixture = new Fixture()) {
            var coarse = PredictionSubCellCoverageTest.tile(0, -1, 0, 8, 8, 64, false);
            var fine = revision(PredictionSubCellCoverageTest.tile(0, -1, 0, 64, 1, 68, false), 100);
            fixture.replace(coarse); fixture.prepare(coarse); fixture.jobs.removeFirst().run();
            var original = fixture.prepare(coarse).stream().filter(d -> !d.seam()).findFirst().orElseThrow();
            var replacement = fixture.replace(fine);
            for (int i = 0; i < 20; i++) {
                var pending = fixture.prepare(fine);
                assertSame(original, pending.stream().filter(d -> !d.seam()).findFirst().orElseThrow());
                assertTrue(fixture.scene.references(original.gpu()));
            }
            fixture.jobs.removeFirst().run();
            var complete = fixture.prepare(fine);
            assertSame(replacement, complete.stream().filter(d -> !d.seam()).findFirst().orElseThrow().gpu());
            byte[] mask = mask(replacement, fine.cellAxis());
            for (byte cell : mask) assertEquals(128, cell & 255);
            assertFalse(fixture.scene.boundaryPending());
            assertEquals(GL_NO_ERROR, glGetError());
            System.out.println("AUDIT_GRID_REFINEMENT waitingFrames=20 oldDrawRetained=true finalMask=128");
        }
    }

    private static PredictionTile revision(PredictionTile t, long revision) {
        return new PredictionTile(t.key(), t.heights(), t.groundHeights(), t.samples(), t.mesh(), t.depthBound(),
                t.generatedAtNanos(), revision, t.cellAxis(), t.spacingBlocks(), t.scopeOnly());
    }
    private static byte[] mask(PredictionGpuTile gpu, int axis) {
        gpu.bindYield(3);
        var data = BufferUtils.createByteBuffer(axis * axis);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_UNSIGNED_BYTE, data);
        byte[] result = new byte[data.remaining()]; data.get(result); return result;
    }
    private static Object field(Class<?> type, Object owner, String name) throws Exception {
        var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static int wallPixel(PredictionTile tile, PredictionGpuTile gpu) {
        var textures = new ArrayList<Integer>();
        var target = new com.mojang.blaze3d.pipeline.TextureTarget(64, 64, true, false);
        int vao = glGenVertexArrays(), ebo = glGenBuffers();
        try (var shader = PredictionTerrainProgram.create()) {
            texture(textures, 0, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1,1,1,1});
            texture(textures, 1, GL_RGBA8, 1, 1, GL_RGBA, new float[]{1,1,1,1});
            texture(textures, 2, GL_RGBA32F, 2, 2, GL_RGBA, new float[16]);
            float[] depth = new float[64*64]; Arrays.fill(depth, 1);
            int depthId = texture(textures, 7, GL_R32F, 64, 64, GL_RED, depth);
            texture(textures, 8, GL_R8, 1, 1, GL_RED, new float[]{0});
            var projection = VssLodProjection.of(new Matrix4f().perspective(1.2f,1,.05f,65536));
            shader.use(); shader.setSamplers(0,1,2,3,4); shader.setOpaqueAlpha(1);
            shader.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
            shader.setCamera(new Matrix4f().lookAlong(1,0,0,0,1,0),projection.matrix());
            shader.bindMainDepth(depthId,projection);
            int program = glGetInteger(GL_CURRENT_PROGRAM);
            glUniform3i(glGetUniformLocation(program,"VanillaMaskSize"),0,0,0);
            glUniform1fv(glGetUniformLocation(program,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
            int[] indices = new int[gpu.quadCount()*6];
            for (int q=0;q<gpu.quadCount();q++) {
                int p=q*6,v=q*4; indices[p]=v;indices[p+1]=v+1;indices[p+2]=v+2;
                indices[p+3]=v;indices[p+4]=v+2;indices[p+5]=v+3;
            }
            glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            target.bindWrite(true);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
            glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
            gpu.bindTerrain(shader,false);gpu.bindYield(3);
            shader.setTile(-32,-80,-32,tile.spacingBlocks(),tile.cellAxis(),true);
            shader.setBoundaryReplacement(true);shader.setMorph(gpu.packed(),0);
            PredictionRenderer.submitRanges(gpu.packed().drawRanges(false,VssLodFaceGroup.ALL),gpu.packed(),false);
            var pixel=BufferUtils.createByteBuffer(4);glReadPixels(32,32,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            return pixel.get(3)&255;
        } finally {
            target.destroyBuffers(); glDeleteBuffers(ebo);glDeleteVertexArrays(vao);
            for (int texture : textures) glDeleteTextures(texture);
        }
    }
    private static int texture(List<Integer> ids,int unit,int format,int width,int height,int channels,float[] data) {
        int id=glCreateTextures(GL_TEXTURE_2D);ids.add(id);glTextureStorage2D(id,1,format,width,height);
        glTextureSubImage2D(id,0,0,0,width,height,channels,GL_FLOAT,data);
        glTextureParameteri(id,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(id,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glBindTextureUnit(unit,id);return id;
    }
    private static final class Fixture implements AutoCloseable {
        final long window;
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        final PredictionRenderer.Scene scene;
        final Map<PredictionTileKey, PredictionGpuTile> gpu, previous;
        final List<PredictionGpuTile> owned = new ArrayList<>();
        final PredictionRenderResidency residency = new PredictionRenderResidency();
        RenderSnapshot source;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
            window = glfwCreateWindow(64, 64, "Refinement blank audit", 0, 0); assertNotEquals(0, window);
            glfwMakeContextCurrent(window); GL.createCapabilities();
            if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            scene = new PredictionRenderer.Scene(jobs::add);
            gpu = (Map<PredictionTileKey, PredictionGpuTile>) field(PredictionRenderer.class, null, "gpuTiles");
            previous = new HashMap<>(gpu); gpu.clear();
        }
        PredictionGpuTile replace(PredictionTile tile) {
            var resource = new PredictionGpuTile(tile.key()); owned.add(resource);
            assertTrue(resource.ensureMesh(tile)); gpu.put(tile.key(), resource);
            source = new RenderSnapshot(Level.OVERWORLD, VssLodLayout.of(4096, 6, true, false),
                    Map.of(tile.key(), tile), Map.of(tile.key(), tile.revision()));
            residency.retain(source); residency.uploaded(tile); return resource;
        }
        List<PredictionRenderer.Draw> prepare(PredictionTile tile) {
            var matrix = new Matrix4f();
            return scene.prepare(residency.snapshot(source), residency.drainChanges(),
                    new PredictionRenderer.CoverageView(0, 0, 1300, null), new Vec3(0, 100, 0),
                    4096, 32, matrix, matrix, null);
        }
        @Override public void close() throws Exception {
            scene.clear();
            for (var resource : owned) resource.close(); gpu.clear(); gpu.putAll(previous);
            glFinish(); PredictionTerrainArena.SHARED.reap(); PredictionTerrainArena.SHARED.close();
            PredictionTerrainArena.WATER.reap(); PredictionTerrainArena.WATER.close();
            PredictionQuadBufferPool.SHARED.close(); glfwDestroyWindow(window); glfwTerminate();
        }
    }
}
