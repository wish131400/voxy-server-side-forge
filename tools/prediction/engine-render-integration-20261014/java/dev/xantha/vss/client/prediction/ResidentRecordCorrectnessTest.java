package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class ResidentRecordCorrectnessTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void clearCoverage() throws Exception { PredictionPreparedPassLocalTest.coverage().clear(); }

    @Test void unrelatedTrackedEpochRetainsCandidatesAndPreparedRangesWithoutAllocatingNormalizedDraws() throws Exception {
        var resident = ResidentRecordIntegrationTest.fixture(128, PredictionPreparedPassLocalTest.mesh());
        var source = List.copyOf(resident); var input = new PredictionGpuSelectionInput();
        var camera = new Vec3(-10,100,-10); input.update(source,camera,16384);
        var candidates = input.candidates(); var pass = new PredictionPreparedPassLocalTest.Pass(false);
        pass.prepare(candidates,1,camera); long built = pass.counter("rangeBuilds"), normalized = input.rebuiltRecords();
        var unrelated = PredictionPreparedPassLocalTest.mesh(); unrelated.trackSubmission();
        for (int i=0;i<12;i++) {
            long epoch=PredictionPackedMesh.submissionEpoch(); unrelated.morph(null,0,0);
            assertEquals(epoch+1,PredictionPackedMesh.submissionEpoch());
            input.update(source,camera,16384); pass.prepare(input.candidates(),1,camera);
            assertSame(candidates,input.candidates()); assertEquals(normalized,input.rebuiltRecords());
            assertEquals(built,pass.counter("rangeBuilds"));
        }
        pass.clear.invoke(pass.value); input.clear();
    }

    @Test void localPayloadAndGpuVersionsInvalidateOnlyTheirRecordAndWithdrawalRestoresImmediately() throws Exception {
        var resident = ResidentRecordIntegrationTest.fixture(32,PredictionPreparedPassLocalTest.mesh());
        var source = List.copyOf(resident); var input=new PredictionGpuSelectionInput(); var old=new EngineBeforeSelectionInput();
        var camera=new Vec3(-10,80,-10); input.update(source,camera,16384); old.update(source,camera,16384);
        var candidates=input.candidates(); long built=input.rebuiltRecords();
        var local=PredictionPreparedPassLocalTest.mesh(); local.trackSubmission();
        payload(resident.get(7),local); input.update(source,camera,16384); old.update(source,camera,16384);
        ResidentRecordIntegrationTest.equivalent(old,input); assertEquals(built+1,input.rebuiltRecords());
        assertSame(candidates,input.candidates(),"local versions must not force unrelated immutable keys to change");
        built=input.rebuiltRecords(); local.morph(new float[]{0},0,0);
        input.update(source,camera,16384); old.update(source,camera,16384);
        ResidentRecordIntegrationTest.equivalent(old,input); assertEquals(built+1,input.rebuiltRecords());
        built=input.rebuiltRecords(); payload(resident.get(7),null);
        input.update(source,camera,16384); old.update(source,camera,16384);
        ResidentRecordIntegrationTest.equivalent(old,input); assertEquals(31,input.candidates().size());
        assertEquals(built+1,input.rebuiltRecords());
        payload(resident.get(7),local); input.update(source,camera,16384); old.update(source,camera,16384);
        ResidentRecordIntegrationTest.equivalent(old,input); assertEquals(32,input.candidates().size());
        var ranges=new IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>();
        for(var draw:input.candidates())ranges.put(draw,new PredictionDrawRanges(new int[]{0},new int[]{1}));
        assertEquals(32,input.visibleQuads(source,ranges));
        assertEquals(-1,input.visibleQuads(List.of(ResidentRecordIntegrationTest.replacement(resident.get(0))),ranges));
        input.clear(); assertEquals(-1,input.visibleQuads(source,ranges));
    }

    @Test void movingHorizonVerticalFacesAndPartialOwnershipMatchTheFrozenImplementation() throws Exception {
        var resident=ResidentRecordIntegrationTest.fixture(256,PredictionPreparedPassLocalTest.mesh());
        var input=new PredictionGpuSelectionInput(); var old=new EngineBeforeSelectionInput();
        var random=new Random(20261014);
        for(int frame=0;frame<64;frame++) {
            if(frame%4==0) { var draw=resident.get(frame);boolean[] allowed=draw.allowed().clone();allowed[0]=false;
                resident.set(frame,new PredictionRenderer.Draw(draw.tile(),draw.gpu(),allowed,draw.features(),false,draw.faces(),0,draw.bounds(),false)); }
            if(frame%5==0)Collections.rotate(resident,1);
            Vec3 camera=new Vec3(random.nextDouble()*8192-100,frame%2==0?-100:1000,random.nextDouble()*128-64);
            double horizon=frame%3==0?64:4096;
            var snapshot=List.copyOf(resident);input.update(snapshot,camera,horizon);old.update(snapshot,camera,horizon);
            ResidentRecordIntegrationTest.equivalent(old,input);
            for(var draw:input.candidates())assertEquals(draw.completeOwnership(),draw.allowed()[0]);
        }
        var snapshot=List.copyOf(resident);input.update(snapshot,Vec3.ZERO,Double.NaN);old.update(snapshot,Vec3.ZERO,Double.NaN);
        ResidentRecordIntegrationTest.equivalent(old,input);
    }

    @Test void bulkReplacementUsesDirectPathAndAllReferencesShrinkOrReleaseWithTheScene() throws Exception {
        var resident=ResidentRecordIntegrationTest.fixture(4096,PredictionPreparedPassLocalTest.mesh());
        var input=new PredictionGpuSelectionInput();var old=new EngineBeforeSelectionInput();var camera=new Vec3(-10,80,-10);
        var snapshot=List.copyOf(resident);input.update(snapshot,camera,16384);
        assertEquals(4096,input.retainedRecords());
        resident.replaceAll(ResidentRecordIntegrationTest::replacement);snapshot=List.copyOf(resident);
        input.update(snapshot,camera,16384);old.update(snapshot,camera,16384);ResidentRecordIntegrationTest.equivalent(old,input);
        assertEquals(0,input.retainedRecords(),"bulk changes bypass per-record metadata");assertEquals(0,input.recordCapacity());
        // A subsequent unrelated resource change can warm the local cache again.
        var unrelated=PredictionPreparedPassLocalTest.mesh();unrelated.trackSubmission();unrelated.morph(null,0,0);
        input.update(snapshot,camera,16384);assertEquals(4096,input.retainedRecords());
        var small=List.of(resident.get(1),resident.get(12));input.update(small,camera,16384);
        assertEquals(2,input.retainedRecords());assertTrue(input.recordCapacity()<=16);
        var field=PredictionGpuSelectionInput.class.getDeclaredField("normalized");field.setAccessible(true);
        var map=(Map<?,?>)field.get(input);assertEquals(2,map.size());assertTrue(map.keySet().containsAll(small));
        input.clear();assertEquals(0,input.retainedRecords());assertEquals(0,input.recordCapacity());assertTrue(input.candidates().isEmpty());
        assertTrue(((Map<?,?>)field.get(input)).isEmpty());
        input.update(small,camera,16384);old.update(small,camera,16384);ResidentRecordIntegrationTest.equivalent(old,input);
    }

    @Test void oversizedSceneRetainsTheExactDirectFallbackWithoutUnboundedMetadata() throws Exception {
        var draw=PredictionPreparedPassLocalTest.draw(0,0,PredictionPreparedPassLocalTest.mesh());
        var source=Collections.nCopies(16385,draw);var input=new PredictionGpuSelectionInput();var old=new EngineBeforeSelectionInput();
        input.update(source,Vec3.ZERO,4096);old.update(source,Vec3.ZERO,4096);ResidentRecordIntegrationTest.equivalent(old,input);
        assertEquals(0,input.retainedRecords());assertEquals(0,input.recordCapacity());
        input.clear();
    }

    private static void payload(PredictionRenderer.Draw draw,PredictionPackedMesh packed) throws Exception {
        PredictionPreparedPassLocalTest.payload(draw.gpu(),packed);
        var changed=PredictionGpuTile.class.getDeclaredMethod("submissionChanged");changed.setAccessible(true);changed.invoke(draw.gpu());
    }
}
