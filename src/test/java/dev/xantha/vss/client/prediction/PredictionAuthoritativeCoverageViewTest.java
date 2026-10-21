package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;

class PredictionAuthoritativeCoverageViewTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static PredictionExactCoverageMask.Snapshot mask() {
        var index = new PredictionExactCoverageIndex();
        for (int z=-8; z<=8; z++) for (int x=-8; x<=8; x++) index.confirm(Level.OVERWORLD,x,z,0);
        return index.snapshot(Level.OVERWORLD,0,0,64,ExactCoverageGate.SETTLE_NANOS);
    }
    static PredictionPackedMesh mesh(int extraFlags) {
        int y=32768+64*4;
        return PredictionPackedMesh.terrainRecords(new int[]{33|34<<16,33|34<<16,33|33<<16,34|34<<16,
                y|y<<16,y|y<<16,0,0xffffff,0,0xffffff,extraFlags|0xffffff,0xffffff},64);
    }

    @Test void sceneAndSubmissionUseTheSameProofAndRestoreWithoutCameraMotion() {
        var mesh=mesh(0); var coverage=new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        var camera=new Vec3(4,68,4); var matrix=new Matrix4f();
        var calls=new AtomicInteger();
        var plan=new PredictionVisiblePlan<Integer,PredictionPackedMesh>(ignored -> new AABB(32,64,32,48,65,48),
                packed -> { calls.incrementAndGet(); return !coverage.fullyOwned(Level.OVERWORLD,packed,0,0); });
        coverage.update(null,camera,480,Level.OVERWORLD); plan.put(1,mesh);
        assertEquals(List.of(mesh),plan.selectStable(camera,matrix,matrix,null));
        var ranges=mesh.drawRanges(false,VssLodFaceGroup.ALL);
        coverage.update(mask(),camera,480,Level.OVERWORLD);
        plan.refreshEligibility(coverage.revision(),mesh.submissionRevision());
        assertTrue(plan.selectStable(camera,matrix,matrix,null).isEmpty());
        assertTrue(plan.residents().isEmpty(),"GPU selection must not bypass scene ownership");
        assertEquals(0,coverage.filter(mesh,ranges,0,0,false,VssLodFaceGroup.ALL).quads);
        assertEquals(1,plan.retainedCount(),"keep the fallback resource for withdrawal");
        int before=calls.get();
        for(int frame=0;frame<120;frame++) {
            plan.refreshEligibility(coverage.revision(),mesh.submissionRevision());
            plan.selectStable(camera,new Matrix4f().rotateY(frame*.01f),matrix,null);
            assertTrue(plan.residents().isEmpty());
        }
        assertEquals(before,calls.get(),"turns must not repeat ownership proofs");
        coverage.update(null,camera,480,Level.OVERWORLD);
        plan.refreshEligibility(coverage.revision(),mesh.submissionRevision());
        assertEquals(List.of(mesh),plan.selectStable(camera,matrix,matrix,null));
        assertEquals(List.of(mesh),plan.residents());
        assertSame(ranges,coverage.filter(mesh,ranges,0,0,false,VssLodFaceGroup.ALL));
    }

    @Test void verticalDistanceDimensionAndMorphNeverReuseAnUnsafeProof() {
        var coverage=new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()); var mesh=mesh(0); var camera=new Vec3(4,68,4);
        coverage.update(mask(),camera,480,Level.OVERWORLD);
        assertTrue(coverage.fullyOwned(Level.OVERWORLD,mesh,0,0));
        assertFalse(coverage.fullyOwned(Level.NETHER,mesh,0,0));
        assertFalse(coverage.fullyOwned(null,mesh,0,0));
        mesh.morph(new float[]{0},-64,1000);
        assertFalse(coverage.fullyOwned(Level.OVERWORLD,mesh,0,0));
        mesh.morph(null,0,0);
        coverage.update(mask(),new Vec3(4,1000,4),480,Level.OVERWORLD);
        assertFalse(coverage.fullyOwned(Level.OVERWORLD,mesh,0,0));
        coverage.update(mask(),camera,0,Level.OVERWORLD);
        assertFalse(coverage.fullyOwned(Level.OVERWORLD,mesh,0,0));
    }

    @Test void completeFeaturesAndPreviewsRemainConservativeWholeMeshCandidates() {
        var coverage=new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        coverage.update(mask(),new Vec3(4,68,4),480,Level.OVERWORLD);
        assertFalse(coverage.fullyOwned(Level.OVERWORLD,mesh(PredictionPackedMesh.FEATURE),0,0));
        assertFalse(coverage.fullyOwned(Level.OVERWORLD,mesh(PredictionPackedMesh.FEATURE|PredictionPackedMesh.PREVIEW),0,0));
    }

    @Test void contentChangeRestoresAHiddenMeshEvenWithUnchangedCoverage() {
        var coverage=new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()); var mesh=mesh(0); var camera=new Vec3(4,68,4);
        coverage.update(mask(),camera,480,Level.OVERWORLD);
        var plan=new PredictionVisiblePlan<Integer,PredictionPackedMesh>(ignored -> new AABB(32,64,32,48,65,48),
                packed -> !coverage.fullyOwned(Level.OVERWORLD,packed,0,0));
        plan.put(1,mesh); plan.refreshEligibility(coverage.revision(),mesh.submissionRevision());
        assertTrue(plan.selectStable(camera,new Matrix4f(),new Matrix4f(),null).isEmpty());
        mesh.morph(new float[]{0},-64,1000);
        plan.refreshEligibility(coverage.revision(),mesh.submissionRevision());
        assertEquals(List.of(mesh),plan.selectStable(camera,new Matrix4f(),new Matrix4f(),null));
        assertEquals(List.of(mesh),plan.residents());
        plan.remove(1); assertEquals(0,plan.retainedCount()); assertEquals(0,plan.residentCount());
        plan.put(2,mesh); plan.clear(); assertEquals(0,plan.retainedCount()); assertTrue(plan.residents().isEmpty());
    }

    @Test void wideningCandidatesAfterWithdrawalRestoresTheRetainedDraw() {
        var eligible=new HashSet<Integer>(); var camera=Vec3.ZERO; var matrix=new Matrix4f();
        var plan=new PredictionVisiblePlan<Integer,Integer>(ignored -> new AABB(0,0,0,1,1,1),eligible::contains);
        plan.put(1,1); assertEquals(0,plan.residentCount());
        plan.refreshEligibility(1,0); assertTrue(plan.selectStable(camera,matrix,matrix,null,Set.of()).isEmpty());
        eligible.add(1); plan.refreshEligibility(2,0);
        assertTrue(plan.selectStable(camera,matrix,matrix,null,Set.of()).isEmpty());
        assertEquals(List.of(1),plan.selectStable(camera,matrix,matrix,null,Set.of(1)));
        var residents=plan.residents(); plan.refreshEligibility(3,0); assertSame(residents,plan.residents());
    }

    @Test void wholeFrameReuseRejectsCoverageWithdrawalAndMeshChanges() {
        var frame=new PredictionRenderer.PreparedFrame(List.of(),10,20);
        assertTrue(frame.current(10,20)); assertFalse(frame.current(11,20)); assertFalse(frame.current(10,21));
    }
}
