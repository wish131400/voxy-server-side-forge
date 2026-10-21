package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionMeridianImplementationTest {
    @BeforeAll static void bootstrap() { AlgorithmBootstrap.bootstrapMinecraft(); }

    @Test void identicalInputsAcrossNewVersionsSkipGeometryAndKeepCurrentTile() throws Exception {
        var current = new ArrayList<PredictionLodSeams.Surface>();
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            current.add(PredictionLodSeamsTest.surface(AlgorithmSupport.tile(x-2, z-2, 64+((x+z)&1)*16, 1)));
        var seams = new PredictionLodSeams(); seams.update(current);
        var rows = new ArrayList<String>(); rows.add("round,mode,cpu_ns,allocated_bytes,updated_surfaces,changed_patch_count");
        for (int round = -16; round < 41; round++) {
            int i = Math.floorMod(round, current.size()); var old = current.get(i);
            var replacement = new PredictionLodSeams.Surface(AlgorithmSupport.version(old.tile(), round+100), old.allowed().clone());
            long before = seams.updatedSurfaces();
            var measurement = AlgorithmSupport.measure(() -> seams.apply(List.of(replacement), Set.of()).patches().size());
            current.set(i, replacement);
            assertEquals(before, seams.updatedSurfaces(), "equivalent inputs cannot rebuild neighbour seams");
            assertSame(replacement.tile(), seams.surface(replacement.tile().key()).tile(), "release the obsolete tile reference");
            if (round >= 0) rows.add(round+",same_edge_new_version,"+measurement.nanos()+","+measurement.bytes()+",0,"+AlgorithmSupport.sink);
        }
        assertOracle(seams, current);
        // Also compare independently built summaries/arrays; object identity is only a fast path.
        var first = current.get(0); var equal = PredictionLodSeamsTest.surface(AlgorithmSupport.tile(-2,-2,64,200));
        long before = seams.updatedSurfaces(); seams.apply(List.of(equal), Set.of()); current.set(0,equal);
        assertEquals(before, seams.updatedSurfaces()); assertOracle(seams,current);
        AlgorithmSupport.save("implemented-seam-version-cost.csv", rows);
    }

    @Test void changedGeometryCoverageAndMixedLodStillMatchFullRebuild() {
        var fine = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(-1,-1,2,64));
        var coarse = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0,-1,4,96));
        var current = new ArrayList<>(List.of(fine,coarse)); var seams = new PredictionLodSeams(); seams.update(current);
        for (int round = 0; round < 20; round++) {
            var old = current.get(1);
            var tile = round%3==0 ? PredictionLodSeamsTest.tile(0,-1,4,80+round) : AlgorithmSupport.version(old.tile(),round+100);
            var allowed = old.allowed().clone();
            if (round%3==1) for (int z=0;z<64;z++) allowed[z*64] = !allowed[z*64];
            var next = new PredictionLodSeams.Surface(tile,allowed); current.set(1,next);
            seams.apply(List.of(next),Set.of()); assertOracle(seams,current);
        }
        seams.apply(List.of(),Set.of(coarse.tile().key())); current.remove(1); assertOracle(seams,current);
        seams.apply(List.of(coarse),Set.of()); current.add(coarse); assertOracle(seams,current);
    }

    @Test void metadataRebindingIsAtomicForRealSeamsAndMasks() {
        var preparation = new PredictionSeamPreparation(Runnable::run);
        var tile = PredictionLodSeamsTest.tile(0,0,2,64);
        var surface = PredictionLodSeamsTest.surface(tile);
        var edges = List.of(new PredictionRealBoundarySeams.Edge(-1,10,1,0,PredictionRealBoundarySeamsTest.ground(67)));
        preparation.request(1,Map.of(tile.key(),new PredictionSeamPreparation.Surface(tile,surface.allowed(),surface.allowed())),edges);
        var first = preparation.poll(1); assertNotNull(first); assertFalse(first.real().isEmpty());
        var next = AlgorithmSupport.version(tile,100);
        preparation.request(2,Map.of(tile.key(),new PredictionSeamPreparation.Surface(next,surface.allowed().clone(),surface.allowed().clone())),edges);
        var second = preparation.poll(2); assertNotNull(second);
        assertTrue(second.maskChanges().isEmpty(), "unchanged masks remain canonical");
        assertSame(first.real().get(0).mesh(),second.real().get(0).mesh());
        assertSame(next,second.real().get(0).surface().tile());
        assertEquals(1,second.realChanges().patches().size(), "metadata is published with the current transaction");
        preparation.clear();
    }

    @Test void cpuCostFeedbackKeepsFloorClassifiesRestoresAndNeverLeaksOnFailure() {
        long mib=PredictionMemoryBudget.MIB; var now=new AtomicLong();
        var budget=new PredictionMemoryBudget(1024*mib,0,()->1024*mib,now::get,4);
        var completed=budget.tryReserveBuild(PredictionMemoryBudget.BuildKind.SURFACE); assertNotNull(completed);
        now.set(5_000_000); completed.completeBuild(80*mib,20*mib,false);
        completed.completeBuild(999*mib,999*mib,false); // idempotence, including release after transfer
        assertTrue(completed.retain(80*mib)); assertEquals(0,budget.activeBuildCount()); completed.close(); completed.close();
        var cost=budget.cost(PredictionMemoryBudget.BuildKind.SURFACE);
        assertEquals(1,cost.completed()); assertEquals(5_000_000,cost.elapsedNanos()); assertEquals(160*mib,cost.reservationBytes());
        assertEquals(PredictionMemoryBudget.BUILD_BYTES,budget.cost(PredictionMemoryBudget.BuildKind.TERRAIN).reservationBytes());
        var failed=budget.tryReserveBuild(); failed.finishBuild(PredictionMemoryBudget.BuildOutcome.FAILED); failed.close();
        var cancelled=budget.tryReserveBuild(); cancelled.close();
        try(var restored=budget.tryReserveRestore()) { assertNotNull(restored); restored.completeBuild(mib,mib,true); }
        var terrain=budget.cost(PredictionMemoryBudget.BuildKind.TERRAIN);
        assertEquals(1,terrain.failed()); assertEquals(1,terrain.cancelled());
        assertEquals(1,budget.cost(PredictionMemoryBudget.BuildKind.RESTORE).completed());
        assertEquals(0,budget.usedBytes()); assertEquals(0,budget.activeBuildCount());
    }

    @Test void allFormalEdgesRetainSourceHeightsAndInteriorStillStabilizes() throws Exception {
        var method=PredictionMeshBuilder.class.getDeclaredMethod("stabilizeGeneratedSurface",ClientColumnSample[].class,int[].class,int.class,int.class);
        method.setAccessible(true);
        for(int step:new int[]{1,2,4,8}) for(int flag:new int[]{0,ClientColumnSample.FLAG_CAPTURED,ClientColumnSample.FLAG_SURFACE_ONLY}) {
            var samples=new ClientColumnSample[65*65]; Arrays.fill(samples,AlgorithmSupport.sample(64,0));
            int[] probes={31*65,31*65+63,31,63*65+31,31*65+31};
            for(int at:probes) samples[at]=AlgorithmSupport.sample(96,flag);
            int[] heights=Arrays.stream(samples).mapToInt(ClientColumnSample::surfaceY).toArray();
            method.invoke(null,samples,heights,step,65);
            for(int i=0;i<4;i++) assertEquals(96,heights[probes[i]],"formal perimeter keeps exact source");
            assertEquals(flag==0&&(step==2||step==4)?64:96,heights[probes[4]]);
        }
    }

    @Test void waterRevisionChangesExactlyWhenTheStableSortedOrderChanges() throws Exception {
        var mesh=PredictionMeshCodecTest.fixture();var draws=new ArrayList<PredictionRenderer.Draw>();
        Random random=new Random(4125);
        for(int i=0;i<128;i++) {
            int x=i<4?(i%2==0?-1:1):random.nextInt(256)-128;
            int z=i<4?(i/2==0?-1:1):random.nextInt(256)-128;
            var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,x,z,0);
            var tile=new PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,new PredictionDepthBound(60,80),0,i+1,16,1);
            var gpu=new PredictionGpuTile(key);PredictionPreparedPassLocalTest.payload(gpu,mesh.gpuPayload());
            boolean[] allowed=new boolean[256];Arrays.fill(allowed,true);
            draws.add(new PredictionRenderer.Draw(tile,gpu,allowed,false,VssLodFaceGroup.ALL,0,
                    new net.minecraft.world.phys.AABB(tile.baseBlockX(),60,tile.baseBlockZ(),tile.baseBlockX()+16,80,tile.baseBlockZ()+16)));
        }
        var pass=new PredictionPreparedPassLocalTest.Pass(true);pass.prepare(draws,1,net.minecraft.world.phys.Vec3.ZERO);
        var getDraw=pass.entries().get(0).getClass().getDeclaredMethod("draw");getDraw.setAccessible(true);
        var expected=new ArrayList<PredictionRenderer.Draw>();for(var entry:pass.entries())expected.add((PredictionRenderer.Draw)getDraw.invoke(entry));
        int stable=0,changed=0;
        for(int frame=1;frame<=512;frame++) {
            double x=frame<128?frame*.001:frame<256?frame*.2:frame<384?(frame%2==0?-4096:4096):0;
            var camera=new net.minecraft.world.phys.Vec3(x,80,x*.37);long before=pass.counter("revision");
            var old=List.copyOf(expected);
            expected.sort(Comparator.comparingDouble(draw->{double dx=draw.tile().baseBlockX()+8-camera.x,dz=draw.tile().baseBlockZ()+8-camera.z;return dx*dx+dz*dz;}));
            pass.prepare(draws,1,camera);var actual=new ArrayList<PredictionRenderer.Draw>();
            for(var entry:pass.entries())actual.add((PredictionRenderer.Draw)getDraw.invoke(entry));
            assertEquals(expected,actual,"transparent order frame="+frame);
            if(old.equals(expected)) {stable++;assertEquals(before,pass.counter("revision"));}
            else {changed++;assertEquals(before+1,pass.counter("revision"));}
        }
        assertTrue(stable>100);assertTrue(changed>100);
        pass.clear.invoke(pass.value);
    }

    @Test void adjacentRuntimeMarginTilesPreserveBordersForWaterCaptureVolumesAndMixedLods() {
        for(int step:new int[]{2,4})for(int direction=0;direction<4;direction++)for(int variant=0;variant<4;variant++) {
            int x=direction<2?(direction==0?0:63):31,z=direction>=2?(direction==2?0:63):31;
            int peakX=-64*step+x*step,peakZ=-64*step+z*step;
            var tile=runtimeTile(-1,-1,step,peakX,peakZ,variant);
            assertTrue(tile.mesh().seamMesh().hasTop(z*64+x));
            assertEquals(96,tile.mesh().seamMesh().topY(z*64+x),"actual runtime border, direction="+direction+" variant="+variant);
            int nx=direction==0?-1:direction==1?1:0,nz=direction==2?-1:direction==3?1:0;
            for(int neighborStep:new int[]{step,step*2}) {
                int wx=tile.baseBlockX()+x*step+(nx<0?-1:nx>0?step:step/2);
                int wz=tile.baseBlockZ()+z*step+(nz<0?-1:nz>0?step:step/2);
                var neighbor=runtimeTile(Math.floorDiv(wx,neighborStep*64),Math.floorDiv(wz,neighborStep*64),neighborStep,peakX,peakZ,variant);
                var surfaces=List.of(PredictionLodSeamsTest.surface(tile),PredictionLodSeamsTest.surface(neighbor));
                var seams=new PredictionLodSeams();seams.update(surfaces);assertOracle(seams,surfaces);
                long before=seams.updatedSurfaces();
                var next=PredictionLodSeamsTest.surface(AlgorithmSupport.version(tile,100));
                seams.apply(List.of(next),Set.of());assertEquals(before,seams.updatedSurfaces());
                assertOracle(seams,List.of(next,surfaces.get(1)));
            }
        }
    }

    private static PredictionTile runtimeTile(int tx,int tz,int step,int peakX,int peakZ,int variant) {
        int axis=64,grid=66;var raw=new ClientColumnSample[grid*grid];int stone=PredictionMaterialPalette.stoneIndex();
        for(int z=0;z<grid;z++)for(int x=0;x<grid;x++) {
            int wx=tx*64*step+(x-1)*step,wz=tz*64*step+(z-1)*step;
            int y=wx==peakX&&wz==peakZ?96:64,flags=variant==1?ClientColumnSample.FLAG_CAPTURED:0;
            var volume=variant==3?PredictionColumnVolume.ofRuns(new int[]{y-8,y,stone,0}):null;
            raw[z*grid+x]=new ClientColumnSample(y,variant==2?100:y,0,stone,0,0,0,0,variant==2?1:0,flags,0,stone,stone,
                    ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,volume);
        }
        var mesh=PredictionMeshBuilder.buildForRendering(raw,null,63,0xff4060aa,step,grid,null,null,tx*64*step,tz*64*step,
                PredictionVegetation.Tile.EMPTY,PredictionSimpleVegetation.Result.EMPTY,null,()->true).compactForRendering();
        var samples=new ClientColumnSample[65*65];int[] heights=new int[samples.length];
        for(int z=0;z<65;z++)for(int x=0;x<65;x++) {samples[z*65+x]=raw[(z+1)*grid+x+1];heights[z*65+x]=samples[z*65+x].surfaceY();}
        var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,tx,tz,Integer.numberOfTrailingZeros(step));
        var tile=new PredictionTile(key,heights,heights,samples,mesh,new PredictionDepthBound(0,110),0,1,axis,step);
        mesh.prepareGpuPayload(tile);return tile;
    }

    private static void assertOracle(PredictionLodSeams seams,List<PredictionLodSeams.Surface> current) {
        var oracle=new PredictionLodSeams(); var patches=oracle.update(current);
        var expected=new HashMap<PredictionTileManager.PredictionTileKey,PredictionLodSeams.Patch>();
        for(var patch:patches) expected.put(patch.surface().tile().key(),patch);
        assertEquals(expected.size(),seams.patchesSnapshot().size());
        for(var patch:seams.patchesSnapshot()) {
            var want=expected.get(patch.surface().tile().key()); assertNotNull(want);
            assertArrayEquals(want.mesh().quads(),patch.mesh().quads());
            assertSame(want.surface().tile(),patch.surface().tile());
            assertTrue(patch.bounds().minY<=want.bounds().minY&&patch.bounds().maxY>=want.bounds().maxY);
        }
        for(var surface:current) assertArrayEquals(oracle.boundaryMask(surface.tile().key()),seams.boundaryMask(surface.tile().key()));
    }
}
