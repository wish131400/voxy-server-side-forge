package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real ownership/residency/seam regressions for a child smaller than one source cell. */
class PredictionSubCellCoverageTest {
    static final VssLodLayout LAYOUT=VssLodLayout.of(8192,2,true,false);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void offCenterAndCenteredChildrenReplaceOnlyTheirActualFootprint() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        for(boolean centered:new boolean[]{false,true}) {
            var fine=tile(centered?-56:-63,centered?-24:-30,0,64,1,96,true);
            var snapshot=snapshot(parent,fine);
            var p=coverage(parent,snapshot);var f=coverage(fine,snapshot);
            assertNotNull(p.grid());assertFalse(p.grid().complete(128));
            long parentArea=0,overlap=0,missing=0;
            for(int z=parent.baseBlockZ();z<0;z+=64) for(int x=parent.baseBlockX();x<0;x+=64) {
                boolean a=owns(p,parent,x+32,z+32,false);
                boolean inside=x>=fine.baseBlockX() && x<fine.baseBlockX()+64 && z>=fine.baseBlockZ() && z<fine.baseBlockZ()+64;
                boolean b=inside && owns(f,fine,x+32,z+32,false);
                if(a) parentArea+=4096;if(a&&b) overlap+=4096;if(!a&&!b) missing+=4096;
            }
            assertEquals(0,overlap,"coarse top must not cover a child away from the source-cell centre");
            assertEquals(0,missing,"a centred child must not erase the rest of its 1024-block source cell");
            assertEquals(8192L*8192-4096,parentArea);
        }
    }

    @Test void uploadsAndRetractionPreserveFallbackAndIndependentFeatureOwnership() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        var child=tile(-63,-30,0,64,1,96,false);
        var residency=new PredictionRenderResidency();
        var initial=snapshot(parent);residency.retain(initial);residency.uploaded(parent);
        var pending=snapshot(parent,child);residency.retain(pending);
        assertTrue(owns(coverage(parent,residency.snapshot(pending)),parent,-4000,-1888,false),"CPU-ready child cannot suppress GPU fallback");
        residency.uploaded(child);
        var mixed=coverage(parent,residency.snapshot(pending));
        assertFalse(owns(mixed,parent,-4000,-1888,false));
        assertTrue(owns(mixed,parent,-4000,-1888,true),"ground-only child cannot erase parent vegetation");
        var ready=tile(-63,-30,0,64,1,96,true);var complete=snapshot(parent,ready);
        residency.retain(complete);residency.uploaded(ready);
        assertFalse(owns(coverage(parent,residency.snapshot(complete)),parent,-4000,-1888,true));
        assertTrue(residency.hasEvictionFallback(ready));
        residency.evict(ready.key());residency.retain(initial);
        assertTrue(owns(coverage(parent,residency.snapshot(initial)),parent,-4000,-1888,false));
        assertFalse(owns(mixed,parent,-4000,-1888,false),"published masks remain immutable after retraction");
    }

    @Test void multipleLevelsAndNegativeCoordinatesAgreeWithTheSelectedOwner() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        var medium=tile(-32,-15,1,16,8,128,true);
        var fine=tile(-63,-30,0,64,1,96,true);
        var snapshot=snapshot(parent,medium,fine);
        var masks=Map.of(parent,coverage(parent,snapshot),medium,coverage(medium,snapshot),fine,coverage(fine,snapshot));
        for(int z=-2048;z<-1792;z+=16) for(int x=-4224;x<-3840;x+=16) {
            var owner=snapshot.coveringTileAtDetail(Math.floorDiv(x,16),Math.floorDiv(z,16),0);
            int count=0;
            for(var tile:masks.keySet()) if(x>=tile.baseBlockX() && z>=tile.baseBlockZ()
                    && x<tile.baseBlockX()+tile.spanBlocks() && z<tile.baseBlockZ()+tile.spanBlocks()) {
                boolean owned=owns(masks.get(tile),tile,x,z,false);if(owned) count++;
                assertEquals(owner==tile,owned);
            }
            assertEquals(1,count);
        }
    }

    @Test void worldSizedParentOnlyAllocatesNodesNearAResidentChild() {
        var parent=tile(-1,-1,19,8,4194304,160,true);
        var fine=tile(-63,-30,0,64,1,96,true);
        var grid=PredictionCoverageGrid.resolve(parent,List.of(fine),(x,z)->x>=-4032 && x<-3968 && z>=-1920 && z<-1856?0:192);
        assertNotNull(grid);assertTrue(grid.size()<300,"sparse ownership must not create a 524288-square bitmap: nodes="+grid.size());
        assertFalse(grid.ground(grid.nodeAt(-4000-parent.baseBlockX(),-1888-parent.baseBlockZ())));
    }

    @Test void movingTheChildWithinOneSourceCellInvalidatesSeamsEvenWhenRootBitsAreIdentical() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        var oldChild=tile(-63,-30,0,64,1,96,true);var newChild=tile(-62,-30,0,64,1,96,true);
        var before=coverage(parent,snapshot(parent,oldChild));var after=coverage(parent,snapshot(parent,newChild));
        assertArrayEquals(before.allowed(),after.allowed());assertFalse(PredictionCoverageGrid.same(before.grid(),after.grid()));
        var seams=new PredictionLodSeams();
        seams.update(List.of(new PredictionLodSeams.Surface(parent,before.allowed(),before.grid()),
                new PredictionLodSeams.Surface(oldChild,coverage(oldChild,snapshot(parent,oldChild)).allowed())));
        seams.apply(List.of(new PredictionLodSeams.Surface(parent,after.allowed(),after.grid()),
                new PredictionLodSeams.Surface(newChild,coverage(newChild,snapshot(parent,newChild)).allowed())),Set.of(oldChild.key()));
        assertSame(parent,seams.index().at(-4000,-1888).tile());
        assertSame(newChild,seams.index().at(-3936,-1888).tile());
    }

    @Test void aChildOnASourceCellEdgeReplacesOnlyTheMatchingSideWallSegment() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        var child=tile(-64,-30,0,64,1,96,true);
        var snapshot=snapshot(parent,child);var mask=coverage(parent,snapshot);var fine=coverage(child,snapshot);
        var seams=new PredictionLodSeams();
        seams.update(List.of(new PredictionLodSeams.Surface(parent,mask.allowed(),mask.grid()),new PredictionLodSeams.Surface(child,fine.allowed())));
        int adjacent=mask.grid().nodeAt(4095,6304),unrelated=mask.grid().nodeAt(4095,6500);
        assertTrue(mask.grid().ground(adjacent));assertEquals(64,mask.grid().step(adjacent));
        byte[] boundary=seams.boundaryMask(parent.key());
        assertNotEquals(0,boundary[adjacent]&2,"only the segment beside the child can release its old positive-X wall");
        assertEquals(0,boundary[unrelated]&2,"a distant piece of the same source-cell wall keeps its fallback");
    }

    @Test void anAdjacentChildRefreshesOnlyTheCoarseNeighborAndRestoresItsOuterWall() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        var distant=tile(-3,-1,7,8,1024,160,true);
        var child=tile(0,-30,0,64,1,96,true);
        var residency=new PredictionRenderResidency();var initial=snapshot(parent,distant);
        residency.retain(initial);residency.uploaded(parent);residency.uploaded(distant);
        var before=residency.snapshot(initial);residency.drainChanges();
        var source=snapshot(parent,distant,child);residency.retain(source);residency.uploaded(child);
        var after=residency.snapshot(source);
        assertTrue(after.epoch(parent.key())>before.epoch(parent.key()),"an adjacent finer tile changes the coarse wall's ownership topology");
        assertEquals(before.epoch(distant.key()),after.epoch(distant.key()),"distant ownership stays cached");
        assertEquals(Set.of(parent.key(),child.key()),residency.drainChanges());
        var mask=coverage(parent,after);var fine=coverage(child,after);
        assertNotNull(mask.grid());assertTrue(mask.grid().complete(128));
        var seams=new PredictionLodSeams();
        seams.update(List.of(new PredictionLodSeams.Surface(parent,mask.allowed(),mask.grid()),
                new PredictionLodSeams.Surface(child,fine.allowed())));
        int adjacent=mask.grid().nodeAt(8191,6304),unrelated=mask.grid().nodeAt(8191,6500);
        assertEquals(64,mask.grid().step(adjacent));
        assertNotEquals(0,seams.boundaryMask(parent.key())[adjacent]&2);
        assertEquals(0,seams.boundaryMask(parent.key())[unrelated]&2);
        residency.evict(child.key());residency.retain(initial);
        var restored=residency.snapshot(initial);
        assertTrue(restored.epoch(parent.key())>after.epoch(parent.key()));
        assertNull(coverage(parent,restored).grid(),"withdrawing the neighbor restores the original wall mask");
    }

    @Test void seamIndexAndTransactionFollowTheSubCellBoundaryAndRevertWithoutStaleWalls() {
        var parent=tile(-1,-1,7,8,1024,160,true);
        var fine=tile(-63,-30,0,64,1,96,true);
        var snapshot=snapshot(parent,fine);var p=coverage(parent,snapshot);var f=coverage(fine,snapshot);
        var a=new PredictionLodSeams.Surface(parent,p.allowed(),p.grid());
        var b=new PredictionLodSeams.Surface(fine,f.allowed());
        var seams=new PredictionLodSeams();var patches=seams.update(List.of(a,b));
        assertSame(fine,seams.index().at(-4000,-1888).tile());
        assertSame(parent,seams.index().at(-4033,-1888).tile());
        assertEquals(4*64*64D,patches.stream().mapToDouble(v->area(v.mesh())).sum(),"only the real child perimeter connects");
        var worker=new PredictionSeamPreparation(Runnable::run);
        worker.request(1,Map.of(parent.key(),new PredictionSeamPreparation.Surface(parent,p.allowed(),p.features(),p.grid()),
                fine.key(),new PredictionSeamPreparation.Surface(fine,f.allowed(),f.features())),List.of());
        var result=worker.poll(1);assertNotNull(result);
        assertEquals(0,result.masks().get(parent.key())[p.grid().nodeAt(4192,6304)] & 128);
        var restored=coverage(parent,snapshot(parent));
        worker.request(2,Map.of(parent.key(),new PredictionSeamPreparation.Surface(parent,restored.allowed(),restored.features())),List.of());
        var next=worker.poll(2);assertNotNull(next);assertTrue(next.lod().isEmpty());
        assertEquals(128,next.masks().get(parent.key())[6*8+4] & 128);
    }

    static PredictionRenderer.TileCoverage coverage(PredictionTile tile,RenderSnapshot snapshot) {
        return PredictionRenderer.resolveCoverage(tile,-252,-120,1,snapshot,null);
    }
    private static double area(PredictionPackedMesh mesh) {
        double sum=0;int[] words=mesh.quads();
        for(int q=0;q<mesh.terrainQuadCount();q++) {
            int i=q*12;
            double length=Math.abs((words[i]&65535)-(words[i]>>>16))+Math.abs((words[i+2]&65535)-(words[i+2]>>>16));
            sum+=length*(1<<((words[i+6]>>>16)&15))*((words[i+4]&65535)-(words[i+5]&65535))/4D;
        }
        return sum;
    }
    static boolean owns(PredictionRenderer.TileCoverage mask,PredictionTile tile,int x,int z,boolean features) {
        if(mask.grid()!=null) {
            int node=mask.grid().nodeAt(x-tile.baseBlockX(),z-tile.baseBlockZ());
            return node>=0 && (features?mask.grid().features(node):mask.grid().ground(node));
        }
        int cell=PredictionLodSeams.cellAt(tile,x,z);return (features?mask.features():mask.allowed())[cell];
    }
    static RenderSnapshot snapshot(PredictionTile... tiles) {
        var map=new LinkedHashMap<PredictionTileKey,PredictionTile>();for(var t:tiles) map.put(t.key(),t);
        return new RenderSnapshot(Level.OVERWORLD,LAYOUT,map,Map.of());
    }
    static PredictionTile tile(int x,int z,int lod,int axis,int step,int y,boolean complete) {
        return tile(x,z,lod,axis,step,y,complete,false);
    }
    static PredictionTile tile(int x,int z,int lod,int axis,int step,int y,boolean complete,boolean water) {
        int count=(axis+1)*(axis+1);var samples=new ClientColumnSample[count];
        var sample=PredictionParentLodTest.sample(y);
        if(water) sample=new ClientColumnSample(y,y+2,0,1,0,0,0,0,1,0,0,1,1,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        Arrays.fill(samples,sample);int[] heights=new int[count];Arrays.fill(heights,y);
        var mesh=PredictionMeshBuilder.build(samples,null,63,0xff3f76e4,step,axis+1,false).compactForRendering();
        var tile=new PredictionTile(new PredictionTileKey(Level.OVERWORLD,x,z,lod),heights,heights,samples,mesh,
                new PredictionDepthBound(y,y),0,lod+1,axis,step);
        mesh.prepareGpuPayload(tile);mesh.surfaceReadyForRendering=complete;return tile;
    }
}
