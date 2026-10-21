package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PredictionHeightProtectionTest {
    @Test void deepFloorCanSimplifyWithoutChangingSourceOrWater() {
        int[] words = flooded(40, 80), original = words.clone();
        var baseline = build(words, true);
        var candidate = build(words, false);
        assertNull(baseline.ranges(), "column-wide water protection blocks the whole floor");
        assertNotNull(candidate.ranges(), "the distant floor can have its own representation");
        assertArrayEquals(original, Arrays.copyOf(candidate.words(), original.length));
        assertTrue(candidate.words().length <= original.length + 1024 * 6);
        for (int t = 1; t <= 3; t++) if (Float.isFinite(candidate.errors()[t])) {
            var range = candidate.range(t, 0, 1);
            assertTrue(range.quads <= 1024 * .65);
            assertTrue(candidate.errors()[t] <= 6);
        }
    }

    @Test void shorelineAndSupportingGroundRemainProtected() {
        var baseline = build(flooded(80, 80), true);
        var candidate = build(flooded(80, 80), false);
        assertArrayEquals(baseline.words(), candidate.words());
        assertArrayEquals(baseline.errors(), candidate.errors());
        assertNull(candidate.ranges(), "ground intersecting the fluid envelope stays exact");
    }

    @Test void fluidFineYProtectsItsActualHeightWithCoarseHorizontalCoordinates() {
        for (int fluid : new int[]{1, 2}) {
            int[] shoreline = fineFluid(flooded(80, 80), 80, fluid);
            var baseline = build(shoreline, true);
            var candidate = build(shoreline, false);
            assertArrayEquals(baseline.words(), candidate.words());
            assertNull(candidate.ranges(), "independent fine Y must protect water/lava contact");
            int[] deep = fineFluid(flooded(40, 80), 80, fluid);
            assertNotNull(build(deep, false).ranges(), "a floor separated from the real fluid height can simplify");
        }
    }

    @Test void existingUsefulVariantsStayByteForByteIdentical() {
        int[] words = PredictionDisplayGeometryTest.grid(0);
        var baseline = build(words, true); var candidate = build(words, false);
        assertNotNull(baseline.ranges());
        assertArrayEquals(baseline.words(), candidate.words());
        assertArrayEquals(baseline.errors(), candidate.errors());
        for (int t=1;t<4;t++) for(int mask=1;mask<32;mask++) {
            var a=baseline.range(t,0,mask); var b=candidate.range(t,0,mask);
            if(a==null) assertNull(b);
            else { assertArrayEquals(a.first,b.first); assertArrayEquals(a.count,b.count); }
        }
    }

    @Test void protectionIncludesTheEntireDisplacementAndOneBlockMargin() throws Exception {
        Method vertex = PredictionDisplayGeometry.class.getDeclaredMethod("protectedVertex", boolean[].class,
                int[].class,int[].class,int.class,int.class,int.class,int.class,int.class,int.class);
        vertex.setAccessible(true);
        boolean[] cells={true,true,true,true}; int[] low={100,100,100,100}, high={120,120,120,120};
        for(int y:new int[]{76,100,120,144}) assertTrue((boolean)vertex.invoke(null,cells,low,high,2,16,16,y,16,24));
        for(int y:new int[]{75,145,-1000}) assertFalse((boolean)vertex.invoke(null,cells,low,high,2,16,16,y,16,24));
        assertTrue((boolean)vertex.invoke(null,cells,null,null,2,16,16,-1000,16,24),"original protection mode");
    }

    @Test void horizontalMovesCannotCrossAProtectedVolumeAtTheSameHeight() throws Exception {
        Method path = PredictionDisplayGeometry.class.getDeclaredMethod("crossesProtection",boolean[].class,
                int[].class,int[].class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
        path.setAccessible(true);
        boolean[] cells=new boolean[16];int[] low=new int[16],high=new int[16];cells[5]=true;low[5]=100;high[5]=120;
        assertTrue((boolean)path.invoke(null,cells,low,high,4,16,0,110,0,48,48,24));
        assertFalse((boolean)path.invoke(null,cells,low,high,4,16,0,0,0,48,48,24));
        assertTrue((boolean)path.invoke(null,cells,low,high,4,16,48,110,48,0,0,24));
    }

    private static PredictionDisplayGeometry.Result build(int[] words,boolean disabled) {
        String previous=System.getProperty("vss.disablePredictionHeightProtection");
        try {
            System.setProperty("vss.disablePredictionHeightProtection",Boolean.toString(disabled));
            int[][] retained=new int[4][5];for(int[] a:retained)a[0]=1024;
            return PredictionDisplayGeometry.build(words,new int[5],new int[]{1024,0,0,0,0},retained,32,new boolean[256]);
        } finally {
            if(previous==null)System.clearProperty("vss.disablePredictionHeightProtection");
            else System.setProperty("vss.disablePredictionHeightProtection",previous);
        }
    }
    private static int[] flooded(int ground,int water) {
        int[] words=Arrays.copyOf(PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE),2048*12);
        for(int q=0;q<1024;q++) {
            int y=32768+ground*4+(q%2)*4;words[q*12+4]=words[q*12+5]=y|y<<16;
            System.arraycopy(words,q*12,words,(q+1024)*12,12);
            int wet=(q+1024)*12,wy=32768+water*4;words[wet+4]=words[wet+5]=wy|wy<<16;
            words[wet+6]=1<<PredictionPackedMesh.FLAGS_FLUID_SHIFT;
        }
        return words;
    }
    private static int[] fineFluid(int[] words, int height, int fluid) {
        for (int q = 1024; q < 2048; q++) {
            int p = q * 12, y = 32768 + height * 16;
            words[p + 4] = words[p + 5] = y | y << 16;
            words[p + 6] = fluid << PredictionPackedMesh.FLAGS_FLUID_SHIFT | PredictionPackedMesh.FLAG_FLUID_FINE_Y;
        }
        return words;
    }
}
