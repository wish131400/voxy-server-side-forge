package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyNodeIndex;
import dev.xantha.vss.compat.StrictVoxyPipeline;

class PredictionVisibilityStabilityTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void reset() { StrictLodVisibility.reset(); }

    private static StrictVoxyNodeIndex residentNodes() throws Exception {
        for (var entry : Map.<String,Object>of("dimension", Level.OVERWORLD, "uploadedNodes", true,
                "failed", false, "renderHookSeen", true, "meshPipeline", new StrictVoxyPipeline()).entrySet()) {
            var field = StrictLodVisibility.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(null,entry.getValue());
        }
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD,4,4,512);
        var field = StrictLodVisibility.class.getDeclaredField("NODES"); field.setAccessible(true);
        var nodes = (StrictVoxyNodeIndex)field.get(null);
        nodes.update(1,StrictVoxyNodeIndex.key(4,0,0,0),1);
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD,2,60,2,3,80,3),
                "fixture must exercise actual resident Voxy coverage without a visible frame");
        return nodes;
    }

    @Test void residentNodesCannotDeleteGroundWaterOrRepresentativesWithoutCurrentFrameVisibility() throws Exception {
        residentNodes();
        var camera = new Vec3(4, 80, 4);
        for (int extra : new int[]{0, PredictionPackedMesh.FEATURE,
                PredictionPackedMesh.FEATURE | PredictionPackedMesh.PREVIEW}) {
            var mesh = PredictionAuthoritativeCoverageViewTest.mesh(extra);
            var draw = PredictionPreparedPassLocalTest.draw(0,0,mesh);
            var pass = new PredictionPreparedPassLocalTest.Pass(false);
            pass.prepare(List.of(draw),1,camera);
            assertEquals(mesh.drawRanges(false,VssLodFaceGroup.ALL).quads,pass.ranges(draw).quads,
                    "resident is not rendered, flags="+extra);
        }
        int[] words = PredictionAuthoritativeCoverageViewTest.mesh(0).cacheWords().clone();
        words[6] |= 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT;
        int[] counts = new int[VssLodFaceGroup.COUNT]; counts[VssLodFaceGroup.HORIZONTAL]=1;
        var water = new PredictionPackedMesh(words,64,0,new int[VssLodFaceGroup.COUNT],
                new int[VssLodFaceGroup.COUNT],new int[VssLodFaceGroup.COUNT],counts,false,0);
        var draw = PredictionPreparedPassLocalTest.draw(0,0,water);
        var pass = new PredictionPreparedPassLocalTest.Pass(true); pass.prepare(List.of(draw),1,camera);
        assertEquals(1,pass.ranges(draw).quads,"water also reaches the actual frame depth");
    }

    @Test void incomingResidentAncestorsCannotMakeTheSamePlantAlternateAcrossFrames() throws Exception {
        var nodes = residentNodes();
        var mesh = PredictionAuthoritativeCoverageViewTest.mesh(PredictionPackedMesh.FEATURE | PredictionPackedMesh.PREVIEW);
        var draw = PredictionPreparedPassLocalTest.draw(0,0,mesh);
        var pass = new PredictionPreparedPassLocalTest.Pass(false); var draws=List.of(draw);
        var camera=new Vec3(4,80,4);
        pass.prepare(draws,1,camera); var ranges=pass.ranges(draw); long encoded=pass.counter("rangeBuilds");
        for (int frame = 0; frame < 120; frame++) {
            nodes.update(1,(frame&1)==0 ? -1 : StrictVoxyNodeIndex.key(4,0,0,0),1);
            pass.prepare(draws,1,camera);
            assertSame(ranges,pass.ranges(draw),
                    "upload metadata changed but the current image still needs the plant, frame=" + frame);
            assertEquals(encoded,pass.counter("rangeBuilds"),"static commands cannot be rebuilt by incoming ancestor metadata");
        }
    }

    @Test void residentVoxySnapshotCannotRetireAnUploadedPredictionBeforeItsReplacementDraws() {
        var layout = VssLodLayout.of(8192, 6, true, true);
        var tile = PredictionCoverageWorkTest.tile(0, 0, 0, layout);
        var parent = PredictionCoverageWorkTest.tile(0, 0, 1, layout);
        var state = new PredictionRenderResidency();
        var initial = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(tile.key(), tile), Map.of());
        state.retain(initial); state.uploaded(tile);
        var next = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout,
                Map.of(parent.key(), parent), Map.of(), null, Set.of(tile.key()));
        state.retain(next);
        assertTrue(state.contains(tile), "resident Voxy metadata cannot replace an unuploaded prediction parent");
        state.uploaded(parent); state.retain(next);
        assertFalse(state.contains(tile), "a real uploaded prediction replacement still allows ordinary retirement");
    }
}
