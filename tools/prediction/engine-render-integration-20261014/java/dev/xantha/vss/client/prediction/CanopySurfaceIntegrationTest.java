package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class CanopySurfaceIntegrationTest {
    static final Set<Integer> LEAVES = Set.of(31,40,41,42,88,91,94,167);
    static int[] leaves() { int[] mask = new int[8]; for (int row : LEAVES) mask[row >> 5] |= 1 << (row & 31); return mask; }
    @Test void capturedSurfaceAggregationCensus() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var rows = new ArrayList<String>(); rows.add("mesh,source_faces,leaf_faces,union_faces,leaf_removed_fraction,projected_additional_bytes,accepted,builder_cpu_ns");
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(
                "tools/prediction/engine-render-20261014/inputs/forest-fixtures.bin"))))) {
            int accepted = 0, n = in.readInt();
            for (int i = 0; i < n; i++) {
                int id = in.readInt(), span = in.readInt(), spacing = in.readInt(); in.readFloat(); in.readFloat();
                for (int mode = 0; mode < 3; mode++) {
                    int[] words = new int[in.readInt()]; for (int j = 0; j < words.length; j++) words[j] = in.readInt();
                    if (mode != 0) continue;
                    int[] counts = new int[5], starts = new int[5];
                    for (int p = 6; p < words.length; p += 12) counts[group(words[p])]++;
                    int at = 0; for (int g = 0; g < 5; g++) { starts[g] = at; at += counts[g]; }
                    int[] sorted = new int[words.length], cursor = starts.clone();
                    int leafCount = 0;
                    for (int p = 0; p < words.length; p += 12) { System.arraycopy(words,p,sorted,cursor[group(words[p+6])]++*12,12); if (LEAVES.contains(words[p+6]&65535)) leafCount++; }
                    var exact = PredictionScanMerge.merge(sorted,starts,counts,span/spacing,spacing,true,16);
                    int[] leafSizes = new int[5], leafStarts = new int[5];
                    var leafMethod = CoverageCanopyGeometry.class.getDeclaredMethod("leaf",int[].class,int.class,int[].class); leafMethod.setAccessible(true);
                    int nLeaf=0; for(int g=0;g<5;g++)for(int q=exact.first()[g];q<exact.first()[g]+exact.count()[g];q++)if((boolean)leafMethod.invoke(null,exact.words(),q*12,leaves()))leafSizes[g]++;
                    for(int g=0;g<5;g++){leafStarts[g]=nLeaf;nLeaf+=leafSizes[g];}
                    int[] leafWords=new int[nLeaf*12];int c=0;
                    for(int g=0;g<5;g++)for(int q=exact.first()[g];q<exact.first()[g]+exact.count()[g];q++)if((boolean)leafMethod.invoke(null,exact.words(),q*12,leaves())){
                        System.arraycopy(exact.words(),q*12,leafWords,c*12,12);leafWords[c*12+9]&=~(1<<24);leafWords[c*12+10]|=1<<25;c++;
                    }
                    var union=PredictionScanMerge.mergeDisplay(leafWords,leafStarts,leafSizes,span/spacing,16);
                    System.out.println("CANOPY_UNION id="+id+" leaves="+nLeaf+" union="+Arrays.stream(union.count()).sum());
                    long start = System.nanoTime();
                    var result = CoverageCanopyGeometry.build(exact.words(),exact.first(),exact.count(),span/spacing,null,leaves());
                    long elapsed = System.nanoTime()-start;
                    // For these exact uniform-material rectangles, the scanline
                    // union preserves both occupied area and source holes.
                    assertEquals(surfaceArea(leafWords), surfaceArea(union.words()));
                    if (result.ranges() != null) {
                        accepted++;
                        assertEquals(multiset(exact.words()),multiset(Arrays.copyOf(result.words(),exact.words().length)),"source records must remain exact");
                        assertTrue(result.words().length <= exact.words().length * 1.25);
                    }
                    long selected = result.ranges() == null ? Arrays.stream(exact.count()).sum() : result.ranges()[1][31].quads;
                    int unionCount=Arrays.stream(union.count()).sum();
                    rows.add(id+","+Arrays.stream(exact.count()).sum()+","+nLeaf+","+unionCount+","+(1-(double)unionCount/nLeaf)+","+unionCount*48L+","+(result.ranges()!=null)+","+elapsed);
                    System.out.println("CANOPY_SURFACE id="+id+" source="+Arrays.stream(exact.count()).sum()+" selected="+selected+" accepted="+(result.ranges()!=null)+" cpu_ms="+elapsed/1e6);
                }
            }
            assertEquals(0, accepted, "these captured surfaces do not justify extra residency; keep production unchanged");
        }
        CompletionGpuFixture.save("canopy-surface-census.csv",rows);
    }
    static Map<String,Long> surfaceArea(int[] words) {
        var result = new TreeMap<String,Long>();
        for (int p = 0; p < words.length; p += 12) {
            int n=PredictionScanMerge.normal(words[p+6]),u=(n+1)%3,v=(n+2)%3;
            long width=Math.abs((long)PredictionScanMerge.coordinate(words,p,2,u)-PredictionScanMerge.coordinate(words,p,0,u));
            long height=Math.abs((long)PredictionScanMerge.coordinate(words,p,2,v)-PredictionScanMerge.coordinate(words,p,0,v));
            String key=words[p+6]+":"+words[p+7]+":"+PredictionScanMerge.coordinate(words,p,0,n);
            result.merge(key,width*height,Long::sum);
        }
        return result;
    }
    static int group(int flags) { boolean p=(flags&(1<<30))!=0; return switch(flags>>>24&3) {case 1 -> p?4:3;case 2 -> p?2:1;default -> 0;}; }
    static Map<List<Integer>,Integer> multiset(int[] words) {
        var result=new HashMap<List<Integer>,Integer>(); for(int p=0;p<words.length;p+=12) {var row=new ArrayList<Integer>(12);for(int j=0;j<12;j++)row.add(words[p+j]);result.merge(row,1,Integer::sum);}return result;
    }
}
