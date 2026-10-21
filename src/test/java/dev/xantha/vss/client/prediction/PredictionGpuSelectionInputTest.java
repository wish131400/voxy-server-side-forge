package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionGpuSelectionInputTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void cameraAndHorizonUpdatesNormalizeStaleResidentFacesWithoutRepublishingOnTurns() throws Exception {
        var draw = PredictionPreparedPassLocalTest.draw(0,0,PredictionPreparedPassLocalTest.mesh());
        var source = List.of(draw); var input = new PredictionGpuSelectionInput();
        var camera = new Vec3(-10,100,-10);
        input.update(source,camera,4096);
        var candidates = input.candidates(); assertEquals(1,candidates.size());
        assertEquals(VssLodFaceGroup.ALL,candidates.get(0).faces());
        assertSame(draw.gpu(),candidates.get(0).gpu()); assertSame(draw.allowed(),candidates.get(0).allowed());
        assertFalse(input.allBatched(),"nonresident GPU slices must disable selection");
        var ranges = new java.util.IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges>();
        ranges.put(candidates.get(0), new PredictionDrawRanges(new int[]{0}, new int[]{1024}));
        assertEquals(1024,input.visibleQuads(source,ranges));
        assertEquals(0,input.visibleQuads(List.of(),ranges),"turning toward an empty scene must immediately stop selection");
        assertEquals(-1,input.visibleQuads(List.of(PredictionPreparedPassLocalTest.draw(1,0,PredictionPreparedPassLocalTest.mesh())),ranges));
        input.update(source,camera,4096); assertSame(candidates,input.candidates());
        long rebuilt = input.rebuiltRecords();
        input.update(source,new Vec3(10000,100,10000),64); assertSame(candidates,input.candidates());
        assertEquals(rebuilt, input.rebuiltRecords(), "camera movement cannot re-encode static GPU input");
        input.update(source,camera,4096); assertEquals(1,input.candidates().size());
        input.clear(); assertTrue(input.candidates().isEmpty()); assertEquals(0,input.quads());
    }
    @Test void withdrawnGpuPayloadReleasesResidentReferencesOnTheNextInput() throws Exception {
        var draw = PredictionPreparedPassLocalTest.draw(0,0,PredictionPreparedPassLocalTest.mesh());
        var input = new PredictionGpuSelectionInput(); input.update(List.of(draw),Vec3.ZERO,4096);
        assertEquals(1,input.candidates().size());
        PredictionPreparedPassLocalTest.payload(draw.gpu(),null);
        input.update(List.of(draw),Vec3.ZERO,4096); assertTrue(input.candidates().isEmpty());
        input.update(List.of(),Vec3.ZERO,4096); assertEquals(0,input.terrainTiles());
    }
}
