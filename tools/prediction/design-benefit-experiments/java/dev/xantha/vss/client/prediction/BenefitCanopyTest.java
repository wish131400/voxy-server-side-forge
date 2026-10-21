package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BenefitCanopyTest {
    @Test void quantifyAddedOccupancyLostMinorityMaterialsAndGapClosureInsteadOfAcceptingOnlyFaceSavings() {
        var rows=new ArrayList<String>();rows.add("shape,source_leaves,coarse_voxels,added_occupied_cells,material_mismatches,bridged_gaps,exact_trunks");
        for(int shape=0;shape<3;shape++) {
            var source=BenefitCanopyProxy.forest(shape);var coarse=BenefitCanopyProxy.reduce(source,2);
            var exact=new HashMap<String,Integer>();var proxy=new HashMap<String,Integer>();
            var exactTrunks=new HashSet<String>();var proxyTrunks=new HashSet<String>();
            for(var v:source){String key=v.x()+":"+v.y()+":"+v.z();if(v.sprite()==2)exactTrunks.add(key);else exact.put(key,v.sprite());}
            for(var v:coarse){if(v.sprite()==2){proxyTrunks.add(v.x()+":"+v.y()+":"+v.z());continue;}
                for(int y=0;y<2;y++)for(int z=0;z<2;z++)for(int x=0;x<2;x++)proxy.put((v.x()+x)+":"+(v.y()+y)+":"+(v.z()+z),v.sprite());}
            int added=0,mismatch=0,gaps=0;
            for(var e:proxy.entrySet())if(!exact.containsKey(e.getKey()))added++;else if(!exact.get(e.getKey()).equals(e.getValue()))mismatch++;
            if(shape>0)for(int z=8;z<128;z+=16)for(int x=8;x<128;x+=16)for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++) {
                String at=(x+dx)+":91:"+(z+dz),next=(x+dx)+":92:"+(z+dz);
                if(!exact.containsKey(at)&&!exact.containsKey(next)&&proxy.containsKey(at)&&proxy.containsKey(next))gaps++;
            }
            assertEquals(exactTrunks,proxyTrunks);assertTrue(proxy.keySet().containsAll(exact.keySet()));
            System.out.printf("BENEFIT_CANOPY_QUALITY shape=%d sourceLeaves=%d coarse=%d extraOccupied=%d materialMismatch=%d bridgedLayerGaps=%d trunksExact=true%n",
                    shape,exact.size(),coarse.size(),added,mismatch,gaps);
            if(shape==1)assertTrue(gaps>0,"layer protection is a real unmet requirement of this candidate");
            if(shape==2)assertTrue(mismatch>0,"majority-vote material loss is measured");
            rows.add(shape+","+exact.size()+","+coarse.size()+","+added+","+mismatch+","+gaps+",true");
        }
        try{CompletionGpuFixture.save("benefit-canopy-quality.csv",rows);}catch(Exception e){throw new AssertionError(e);}
    }
}
