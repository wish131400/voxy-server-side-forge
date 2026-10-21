package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionVoxelHierarchyTest {
    @BeforeEach void prepare(){ClientTerrainSamplerTest.bootstrapMinecraft();VssLodSpriteTable.close();}
    @AfterEach void clear(){VssLodSpriteTable.close();System.clearProperty("vss.disablePredictionVoxelLod");}

    @Test void workerBuildsBoundedVoxelLayersAndKeepsExactSource() throws Exception {
        try(var contents=PredictionVegetationMaterialTest.contents("block/vss_voxel_grass")) {
            int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
            int[] input=building();
            System.setProperty("vss.disablePredictionVoxelLod","true");
            var before=PredictionPackedMesh.terrainRecords(input,128);
            System.clearProperty("vss.disablePredictionVoxelLod");
            var after=PredictionPackedMesh.terrainRecords(input,128);
            assertTrue(after.hasFeatureVoxelLod());
            assertFalse(after.hasGroundCoverLod());
            assertFalse(after.hasVegetationLod());
            assertArrayEquals(before.cacheWords(),after.cacheWords(),"lossless source stays available for close views");
            assertTrue(after.quadCount()<=before.quadCount()*1.5,"bounded extra GPU residency");
            int full=after.drawRanges(false,31,0,0,0,0,false).quads;
            for(int tier=1;tier<4;tier++) {
                var ranges=after.drawRanges(false,31,0,tier,0,0,false);
                assertTrue(ranges.quads<full/4,"tier="+tier+", quads="+ranges.quads);
                assertTrue(Float.isFinite(after.displayErrors()[tier]));
                assertEquals(ranges.quads,after.drawRanges(false,31,0,tier,3,4,false).quads,
                        "a pixel plant selector cannot independently change a voxel representation");
            }
            var selection=new PredictionDisplayLod();
            selection.update(after.displayErrors(),100,1000,256,false);assertEquals(0,selection.tier());
            selection.update(after.displayErrors(),12000,1000,256,false);assertTrue(selection.tier()>0);
            selection.update(after.displayErrors(),12000,100000,256,false);assertEquals(0,selection.tier(),"zoom restores exact geometry");
            selection.update(after.displayErrors(),12000,1000,256,true);assertEquals(0,selection.tier(),"morph and partial ownership protect source");
            System.out.printf(Locale.ROOT,"VOXEL_GEOMETRY source=%d stored=%d levels=%d,%d,%d%n",full,after.quadCount(),
                    after.drawRanges(false,31,0,1,0,0,false).quads,after.drawRanges(false,31,0,2,0,0,false).quads,
                    after.drawRanges(false,31,0,3,0,0,false).quads);
        }
    }

    @Test void preparedCacheRetainsVoxelMembershipWithoutCoordinates() throws Exception {
        try(var contents=PredictionVegetationMaterialTest.contents("block/vss_voxel_cache")) {
            int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
            int[] plants=building();
            var mesh=PredictionPackedMesh.terrainRecords(plants,64);
            var plan=mesh.cachePlan();assertNotNull(plan);assertNotNull(plan.display().voxelFeatures());
            assertNull(plan.display().words());assertNull(plan.display().voxelFeatures().base.words());
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){PredictionMeshDerivedCache.write(out,plan,mesh.quads());}
            int[] first=new int[5],count=new int[5];
            for(int g=0;g<5;g++){first[g]=plan.vegetation().cacheFirst()[g*4];for(int k=0;k<4;k++)count[g]+=plan.vegetation().cacheCounts()[0][g*4+k];}
            var input=ByteBuffer.wrap(bytes.toByteArray());
            var restored=PredictionMeshDerivedCache.read(input,mesh.cacheWords().length,mesh.quadCount(),first,count);
            assertFalse(input.hasRemaining());assertNotNull(restored.display().voxelFeatures());
            for(int t=1;t<4;t++)for(int mask=0;mask<32;mask++) {
                var expected=plan.display().range(t,0,mask,0,0);var actual=restored.display().range(t,0,mask,0,0);
                assertArrayEquals(expected.first,actual.first);assertArrayEquals(expected.count,actual.count);
            }
            var full=PredictionMesh.restored(mesh.cacheWords().length/2,0,mesh,PredictionMeshCodecTest.fixture(64).seamMesh());
            byte[] identity=new byte[32];
            int[] canonical=mesh.quads().clone();
            try(var table=mockStatic(VssLodSpriteTable.class,CALLS_REAL_METHODS)) {
                table.when(()->VssLodSpriteTable.readMaterial(any())).thenAnswer(call->
                        VssLodSpriteTable.readMaterial((DataInputStream)call.getArgument(0),name->new PredictionVegetationMaterialTest.Sprite(contents)));
                for(int round=0;round<3;round++) {
                    full=PredictionMeshCodec.decode(PredictionMeshCodec.encode(full,identity),identity,64);
                    assertNotNull(full);assertTrue(full.gpuPayload().hasFeatureVoxelLod());
                    assertArrayEquals(canonical,full.gpuPayload().quads(),"saving a restored mesh must not append another hierarchy");
                    assertArrayEquals(mesh.displayErrors(),full.gpuPayload().displayErrors());
                }
            }
            var voxel=plan.display().voxelFeatures();
            var basePlan=new PredictionMeshDerivedCache.Plan(plan.sourceWords(),plan.policy(),plan.materials(),plan.aquatic(),plan.vegetation(),voxel.base);
            var prefix=new ByteArrayOutputStream();PredictionMeshDerivedCache.write(new DataOutputStream(prefix),basePlan,mesh.quads());
            int offset=prefix.size()-1;
            assertEquals(1,bytes.toByteArray()[offset]);
            byte[] malformed=bytes.toByteArray();ByteBuffer.wrap(malformed).putFloat(offset+1+4,0);
            assertThrows(IOException.class,()->PredictionMeshDerivedCache.read(ByteBuffer.wrap(malformed),mesh.cacheWords().length,mesh.quadCount(),first,count));
        }
    }

    @Test void bakedModelsAndOrdinaryGroundSurviveAllVoxelSelections() throws Exception {
        try(var contents=PredictionVegetationMaterialTest.contents("block/vss_voxel_exceptions")) {
            int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
            int[] plants=building();
            int[] input=Arrays.copyOf(plants,plants.length+24);
            PredictionSpatialOrderTest.quad(input,plants.length/12,3,3,90,row|PredictionPackedMesh.FLAG_MODEL_UV);
            input[plants.length+10]|=PredictionPackedMesh.FEATURE;
            PredictionSpatialOrderTest.quad(input,plants.length/12+1,4,4,90,0);
            var mesh=PredictionPackedMesh.terrainRecords(input,128);
            assertTrue(mesh.hasFeatureVoxelLod());int[] source=mesh.cacheWords();
            var protectedQuads=new BitSet();
            for(int q=0;q<source.length/12;q++)if((source[q*12+6]&PredictionPackedMesh.FLAG_MODEL_UV)!=0
                    ||(source[q*12+10]&PredictionPackedMesh.FEATURE)==0)protectedQuads.set(q);
            assertEquals(2,protectedQuads.cardinality());
            for(int t=1;t<4;t++) {
                var range=mesh.drawRanges(false,31,0,t,0,0,false);var actual=new BitSet();
                for(int r=0;r<range.first.length;r++)actual.set(range.first[r],range.first[r]+range.count[r]);
                var missing=(BitSet)protectedQuads.clone();missing.andNot(actual);assertTrue(missing.isEmpty());
            }
        }
    }

    @Test void solidBuildingHasAllDirectionsAndNegativeHeightWithoutInteriorFaces() {
        int[] input=building();
        var mesh=PredictionPackedMesh.terrainRecords(input,40);
        assertTrue(mesh.hasFeatureVoxelLod());
        int source=mesh.cacheWords().length/12;
        for(int t=1;t<4;t++) {
            var ranges=mesh.drawRanges(false,31,0,t,0,0,false);
            assertTrue(ranges.quads<source/2,"solid shell must actually simplify");
            int[] words=mesh.quads();boolean[] directions=new boolean[6];
            for(int r=0;r<ranges.first.length;r++)for(int q=ranges.first[r];q<ranges.first[r]+ranges.count[r];q++) {
                int p=q*12,f=words[p+6],n=f>>>24&3;
                directions[n==0?(f&PredictionPackedMesh.FLAG_BANK)==0?1:0:n==1?(f&PredictionPackedMesh.FLAG_FACE_POSITIVE)==0?2:3:(f&PredictionPackedMesh.FLAG_FACE_POSITIVE)==0?4:5]=true;
                assertNotEquals(0,words[p+10]&PredictionPackedMesh.DISPLAY_APPROXIMATE);
                for(int c=0;c<4;c++) {
                    int y=PredictionDisplayGeometry.coordinate(words,p,c,1);
                    assertTrue(y>=-32*16&&y<=8*16,"negative coordinates must not wrap");
                }
            }
            for(boolean present:directions)assertTrue(present,"all six sides remain represented");
            System.out.printf(Locale.ROOT,"VOXEL_BUILDING tier=%d source=%d selected=%d%n",t,source,ranges.quads);
        }
    }

    @Test void boundedRasterizationAbortsAndCancellationLeavesSourceIntact() {
        int[] input=new int[128*12];
        for(int q=0;q<128;q++) {
            PredictionSpatialOrderTest.quad(input,q,0,0,80,0);
            input[q*12]=65535<<16;input[q*12+1]=65535;
            input[q*12+3]=0xffffffff;input[q*12+10]|=PredictionPackedMesh.FEATURE;
        }
        int[] first=new int[5],count={128,0,0,0,0};
        var plants=PredictionVegetationRanges.arrange(input,first,count,new boolean[0],new boolean[0]);
        var base=new PredictionDisplayGeometry.Result(input,null);
        int[] unchanged=input.clone();
        assertSame(base,PredictionFeatureVoxels.build(input,first,count,64,plants,base),"oversized raster falls back before allocating its volume");
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class,()->PredictionFeatureVoxels.build(input,first,count,64,plants,base));
        } finally {Thread.interrupted();}
        assertArrayEquals(unchanged,input);
    }

    @Test void capturedForestsReduceWorkWithinResidencyBudget() throws Exception {
        var csv=new ArrayList<String>();csv.add("mesh,source,stored_before,stored_after,tier1,tier2,tier3,build_ns,allocated_bytes");
        int useful=0;
        for(var forest:AlgorithmSupport.forests()) {
            int[] words=forest.words()[0].clone();
            for(int p=6;p<words.length;p+=12)if(Set.of(31,40,41,42,88,91,94,167).contains(words[p]&65535))words[p]|=PredictionPackedMesh.FLAG_CUTOUT;
            System.setProperty("vss.disablePredictionVoxelLod","true");
            var baseline=PredictionPackedMesh.terrainRecords(words,forest.span()/forest.spacing());
            System.out.printf(Locale.ROOT,"VOXEL_BASE id=%d errors=%s quads=%d,%d,%d%n",forest.id(),Arrays.toString(baseline.displayErrors()),baseline.drawRanges(false,31,0,1,0,0,false).quads,baseline.drawRanges(false,31,0,2,0,0,false).quads,baseline.drawRanges(false,31,0,3,0,0,false).quads);
            System.clearProperty("vss.disablePredictionVoxelLod");
            long thread=Thread.currentThread().getId(),allocated=AlgorithmSupport.THREADS.getThreadAllocatedBytes(thread),start=System.nanoTime();
            var mesh=PredictionPackedMesh.terrainRecords(words,forest.span()/forest.spacing());
            long elapsed=System.nanoTime()-start;allocated=AlgorithmSupport.THREADS.getThreadAllocatedBytes(thread)-allocated;
            assertArrayEquals(baseline.cacheWords(),mesh.cacheWords());
            assertTrue(mesh.quadCount()<=baseline.quadCount()+mesh.cacheWords().length/24);
            int[] quads=new int[4];
            for(int t=0;t<4;t++)quads[t]=mesh.drawRanges(false,31,0,t,0,0,false).quads;
            // Compare actual choices, including the terrain LOD that already
            // existed. Fresh and continuous selections cover hysteresis too.
            for(double pixels:new double[]{256,700,1500,100000})for(double error:new double[]{1,1.5,2,3}) {
                var oldChoice=new PredictionDisplayLod();var newChoice=new PredictionDisplayLod();
                for(int d:new int[]{512,1024,2048,4096,8192,16384,8192,4096,2048,1024,512}) {
                    oldChoice.update(baseline.displayErrors(),d,pixels,256,false,error);
                    newChoice.update(mesh.displayErrors(),d,pixels,256,false,error);
                    for(int mask=0;mask<32;mask++) {
                        int oldCount=baseline.drawRanges(false,mask,0,oldChoice.tier(),0,0,false).quads;
                        int newCount=mesh.drawRanges(false,mask,0,newChoice.tier(),0,0,false).quads;
                        assertTrue(newCount<=oldCount,"LOD regression mesh="+forest.id()+", distance="+d+", pixels="+pixels+", mask="+mask);
                    }
                }
            }
            if(mesh.hasFeatureVoxelLod()){useful++;assertTrue(quads[3]<quads[0]);}
            csv.add(forest.id()+","+quads[0]+","+baseline.quadCount()+","+mesh.quadCount()+","+quads[1]+","+quads[2]+","+quads[3]+","+elapsed+","+allocated);
            System.out.printf(Locale.ROOT,"VOXEL_FOREST id=%d source=%d stored=%d->%d selected=%d,%d,%d worker_ms=%.3f allocated_MiB=%.3f%n",
                    forest.id(),quads[0],baseline.quadCount(),mesh.quadCount(),quads[1],quads[2],quads[3],elapsed/1e6,allocated/1048576.);
        }
        assertTrue(useful>=4,"real forest fixtures must benefit, not just uniform synthetic plants");
        AlgorithmSupport.save("voxel-forest-build.csv",csv);
    }

    @Test void groundPlantsKeepTheirExistingDensityAndOwnershipRules() throws Exception {
        try(var contents=PredictionVegetationMaterialTest.contents("block/vss_voxel_ground_control")) {
            int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
            int[] plants=PredictionVegetationLodGpuTest.plants(row),building=building();
            int[] input=Arrays.copyOf(plants,plants.length+building.length);
            System.arraycopy(building,0,input,plants.length,building.length);
            var mesh=PredictionPackedMesh.terrainRecords(input,128);
            assertTrue(mesh.hasFeatureVoxelLod());assertTrue(mesh.hasGroundCoverLod());
            assertTrue(mesh.drawRanges(false,31,0,3,0,0,false).quads>mesh.drawRanges(false,31,0,3,0,4,false).quads);
            var hierarchy=mesh.cachePlan().display().voxelFeatures();
            for(int t=1;t<4;t++) {
                var actual=mesh.drawRanges(false,31,0,t,0,0,true);
                var expected=hierarchy.original(t,0,31,0,0);
                assertArrayEquals(expected.first,actual.first);assertArrayEquals(expected.count,actual.count);
                var proxy=hierarchy.proxies[t];if(proxy==null)continue;
                var ranges=proxy.range(31,0,0,0);
                for(int i=0;i<ranges.first.length;i++)for(int q=ranges.first[i];q<ranges.first[i]+ranges.count[i];q++)
                    assertNotEquals(row,mesh.quads()[q*12+6]&65535,"grass is never filled into opaque voxel boxes");
            }
        }
    }

    @Test void pairedWorkerCostIncludesPackingAndAdditionalResidency() throws Exception {
        var rows=new ArrayList<String>();rows.add("mesh,round,mode,nanos,allocated,storage_bytes,retained_bytes");
        for(var forest:AlgorithmSupport.forests().subList(0,2)) {
            int[] words=forest.words()[0].clone();
            for(int p=6;p<words.length;p+=12)if(Set.of(31,40,41,42,88,91,94,167).contains(words[p]&65535))words[p]|=PredictionPackedMesh.FLAG_CUTOUT;
            long[][] nanos=new long[2][7],allocated=new long[2][7],storage=new long[2][7];
            for(int round=-3;round<7;round++)for(int order=0;order<2;order++) {
                int mode=Math.floorMod(round+order,2);
                if(mode==0)System.setProperty("vss.disablePredictionVoxelLod","true");else System.clearProperty("vss.disablePredictionVoxelLod");
                long id=Thread.currentThread().getId(),bytes=AlgorithmSupport.THREADS.getThreadAllocatedBytes(id),start=System.nanoTime();
                var mesh=PredictionPackedMesh.terrainRecords(words,forest.span()/forest.spacing());mesh.prepareGpuStorage();mesh.prepareStorage();
                long elapsed=System.nanoTime()-start;bytes=AlgorithmSupport.THREADS.getThreadAllocatedBytes(id)-bytes;
                if(round>=0) {
                    nanos[mode][round]=elapsed;allocated[mode][round]=bytes;storage[mode][round]=mesh.gpuStorageBytes();
                    rows.add(forest.id()+","+round+","+mode+","+elapsed+","+bytes+","+mesh.gpuStorageBytes()+","+mesh.retainedHeapBytes());
                }
                assertTrue(mesh.compressed());
            }
            System.clearProperty("vss.disablePredictionVoxelLod");
            System.out.printf(Locale.ROOT,"VOXEL_WORKER_AB id=%d CPU_ms=%.3f->%.3f allocated_MiB=%.3f->%.3f GPU_MiB=%.3f->%.3f%n",
                    forest.id(),AlgorithmSupport.median(nanos[0])/1e6,AlgorithmSupport.median(nanos[1])/1e6,
                    AlgorithmSupport.median(allocated[0])/1048576.,AlgorithmSupport.median(allocated[1])/1048576.,
                    AlgorithmSupport.median(storage[0])/1048576.,AlgorithmSupport.median(storage[1])/1048576.);
        }
        AlgorithmSupport.save("voxel-worker-paired.csv",rows);
    }

    static int[] building() {
        int[] words=new int[6*32*32*12];int q=0;
        for(int face=0;face<6;face++)for(int a=0;a<32;a++)for(int b=0;b<32;b++) {
            int p=q++*12;
            for(int c=0;c<4;c++) {
                int u=(c==1||c==2?1:0),v=(c>=2?1:0);
                int x=face<2?4+a+u:face<4?face==3?36:4:4+a+u;
                int y=face<2?face==1?1:-31:-31+b+v;
                int z=face<2?4+b+v:face<4?4+a+u:face==5?36:4;
                int shift=(c&1)*16;
                words[p+c/2]|=x<<shift;words[p+2+c/2]|=z<<shift;words[p+4+c/2]|=(32768+y*4)<<shift;
            }
            int flags=face<2?face==0?PredictionPackedMesh.FLAG_BANK:0:(face<4?1:2)<<24;
            if(face>=2&&(face&1)!=0)flags|=PredictionPackedMesh.FLAG_FACE_POSITIVE;
            words[p+6]=flags;
            words[p+7]=words[p+9]=words[p+11]=(a+b)%2==0?0x886644:0x997755;
            words[p+10]=words[p+7]|PredictionPackedMesh.FEATURE;
            words[p+8]=b*40+a;
        }
        return words;
    }
}
