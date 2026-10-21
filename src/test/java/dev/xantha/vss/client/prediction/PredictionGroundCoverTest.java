package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;

class PredictionGroundCoverTest {
    @BeforeEach void prepare() { ClientTerrainSamplerTest.bootstrapMinecraft(); VssLodSpriteTable.close(); }
    @AfterEach void clear() { VssLodSpriteTable.close(); }

    @Test void screenSizeProgressionHysteresisZoomAndInvalidViewsRestoreDetail() {
        var lod = new PredictionGroundCoverLod();
        int[] distances = {200,300,500,1000,2000};
        for (int i = 0; i < distances.length; i++) {
            lod.update(distances[i], 1000, 256, false);
            assertEquals(i, lod.tier(), "distance=" + distances[i]);
        }
        lod.update(1100,1000,256,false); assertEquals(4,lod.tier());
        lod.update(990,1000,256,false); assertEquals(3,lod.tier());
        lod.update(1100,1000,256,false); assertEquals(3,lod.tier(),"do not reenter inside hysteresis band");
        lod.update(1300,1000,256,false); assertEquals(4,lod.tier());
        assertFalse(lod.update(1300,1000,256,false),"a static view keeps its cached selection");
        lod.update(1300,16000,256,false); assertEquals(0,lod.tier(),"magnified projection restores plants");
        lod.update(2000,1000,256,false);
        lod.update(2000,1000,256,true); assertEquals(0,lod.tier(),"handoff/morph/source protection");
        lod.update(2000,1000,256,false);
        lod.update(2000,1000,2400,false); assertEquals(0,lod.tier(),"configured near radius");
        for (double value : new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            lod.update(2000,1000,256,false);
            lod.update(2000,value,256,false); assertEquals(0,lod.tier());
            lod.update(value,1000,256,false); assertEquals(0,lod.tier());
        }
    }

    @Test void warmMaterialsIdentifySmallPlantsAndProtectTreesTallStalksAndSharedRows() throws Exception {
        try (var contents = PredictionVegetationMaterialTest.contents("block/vss_ground_cover")) {
            var sprite = new PredictionVegetationMaterialTest.Sprite(contents);
            for (Block block : new Block[]{Blocks.GRASS,Blocks.TALL_GRASS,Blocks.FERN,Blocks.LARGE_FERN,
                    Blocks.DANDELION,Blocks.POPPY,Blocks.SUNFLOWER,Blocks.ROSE_BUSH}) {
                VssLodSpriteTable.close();
                int row = PredictionVegetationMaterialTest.restore(sprite,block);
                boolean[] snapshot = VssLodSpriteTable.groundCoverSprites();
                assertTrue(snapshot[row],block.toString());
                assertSame(snapshot,VssLodSpriteTable.groundCoverSprites());
                PredictionVegetationMaterialTest.restore(sprite,Blocks.OAK_LEAVES);
                assertFalse(VssLodSpriteTable.groundCoverSprites()[row],"shared leaf row must remain");
                assertTrue(snapshot[row],"published worker snapshot is immutable");
            }
            for (Block block : new Block[]{Blocks.OAK_LEAVES,Blocks.KELP_PLANT,Blocks.SEAGRASS,Blocks.STONE,
                    Blocks.BAMBOO,Blocks.SUGAR_CANE,Blocks.OAK_SAPLING,Blocks.WHEAT}) {
                VssLodSpriteTable.close();
                int row=PredictionVegetationMaterialTest.restore(sprite,block);
                assertFalse(VssLodSpriteTable.groundCoverSprites()[row],block.toString());
            }
        }
        boolean[] old=VssLodSpriteTable.groundCoverSprites();
        try(var contents=PredictionVegetationMaterialTest.contents("block/short_grass")) {
            int row=VssLodSpriteTable.registerSprite(new PredictionVegetationMaterialTest.Sprite(contents));
            assertTrue(VssLodSpriteTable.groundCoverSprites()[row]);assertFalse(old[row]);
        }
    }

    @Test void terminalRangesKeepWholePlantsAndNeverRemoveModelsWaterTreesOrGround() {
        int[] words=new int[(1024+8)*12];
        for(int q=0;q<1024;q++) {
            int column=q/4,face=q%2,height=q/2%2;
            PredictionCrossEncodingTest.cross(words,q,column%16+face,column/16,33088+height*16,
                    column%16+1-face,column/16+1,33104+height*16,q%4+1);
            words[q*12+6]=words[q*12+6]&~65535|1;words[q*12+8]=column;
        }
        for(int i=0;i<8;i++) {
            int p=(1024+i)*12;
            PredictionSpatialOrderTest.quad(words,1024+i,7,7,80,1|PredictionPackedMesh.FLAG_UNSHADED);
            words[p+10]=PredictionPackedMesh.FEATURE;
            if(i==0) words[p+6]|=PredictionPackedMesh.FLAG_MODEL_UV;
            if(i==1) words[p+10]|=PredictionPackedMesh.PREVIEW;
            if(i==2) words[p+6]|=1<<PredictionPackedMesh.FLAGS_FLUID_SHIFT;
            if(i==3) words[p+10]=0;
            if(i==4) words[p+6]&=~PredictionPackedMesh.FLAG_UNSHADED;
            if(i>=5) words[p+6]=words[p+6]&~65535|i-3;
            words[p+8]=256+i;
        }
        boolean[] small=new boolean[256];small[1]=true;
        var ranges=PredictionVegetationRanges.arrange(words,new int[]{0},new int[]{1032},new boolean[256],new boolean[256],small);
        assertTrue(ranges.hasGroundCover());
        int[] expected={1024,256,64,16,0};
        for(int tier=0;tier<5;tier++) {
            var selected=ranges.range(1,0,0,tier);assertEquals(expected[tier]+8,selected.quads);
            assertSame(selected,ranges.range(1,0,0,tier),"selection is cached");
            int[] parts=new int[264];
            for(int r=0;r<selected.first.length;r++)for(int q=selected.first[r];q<selected.first[r]+selected.count[r];q++)
                parts[words[q*12+8]]++;
            for(int i=0;i<256;i++)assertTrue(parts[i]==0||parts[i]==4,"both directions and vertical halves stay together");
            for(int i=256;i<264;i++)assertEquals(1,parts[i],"protected record="+i);
        }
        var bounds=new PredictionMeshletBounds(words,new boolean[256],small);
        var plan=new PredictionGpuRepresentations(new PredictionDrawRanges[]{ranges.range(1,0,0,0),ranges.range(1,0,0,0),
                ranges.range(1,0,0,0),ranges.range(1,0,0,0)});
        var segments=plan.clip(plan.union,bounds,false);
        for(int i=0;i<segments.kinds().length;i++) {
            int kind=segments.kinds()[i];
            for(int q=segments.ranges().first[i];q<segments.ranges().first[i]+segments.ranges().count[i];q++) {
                assertEquals(words[q*12+8]<256,(kind&4)!=0,"only small-plant commands can disappear");
                if((kind&4)!=0)assertEquals(PredictionVegetationRanges.through(words,q*12),kind>>3);
            }
        }
    }

    @Test void productionPassRestoresNearPartialScopeAndSeamGeometryAndWarmCacheRebuildsTiers() throws Exception {
        try(var contents=PredictionVegetationMaterialTest.contents("block/vss_ground_pass")) {
            int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.GRASS);
            var packed=PredictionPackedMesh.terrainRecords(PredictionVegetationLodGpuTest.plants(row),128);
            assertTrue(packed.hasGroundCoverLod());
            var restored=PredictionPackedMesh.terrainRecords(packed.cacheWords(),128);
            for(int t=0;t<5;t++)assertEquals(packed.drawRanges(false,31,0,0,0,t,false).quads,
                    restored.drawRanges(false,31,0,0,0,t,false).quads);
            assertEquals(0,restored.drawRanges(false,31,0,0,0,4,false).quads);
            assertEquals(32768,packed.quadCount(),"no new GPU geometry copies");
            var tile=PredictionLodSeamsTest.tile(0,0,1,128);
            var gpu=new PredictionGpuTile(tile.key());
            var field=PredictionGpuTile.class.getDeclaredField("packed");field.setAccessible(true);field.set(gpu,packed);
            boolean[] all=new boolean[128*128];Arrays.fill(all,true);
            var draw=new PredictionRenderer.Draw(tile,gpu,all,false,31,0,new AABB(0,80,0,128,82,128));
            var scale=PredictionRenderer.class.getDeclaredField("selectionScale");scale.setAccessible(true);
            var scope=PredictionRenderer.class.getDeclaredField("selectionScoping");scope.setAccessible(true);
            double old=scale.getDouble(null);boolean oldScope=scope.getBoolean(null);
            try {
                scale.setDouble(null,1000);scope.setBoolean(null,false);
                var pass=new PredictionPreparedPassLocalTest.Pass(false);
                var far=new Vec3(3000,100,64);
                pass.prepare(List.of(draw),1,far);assertNull(pass.ranges(draw),"fully omitted plants produce no submission entry");
                pass.prepare(List.of(draw),1,new Vec3(64,100,64));assertEquals(32768,pass.ranges(draw).quads);
                boolean[] mixed=all.clone();mixed[0]=false;
                var partial=new PredictionRenderer.Draw(tile,gpu,mixed,false,31,0,draw.bounds());
                pass.prepare(List.of(partial),1,far);assertEquals(32768,pass.ranges(partial).quads);
                scope.setBoolean(null,true);pass.prepare(List.of(draw),1,far);assertEquals(32768,pass.ranges(draw).quads);
                scope.setBoolean(null,false);
                var seam=new PredictionRenderer.Draw(tile,gpu,all,true,31,0,draw.bounds());
                pass.prepare(List.of(seam),1,far);assertEquals(32768,pass.ranges(seam).quads);
            } finally {scale.setDouble(null,old);scope.setBoolean(null,oldScope);}
        }
    }
}
