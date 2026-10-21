package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionGpuSceneGpuTest {
    private static final int SIZE = 512;
    private final List<PredictionGpuTile> allocated = new ArrayList<>();
    private final List<Integer> textures = new ArrayList<>();
    private int vao, ebo, mainDepth, exact;
    private TextureTarget target;

    @Test void fixedRecordsSurviveChurnAndMatchOrderedProductionSubmission() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(SIZE, SIZE, "persistent scene validation", 0, 0);
        assertNotEquals(0, window);
        var properties = new HashMap<String,String>();
        for (String name : List.of("vss.forcePredictionOcclusion", "vss.disablePredictionGpuScene",
                "vss.disablePredictionOcclusion", "vss.disableGpuPredictionSelection")) {
            properties.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
        System.setProperty("vss.forcePredictionOcclusion", "true");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            assertTrue(PredictionGpuCommandSelector.supported());
            System.out.println("PERSISTENT_SCENE_DEVICE " + glGetString(GL_RENDERER));
            setup();
            try (var program = PredictionTerrainProgram.createBatch()) {
                configure(program);
                verifySlots();
                verifyCapacityAdmission();
                verifyProgressivePreparation();
                verifyHierarchyAndRepresentations();
                verifyGpuDisplayChoice(program);
                verifyPixels(program);
                benchmark(program);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            for (var property : properties.entrySet()) {
                if (property.getValue() == null) System.clearProperty(property.getKey());
                else System.setProperty(property.getKey(), property.getValue());
            }
            allocated.forEach(PredictionGpuTile::close);
            glFinish(); PredictionTerrainArena.SHARED.reap(); PredictionTerrainArena.SHARED.close();
            PredictionTerrainArena.WATER.reap(); PredictionTerrainArena.WATER.close();
            PredictionQuadBufferPool.SHARED.close();
            if (target != null) target.destroyBuffers();
            glDeleteBuffers(ebo); glDeleteVertexArrays(vao);
            textures.forEach(t -> glDeleteTextures(t));
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static boolean ready(PredictionGpuScene scene, List<PredictionRenderer.Draw> draws,
                                 IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges> selected,
                                 boolean average) {
        for (int frame=0;frame<16384;frame++) {
            boolean ready=scene.update(draws,selected,average);
            assertTrue(scene.lastWorkItems()<=PredictionGpuScene.MAX_UPDATE_ITEMS);
            if (ready) return true;
        }
        return false;
    }

    private void verifyProgressivePreparation() throws Exception {
        var draws=new ArrayList<PredictionRenderer.Draw>();
        // Similar tile count to the live capture, with especially sparse regions.
        for(int i=0;i<1536;i++) draws.add(tile(i*32,32,i));
        var input=List.copyOf(draws);var selected=ranges(input);
        var times=new ArrayList<Long>();
        int frames=0;boolean changed=false;
        try(var scene=new PredictionGpuScene()) {
            assertFalse(scene.update(input,selected,true),"a cold forest must yield before full scene creation");
            assertEquals(0,scene.nodeUploads(),"partial scenes cannot publish an armed graph");
            boolean complete=false;
            for(;frames<16384;frames++) {
                long before=scene.encodedItems();long start=System.nanoTime();
                complete=scene.update(input,selected,true);
                times.add(System.nanoTime()-start);
                assertTrue(scene.lastWorkItems()<=PredictionGpuScene.MAX_UPDATE_ITEMS);
                assertTrue(scene.encodedItems()-before<=PredictionGpuScene.MAX_UPDATE_ITEMS,
                        "each production update must cap the number of encoded records");
                if(complete)break;
                assertEquals(0,scene.nodeUploads(),"LOD graph must commit only after every source record is ready");
                if(!changed && scene.retainedRecords()>64) {
                    draws.remove(0);draws.add(tile(32*1600,32,1));
                    input=List.copyOf(draws);selected=ranges(input);
                    selected.put(input.get(0),new PredictionDrawRanges(new int[]{0},new int[]{128}));
                    changed=true;
                } else if(changed) {
                    // Equivalent publications while a transaction is incomplete
                    // must reuse the already prepared records, not restart it.
                    var next=new ArrayList<>(input);Collections.reverse(next);
                    input=List.copyOf(next);selected=new IdentityHashMap<>(selected);
                }
            }
            assertTrue(complete,"a changing input must eventually finish without losing progress");
            assertTrue(changed);assertTrue(frames>1);
            assertEquals(input.size(),scene.retainedRecords());
            assertEquals(1,scene.nodeUploads(),"a complete cold graph uses one GL upload, not one per node");
            for(var draw:input) {
                var entry=scene.entry(draw.gpu());assertNotNull(entry);
                assertEquals(entry.nodeIndex,ints(entry.chunk.slot.bounds,entry.first*32L+28,1)[0]);
                assertEquals(1,ints(scene.nodeBuffer(),entry.nodeIndex*64L+40,1)[0],
                        "every live command must reference the current committed node");
                assertEquals(Float.floatToIntBits((float)draw.bounds().minX),
                        ints(scene.nodeBuffer(),entry.nodeIndex*64L,1)[0],
                        "reused node slots must describe the command's current spatial region");
            }
            var stable=scene.entry(input.get(0).gpu());
            long encoded=scene.encodedItems(),bytes=scene.uploadedBytes(),nodes=scene.nodeUploads();
            byte[] command=bytes(stable.chunk.slot.indirect,stable.first*20L,20);
            var touch=VssLodSpriteTable.class.getDeclaredMethod("touchMaterialRevision");touch.setAccessible(true);
            for(int i=0;i<40;i++) {
                touch.invoke(null);
                assertTrue(scene.update(input,selected,true));
                assertSame(stable,scene.entry(input.get(0).gpu()));
                assertEquals(encoded,scene.encodedItems(),"unrelated material discovery must not rebuild a scene");
                assertEquals(bytes,scene.uploadedBytes());assertEquals(nodes,scene.nodeUploads());
            }
            assertArrayEquals(command,bytes(stable.chunk.slot.indirect,stable.first*20L,20));
            scene.clear();
            assertFalse(scene.update(input,selected,true),"real resource resets must prepare safely again");
            assertEquals(0,scene.nodeBuffer());
        }
        times.sort(Long::compare);
        System.out.printf(Locale.ROOT,"PROGRESSIVE_SCENE tiles=%d updates=%d median_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
                input.size(),frames+1,times.get(times.size()/2)/1e6,
                times.get((times.size()-1)*95/100)/1e6,times.get(times.size()-1)/1e6);
        System.out.println("PASS: cold preparation yields, equivalent inputs preserve progress, material discovery preserves all records, graph commits once");
    }

    private void verifyHierarchyAndRepresentations() {
        var children = new ArrayList<PredictionRenderer.Draw>();
        for (int i=0;i<4;i++) children.add(familyTile(new PredictionTileManager.PredictionTileKey(
                Level.OVERWORLD,i&1,i>>1,0),false));
        var parent=familyTile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1),true);
        var tiles=new HashMap<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>();
        for(var child:children)tiles.put(child.tile().key(),child.tile());
        var input=PredictionParentLod.capture(parent.tile().key(),64,PredictionParentLodTest.LAYOUT,tiles,Set.of(),Map.of());
        assertNotNull(input);
        parent.tile().mesh().parentLod=PredictionParentLod.reduce(input).proof();
        assertTrue(parent.tile().mesh().parentLod.sealedEdges());
        var draws=new ArrayList<>(children);draws.add(parent);
        try(var scene=new PredictionGpuScene();var selector=new PredictionGpuCommandSelector()) {
            var selected=ranges(draws);
            assertTrue(ready(scene, draws,selected,true));
            var before=scene.entry(children.get(0).gpu());
            for (int test=0;test<7;test++) {
                var camera=test==1?new Vec3(64,110,200):new Vec3(64,110,4096);
                var view=new Matrix4f().lookAt(0,0,0,0,-30,(float)-camera.z,0,1,0);
                var projection=new Matrix4f().perspective((float)Math.toRadians(test==2?1:70),1,.05f,8192);
                if(test==3){selected=new IdentityHashMap<>(selected);selected.remove(children.get(0));}
                if(test==4)selected=ranges(draws);
                if(test==5){selected=new IdentityHashMap<>(selected);
                    selected.put(children.get(0),new PredictionDrawRanges(new int[]{0},new int[]{512}));}
                if(test==6){selected=ranges(draws);children.get(0).gpu().close();}
                assertTrue(ready(scene, draws,selected,true));
                int sentinel=glCreateBuffers();glNamedBufferData(sentinel,512,GL_DYNAMIC_DRAW);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER,4,sentinel,0,256);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER,5,sentinel,256,256);
                selector.begin(new Matrix4f(projection).mul(view),(float)-camera.x,(float)-camera.y,(float)-camera.z,
                        SIZE,SIZE,0,0);
                try {selector.lod(scene.nodeBuffer(),scene.choiceBuffer(),scene.nodeCount(),1.5f,256,8192,true);}
                finally {selector.end();}
                assertEquals(sentinel,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,4));
                assertEquals(sentinel,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,5));
                assertEquals(256,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,5));
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,0);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,0);glDeleteBuffers(sentinel);
                int[] choices=new int[scene.nodeCount()];glGetNamedBufferSubData(scene.choiceBuffer(),0,choices);
                boolean coarse=test==0||test==4;
                for(var child:children) {
                    var entry=scene.entry(child.gpu());
                    if(entry==null)continue;
                    int id=ints(entry.chunk.slot.bounds,entry.first*32L+28,1)[0];
                    assertEquals(coarse?0:1,choices[id]&1,"complete child family or parent must exclusively cover the area, case="+test);
                }
                var entry=scene.entry(parent.gpu());int id=ints(entry.chunk.slot.bounds,entry.first*32L+28,1)[0];
                assertEquals(coarse?1:0,choices[id]&1,"a missing child or magnified view must not arm an unsafe parent");
                assertEquals(0,ints(entry.chunk.slot.safeIndirect(0),entry.first*20L,1)[0],"fallback must suppress coarse proxies");
            }
            assertNotNull(before);
            // Metadata-only revisions keep the same node topology and static slot.
            long encoded=scene.encodedItems();
            children.get(1).gpu().packed().morph(null,60,90);
            assertTrue(ready(scene, draws,selected,true));
            assertEquals(encoded+1,scene.encodedItems());
            assertFalse(scene.update(List.of(parent,parent),ranges(List.of(parent)),true));
            assertEquals(0,scene.nodeCount(),"rejected scene cannot retain a partially armed graph");
            assertFalse(scene.update(List.of(parent,parent),ranges(List.of(parent)),true));
        }
    }

    private void verifyCapacityAdmission() throws Exception {
        var draws = new ArrayList<PredictionRenderer.Draw>();
        for (int i = 0; i < 32; i++) draws.add(tile(i * 32, 0, i));
        var input = List.copyOf(draws);
        var selected = ranges(input);
        try (var scene = new PredictionGpuScene(128 * 1024L)) {
            assertFalse(scene.update(input, selected, true));
            assertEquals(0, scene.encodedItems(), "capacity rejection must precede any scene record encoding");
            assertEquals(0, scene.uploadedBytes(), "capacity rejection must precede all GPU uploads");
            assertEquals(0, scene.nodeBuffer());
            assertEquals(0, scene.choiceBuffer());
            for (int i = 0; i < 40; i++) {
                PredictionSubmissionChanges.GPU.publish(PredictionSubmissionChanges.newId());
                PredictionSubmissionChanges.MESH.publish(PredictionSubmissionChanges.newId());
                assertFalse(scene.update(input, selected, true),
                        "a rejected empty scene cannot turn into a successful scene on unrelated epochs");
            }
            for (int i = 0; i < 8; i++) {
                var changed = new ArrayList<>(input);
                Collections.rotate(changed, i);
                assertFalse(scene.update(List.copyOf(changed), new IdentityHashMap<>(selected), true));
            }
            assertEquals(0, scene.encodedItems()); assertEquals(0, scene.uploadedBytes());
            assertTrue(ready(scene, List.of(draws.get(0)), ranges(List.of(draws.get(0))), true),
                    "shrinking the actual scene must recover normally");
            long encoded = scene.encodedItems(), uploaded = scene.uploadedBytes();
            assertFalse(scene.update(input, selected, true));
            assertEquals(encoded, scene.encodedItems(), "oversized replacement cannot partially rewrite a live scene");
            assertEquals(uploaded, scene.uploadedBytes());
            assertEquals(0, scene.retainedRecords()); assertEquals(0, scene.nodeCount());
        }
        try (var batch = new PredictionIndirectBatch()) {
            var field = PredictionIndirectBatch.class.getDeclaredField("gpuScene");
            field.setAccessible(true); field.set(batch, new PredictionGpuScene(128 * 1024L));
            flag(batch, "gpuSelection", true);
            assertEquals(-1, batch.submitScene(input, selected, true, Vec3.ZERO, Long.MAX_VALUE));
            assertFalse(batch.gpuSelection(),
                    "rejected resident submission must disable GPU selection before ordered fallback without bounds");
        }
        System.out.println("PASS: oversized scenes reject before GL allocation/upload, stay rejected across unrelated epochs and recover after shrinking");
    }

    private PredictionRenderer.Draw familyTile(PredictionTileManager.PredictionTileKey key,boolean proxy) {
        int[] words=PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_MODEL_UV);
        int scale=1<<key.lod();
        if(scale>1)for(int q=0;q<1024;q++)for(int p=0;p<4;p++) {
            int at=q*12+p,v=words[at];words[at]=(v&65535)*scale|((v>>>16)*scale)<<16;
        }
        var packed=PredictionPackedMesh.terrainRecords(words,64);packed.prepareGpuStorage();
        var mesh=PredictionMesh.restored(words.length/12*6,0,packed,null);
        var columns=new ClientColumnSample[65*65];Arrays.fill(columns,PredictionParentLodTest.sample(80));
        var tile=new PredictionTileManager.PredictionTile(key,new int[65*65],new int[65*65],columns,mesh,
                new PredictionDepthBound(80,80),0,allocated.size()+1L,64,scale);
        var gpu=new PredictionGpuTile(key);allocated.add(gpu);assertTrue(gpu.ensureMesh(tile));
        boolean[] allowed=new boolean[4096];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
        return new PredictionRenderer.Draw(tile,gpu,allowed,allowed,false,VssLodFaceGroup.ALL,0,
                new AABB(tile.baseBlockX(),78,tile.baseBlockZ(),tile.baseBlockX()+tile.spanBlocks(),82,
                        tile.baseBlockZ()+tile.spanBlocks()),true,true,proxy);
    }

    private void verifyGpuDisplayChoice(PredictionTerrainProgram program) throws Exception {
        var packed=new PredictionPackedMesh(PredictionDisplayGeometryTest.grid(0),64,1024,
                new int[5],new int[]{1024,0,0,0,0},new int[5],new int[5],false,0);
        packed.prepareGpuStorage();assertTrue(packed.hasTerrainDisplayLod());
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                PredictionMesh.restored(6144,0,packed,null),new PredictionDepthBound(80,80),0,999,64,1);
        var gpu=new PredictionGpuTile(key);allocated.add(gpu);assertTrue(gpu.ensureMesh(tile));
        boolean[] allowed=new boolean[4096];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
        var draw=new PredictionRenderer.Draw(tile,gpu,allowed,allowed,false,VssLodFaceGroup.ALL,0,
                new AABB(0,78,0,64,82,64),true,true,false);
        var draws=List.of(draw);var selected=new IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>();
        selected.put(draw,packed.gpuRepresentations().union);
        try(var scene=new PredictionGpuScene();var selector=new PredictionGpuCommandSelector();var reference=new PredictionIndirectBatch()) {
            assertTrue(ready(scene, draws,selected,true));var entry=scene.entry(gpu);
            long encoded=scene.encodedItems(),uploaded=scene.uploadedBytes();
            for(int test=0;test<3;test++) {
                var camera=test==1?new Vec3(16,16000,16000):new Vec3(16,120,16);
                var view=new Matrix4f().lookAlong(0,-1,test==1?-1:0,0,0,-1);
                var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,65536));
                frame(program,view,projection,0);program.setBatchCameraOffset((float)-camera.x,(float)-camera.y,(float)-camera.z);
                int result=glCreateBuffers(),count=glCreateBuffers();
                glNamedBufferData(result,entry.chunk.commandCount*20L,GL_DYNAMIC_DRAW);glNamedBufferData(count,4,GL_DYNAMIC_DRAW);
                try {
                    selector.begin(new Matrix4f(projection.matrix()).mul(view),(float)-camera.x,(float)-camera.y,(float)-camera.z,
                            SIZE,SIZE,0,0);
                    try {
                        selector.lod(scene.nodeBuffer(),scene.choiceBuffer(),scene.nodeCount(),1.5f,256,65536,true);
                        selector.dispatch(entry.chunk.slot.bounds,entry.chunk.slot.indirect,result,entry.chunk.commandCount,count);
                    } finally {selector.end();}
                    int node=ints(entry.chunk.slot.bounds,entry.first*32L+28,1)[0];
                    int choice=ints(scene.choiceBuffer(),node*4L,1)[0],tier=(choice>>1)&3;
                    assertEquals(1,choice&1);
                    assertEquals(test==1?3:0,tier,"current projection must restore source detail on return");
                    int n=ints(count,0,1)[0];int[] output=ints(result,0,n*5);
                    long quads=0;for(int i=0;i<n;i++)quads+=output[i*5]/6;
                    var expected=packed.drawRanges(false,VssLodFaceGroup.ALL,tier,tier,tier);
                    assertEquals(expected.quads,quads,"GPU membership must select precisely one complete representation");
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,entry.chunk.slot.metadata);
                    PredictionGlState.activeTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,entry.chunk.page().texture);
                    program.use();program.batch(true);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,result);
                    PredictionGpuCommandSelector.draw(count,entry.chunk.commandCount);program.batch(false);
                    byte[] actual=colour();
                    frame(program,view,projection,0);
                    reference.begin(program,false);assertTrue(reference.add(draw,expected,camera,false,true,Long.MAX_VALUE));reference.end();
                    assertArrayEquals(colour(),actual,"GPU selection and ordered production ranges must be pixel exact");
                    for(int at=entry.first;at<entry.first+entry.item.commands;at++) {
                        int[] cmd=ints(entry.chunk.slot.safeIndirect(0),at*20L,5);
                        if(cmd[0]!=0)assertTrue(cmd[2]/6<1024,"a failed selector must only draw exact source commands");
                    }
                } finally {glDeleteBuffers(result);glDeleteBuffers(count);}
                assertTrue(ready(scene, draws,selected,true));
                assertEquals(encoded,scene.encodedItems());assertEquals(uploaded,scene.uploadedBytes());
            }
        }
    }
    private byte[] colour() {
        ByteBuffer pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
        glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        byte[] result=new byte[pixels.remaining()];pixels.get(result);return result;
    }

    private void verifySlots() {
        var draws = new ArrayList<PredictionRenderer.Draw>();
        for (int i=0; i<16; i++) draws.add(tile(i%16, i/16, i));
        try (var scene = new PredictionGpuScene()) {
            var input = List.copyOf(draws); var ranges = ranges(input);
            assertTrue(ready(scene, input, ranges, true));
            var stable = scene.entry(draws.get(1).gpu());
            byte[] metadata = bytes(stable.chunk.slot.metadata, stable.record*64L, 64);
            byte[] command = bytes(stable.chunk.slot.indirect, stable.first*20L, 20);
            assertEquals(stable.record, ints(stable.chunk.slot.indirect, stable.first*20L, 5)[4]);
            long encoded = scene.encodedItems(), uploaded = scene.uploadedBytes();
            for (int turn=0; turn<120; turn++) {
                assertTrue(ready(scene, input, ranges, true));
                scene.ordered(new Vec3(turn/2.0, 110, 0));
            }
            assertEquals(encoded, scene.encodedItems()); assertEquals(uploaded, scene.uploadedBytes());
            PredictionSubmissionChanges.GPU.publish(PredictionSubmissionChanges.newId());
            assertTrue(ready(scene, input, ranges, true));
            assertEquals(encoded, scene.encodedItems(), "unrelated residency must not encode scene records");
            for (int i=16; i<257; i++) draws.add(tile(i%16, i/16, i));
            input = List.copyOf(draws); ranges = ranges(input);
            assertTrue(ready(scene, input, ranges, true));
            assertSame(stable, scene.entry(draws.get(1).gpu()));
            assertArrayEquals(metadata, bytes(stable.chunk.slot.metadata, stable.record*64L, 64));
            assertArrayEquals(command, bytes(stable.chunk.slot.indirect, stable.first*20L, 20),
                    "buffer growth must preserve other baseInstance records and geometry offsets");
            var first = draws.get(0); encoded = scene.encodedItems();
            var changed = new IdentityHashMap<>(ranges);
            changed.put(first, new PredictionDrawRanges(new int[]{0,128,256,512}, new int[]{64,64,64,64}));
            assertTrue(ready(scene, input, changed, true));
            assertEquals(encoded+1, scene.encodedItems(), "one range change must update one record");
            var moved = scene.entry(first.gpu()); assertEquals(4, moved.reserved);
            for (int i=0; i<4; i++) assertEquals(moved.record,
                    ints(moved.chunk.slot.indirect, (moved.first+i)*20L, 5)[4]);
            assertSame(stable, scene.entry(draws.get(1).gpu()));
            assertArrayEquals(command, bytes(stable.chunk.slot.indirect, stable.first*20L, 20));
            first.gpu().packed().morph(null, 70, 95);
            encoded = scene.encodedItems();
            assertTrue(ready(scene, input, changed, true));
            assertEquals(encoded+1, scene.encodedItems(), "mutable metadata revision must update its record");
            var hidden = new IdentityHashMap<>(changed); hidden.remove(first);
            assertTrue(ready(scene, input, hidden, true));
            assertNull(scene.entry(first.gpu()));
            assertArrayEquals(new byte[moved.reserved*20],
                    bytes(moved.chunk.slot.indirect, moved.first*20L, moved.reserved*20),
                    "removed intervals must never retain a drawable command");
            assertTrue(ready(scene, input, changed, true));
            assertNotNull(scene.entry(first.gpu()), "coverage withdrawal restores the same resident payload");
            draws.add(tile(200, 0, 258)); draws.add(tile(-300, 0, 259));
            input = List.copyOf(draws); changed = ranges(input);
            assertTrue(ready(scene, input, changed, true));
            scene.ordered(Vec3.ZERO);
            Collections.reverse(draws); input = List.copyOf(draws);
            encoded = scene.encodedItems();
            assertTrue(ready(scene, input, changed, true));
            assertEquals(encoded, scene.encodedItems(), "list order cannot renumber the scene");
            double previous = -1;
            for (var chunk : scene.ordered(Vec3.ZERO)) {
                assertTrue(chunk.distance >= previous, "equivalent replacement must retain near-to-far ordering");
                previous = chunk.distance;
            }
            assertTrue(ready(scene, List.of(), new IdentityHashMap<>(), true));
            assertEquals(0, scene.retainedRecords()); assertTrue(scene.ordered(Vec3.ZERO).isEmpty());
            assertTrue(ready(scene, List.of(first), ranges(List.of(first)), true));
            assertFalse(scene.update(List.of(first, first), ranges(List.of(first)), true));
            assertEquals(0, scene.retainedRecords(), "fallback must release stale scene references");
            var sharedGpu = new PredictionGpuTile(first.tile().key()); allocated.add(sharedGpu);
            assertTrue(sharedGpu.ensureMesh(first.tile()));
            var shared = new PredictionRenderer.Draw(first.tile(), sharedGpu, first.allowed(), false,
                    first.faces(), first.morph(), first.bounds());
            input = List.of(first, shared); ranges = ranges(input);
            assertTrue(ready(scene, input, ranges, true));
            encoded = scene.encodedItems();
            first.gpu().packed().morph(null, 50, 100);
            assertTrue(ready(scene, input, ranges, true));
            assertEquals(encoded + 2, scene.encodedItems(), "all GPU owners of a shared mesh must update");
            encoded = scene.encodedItems();
            first.gpu().packed().morph(null, 40, 110);
            for (int i=0; i<=PredictionSubmissionChanges.CAPACITY; i++)
                PredictionSubmissionChanges.MESH.publish(PredictionSubmissionChanges.newId());
            assertTrue(ready(scene, input, ranges, true));
            assertEquals(encoded + 2, scene.encodedItems(), "overflow must fall back to exact validation");
        }
    }

    private void verifyPixels(PredictionTerrainProgram program) throws Exception {
        var draws = new ArrayList<PredictionRenderer.Draw>();
        for (int i=0; i<128; i++) draws.add(tile(i%16-8, i/16-4, i*31));
        var camera = new Vec3(0,110,0);
        var batches = new PredictionIndirectBatch[]{new PredictionIndirectBatch(),new PredictionIndirectBatch()};
        var groups = new PredictionOpaqueBatches();
        try {
            for (int step=0; step<12; step++) {
                List<PredictionRenderer.Draw> input = List.copyOf(draws);
                var ranges = ranges(input);
                if (step>=2) ranges.put(input.get(1), new PredictionDrawRanges(new int[]{0,512},new int[]{256,256}));
                if (step>=4 && step<6) ranges.remove(input.get(0));
                if (step==7) { Collections.reverse(draws); input=List.copyOf(draws); ranges=ranges(input); }
                if (step==8) {
                    var old = draws.get(0);
                    draws.set(0, tile(old.tile().key().tileX(), old.tile().key().tileZ(), 99));
                    input = List.copyOf(draws); ranges = ranges(input);
                }
                var view = new Matrix4f().lookAlong((float)Math.sin(step*.15),-.4f,(float)-Math.cos(step*.15),0,1,0);
                var projection = VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096));
                var transform = new Matrix4f(projection.matrix()).mul(view);
                byte[] reference = null; float[] referenceDepth = null;
                for (int mode=0; mode<2; mode++) {
                    boolean ready=false;
                    for(int attempt=0;!ready && attempt<16384;attempt++) {
                    frame(program, view, projection, 0);
                    var batch = batches[mode]; batch.workload(1024L*128);
                    batch.prepareGpuSelection(target.getDepthTextureId(), SIZE,SIZE,transform,camera,1,1,
                            new PredictionGpuSelectionInput(),input,4096);
                    // Small pixel fixture: exercise the production selector and
                    // scene without relaxing any production workload gates.
                    flag(batch,"plannedCulling",true); flag(batch,"plannedSelection",true);
                    int sentinel = glCreateBuffers(); glNamedBufferData(sentinel,256,GL_STATIC_DRAW);
                    glBindBufferRange(GL_SHADER_STORAGE_BUFFER,7,sentinel,0,128);
                    glBindBuffer(GL_DRAW_INDIRECT_BUFFER,sentinel);
                    batch.begin(program,target.getDepthTextureId(),SIZE,SIZE,transform,camera,1,1);
                    ready=mode==0 || batch.submitScene(input,ranges,true,camera,Long.MAX_VALUE)>=0;
                    if(mode==0 || !ready)ordered(batch,groups,input,ranges,camera);
                    batch.end();
                    assertEquals(sentinel,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,7));
                    assertEquals(128,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,7));
                    assertEquals(sentinel,glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING));
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,0);
                    glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0); glDeleteBuffers(sentinel);
                    var pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
                    glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                    byte[] image=new byte[pixels.remaining()];pixels.get(image);
                    var depths=BufferUtils.createFloatBuffer(SIZE*SIZE);
                    glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
                    float[] depth=new float[depths.remaining()];depths.get(depth);
                    if(mode==0){reference=image;referenceDepth=depth;}
                    else {assertArrayEquals(reference,image,"scene colour step="+step);
                        assertArrayEquals(referenceDepth,depth,"scene depth step="+step);}
                    assertEquals(GL_NO_ERROR,glGetError());
                    }
                    assertTrue(ready,"production scene submission must finish after bounded preparation");
                }
                if(step==0) {int coloured=0;for(int i=0;i<reference.length;i+=4)
                    if(reference[i]!=0 || reference[i+1]!=0 || reference[i+2]!=0)coloured++;
                    assertTrue(coloured>100,"pixel comparison must contain visible geometry");}
            }
        } finally {for(var batch:batches)batch.close();}
    }

    private void benchmark(PredictionTerrainProgram program) throws Exception {
        var resident = new ArrayList<PredictionRenderer.Draw>();
        for (int i=0; i<3072; i++) resident.add(tile(i%56-28,i/56-28,i));
        var rows = new ArrayList<String>(); rows.add("case,round,mode,frames,elapsed_ns,selected_frames,encoded_items,uploaded_bytes");
        for (int scenario=0; scenario<2; scenario++) {
            var plans = new ArrayList<PredictionVisiblePlan<Integer,PredictionRenderer.Draw>>();
            var passes = new ArrayList<PredictionPreparedPassLocalTest.Pass>();
            var inputs = new ArrayList<PredictionGpuSelectionInput>();
            var batches = new ArrayList<PredictionIndirectBatch>();
            var groups = new ArrayList<PredictionOpaqueBatches>();
            for(int mode=0;mode<2;mode++) {
                var plan=new PredictionVisiblePlan<Integer,PredictionRenderer.Draw>(PredictionRenderer.Draw::bounds);
                for(int i=0;i<resident.size();i++)plan.put(i,resident.get(i));
                plans.add(plan);passes.add(new PredictionPreparedPassLocalTest.Pass(false));
                inputs.add(new PredictionGpuSelectionInput());batches.add(new PredictionIndirectBatch());
                groups.add(new PredictionOpaqueBatches());
            }
            int frames=32;long[][] elapsed=new long[2][21];
            try {
                for(int round=-12;round<21;round++)for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2); long encoded=0,uploaded=0;int selectedFrames=0;
                    glFinish();long start=System.nanoTime();
                    for(int f=0;f<frames;f++) {
                        double yaw=(round*frames+f)*.007;
                        var camera=new Vec3(0,110,0);
                        var view=new Matrix4f().lookAlong((float)Math.sin(yaw),-.13f,(float)-Math.cos(yaw),0,1,0);
                        var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096));
                        var transform=new Matrix4f(projection.matrix()).mul(view);
                        var frustum=new Frustum(view,projection.culling());frustum.prepare(camera.x,camera.y,camera.z);
                        frame(program,view,projection,.001);
                        if(scenario==1) resident.get(Math.floorMod(round*frames+f,resident.size())).gpu().packed()
                                .morph(null,70+Math.floorMod(round,3),90+f);
                        var batch=batches.get(mode);var pass=passes.get(mode);var input=inputs.get(mode);
                        var visible=plans.get(mode).selectStable(camera,view,projection.matrix(),frustum);
                        batch.workload((long)visible.size()*1024);
                        boolean selected=batch.prepareGpuSelection(target.getDepthTextureId(),SIZE,SIZE,
                                transform,camera,1,1,input,plans.get(mode).residents(),4096);
                        if(selected)selectedFrames++;
                        var draws=selected?input.candidates():visible;
                        pass.prepare(draws,1,camera);
                        batch.workload(pass.counter("selectedCandidates"));
                        batch.begin(program,target.getDepthTextureId(),SIZE,SIZE,transform,camera,1,1,
                                pass.counter("workloadRevision"));
                        var ranges=passRanges(pass);long before=batch.encodedItems();
                        long submitted=mode==1?batch.submitScene(draws,ranges,true,camera,Long.MAX_VALUE):-1;
                        if(submitted<0)submitted=batch.replayPass(pass.value,pass.counter("revision"),true,camera,Long.MAX_VALUE);
                        if(submitted<0)ordered(batch,groups.get(mode),draws,ranges,camera);
                        batch.end();encoded+=batch.encodedItems()-before;uploaded+=batch.uploadedBytes();
                    }
                    glFinish();long time=System.nanoTime()-start;
                    assertTrue(selectedFrames>=frames-1,"benchmark must pass actual production selection gates");
                    if(round>=0){elapsed[mode][round]=time;
                        rows.add(scenario+","+round+","+mode+","+frames+","+time+","+selectedFrames+","+encoded+","+uploaded);}
                    assertEquals(GL_NO_ERROR,glGetError());
                }
                for(int mode=0;mode<2;mode++) {
                    var sorted=elapsed[mode].clone();Arrays.sort(sorted);
                    System.out.printf(Locale.ROOT,"PERSISTENT_SCENE_PIPELINED case=%d mode=%d ms_per_pass=%.6f%n",
                            scenario,mode,sorted[sorted.length/2]/1e6/frames);
                }
            } finally {batches.forEach(PredictionIndirectBatch::close);}
        }
        var output=Path.of(System.getProperty("vss.persistentSceneResults","build/persistent-scene-results"));
        Files.createDirectories(output);Files.write(output.resolve("persistent-scene-throughput.csv"),rows);
    }

    private void ordered(PredictionIndirectBatch batch, PredictionOpaqueBatches groups,
                         List<PredictionRenderer.Draw> draws,
                         IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges> ranges, Vec3 camera) {
        int buckets=groups.prepare(draws,1,ranges,camera);
        for(int b=0;b<buckets;b++) {
            batch.nextOcclusionBucket();
            for(var page:groups.bucket(b).pages) {
                batch.nextRegion(page.region);
                for(var draw:page.draws)assertTrue(batch.add(draw,ranges.get(draw),camera,false,true,Long.MAX_VALUE));
            }
            assertTrue(groups.bucket(b).fallback.isEmpty());
        }
    }

    private void setup() {
        vao=glGenVertexArrays();ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
        int[] indices=new int[4096*6],corners={0,1,2,0,2,3};
        for(int q=0;q<4096;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
        glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
        tex(0,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});tex(1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
        tex(2,1,GL_RGBA32F,GL_RGBA,new float[4]);exact=tex(8,1,GL_R8,GL_RED,new float[]{0});
        float[] depth=new float[SIZE*SIZE];Arrays.fill(depth,1);mainDepth=tex(7,SIZE,GL_R32F,GL_RED,depth);
        glActiveTexture(GL_TEXTURE6);int volume=glGenTextures();textures.add(volume);glBindTexture(GL_TEXTURE_3D,volume);
        glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexImage3D(GL_TEXTURE_3D,0,GL_R8,1,1,1,0,GL_RED,GL_FLOAT,new float[]{0});
        target=new TextureTarget(SIZE,SIZE,true,false);
    }
    private void configure(PredictionTerrainProgram program) {
        program.use();program.setSamplers(0,1,2,3,4);
        program.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
        int id=glGetInteger(GL_CURRENT_PROGRAM);
        glUniform1i(glGetUniformLocation(id,"ExactCoverage"),8);glUniform3f(glGetUniformLocation(id,"ExactCoverageGrid"),0,0,1);
        glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),1,1,1);
        glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
        glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});

    }
    private void frame(PredictionTerrainProgram program, Matrix4f view,VssLodProjection.MatrixData projection,double depth) {
        target.bindWrite(true);glBindVertexArray(vao);program.use();program.setCamera(view,projection.matrix());
        program.bindMainDepth(mainDepth,projection);glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D,exact);
        glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
        glClearDepth(depth);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);program.setOpaqueAlpha(1);
    }
    private PredictionRenderer.Draw tile(int x,int z,int colour) {
        int[] words=PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_MODEL_UV);
        for(int q=0;q<1024;q++)for(int offset:new int[]{7,9,10,11})words[q*12+offset]=0x335522+(colour&127);
        var packed=PredictionPackedMesh.terrainRecords(words,64);packed.prepareGpuStorage();
        var mesh=PredictionMesh.restored(words.length/12*6,0,packed,null);
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x,z,0);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,
                new PredictionDepthBound(80,80),0,allocated.size()+1L,64,1);
        var gpu=new PredictionGpuTile(key);allocated.add(gpu);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
        boolean[] allowed=new boolean[4096];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
        byte[] mask=new byte[4096];Arrays.fill(mask,(byte)128);gpu.updateBoundaryCoverage(mask);
        return new PredictionRenderer.Draw(tile,gpu,allowed,false,VssLodFaceGroup.ALL,0,
                new AABB(tile.baseBlockX(),78,tile.baseBlockZ(),tile.baseBlockX()+64,82,tile.baseBlockZ()+64));
    }
    private IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges> ranges(List<PredictionRenderer.Draw> draws) {
        var result=new IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>();
        for(var draw:draws)result.put(draw,draw.gpu().packed().drawRanges(false,VssLodFaceGroup.ALL));return result;
    }
    @SuppressWarnings("unchecked") private static IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>
            passRanges(PredictionPreparedPassLocalTest.Pass pass) throws Exception {
        var field=pass.type.getDeclaredField("byDraw");field.setAccessible(true);
        return (IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>)field.get(pass.value);
    }
    private static void flag(PredictionIndirectBatch batch,String name,boolean value) throws Exception {
        var field=PredictionIndirectBatch.class.getDeclaredField(name);field.setAccessible(true);field.setBoolean(batch,value);
    }
    private static byte[] bytes(int buffer,long offset,int size) {
        ByteBuffer data=BufferUtils.createByteBuffer(size);glGetNamedBufferSubData(buffer,offset,data);
        byte[] result=new byte[size];data.get(result);return result;
    }
    private static int[] ints(int buffer,long offset,int count) {
        int[] data=new int[count];glGetNamedBufferSubData(buffer,offset,data);return data;
    }
    private int tex(int unit,int size,int internal,int format,float[] values) {
        glActiveTexture(GL_TEXTURE0+unit);int texture=glGenTextures();textures.add(texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D,0,internal,size,size,0,format,GL_FLOAT,values);return texture;
    }
}
