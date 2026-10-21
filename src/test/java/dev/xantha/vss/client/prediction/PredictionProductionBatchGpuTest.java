package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import java.nio.*;
import java.util.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionProductionBatchGpuTest {
    @Test void productionArenaMaskBatchAndFallbackArePixelExact()throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(128,128,"production batch",0,0);assertNotEquals(0,window);
        var gpuTiles=new ArrayList<PredictionGpuTile>();var textures=new ArrayList<Integer>();
        String oldIndirect=System.getProperty("vss.disableIndirect");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            PredictionMeshCodecTest.bootstrap();
            try(var program=PredictionTerrainProgram.createBatch();var legacy=referenceProgram();var batch=new PredictionIndirectBatch()) {
                int vao=glGenVertexArrays();glBindVertexArray(vao);
                int ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
                var entries=new ArrayList<PredictionRenderer.Draw>();int max=0;
                for(int t=0;t<128;t++) {
                    var mesh=PredictionMeshCodecTest.fixture();int n=(mesh.cellAxis()+1)*(mesh.cellAxis()+1);
                    var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,t%4,t/4,0);
                    var tile=new PredictionTileManager.PredictionTile(key,new int[n],new int[n],new ClientColumnSample[n],mesh,
                            new PredictionDepthBound(60,80),0,t+1,mesh.cellAxis(),1);
                    var gpu=new PredictionGpuTile(key);gpuTiles.add(gpu);
                    if(t%3==1)System.setProperty("vss.disableIndirect","true");
                    else System.clearProperty("vss.disableIndirect");
                    assertTrue(gpu.ensureMesh(tile));
                    if(t%3==1)assertNull(gpu.arenaSlice());else assertNotNull(gpu.arenaSlice());
                    boolean[] allowed=new boolean[mesh.cellCount()];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
                    byte[] mask=new byte[allowed.length];for(int i=0;i<mask.length;i++)mask[i]=(byte)((i+t)%7==0?0:128);
                    gpu.updateBoundaryCoverage(mask);
                    entries.add(new PredictionRenderer.Draw(tile,gpu,allowed,false,VssLodFaceGroup.ALL,0,
                            new AABB(tile.baseBlockX(),60,tile.baseBlockZ(),tile.baseBlockX()+tile.spanBlocks(),80,tile.baseBlockZ()+tile.spanBlocks())));max=Math.max(max,gpu.quadCount());
                }
                int[] indices=new int[max*6];int[] corners={0,1,2,0,2,3};
                for(int q=0;q<max;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
                tex(textures,0,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
                tex(textures,1,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
                tex(textures,2,2,2,GL_RGBA32F,GL_RGBA,new float[16]);
                tex(textures,5,128,128,GL_R32F,GL_RED,new float[128*128]);
                float[] clear=new float[128*128];Arrays.fill(clear,1);int depth=tex(textures,7,128,128,GL_R32F,GL_RED,clear);
                var camera=new Vec3(32,145,16);
                for(var setup:List.of(legacy,program)) {
                setup.use();setup.setSamplers(0,1,2,3,4);
                setup.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                var p=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(80),1,.05f,65536));
                setup.setCamera(new Matrix4f().lookAlong(0,-1,0,0,0,-1),p.matrix());setup.bindMainDepth(depth,p);
                int handle=glGetInteger(GL_CURRENT_PROGRAM);
                glUniform1i(glGetUniformLocation(handle,"ExactCoverage"),5);glUniform1i(glGetUniformLocation(handle,"VanillaMask"),6);
                glUniform3i(glGetUniformLocation(handle,"VanillaMaskSize"),1,1,1);glUniform3f(glGetUniformLocation(handle,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
                glUniform1fv(glGetUniformLocation(handle,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                }
                program.use();program.setSamplers(0,1,2,3,4);
                program.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(80),1,.05f,65536));
                program.setCamera(new Matrix4f().lookAlong(0,-1,0,0,0,-1),projection.matrix());program.bindMainDepth(depth,projection);
                int id=glGetInteger(GL_CURRENT_PROGRAM);
                glUniform1i(glGetUniformLocation(id,"ExactCoverage"),5);glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);
                glActiveTexture(GL_TEXTURE6);int volume=glGenTextures();textures.add(volume);glBindTexture(GL_TEXTURE_3D,volume);
                glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                glTexImage3D(GL_TEXTURE_3D,0,GL_R8,1,1,1,0,GL_RED,GL_FLOAT,new float[]{0});
                glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),1,1,1);glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
                glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                var target = new com.mojang.blaze3d.pipeline.TextureTarget(128,128,true,false);
                tex(textures,8,1,1,GL_R8,GL_RED,new float[]{0});
                for(var setup:List.of(legacy,program)) {
                    setup.use();int handle=glGetInteger(GL_CURRENT_PROGRAM);
                    glUniform1i(glGetUniformLocation(handle,"ExactCoverage"),8);
                    glUniform3f(glGetUniformLocation(handle,"ExactCoverageGrid"),0,0,1);

                }
                for(boolean water:new boolean[]{false,true})for(boolean split:new boolean[]{false,true}) {
                    byte[][] pixels=new byte[3][];float[][] depths=new float[3][];
                    for(int mode=0;mode<3;mode++) {
                        target.bindWrite(true);
                        var activeProgram=mode==0?legacy:program;activeProgram.use();
                        glViewport(0,0,128,128);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
                        if(water){glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);
                        glClearDepth(0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        activeProgram.batch(false);activeProgram.setOpaqueAlpha(water?0:1);
                        int sentinel=glGenBuffers();glBindBuffer(GL_SHADER_STORAGE_BUFFER,sentinel);glBufferData(GL_SHADER_STORAGE_BUFFER,256,GL_STATIC_DRAW);
                        glBindBufferRange(GL_SHADER_STORAGE_BUFFER,7,sentinel,0,128);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,sentinel);
                        if(mode==1 || mode==2&&water)batch.begin(program, !water);
                        else if(mode==2) {
                            batch.begin(program,target.getDepthTextureId(),128,128,
                                    new Matrix4f(projection.matrix()).mul(new Matrix4f().lookAlong(0,-1,0,0,0,-1)));
                            batch.nextOcclusionBucket();
                        }
                        List<PredictionRenderer.Draw> ordered = entries;
                        if (mode > 0 && !water) {
                            var groups = new PredictionOpaqueBatches();
                            var grouped = new ArrayList<PredictionRenderer.Draw>();
                            int count = groups.prepare(entries);
                            for (int b = 0; b < count; b++) {
                                var bucket = groups.bucket(b);
                                for (var page : bucket.pages) grouped.addAll(page.draws);
                                grouped.addAll(bucket.fallback);
                            }
                            assertEquals(new HashSet<>(entries), new HashSet<>(grouped));
                            assertEquals(entries.size(), grouped.size());
                            ordered = grouped;
                        }
                        for(int i=0;i<entries.size();i++) {
                            if(mode==2&&!water&&i%32==0)batch.nextOcclusionBucket();
                            var draw=ordered.get(water?ordered.size()-1-i:i);var p=draw.gpu().packed();
                            var ranges=p.drawRanges(water,split?5:VssLodFaceGroup.ALL);if(ranges.quads==0)continue;
                            if(mode>0 && batch.add(draw,ranges,camera,water,true,Long.MAX_VALUE))continue;
                            if(mode>0)batch.flush();
                            draw.gpu().bindTerrain(activeProgram, water);draw.gpu().bindYield(3);
                            activeProgram.setTile((float)(draw.tile().baseBlockX()-camera.x),(float)-camera.y,(float)(draw.tile().baseBlockZ()-camera.z),1,p.cellAxis(),true);
                            activeProgram.setBoundaryReplacement(!water);PredictionRenderer.submitRanges(ranges, p, water);
                        }
                        if(mode>0){assertTrue(batch.end()>0);assertEquals(sentinel,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,7));assertEquals(128,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,7));assertEquals(sentinel,glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING));}
                        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,0);glBindBuffer(GL_SHADER_STORAGE_BUFFER,0);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0);glDeleteBuffers(sentinel);
                        var bytes=BufferUtils.createByteBuffer(128*128*4);glReadPixels(0,0,128,128,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
                        pixels[mode]=new byte[bytes.remaining()];bytes.get(pixels[mode]);
                        var values=BufferUtils.createFloatBuffer(128*128);glReadPixels(0,0,128,128,GL_DEPTH_COMPONENT,GL_FLOAT,values);
                        depths[mode]=new float[values.remaining()];values.get(depths[mode]);assertEquals(GL_NO_ERROR,glGetError());
                    }
                    assertArrayEquals(pixels[0],pixels[1],"colors water="+water+" split="+split);assertArrayEquals(depths[0],depths[1]);
                    assertArrayEquals(pixels[0],pixels[2],"HiZ colors water="+water+" split="+split);assertArrayEquals(depths[0],depths[2]);
                    if(!water&&!split){int visible=0;for(int i=0;i<pixels[0].length;i+=4)if(pixels[0][i]!=0||pixels[0][i+1]!=0||pixels[0][i+2]!=0)visible++;assertTrue(visible>100);}
                }
                verifyPlanTransitions(program, entries, camera, target);
                verifyGroupedFallbackReplay(program, entries, camera, target);
                verifyOwnershipPrimitives(program, legacy, batch, entries, camera);
                benchmarkOcclusion(program,batch,entries.get(0),camera,projection,target);
                CompletionProductionDirectionBenchmark.run(program,camera,
                        new Matrix4f(projection.matrix()).mul(new Matrix4f().lookAlong(0,-1,0,0,0,-1)),target);
                target.destroyBuffers();glBindFramebuffer(GL_FRAMEBUFFER,0);
                var groups = new PredictionOpaqueBatches();
                var expanded = new ArrayList<PredictionRenderer.Draw>();
                for (int i = 0; i < 8; i++) expanded.addAll(entries);
                int groupCount = groups.prepare(expanded);
                assertTrue(groupCount > 1);
                var oldBucket = groups.bucket(groupCount - 1);
                var oldPages = List.copyOf(oldBucket.pages);
                assertFalse(oldPages.isEmpty());
                groups.prepare(entries.subList(0, 1));
                assertTrue(oldBucket.pages.isEmpty());
                assertTrue(oldBucket.fallback.isEmpty());
                for (var oldPage : oldPages) assertTrue(oldPage.draws.isEmpty(),
                        "shrinking visibility must release obsolete tile references");
                groups.clear();
                assertTrue(groups.bucket(0).pages.isEmpty());
                assertTrue(groups.bucket(0).fallback.isEmpty());
                assertEquals(0, groups.prepare(List.of()));
                benchmark(program,legacy,batch,entries,camera,VssLodFaceGroup.ALL);
                benchmark(program,legacy,batch,entries,camera,5);
                ArchitectureBatchBenchmark.run(program, entries, camera);
                ArchitectureOverflowBenchmark.run(program, entries, camera);
                glEnable(GL_RASTERIZER_DISCARD);
                try {
                    program.use();
                    batch.begin(program);
                    for (var draw : entries) batch.add(draw, draw.gpu().packed().drawRanges(false, 5),
                            camera, false, true, Long.MAX_VALUE);
                    batch.end();
                    assertTrue(batch.reusedBatches() > 0, "unchanged camera should reuse command buffers");
                    batch.begin(program);
                    Vec3 moved = camera.add(1, 0, 0);
                    for (var draw : entries) batch.add(draw, draw.gpu().packed().drawRanges(false, 5),
                            moved, false, true, Long.MAX_VALUE);
                    batch.end();
                    assertEquals(0, batch.uploadedBatches(),
                            "camera movement must reuse stable command records");
                    assertTrue(batch.reusedBatches() > 0,
                            "camera movement must reuse command buffers");
                } finally {
                    glDisable(GL_RASTERIZER_DISCARD);
                }
                glDeleteBuffers(ebo);glDeleteVertexArrays(vao);
            }
        } finally {
            if(oldIndirect==null)System.clearProperty("vss.disableIndirect");else System.setProperty("vss.disableIndirect",oldIndirect);
            for(var gpu:gpuTiles)gpu.close();glFinish();PredictionTerrainArena.SHARED.reap();PredictionTerrainArena.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static void verifyPlanTransitions(PredictionTerrainProgram program,
            List<PredictionRenderer.Draw> entries, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target) throws Exception {
        var arena = entries.stream().filter(d -> d.gpu().arenaSlice() != null
                && d.gpu().arenaSlice(true) != null).limit(8).toList();
        assertEquals(8, arena.size());
        var reordered = new ArrayList<>(arena);
        Collections.swap(reordered, 2, 3);
        assertAll("production cached submission transitions",
            () -> {
                try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
                    transitionFrame(program, cached, arena, camera, target, false, true, 60, true, true);
                    long hits = diagnosticCounter(cached, "planIdentityHits");
                    var fast = transitionFrame(program, cached, arena, camera, target, false, true, 60, true, true);
                    assertEquals(hits, diagnosticCounter(cached, "planIdentityHits"), "culled whole replay must bypass individual draws");
                    assertTrue(diagnosticCounter(cached, "wholePlanHits") > 0);
                    assertArrayEquals(transitionFrame(program, cold, arena, camera, target, false, true, 60, false, true), fast,
                            "current-frame depth must be rebuilt at the same near-to-far bucket boundaries");
                }
            },
            () -> {
                try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
                    for (boolean water : new boolean[]{false, true}) {
                        transitionFrame(program, cached, arena, camera, target, water, true, 61, true);
                        long hits = diagnosticCounter(cached, "planIdentityHits");
                        var fast = transitionFrame(program, cached, arena, camera, target, water, true, 61, true);
                        assertEquals(hits, diagnosticCounter(cached, "planIdentityHits"), "whole pass must bypass individual draw checks");
                        assertArrayEquals(transitionFrame(program, cold, arena, camera, target, water, true, 61), fast);
                        assertEquals(0, cached.uploadedBatches());
                        long whole = diagnosticCounter(cached, "wholePlanHits");
                        transitionFrame(program, cached, arena, camera, target, water, true, 62, true);
                        assertEquals(whole, diagnosticCounter(cached, "wholePlanHits"), "residency changes must require item validation");
                        arena.get(0).gpu().packed().morph(null, 0, 0);
                        transitionFrame(program, cached, arena, camera, target, water, true, 62, true);
                        assertEquals(whole, diagnosticCounter(cached, "wholePlanHits"), "mutable metadata must invalidate whole replay");
                    }
                }
            },
            () -> {
                try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
                    for (boolean water : new boolean[]{false, true}) {
                        transitionFrame(program, cached, arena, camera, target, water, true, 17);
                        long checks = diagnosticCounter(cached, "planContentChecks");
                        long hits = diagnosticCounter(cached, "planIdentityHits");
                        var replay = transitionFrame(program, cached, arena, camera, target, water, true, 17);
                        assertArrayEquals(transitionFrame(program, cold, arena, camera, target, water, true, 17), replay);
                        assertEquals(checks, diagnosticCounter(cached, "planContentChecks"),
                                "warm replay must bypass signatures and content comparisons");
                        assertTrue(diagnosticCounter(cached, "planIdentityHits") >= hits + arena.size());
                        hits = diagnosticCounter(cached, "planIdentityHits");
                        var unrelatedUpdate = transitionFrame(program, cached, arena, camera, target, water, true, 18);
                        assertArrayEquals(replay, unrelatedUpdate);
                        assertTrue(diagnosticCounter(cached, "planIdentityHits") >= hits + arena.size(),
                                "unrelated residency updates must verify and reuse each existing item");
                        assertEquals(0, cached.uploadedBatches());
                    }
                    var equivalents = arena.stream().map(draw -> new PredictionRenderer.Draw(draw.tile(), draw.gpu(),
                            draw.allowed(), draw.features(), draw.seam(), draw.faces(), draw.morph(), draw.bounds())).toList();
                    var equivalentFrame = transitionFrame(program, cached, equivalents, camera, target, false, true, 17);
                    assertArrayEquals(transitionFrame(program, cold, equivalents, camera, target, false, true, 17), equivalentFrame);
                    long equivalentChecks = diagnosticCounter(cached, "planContentChecks");
                    transitionFrame(program, cached, equivalents, camera, target, false, true, 17);
                    assertEquals(equivalentChecks, diagnosticCounter(cached, "planContentChecks"),
                            "an equivalent replacement draw must adopt its identity after one verification");
                    var sameMesh = equivalents.get(0).gpu().packed();
                    sameMesh.morph(null, sameMesh.morphMinY(), sameMesh.morphMaxY());
                    transitionFrame(program, cached, equivalents, camera, target, false, true, 17);
                    long sameMetadataChecks = diagnosticCounter(cached, "planContentChecks");
                    transitionFrame(program, cached, equivalents, camera, target, false, true, 17);
                    assertEquals(sameMetadataChecks, diagnosticCounter(cached, "planContentChecks"),
                            "an unchanged metadata update must adopt its revision after one verification");
                    // A mutable metadata update behind the same Draw must still
                    // invalidate the fast path and match a freshly encoded batch.
                    var mesh = arena.get(0).gpu().packed();
                    long checks = diagnosticCounter(cached, "planContentChecks");
                    mesh.morph(null, -64, 384);
                    var actual = transitionFrame(program, cached, arena, camera, target, false, true, 17);
                    var expected = transitionFrame(program, cold, arena, camera, target, false, true, 17);
                    assertArrayEquals(expected, actual, "morph metadata behind an unchanged draw must rebuild");
                    assertTrue(diagnosticCounter(cached, "planContentChecks") > checks);
                    mesh.morph(null, 0, 0);
                    assertArrayEquals(transitionFrame(program, cold, arena, camera, target, false, true, 17),
                            transitionFrame(program, cached, arena, camera, target, false, true, 17));
                }
            },
            () -> {
                for (boolean water : new boolean[]{false, true}) {
                    for (var changed : List.of(reordered, arena.subList(0, 5), arena.subList(2, 8), arena)) {
                        try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
                            transitionFrame(program, cached, arena, camera, target, water, false, 31);
                            var actual = transitionFrame(program, cached, changed, camera, target, water, false, 31);
                            var expected = transitionFrame(program, cold, changed, camera, target, water, false, 31);
                            assertArrayEquals(expected, actual, "mid-batch mismatch/truncation water=" + water);
                            assertCommandRecords(cached, water);
                            assertArrayEquals(expected, transitionFrame(program, cached, changed, camera,
                                    target, water, false, 31), "rebuilt plan must remain correct on replay");
                            assertArrayEquals(transitionFrame(program, cold, arena, camera, target, water, false, 31),
                                    transitionFrame(program, cached, arena, camera, target, water, false, 31),
                                    "restored membership must render every original tile");
                            assertCommandRecords(cached, water);
                        }
                    }
                }
            },
            () -> {
                try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
                    transitionFrame(program, cached, arena, camera, target, false, false, 41);
                    transitionFrame(program, cached, arena, camera, target, true, true, 41);
                    var expected = transitionFrame(program, cold, arena, camera, target, true, true, 41);
                    for (int frame = 0; frame < 120; frame++) {
                        // New uploads invalidate opaque while water membership
                        // remains stable. The production overload must reset its
                        // own replay state instead of inheriting the opaque pass.
                        transitionFrame(program, cached, arena, camera, target, false, true, 51 + frame);
                        var actual = transitionFrame(program, cached, arena, camera, target, true, true, 41);
                        assertArrayEquals(expected, actual, "opaque/water alternation frame " + frame);
                    }
                    var field = PredictionIndirectBatch.class.getDeclaredField("waterPlan"); field.setAccessible(true);
                    Object plan = field.get(cached);
                    var batches = plan.getClass().getDeclaredField("batches"); batches.setAccessible(true);
                    var slots = PredictionIndirectBatch.class.getDeclaredField("waterSlots"); slots.setAccessible(true);
                    assertEquals(((List<?>) slots.get(cached)).size(), ((List<?>) batches.get(plan)).size(),
                            "water plans must not append stale batches each frame");
                    assertEquals(0, cached.uploadedBatches(), "production water overload replays an unchanged plan");
                }
            });
    }
    /** Opaque page grouping draws standalone tiles without calling add(). The
     * cached MDI prefix must never stand in for that complete mixed pass. */
    private static void verifyGroupedFallbackReplay(PredictionTerrainProgram program,
            List<PredictionRenderer.Draw> entries, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target) throws Exception {
        verifyRecentRegions(program, entries, camera, target);
        assertTrue(entries.stream().anyMatch(d -> d.gpu().arenaSlice() == null));
        assertTrue(entries.stream().anyMatch(d -> d.gpu().arenaSlice() != null));
        for (boolean culled : new boolean[]{false, true}) {
            try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
                byte[] expected = groupedFallbackFrame(program, cold, entries, camera, target, false, culled);
                assertArrayEquals(expected, groupedFallbackFrame(program, cached, entries, camera, target, true, culled));
                long whole = diagnosticCounter(cached, "wholePlanHits");
                long identity = diagnosticCounter(cached, "planIdentityHits");
                for (int frame = 0; frame < 12; frame++) {
                    assertArrayEquals(expected, groupedFallbackFrame(program, cached, entries, camera, target, true, culled),
                            "grouped standalone geometry must survive every cached frame; HiZ=" + culled + " frame=" + frame);
                    assertEquals(whole, diagnosticCounter(cached, "wholePlanHits"),
                            "an MDI-only plan is incomplete when the pass also contains direct draws");
                    assertEquals(0, cached.uploadedBatches(), "mixed passes still reuse static page command buffers");
                }
                assertTrue(diagnosticCounter(cached, "planIdentityHits") > identity,
                        "standalone draws must retain per-item replay of unchanged MDI batches");
            }
        }
        System.out.println("PASS: mixed page/standalone opaque passes preserve color and depth for 12 cached frames, with and without current-frame HiZ");
    }

    private static void verifyRecentRegions(PredictionTerrainProgram program,
            List<PredictionRenderer.Draw> entries, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target) throws Exception {
        var all = entries.stream().filter(d -> d.gpu().arenaSlice() != null).limit(8).toList();
        var partial = all.subList(1, all.size());
        try (var cached = new PredictionIndirectBatch(); var cold = new PredictionIndirectBatch()) {
            byte[] expected = regionFrame(program, cold, all, camera, target);
            assertArrayEquals(expected, regionFrame(program, cached, all, camera, target));
            long encoded = diagnosticCounter(cached, "encodedItems");
            regionFrame(program, cached, partial, camera, target);
            assertEquals(encoded, diagnosticCounter(cached, "encodedItems"), "leaving a region must not rebuild its neighbours");
            assertArrayEquals(expected, regionFrame(program, cached, all, camera, target));
            assertEquals(encoded, diagnosticCounter(cached, "encodedItems"), "turning back reuses retained records");
            assertEquals(0, cached.uploadedBatches());

            var changed = new ArrayList<>(all); var old = all.get(2);
            changed.set(2, new PredictionRenderer.Draw(old.tile(), old.gpu(), old.allowed(), old.features(), old.seam(),
                    old.faces(), old.morph() == 0 ? 1 : 0, old.bounds()));
            assertArrayEquals(regionFrame(program, cold, changed, camera, target),
                    regionFrame(program, cached, changed, camera, target));
            assertEquals(encoded + 1, diagnosticCounter(cached, "encodedItems"), "only the changed region is encoded");
            regionFrame(program, cached, all, camera, target);
            regionFrame(program, cached, partial, camera, target);
            var planField = PredictionIndirectBatch.class.getDeclaredField("opaquePlan"); planField.setAccessible(true);
            Object plan = planField.get(cached);
            var regionsField = plan.getClass().getDeclaredField("regions"); regionsField.setAccessible(true);
            var frameField = plan.getClass().getDeclaredField("frame"); frameField.setAccessible(true);
            var regions = (Map<?, ?>) regionsField.get(plan); Object unused = null;
            for (var entry : regions.values()) {
                var usedFrame = entry.getClass().getDeclaredField("usedFrame"); usedFrame.setAccessible(true);
                if (usedFrame.getLong(entry) != frameField.getLong(plan)) { unused = entry; break; }
            }
            assertNotNull(unused, "a turn retains the offscreen region within budget");
            var usedAt = unused.getClass().getDeclaredField("usedAt"); usedAt.setAccessible(true);
            usedAt.setLong(unused, System.nanoTime() - 3_000_000_000L);
            regionFrame(program, cached, partial, camera, target);
            assertFalse(regions.containsValue(unused), "expired offscreen regions release their records and mesh references");
            encoded = diagnosticCounter(cached, "encodedItems");
            assertArrayEquals(expected, regionFrame(program, cached, all, camera, target));
            assertEquals(encoded + 1, diagnosticCounter(cached, "encodedItems"), "only the expired region needs re-encoding");
        }
        System.out.println("PASS: fixed regions retain records during turns, rebuild only changed/expired records, and preserve color+depth");
    }

    private static byte[] regionFrame(PredictionTerrainProgram program, PredictionIndirectBatch batch,
            List<PredictionRenderer.Draw> draws, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target) {
        target.bindWrite(true); glViewport(0, 0, 128, 128);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GREATER); glDepthMask(true); glDisable(GL_BLEND);
        glClearDepth(0); glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        program.use(); program.batch(false); program.setOpaqueAlpha(1);
        batch.begin(program, true, 73, 19);
        for (var draw : draws) {
            batch.nextRegion(draw.tile().key().tileX() * 31 + draw.tile().key().tileZ());
            assertTrue(batch.add(draw, draw.gpu().packed().drawRanges(false, draw.faces()), camera, false, true, Long.MAX_VALUE));
        }
        batch.end();
        var pixels = BufferUtils.createByteBuffer(128 * 128 * 4); var depth = BufferUtils.createFloatBuffer(128 * 128);
        glReadPixels(0, 0, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glReadPixels(0, 0, 128, 128, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        var result = ByteBuffer.allocate(pixels.remaining() + depth.remaining() * 4);
        result.put(pixels); while (depth.hasRemaining()) result.putFloat(depth.get());
        assertEquals(GL_NO_ERROR, glGetError()); return result.array();
    }

    static byte[] groupedFallbackFrame(PredictionTerrainProgram program, PredictionIndirectBatch batch,
            List<PredictionRenderer.Draw> draws, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target, boolean whole, boolean culled) {
        target.bindWrite(true); glViewport(0, 0, 128, 128);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GREATER); glDepthMask(true); glDisable(GL_BLEND);
        glClearDepth(0); glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        program.use(); program.batch(false); program.setOpaqueAlpha(1);
        var projection = VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(80), 1, .05f, 65536));
        var view = new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1);
        program.setCamera(view, projection.matrix());
        if (culled) batch.begin(program, target.getDepthTextureId(), 128, 128,
                new Matrix4f(projection.matrix()).mul(view), null, 73, 19);
        else batch.begin(program, true, 73, 19);
        long replayed = whole ? batch.replayPass(draws, 1, true, camera, Long.MAX_VALUE) : -1;
        if (replayed < 0) {
            var groups = new PredictionOpaqueBatches();
            int count = groups.prepare(draws);
            for (int b = 0; b < count; b++) {
                batch.nextOcclusionBucket();
                var bucket = groups.bucket(b);
                for (var page : bucket.pages) {
                batch.nextRegion(page.region);
                for (var draw : page.draws) {
                    var ranges = draw.gpu().packed().drawRanges(false, VssLodFaceGroup.ALL);
                    assertTrue(batch.add(draw, ranges, camera, false, true, Long.MAX_VALUE));
                }
                }
                if (!bucket.fallback.isEmpty()) batch.flush();
                for (var draw : bucket.fallback) {
                    var packed = draw.gpu().packed();
                    var ranges = packed.drawRanges(false, VssLodFaceGroup.ALL);
                    // Match drawNormalPass's grouped fallback, which bypasses add().
                    batch.fallback(draw, ranges, camera);
                    program.batch(false); draw.gpu().bindTerrain(program); draw.gpu().bindYield(3);
                    program.setTile((float)(draw.tile().baseBlockX() - camera.x), (float)-camera.y,
                            (float)(draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(), packed.cellAxis(), true);
                    program.setMorph(packed, 0); program.setBoundaryReplacement(!draw.seam());
                    PredictionRenderer.submitRanges(ranges, packed, false);
                }
            }
        }
        batch.end();
        long expectedQuads=draws.stream().mapToLong(d -> d.gpu().packed().drawRanges(false, d.faces()).quads).sum();
        assertEquals(expectedQuads, diagnosticCounter(batch, "mdiCandidateQuads") + diagnosticCounter(batch, "directCandidateQuads"),
                "complete mixed submission includes direct geometry, including replayed frames");
        var color = BufferUtils.createByteBuffer(128 * 128 * 4);
        var depth = BufferUtils.createFloatBuffer(128 * 128);
        glReadPixels(0, 0, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, color);
        glReadPixels(0, 0, 128, 128, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        ByteBuffer result = ByteBuffer.allocate(color.remaining() + depth.remaining() * 4);
        result.put(color); while (depth.hasRemaining()) result.putFloat(depth.get());
        assertEquals(GL_NO_ERROR, glGetError());
        return result.array();
    }

    private static long diagnosticCounter(PredictionIndirectBatch batch, String name) {
        var match = java.util.regex.Pattern.compile("(?:^|,)" + name + "=(\\d+)").matcher(batch.diagnostics());
        assertTrue(match.find(), batch.diagnostics()); return Long.parseLong(match.group(1));
    }
    private static byte[] transitionFrame(PredictionTerrainProgram program, PredictionIndirectBatch batch,
            List<PredictionRenderer.Draw> draws, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target, boolean water, boolean productionOverload, long revision) {
        return transitionFrame(program, batch, draws, camera, target, water, productionOverload, revision, false);
    }
    private static byte[] transitionFrame(PredictionTerrainProgram program, PredictionIndirectBatch batch,
            List<PredictionRenderer.Draw> draws, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target, boolean water, boolean productionOverload, long revision, boolean whole) {
        return transitionFrame(program, batch, draws, camera, target, water, productionOverload, revision, whole, false);
    }
    private static byte[] transitionFrame(PredictionTerrainProgram program, PredictionIndirectBatch batch,
            List<PredictionRenderer.Draw> draws, Vec3 camera,
            com.mojang.blaze3d.pipeline.TextureTarget target, boolean water, boolean productionOverload, long revision,
            boolean whole, boolean culled) {
        target.bindWrite(true); glViewport(0, 0, 128, 128);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GREATER); glDepthMask(true); glDisable(GL_BLEND);
        glClearDepth(0); glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        program.use(); program.setOpaqueAlpha(water ? 0 : 1);
        if (culled) {
            var projection = VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(80), 1, .05f, 65536));
            batch.begin(program, target.getDepthTextureId(), 128, 128,
                    new Matrix4f(projection.matrix()).mul(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1)), null, revision, 19);
        } else if (productionOverload) batch.begin(program, !water, revision, 19);
        else batch.begin(program, !water);
        long replayed = whole ? batch.replayPass(draws, 1, true, camera, Long.MAX_VALUE) : -1;
        if (replayed < 0) for (int i = 0; i < draws.size(); i++) {
            if (culled && i % 2 == 0) batch.nextOcclusionBucket();
            var draw = draws.get(i);
            assertTrue(batch.add(draw, draw.gpu().packed().drawRanges(water, VssLodFaceGroup.ALL), camera, water, true, Long.MAX_VALUE));
        }
        batch.end();
        var pixels = BufferUtils.createByteBuffer(128 * 128 * 4);
        glReadPixels(0, 0, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        byte[] result = new byte[pixels.remaining()]; pixels.get(result);
        assertEquals(GL_NO_ERROR, glGetError());
        return result;
    }
    private static PredictionTerrainProgram referenceProgram() {
        String old = System.getProperty("vss.disableFixedOwnershipReuse");
        System.setProperty("vss.disableFixedOwnershipReuse", "true");
        try { return PredictionTerrainProgram.create(); }
        finally { if (old == null) System.clearProperty("vss.disableFixedOwnershipReuse"); else System.setProperty("vss.disableFixedOwnershipReuse", old); }
    }
    private static void assertCommandRecords(PredictionIndirectBatch batch, boolean water) throws Exception {
        var field = PredictionIndirectBatch.class.getDeclaredField(water ? "waterSlots" : "opaqueSlots"); field.setAccessible(true);
        int saved = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        try {
            for (Object slot : (List<?>) field.get(batch)) {
                var type = slot.getClass(); var buffer = type.getDeclaredField("indirect"); buffer.setAccessible(true);
                var length = type.getDeclaredField("drawLength"); length.setAccessible(true);
                var metadata = type.getDeclaredField("recordLength"); metadata.setAccessible(true);
                int records = metadata.getInt(slot) / 64;
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, buffer.getInt(slot));
                var commands = BufferUtils.createIntBuffer(length.getInt(slot) / 4);
                glGetBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, commands);
                int last = -1;
                for (int i = 4; i < commands.limit(); i += 5) {
                    int record = commands.get(i);
                    assertTrue(record >= 0 && record < records, "GPU command must reference an existing record");
                    assertTrue(record == last || record == last + 1,
                            "GPU command record sequence must start at zero and advance in draw order: " + last + " -> " + record);
                    last = record;
                }
                assertEquals(records - 1, last);
            }
        } finally { glBindBuffer(GL_DRAW_INDIRECT_BUFFER, saved); }
    }
    private static void verifyOwnershipPrimitives(PredictionTerrainProgram program, PredictionTerrainProgram legacy,
            PredictionIndirectBatch batch, List<PredictionRenderer.Draw> entries, Vec3 camera) throws Exception {
        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        var passClass = Class.forName(PredictionRenderer.class.getName() + "$PreparedPass");
        var constructor = passClass.getDeclaredConstructor(boolean.class); constructor.setAccessible(true);
        var prepare = passClass.getDeclaredMethod("prepare", List.class, long.class, Vec3.class); prepare.setAccessible(true);
        var rangesByDraw = passClass.getDeclaredMethod("rangesByDraw"); rangesByDraw.setAccessible(true);
        var index = new PredictionExactCoverageIndex();
        for (int z = -8; z <= 140; z++) for (int x = -8; x <= 24; x++)
            index.confirm(net.minecraft.world.level.Level.OVERWORLD, x, z, 0);
        var all = index.snapshot(net.minecraft.world.level.Level.OVERWORLD, 0, 64, 160, ExactCoverageGate.SETTLE_NANOS);
        for (int z = -8; z <= 140; z++) index.remove(net.minecraft.world.level.Level.OVERWORLD, 4, z);
        var mixed = index.snapshot(net.minecraft.world.level.Level.OVERWORLD, 0, 64, 160, ExactCoverageGate.SETTLE_NANOS);
        int query = glGenQueries(); glEnable(GL_RASTERIZER_DISCARD);
        try {
            for (boolean water : new boolean[]{false, true}) for (boolean mdi : new boolean[]{false, true}) {
                long baseline = -1;
                var pass = constructor.newInstance(water);
                var groups = new PredictionOpaqueBatches();
                for (var coverage : new PredictionExactCoverageMask.Snapshot[]{null, all, mixed, null}) {
                    state.update(coverage, camera, 8192);
                    prepare.invoke(pass, entries, 1L, camera);
                    @SuppressWarnings("unchecked")
                    var prepared = (IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges>) rangesByDraw.invoke(pass);
                    prepare.invoke(pass, entries, 1L, camera);
                    assertSame(prepared, rangesByDraw.invoke(pass), "unchanged renderer pass reuses its submission plan");
                    int buckets = groups.prepare(entries, 1L, prepared);
                    var grouped = new HashSet<PredictionRenderer.Draw>();
                    for (int b = 0; b < buckets; b++) {
                        grouped.addAll(groups.bucket(b).fallback);
                        for (var page : groups.bucket(b).pages) grouped.addAll(page.draws);
                    }
                    assertEquals(prepared.keySet(), grouped, "coverage-only changes rebuild page groups and restore draws");
                    var active = mdi ? program : legacy; active.use(); active.batch(false);
                    glBeginQuery(GL_PRIMITIVES_GENERATED, query);
                    if (mdi) batch.begin(program, !water);
                    long quads = 0;
                    for (var draw : entries) {
                        var packed = draw.gpu().packed();
                        var ranges = prepared.get(draw);
                        if (ranges == null) continue;
                        quads += ranges.quads;
                        if (mdi && batch.add(draw, ranges, camera, water, true, Long.MAX_VALUE)) continue;
                        if (mdi) batch.flush();
                        active.batch(false); draw.gpu().bindTerrain(active, water); draw.gpu().bindYield(3);
                        active.setTile((float)(draw.tile().baseBlockX()-camera.x), (float)-camera.y,
                                (float)(draw.tile().baseBlockZ()-camera.z), 1, packed.cellAxis(), true);
                        active.setBoundaryReplacement(!water); PredictionRenderer.submitRanges(ranges, packed, water);
                    }
                    if (mdi) batch.end();
                    glEndQuery(GL_PRIMITIVES_GENERATED);
                    long primitives = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                    assertEquals(quads * 2, primitives, "actual GPU triangle count, not pixel visibility");
                    if (coverage == all) assertEquals(baseline, primitives, "resident interior keeps every fallback primitive for depth testing");
                    else if (coverage == mixed) assertEquals(baseline, primitives, "resident boundary cannot change static submission");
                    else if (baseline < 0) { baseline = primitives; assertTrue(baseline > 0); }
                    else assertEquals(baseline, primitives, "coverage revocation restores every primitive");
                    System.out.println("OWNERSHIP_PRIMITIVES water="+water+" mdi="+mdi+" coverage="
                            +(coverage==null?"missing":coverage==all?"interior":"mixed")+" triangles="+primitives);
                }
            }
        } finally { state.clear(); glDisable(GL_RASTERIZER_DISCARD); glDeleteQueries(query); }
        assertEquals(GL_NO_ERROR, glGetError());
    }
    private static void benchmarkOcclusion(PredictionTerrainProgram program,PredictionIndirectBatch batch,
                                           PredictionRenderer.Draw draw,Vec3 camera,VssLodProjection.MatrixData projection,
                                           com.mojang.blaze3d.pipeline.TextureTarget target) {
        int repeats=4096,query=glGenQueries();long[][] times=new long[2][31];
        var ranges=draw.gpu().packed().drawRanges(false,VssLodFaceGroup.ALL);
        target.bindWrite(true);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glDisable(GL_BLEND);
        try {
            for(int frame=-8;frame<31;frame++)for(int mode:frame%2==0?new int[]{0,1}:new int[]{1,0}) {
                glClearDepth(.5);glClear(GL_DEPTH_BUFFER_BIT);program.use();program.setOpaqueAlpha(1);
                glFinish();glBeginQuery(GL_TIME_ELAPSED,query);
                if(mode==0)batch.begin(program);
                else batch.begin(program,target.getDepthTextureId(),128,128,
                        new Matrix4f(projection.matrix()).mul(new Matrix4f().lookAlong(0,-1,0,0,0,-1)));
                for(int i=0;i<repeats;i++) {
                    if(mode==1&&i%512==0)batch.nextOcclusionBucket();
                    assertTrue(batch.add(draw,ranges,camera,false,true,Long.MAX_VALUE));
                }
                batch.end();glEndQuery(GL_TIME_ELAPSED);
                long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                if(frame>=0)times[mode][frame]=elapsed;
            }
            for(long[] values:times)Arrays.sort(values);
            assertTrue(batch.diagnostics().contains(",hiddenQuads="+((long)repeats*ranges.quads)),
                    "the asynchronous sample must confirm all repeated production geometry was suppressed: "+batch.diagnostics());
            assertEquals((long)repeats*ranges.quads, diagnosticCounter(batch,"sampleCandidateQuads"));
            assertEquals(0, diagnosticCounter(batch,"sampleAfterHizQuads"));
            assertTrue(diagnosticCounter(batch,"hizBuilds") <= 2, "eight bands share at most two current-frame pyramids");
            assertTrue(diagnosticCounter(batch,"filterBarriers") <= 2, "pages share the filtering barriers");
            assertTrue(diagnosticCounter(batch,"sampleSubmissionFrame") <= diagnosticCounter(batch,"submissionFrame"));
            System.out.printf(Locale.ROOT,"PRODUCTION_OCCLUSION hiddenQuads=%d buckets=8 GPU_ms=%.4f->%.4f %s (repeated production fixture, not world FPS)%n",
                    (long)repeats*ranges.quads,times[0][15]/1e6,times[1][15]/1e6,batch.diagnostics());
        } finally {glDeleteQueries(query);}
    }
    private static void benchmark(PredictionTerrainProgram program,PredictionTerrainProgram legacy,PredictionIndirectBatch batch,
                                  List<PredictionRenderer.Draw> entries,Vec3 camera,int faces) {
        int query=glGenQueries();long[][] cpu=new long[2][31],gpu=new long[2][31];
        try {
            glEnable(GL_RASTERIZER_DISCARD);program.setOpaqueAlpha(1);
            for(int round=-30;round<31;round++)for(int order=0;order<2;order++) {
                int mode=Math.floorMod(round+order,2);glFinish();glBeginQuery(GL_TIME_ELAPSED,query);
                long start=System.nanoTime();
                var active=mode==0?legacy:program;active.use();active.setOpaqueAlpha(1);
                if(mode!=0)batch.begin(program);else legacy.batch(false);
                for(var draw:entries) {
                    var packed=draw.gpu().packed();var ranges=packed.drawRanges(false,faces);
                    if(mode!=0){batch.add(draw,ranges,camera,false,true,Long.MAX_VALUE);continue;}
                    draw.gpu().bindTerrain(legacy);draw.gpu().bindYield(3);
                    legacy.setTile((float)(draw.tile().baseBlockX()-camera.x),(float)-camera.y,
                            (float)(draw.tile().baseBlockZ()-camera.z),1,packed.cellAxis(),true);
                    legacy.setBoundaryReplacement(true);PredictionRenderer.submitRanges(ranges);
                }
                if(mode!=0) {
                    batch.end();
                    if (mode==1 && round > 2) assertTrue(batch.reusedBatches() > 0,
                            "unchanged batch commands should remain resident");
                }
                long elapsed=System.nanoTime()-start;glEndQuery(GL_TIME_ELAPSED);
                long device=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                if(round>=0){cpu[mode][round]=elapsed;gpu[mode][round]=device;}
            }
            for(var values:cpu)Arrays.sort(values);for(var values:gpu)Arrays.sort(values);
            System.out.printf(Locale.ROOT,"PRODUCTION_BATCH faces=%d tiles=%d quads=%d cpuMs=legacy %.6f cached %.6f gpuMs=legacy %.6f cached %.6f%n",
                    faces,entries.size(),entries.stream().mapToInt(d->d.gpu().quadCount()).sum(),
                    cpu[0][15]/1e6,cpu[1][15]/1e6,
                    gpu[0][15]/1e6,gpu[1][15]/1e6);
        }finally{glDisable(GL_RASTERIZER_DISCARD);glDeleteQueries(query);}
    }
    private static int tex(List<Integer> textures,int unit,int w,int h,int format,int channels,float[] data){
        glActiveTexture(GL_TEXTURE0+unit);int texture=glGenTextures();textures.add(texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,channels,GL_FLOAT,data);return texture;
    }
}
