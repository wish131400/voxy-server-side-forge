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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

/** Real GPU state and pixels across the production asynchronous boundary commit. */
@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionBoundaryCommitGpuTest {
    @Test void replacementWithIdenticalSeamsMustInitializeTheNewGpuMask() throws Exception {
        try (var fixture = new Fixture()) {
            var first = PredictionLodSeamsTest.wetTile(0, -1, 1, 64, 0, 96);
            var second = PredictionLodSeamsTest.wetTile(0, -1, 1, 64, 0, 96);
            second = revision(second, first.revision() + 1);
            fixture.replace(first);
            fixture.prepare();
            fixture.jobs.removeFirst().run();
            var published = fixture.prepare();
            assertEquals(1, published.stream().filter(d -> !d.seam()).count());
            var oldGpu = fixture.gpu.get(first.key());
            byte[] before = mask(oldGpu, first.cellAxis());
            int beforePixel = wallPixel(first, oldGpu);
            assertEquals(255, beforePixel, "fixture must see the original source wall");
            assertEquals(128, before[0] & 255);
            assertFalse(PredictionBoundaryWalls.replaced(before, 63, 64, 1, 64, true));
            assertTrue(first.mesh().seamMesh().sameContent(second.mesh().seamMesh()));
            var replacementGpu = fixture.replace(second);
            var waiting = fixture.prepare();
            assertSame(oldGpu, waiting.stream().filter(d -> !d.seam()).findFirst().orElseThrow().gpu());
            assertArrayEquals(before, mask(oldGpu, first.cellAxis()), "the waiting frame retains the old GPU state");
            fixture.jobs.removeFirst().run();
            var ready = fixture.prepare();
            assertSame(replacementGpu, ready.stream().filter(d -> !d.seam()).findFirst().orElseThrow().gpu());
            assertFalse(fixture.scene.boundaryPending());
            assertEquals(beforePixel, wallPixel(second, replacementGpu, true), "MDI retains the same source wall");
            byte[] after = mask(replacementGpu, second.cellAxis());
            int afterPixel = wallPixel(second, replacementGpu);
            var boundaries = (PredictionSeamPreparation) field(PredictionRenderer.Scene.class, fixture.scene, "boundaries");
            var result = (PredictionSeamPreparation.Result) field(PredictionSeamPreparation.class, boundaries, "published");
            assertTrue(result.maskChanges().isEmpty(), "replacement must exercise the unchanged logical mask path");
            System.out.println("BOUNDARY_SAME_SEAMS oldMask=" + (before[0] & 255) + " newMask=" + (after[0] & 255)
                    + " intended=" + (result.masks().get(second.key())[0] & 255)
                    + " changedMasks=" + result.maskChanges().size()
                    + " removedEastWall=" + PredictionBoundaryWalls.replaced(after, 63, 64, 1, 64, true));
            System.out.println("BOUNDARY_WALL_PIXEL alphaBefore=" + beforePixel + " alphaAfter=" + afterPixel);
            long stableRevision = replacementGpu.submissionRevision();
            for (int frame=0;frame<20;frame++) fixture.prepare();
            assertEquals(stableRevision, replacementGpu.submissionRevision(), "stable frames do not re-upload masks");
            assertEquals(afterPixel, wallPixel(second,replacementGpu), "unchanged frames retain the initialized mask");
            var changed = revision(PredictionLodSeamsTest.wetTile(0,-1,1,64,0,97),second.revision()+1);
            var changedGpu=fixture.replace(changed);fixture.prepare();fixture.jobs.removeFirst().run();fixture.prepare();
            int recoveredPixel=wallPixel(changed,changedGpu);
            assertEquals(255,recoveredPixel,"a later real seam change uploads the complete mask and restores the wall");
            System.out.println("BOUNDARY_RECOVERY unchangedFrames=20 alpha="+afterPixel+" laterSeamChangeAlpha="+recoveredPixel);
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
            fixture.replace(coarse); fixture.prepare(); fixture.jobs.removeFirst().run();
            var original = fixture.prepare().stream().filter(d -> !d.seam()).findFirst().orElseThrow();
            var replacement = fixture.replace(fine);
            for (int i = 0; i < 20; i++) {
                var pending = fixture.prepare();
                assertSame(original, pending.stream().filter(d -> !d.seam()).findFirst().orElseThrow());
                assertTrue(fixture.scene.references(original.gpu()));
            }
            fixture.jobs.removeFirst().run();
            var complete = fixture.prepare();
            assertSame(replacement, complete.stream().filter(d -> !d.seam()).findFirst().orElseThrow().gpu());
            byte[] mask = mask(replacement, fine.cellAxis());
            for (byte cell : mask) assertEquals(128, cell & 255);
            assertFalse(fixture.scene.boundaryPending());
            assertEquals(GL_NO_ERROR, glGetError());
            System.out.println("BOUNDARY_GRID_REFINEMENT waitingFrames=20 oldDrawRetained=true finalMask=128");
        }
    }

    @Test void completedRefinementCommitsBeforeAnUnrelatedNewVanillaEdgeSnapshot() throws Exception {
        Object mask = field(PredictionRenderer.class, null, "vanillaMask");
        var edgeField = PredictionVanillaMask.class.getDeclaredField("groundEdges"); edgeField.setAccessible(true);
        Object previousEdges = edgeField.get(mask);
        try (var fixture = new Fixture()) {
            edgeField.set(mask, List.of());
            var coarse = PredictionSubCellCoverageTest.tile(0, -1, 0, 8, 8, 64, false);
            fixture.replace(coarse); fixture.commit();
            var fine = revision(PredictionSubCellCoverageTest.tile(0, -1, 0, 64, 1, 68, false), 100);
            var replacement = fixture.replace(fine); fixture.prepare();
            fixture.jobs.removeFirst().run();
            long committed = fixture.result().version();
            // Compiling another vanilla column must not discard this completed
            // prediction handoff and keep the old coarse slab indefinitely.
            edgeField.set(mask, List.of(new PredictionRealBoundarySeams.Edge(30000, 30000, 1, 0, fine.samples()[0])));
            var draws = fixture.prepare();
            var draw = draws.stream().filter(d -> !d.seam() && d.tile().key().equals(fine.key())).findFirst().orElseThrow();
            assertSame(replacement, draw.gpu());
            assertTrue(fixture.result().version() > committed);
            assertTrue(fixture.scene.boundaryPending(), "the new independent vanilla seam snapshot still gets processed");
            for (byte cell : mask(replacement, fine.cellAxis())) assertEquals(128, cell & 255);
            fixture.jobs.removeFirst().run(); fixture.prepare();
            assertFalse(fixture.scene.boundaryPending());
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { edgeField.set(mask, previousEdges); }
    }

    @Test void replacementRestoresUnchangedTreeIncludingFeatureOnlyLeavesAndBothArenaMasks() throws Exception {
        try (var fixture = new Fixture()) {
            var parent = PredictionSubCellCoverageTest.tile(-1, -1, 7, 8, 1024, 160, true, true);
            var child = PredictionSubCellCoverageTest.tile(-63, -30, 0, 64, 1, 96, false);
            var oldGpu = fixture.replace(parent); fixture.replace(child); fixture.commit();
            var result = fixture.result();
            byte[] expected = result.masks().get(parent.key());
            var grid = (PredictionCoverageGrid) field(PredictionGpuTile.class, oldGpu, "coverageGrid");
            assertNotNull(grid);
            int featureOnly = 0, shared = 0, replacedWalls = 0;
            for (int i=0;i<expected.length;i++) if (grid.leaf(i)) {
                if ((expected[i] & 192) == 64) featureOnly++;
                if ((expected[i] & 192) == 192) shared++;
                if ((expected[i] & 15) != 0) replacedWalls++;
            }
            assertTrue(featureOnly > 0, "unfinished child ground must retain the parent's completed features");
            assertTrue(shared > 0); assertTrue(replacedWalls > 0, "fixture includes legal wall replacement flags");
            int[] words = grid.words(expected);
            assertGpuWords(oldGpu, words);
            long childRevision = fixture.gpu.get(child.key()).submissionRevision();
            var replacement = fixture.replace(revision(parent, parent.revision()+100)); fixture.commit();
            assertFalse(fixture.result().maskChanges().containsKey(parent.key()), "logical masks are unchanged");
            assertGpuWords(replacement, words);
            assertEquals(childRevision, fixture.gpu.get(child.key()).submissionRevision(), "unchanged child GPU remains untouched");
            long stableRevision = replacement.submissionRevision();
            for (int frame=0;frame<20;frame++) fixture.prepare();
            assertEquals(stableRevision, replacement.submissionRevision());
            System.out.println("BOUNDARY_TREE nodes="+grid.size()+" featureOnly="+featureOnly
                    +" shared="+shared+" legalWallNodes="+replacedWalls+" opaqueAndWaterExact=true");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test void neighborRemovalUpdatesAnUnstagedGpuAndReusedMeshRestoresItsMask() throws Exception {
        try (var fixture = new Fixture()) {
            var first = PredictionLodSeamsTest.wetTile(-1, 0, 2, 64, 0, 180);
            var neighbor = PredictionLodSeamsTest.tile(0, 0, 4, 68);
            var gpu = fixture.replace(first); fixture.replace(neighbor); fixture.commit();
            byte[] initial = mask(gpu, first.cellAxis());
            assertTrue(PredictionBoundaryWalls.replaced(initial, 63, 64, 2, 128, true));
            // Reuse the GPU object for a new mesh upload, which invalidates its mask.
            var rebound = revision(first, first.revision()+100);
            assertTrue(gpu.ensureMesh(rebound));
            fixture.tiles.put(rebound.key(), rebound);
            fixture.source = new RenderSnapshot(Level.OVERWORLD, fixture.layout, Map.copyOf(fixture.tiles), Map.of());
            fixture.residency.retain(fixture.source); fixture.residency.uploaded(rebound);
            long priorRevision = gpu.submissionRevision(); fixture.commit();
            assertTrue(gpu.submissionRevision() > priorRevision, "new mesh upload must restore the complete mask");
            assertArrayEquals(initial, mask(gpu, first.cellAxis()));
            long stableRevision = gpu.submissionRevision();
            for (int frame=0;frame<20;frame++) fixture.prepare();
            assertEquals(stableRevision, gpu.submissionRevision(), "stable frames skip GPU mask uploads");
            // Remove only the neighbor; the first tile must stay out of stagedKeys.
            fixture.tiles.remove(neighbor.key());
            fixture.source = new RenderSnapshot(Level.OVERWORLD, fixture.layout, Map.copyOf(fixture.tiles), Map.of());
            fixture.residency.retain(fixture.source); fixture.prepare();
            var staged = (Set<?>) field(PredictionRenderer.Scene.class, fixture.scene, "stagedKeys");
            assertFalse(staged.contains(first.key())); assertTrue(staged.contains(neighbor.key()));
            fixture.jobs.removeFirst().run(); fixture.prepare();
            byte[] after = mask(gpu, first.cellAxis());
            assertFalse(PredictionBoundaryWalls.replaced(after, 63, 64, 2, 128, true));
            assertTrue(fixture.result().maskChanges().containsKey(first.key()));
            assertTrue(gpu.submissionRevision() > stableRevision);
            assertEquals(GL_NO_ERROR, glGetError());
            System.out.println("BOUNDARY_NEIGHBOR unstagedUpdated=true reusedMeshMaskRestored=true stableUploadsSkipped=true");
        }
    }

    private static void assertGpuWords(PredictionGpuTile gpu, int[] expected) {
        gpu.bindYield(3);
        int axis = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        var texels = BufferUtils.createFloatBuffer(axis*axis*2);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RG, GL_FLOAT, texels);
        for (int i=0;i<expected.length;i++) {
            assertEquals(expected[i] & 255, Math.round(texels.get(i*2)*255), "tree mask at node "+i);
            assertEquals(expected[i] >>> 8, Math.round(texels.get(i*2+1)), "tree child at node "+i);
        }
        assertNotNull(gpu.arenaSlice(false)); assertNotNull(gpu.arenaSlice(true));
        assertTrue(gpu.dedicatedWaterArena());
        for (boolean water : new boolean[]{false,true}) {
            var slice = gpu.arenaSlice(water);
            var words = BufferUtils.createIntBuffer(expected.length);
            glBindBuffer(GL_COPY_READ_BUFFER, slice.page.buffer);
            glGetBufferSubData(GL_COPY_READ_BUFFER, slice.maskOffset, words);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            int[] actual = new int[expected.length]; words.get(actual);
            assertArrayEquals(expected, actual, "arena mask water="+water);
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
        return wallPixel(tile, gpu, false);
    }
    private static int wallPixel(PredictionTile tile, PredictionGpuTile gpu, boolean mdi) {
        var textures = new ArrayList<Integer>();
        var target = new com.mojang.blaze3d.pipeline.TextureTarget(64, 64, true, false);
        int vao = glGenVertexArrays(), ebo = glGenBuffers();
        try (var shader = mdi ? PredictionTerrainProgram.createBatch() : PredictionTerrainProgram.create();
             var batch = new PredictionIndirectBatch()) {
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
            var ranges = gpu.packed().drawRanges(false, VssLodFaceGroup.ALL);
            if (mdi) {
                var allowed = new boolean[tile.cellAxis() * tile.cellAxis()]; Arrays.fill(allowed, true);
                var draw = new PredictionRenderer.Draw(tile, gpu, allowed, false, VssLodFaceGroup.ALL, 0,
                        new net.minecraft.world.phys.AABB(tile.baseBlockX(), 0, tile.baseBlockZ(),
                                tile.baseBlockX() + tile.spanBlocks(), 96, tile.baseBlockZ() + tile.spanBlocks()));
                batch.begin(shader);
                assertTrue(batch.add(draw, ranges, new Vec3(tile.baseBlockX()+32,80,tile.baseBlockZ()+32), false, true, Long.MAX_VALUE));
                assertTrue(batch.end() > 0);
            } else {
                gpu.bindTerrain(shader,false);gpu.bindYield(3);
                shader.setTile(-32,-80,-32,tile.spacingBlocks(),tile.cellAxis(),true);
                shader.setBoundaryReplacement(true);shader.setMorph(gpu.packed(),0);
                PredictionRenderer.submitRanges(ranges,gpu.packed(),false);
            }
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
        final Map<PredictionTileKey, PredictionTile> tiles = new LinkedHashMap<>();
        final VssLodLayout layout = VssLodLayout.of(16384, 9, true, false);
        RenderSnapshot source;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
            window = glfwCreateWindow(64, 64, "Boundary commit regression", 0, 0); assertNotEquals(0, window);
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
            tiles.put(tile.key(), tile);
            source = new RenderSnapshot(Level.OVERWORLD, layout, Map.copyOf(tiles), Map.of());
            residency.retain(source); residency.uploaded(tile); return resource;
        }
        List<PredictionRenderer.Draw> prepare() {
            var matrix = new Matrix4f();
            return scene.prepare(residency.snapshot(source), residency.drainChanges(),
                    new PredictionRenderer.CoverageView(0, 0, 1300, null), new Vec3(0, 100, 0),
                    16384, 32, matrix, matrix, null);
        }
        PredictionSeamPreparation.Result result() throws Exception {
            var boundaries = (PredictionSeamPreparation) field(PredictionRenderer.Scene.class, scene, "boundaries");
            return (PredictionSeamPreparation.Result) field(PredictionSeamPreparation.class, boundaries, "published");
        }
        void commit() {
            prepare(); assertEquals(1, jobs.size()); jobs.removeFirst().run(); prepare();
            assertFalse(scene.boundaryPending());
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
