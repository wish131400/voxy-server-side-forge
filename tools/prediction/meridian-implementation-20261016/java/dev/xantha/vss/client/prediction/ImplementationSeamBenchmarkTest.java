package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Identical update sequence on independently compiled before/after production. */
class ImplementationSeamBenchmarkTest {
    @BeforeAll static void bootstrap(){AlgorithmBootstrap.bootstrapMinecraft();}
    @Test void replayUnchangedAndChangingInputsAgainstAFullOracle() throws Exception {
        var rows=new ArrayList<String>();rows.add("scenario,round,cpu_ns,allocated_bytes,updated_surfaces,patch_updates");
        for(String scenario:List.of("same_mesh_version","equal_summary","changed_height","changed_coverage")) {
            var current=new ArrayList<PredictionLodSeams.Surface>();
            for(int z=0;z<4;z++)for(int x=0;x<4;x++)current.add(PredictionLodSeamsTest.surface(AlgorithmSupport.tile(x-2,z-2,64+((x+z)&1)*16,1)));
            var seams=new PredictionLodSeams();seams.update(current);
            long[] ns=new long[61],allocated=new long[61],updated=new long[61];
            for(int round=-24;round<61;round++) {
                int i=Math.floorMod(round,current.size());var old=current.get(i);var tile=old.tile();
                if(scenario.equals("equal_summary"))tile=AlgorithmSupport.tile(tile.key().tileX(),tile.key().tileZ(),tile.heights()[0],100+round);
                else if(scenario.equals("changed_height"))tile=AlgorithmSupport.tile(tile.key().tileX(),tile.key().tileZ(),64+Math.floorMod(round,3)*16,100+round);
                else tile=AlgorithmSupport.version(tile,round+100);
                var allowed=old.allowed().clone();
                if(scenario.equals("changed_coverage"))for(int z=0;z<64;z++)allowed[z*64]=!allowed[z*64];
                var replacement=new PredictionLodSeams.Surface(tile,allowed);long before=seams.updatedSurfaces();
                var measurement=AlgorithmSupport.measure(()->seams.apply(List.of(replacement),Set.of()).patches().size());current.set(i,replacement);
                if(round>=0){ns[round]=measurement.nanos();allocated[round]=measurement.bytes();updated[round]=seams.updatedSurfaces()-before;
                    rows.add(scenario+","+round+","+measurement.nanos()+","+measurement.bytes()+","+updated[round]+","+AlgorithmSupport.sink);}
                if(round%8==0)assertOracle(seams,current);
            }
            assertOracle(seams,current);
            System.out.println("IMPLEMENTED_SEAM "+scenario+" median_ns="+AlgorithmSupport.median(ns)+" allocated="+AlgorithmSupport.median(allocated)+" updated="+AlgorithmSupport.median(updated));
        }
        AlgorithmSupport.save("seam-before-after.csv",rows);
    }
    private static void assertOracle(PredictionLodSeams seams,List<PredictionLodSeams.Surface> current) {
        var oracle=new PredictionLodSeams();var wanted=new HashMap<PredictionTileManager.PredictionTileKey,int[]>();
        for(var patch:oracle.update(current))wanted.put(patch.surface().tile().key(),patch.mesh().quads());
        assertEquals(wanted.size(),seams.patchesSnapshot().size());
        for(var patch:seams.patchesSnapshot())assertArrayEquals(wanted.get(patch.surface().tile().key()),patch.mesh().quads());
        for(var surface:current)assertArrayEquals(oracle.boundaryMask(surface.tile().key()),seams.boundaryMask(surface.tile().key()));
    }
}
