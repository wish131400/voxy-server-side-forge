package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Rejected test-only parent selector: projected cell size cannot prove geometry/content error. */
class PredictionParentSelectionExperimentTest {
    @Test void projectedSpacingAcceptsFlatGroundButCannotGuaranteeOrdinaryContentOrSeams() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var flat=PredictionFeatureHandoffTest.tile(1,8,false,true,1);
        var child=PredictionFeatureHandoffTest.tile(0,1,true,true,2);
        double depth=10000,scale=1475,cellPixels=flat.spacingBlocks()*scale/depth;
        assertTrue(cellPixels<=PredictionDisplayLod.PIXEL_ERROR);
        var snapshot=PredictionFeatureHandoffTest.snapshot(flat,child);
        assertSame(child,snapshot.coveringTileAtDetail(0,0,3));
        var coarse=flat.mesh().gpuPayload();var fine=child.mesh().gpuPayload();
        long parentFeatures=features(coarse),childFeatures=features(fine);
        assertEquals(0,parentFeatures);assertTrue(childFeatures>0);
        var peakWords=PredictionDisplayGeometryTest.grid(0);
        int peak=(9*32+9)*12,peakY=32768+96*4;
        peakWords[peak+4]=peakWords[peak+5]=peakY|peakY<<16;
        var peakMesh=PredictionPackedMesh.terrainRecords(peakWords,64);
        double spikeError=(maximumHeight(peakMesh,false)-maximumHeight(coarse,false))*scale/depth;
        var lowWater=water(64,0,4);var highWater=water(80,1,8);
        double waterError=Math.abs(maximumHeight(lowWater,true)-maximumHeight(highWater,true))*scale/depth;
        assertTrue(spikeError>PredictionDisplayLod.PIXEL_ERROR);
        assertTrue(waterError>PredictionDisplayLod.PIXEL_ERROR);
        var groundOnly=PredictionFeatureHandoffTest.tile(1,8,false,false,3);
        assertFalse(groundOnly.mesh().surfaceReadyForRendering);
        var rows=List.of("case,cell_pixels,error_pixels,missing_features,parent_ready,safe",
                "flat-identical,"+cellPixels+",0,0,true,true",
                "narrow-32-block-height-change,"+cellPixels+","+spikeError+",0,true,false",
                "16-block-water-height-change,"+cellPixels+","+waterError+",0,true,false",
                "fine-only-decoration,"+cellPixels+",unknown,"+childFeatures+",true,false",
                "ground-only-parent,"+cellPixels+",unknown,unknown,false,false",
                "fine-neighbour-seam,"+cellPixels+",unversioned,unknown,true,false");
        var output=Path.of(System.getProperty("vss.followupResults", "build/prediction-completion-results"));Files.createDirectories(output);
        Files.write(output.resolve("parent-selection.csv"),rows);
        System.out.printf(Locale.ROOT,"COMPLETION_PARENT cellPixels=%.4f heightError=%.4f waterError=%.4f features=%d->%d groundQuads=%d->%d decision=reject-uncertified-parent%n",
                cellPixels,spikeError,waterError,childFeatures,parentFeatures,
                fine.drawRanges(false,31).quads,coarse.drawRanges(false,31).quads);
    }
    private static long features(PredictionPackedMesh mesh) {
        var words=mesh.cacheWords();long count=0;
        var ranges=mesh.drawRanges(false,31);
        for(int r=0;r<ranges.first.length;r++)for(int q=ranges.first[r];q<ranges.first[r]+ranges.count[r];q++)
            if((words[q*12+10]&PredictionPackedMesh.FEATURE)!=0)count++;
        return count;
    }
    private static PredictionPackedMesh water(int sea,int lod,int step) {
        int[] heights=new int[17*17];Arrays.fill(heights,32);
        var mesh=PredictionMeshBuilder.build(heights,heights,sea,step).compactForRendering();
        var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,lod);
        var tile=new PredictionTileManager.PredictionTile(key,heights,heights,new ClientColumnSample[heights.length],mesh,
                new PredictionDepthBound(32,sea),0,1,16,step);
        mesh.prepareGpuPayload(tile);return mesh.gpuPayload();
    }
    private static double maximumHeight(PredictionPackedMesh mesh,boolean water) {
        var words=mesh.cacheWords();var ranges=mesh.drawRanges(water,31);double max=Double.NEGATIVE_INFINITY;
        for(int r=0;r<ranges.first.length;r++)for(int q=ranges.first[r];q<ranges.first[r]+ranges.count[r];q++) {
            int p=q*12,flags=words[p+6];boolean fine=(flags&PredictionPackedMesh.FLAG_FINE_COORDINATES)!=0;
            boolean fineFluid=(flags&PredictionPackedMesh.FLAG_FLUID_FINE_Y)!=0&&(flags>>>22&3)!=0;
            for(int c=0;c<4;c++)max=Math.max(max,(((words[p+4+c/2]>>>((c&1)*16))&65535)-32768)/(fine||fineFluid?16D:4D));
        }
        return max;
    }
}
