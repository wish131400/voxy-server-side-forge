package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/** Worker-only sparse surface voxels derived from the immutable prediction mesh.
 * The exact terrain, water, baked UV models and source mesh remain independent.
 * Each parent chooses one existing material; no camera/world access or retained coordinates. */
final class PredictionFeatureVoxels {
    private static final int STRIDE = PredictionPackedMesh.STRIDE_INTS;
    private static final int MAX_CELLS = 262_144, MAX_VISITS = 2_097_152;
    private static final int[] DX = {0,0,-1,1,0,0}, DY = {-1,1,0,0,0,0}, DZ = {0,0,0,0,-1,1};
    private PredictionFeatureVoxels() { }

    static PredictionDisplayGeometry.Result build(int[] source, int[] first, int[] count, int axis,
            PredictionVegetationRanges vegetation, PredictionDisplayGeometry.Result base) {
        if (vegetation == null || Boolean.getBoolean("vss.disablePredictionVoxelLod")
                || Boolean.getBoolean("vss.disablePredictionDisplayLod")) return base;
        checkCancelled();
        int total = Arrays.stream(count).sum(), features = 0, span = 0;
        boolean cutout = false;
        if (total < 128 || total > 1_048_576 || axis < 1) return base;
        int[] bounds = new int[6];
        for (int q = 0; q < total; q++) {
            bounds(source, q * STRIDE, bounds);
            span = Math.max(span, Math.max(bounds[3], bounds[5]));
            if (eligible(source, q * STRIDE, bounds)) {
                features++;cutout |= !opaque(source,q);
            }
            if ((q & 1023) == 0) checkCancelled();
        }
        span = (span + 15) / 16;
        if (features < 128 || span < 1 || span > 65535) return base;
        var removedFirst = new IntArrayList(); var removedCount = new IntArrayList();
        for (int q = 0; q < base.words().length / STRIDE; q++) {
            bounds(base.words(), q * STRIDE, bounds);
            if (!eligible(base.words(), q * STRIDE, bounds)) continue;
            int n = removedFirst.size();
            if (n > 0 && removedFirst.getInt(n-1) + removedCount.getInt(n-1) == q)
                removedCount.set(n-1, removedCount.getInt(n-1)+1);
            else {
                if(n>=128)return base; // Bound range metadata and every mask composition.
                removedFirst.add(q); removedCount.add(1);
            }
        }
        var cells = new Long2IntOpenHashMap(); cells.defaultReturnValue(-1);
        int visits = 0;
        for (int q = 0; q < total; q++) {
            int p = q * STRIDE;
            bounds(source, p, bounds);
            if (!eligible(source, p, bounds)) continue;
            int normal = normal(source[p + 6]);
            boolean positive = positive(source[p + 6], normal);
            int x0 = Math.floorDiv(bounds[0], 16), y0 = Math.floorDiv(bounds[1], 16), z0 = Math.floorDiv(bounds[2], 16);
            int x1 = Math.floorDiv(bounds[3] - 1, 16), y1 = Math.floorDiv(bounds[4] - 1, 16), z1 = Math.floorDiv(bounds[5] - 1, 16);
            int cell = Math.floorDiv(bounds[normal] - (positive ? 1 : 0), 16);
            if (normal == 0) x0 = x1 = cell;
            else if (normal == 1) y0 = y1 = cell;
            else z0 = z1 = cell;
            long volume = (long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1);
            if (volume < 0 || volume > MAX_VISITS - visits) return base;
            visits += (int)volume;
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                choose(cells, key(x,y,z), q, source);
                if (cells.size() > MAX_CELLS) return base;
            }
            if ((q & 1023) == 0) checkCancelled();
        }
        @SuppressWarnings("unchecked")
        Long2IntOpenHashMap[] levels = new Long2IntOpenHashMap[4];
        for (int tier = 1; tier <= 3; tier++) {
            var parent = new Long2IntOpenHashMap(); parent.defaultReturnValue(-1);
            for (var entry : cells.long2IntEntrySet()) {
                long k = entry.getLongKey();
                choose(parent, key(Math.floorDiv(x(k),2), Math.floorDiv(y(k),2), Math.floorDiv(z(k),2)), entry.getIntValue(), source);
            }
            levels[tier] = parent; cells = parent; checkCancelled();
        }
        int budget = Math.min(262_144, total / 2), extras = 0;
        int[][] variants = new int[4][];
        PredictionScanMerge.Result[] candidates = new PredictionScanMerge.Result[4];
        boolean[] attempted = new boolean[4];
        PredictionVegetationRanges[] plans = new PredictionVegetationRanges[4];
        int[] terrainTiers = new int[4], selected = new int[4];
        for(int t=1;t<4;t++)if(Float.isFinite(base.errors()[t]))terrainTiers[t]=t;
        float[] errors = base.errors().clone();
        var removed = new PredictionDrawRanges(removedFirst.toIntArray(), removedCount.toIntArray());
        // Every distinct existing terrain representation keeps its slot and
        // threshold. Raising its error to fit foliage would regress terrain LOD.
        // Only empty/repeated slots may introduce a new threshold.
        for (int tier = 1; tier <= 3; tier++) {
            boolean spare = !Float.isFinite(base.errors()[tier]) || repeated(base,tier);
            float next = Float.POSITIVE_INFINITY;
            if (spare) for (int t=tier+1;t<4;t++)
                if (Float.isFinite(base.errors()[t]) && !repeated(base,t)) next=Math.min(next,base.errors()[t]);
            for (int step=1;step<=3;step++) {
                int level=spare?step:4-step;
                // Alpha surfaces need an additional coverage margin, beyond
                // the geometric diagonal, before their shape may change.
                float error=(float)(Math.sqrt(3)*(1<<level)*(cutout?2:1));
                if (!spare && error>errors[tier]) continue;
                int ground=tier;
                if (spare) {
                    if (Float.isFinite(base.errors()[tier])) error=Math.max(error,base.errors()[tier]);
                    if (tier>1 && Float.isFinite(errors[tier-1])) error=Math.max(error,errors[tier-1]);
                    if (error>=next) continue;
                    ground=0;
                    for (int t=1;t<=tier;t++)
                        if (Float.isFinite(base.errors()[t]) && base.errors()[t]<=error) ground=t;
                }
                if (!attempted[level]) {
                    attempted[level]=true;
                    candidates[level]=mesh(levels[level],source,span,axis,1<<level,budget);
                }
                var variant=candidates[level];
                if (variant==null || !benefits(base,vegetation,ground,removed,variant)) continue;
                int added=variants[level]==null?variant.words().length/STRIDE:0;
                if (added>budget-extras) continue;
                variants[level]=variant.words();extras+=added;selected[tier]=level;terrainTiers[tier]=ground;
                if (spare) errors[tier]=error;
                break;
            }
        }
        if (extras == 0) return base;
        int[] words = Arrays.copyOf(base.words(), base.words().length + extras * STRIDE);
        int cursor = base.words().length / STRIDE;
        var stored=new PredictionVegetationRanges[4];
        for (int level = 1; level <= 3; level++) if (variants[level] != null) {
            var variant=candidates[level];
            stored[level]=PredictionVegetationRanges.arrange(variants[level],variant.first(),variant.count(),
                    new boolean[0],new boolean[0],new boolean[0]);
            System.arraycopy(variants[level],0,words,cursor*STRIDE,variants[level].length);
            stored[level].offset(cursor);cursor+=variants[level].length/STRIDE;
        }
        for (int tier=1;tier<4;tier++) plans[tier]=stored[selected[tier]];
        return new PredictionFeatureVoxelRanges(base.metadataOnly(), vegetation,
                removed, plans, errors,terrainTiers).result(words);
    }

    private static boolean repeated(PredictionDisplayGeometry.Result base,int tier) {
        if (tier<2 || !Float.isFinite(base.errors()[tier]) || base.errors()[tier]!=base.errors()[tier-1]) return false;
        for (int mask=0;mask<32;mask++) {
            var a=base.range(tier,0,mask,0,0);var b=base.range(tier-1,0,mask,0,0);
            if (a==null || b==null || !Arrays.equals(a.first,b.first) || !Arrays.equals(a.count,b.count)) return false;
        }
        return true;
    }

    private static boolean benefits(PredictionDisplayGeometry.Result base,PredictionVegetationRanges source,
            int tier,PredictionDrawRanges removed,PredictionScanMerge.Result proxy) {
        int saved=0,replaced=0,added=proxy.words().length/STRIDE;
        // Exact/cross plants are excluded from voxelization. These removed
        // surfaces therefore do not depend on aquatic/plant density settings.
        for (int g=0;g<VssLodFaceGroup.COUNT;g++) {
            var range=tier==0?null:base.range(tier,0,1<<g,0,0);
            if (range==null) range=source.range(1<<g,0,0,0);
            int n=0,r=0;
            for (int i=0;i<range.first.length;i++) {
                int lo=range.first[i],hi=lo+range.count[i];
                while(r<removed.first.length && removed.first[r]+removed.count[r]<=lo)r++;
                for(int j=r;j<removed.first.length && removed.first[j]<hi;j++)
                    n+=Math.max(0,Math.min(hi,removed.first[j]+removed.count[j])-Math.max(lo,removed.first[j]));
            }
            if (proxy.count()[g]>n) return false;
            saved+=n-proxy.count()[g];replaced+=n;
        }
        return saved>=64 && added<=replaced*.75;
    }

    private static PredictionScanMerge.Result mesh(Long2IntOpenHashMap cells, int[] source,
            int span, int axis, int size, int budget) {
        if (budget < 1) return null;
        var words = new IntArrayList(); int[] counts = new int[VssLodFaceGroup.COUNT];
        int[] record = new int[STRIDE];
        long[] keys = cells.keySet().toLongArray(); Arrays.sort(keys);
        for (long k : keys) {
            int q = cells.get(k), p = q * STRIDE;
            int bx = x(k)*size, by = y(k)*size, bz = z(k)*size;
            int x0 = Math.max(0,bx), x1 = Math.min(span,bx+size), z0 = Math.max(0,bz), z1 = Math.min(span,bz+size);
            if (x0 >= x1 || z0 >= z1 || by < -8192 || by+size > 8191) return null;
            for (int face = 0; face < 6; face++) {
                int other = cells.get(key(x(k)+DX[face],y(k)+DY[face],z(k)+DZ[face]));
                if (other >= 0 && (opaque(source,other) || (source[other*STRIDE+6]&65535)==(source[p+6]&65535))) continue;
                // Allow scanline merging to earn admission before rejecting the layer.
                if (words.size() / STRIDE >= Math.max(4096,Math.min(262144,budget*4L))) return null;
                face(source,p,face,x0,by,z0,x1,by+size,z1,axis,span,record);
                words.addElements(words.size(),record); counts[group(face)]++;
            }
            if ((words.size() & 4095) == 0) checkCancelled();
        }
        int[] first = new int[counts.length]; int at = 0;
        for (int g = 0; g < counts.length; g++) { first[g]=at;at+=counts[g]; }
        int[] sorted = new int[words.size()], next = first.clone();
        for (int p = 0; p < words.size(); p += STRIDE) {
            int flags = words.getInt(p+6), normal = normal(flags);
            int g = normal == 1 ? 0 : normal == 0 ? (positive(flags,normal)?4:3) : (positive(flags,normal)?2:1);
            System.arraycopy(words.elements(),p,sorted,next[g]++*STRIDE,STRIDE);
        }
        var merged = PredictionScanMerge.mergeDisplay(sorted,first,counts,axis,Math.max(16,span*16/axis));
        return merged.words().length / STRIDE <= budget ? merged : null;
    }

    private static void face(int[] source,int p,int face,int x0,int y0,int z0,int x1,int y1,int z1,
                             int axis,int span,int[] result) {
        // Reuse one canonical record; no per-face corner arrays on the worker.
        Arrays.fill(result,0);
        int xMask=face<2?6:face<4?(face==3?15:0):6;
        int yMask=face<2?(face==1?15:0):12;
        int zMask=face<2?12:face<4?6:face==5?15:0;
        for (int c=0;c<4;c++) {
            int shift=(c&1)*16;
            result[c/2]|=((xMask&(1<<c))==0?x0:x1)<<shift;
            result[2+c/2]|=((zMask&(1<<c))==0?z0:z1)<<shift;
            result[4+c/2]|=(((yMask&(1<<c))==0?y0:y1)*4+32768)<<shift;
        }
        int flags=source[p+6]&65535;
        if (!opaque(source,p/STRIDE)) flags|=PredictionPackedMesh.FLAG_CUTOUT;
        if(face==0) flags|=PredictionPackedMesh.FLAG_BANK;
        if(face>=2) flags|=(face<4?1:2)<<PredictionPackedMesh.FLAGS_AXIS_SHIFT;
        if(face>=2&&(face&1)!=0) flags|=PredictionPackedMesh.FLAG_FACE_POSITIVE;
        result[6]=flags;
        int color=source[p+7]&0xf0ffffff;
        result[7]=color;result[9]=color;result[10]=color|PredictionPackedMesh.FEATURE|PredictionPackedMesh.DISPLAY_APPROXIMATE;result[11]=color;
        int spacing=Math.max(1,span/axis);
        result[8]=Math.min(axis-1,z0/spacing)*axis+Math.min(axis-1,x0/spacing);
    }
    private static int group(int face) { return face<2?0:face==2?3:face==3?4:face==4?1:2; }
    private static boolean opaque(int[] source,int q) { return (source[q*STRIDE+6]&(PredictionPackedMesh.FLAG_CUTOUT|PredictionPackedMesh.FLAG_UNSHADED))==0; }
    private static void choose(Long2IntOpenHashMap map,long key,int candidate,int[] source) {
        int old=map.get(key);
        // Opaque contents win; ties use stable source order, never the mutable
        // atlas row number or a numeric average of unrelated materials.
        if(old<0 || opaque(source,candidate)&&!opaque(source,old)
                || opaque(source,candidate)==opaque(source,old)&&candidate<old) map.put(key,candidate);
    }
    private static int normal(int flags) { int n=flags>>>PredictionPackedMesh.FLAGS_AXIS_SHIFT&3;return n==0?1:n==1?0:2; }
    private static boolean positive(int flags,int n) { return n==1?(flags&PredictionPackedMesh.FLAG_BANK)==0:(flags&PredictionPackedMesh.FLAG_FACE_POSITIVE)!=0; }
    private static void bounds(int[] w,int p,int[] b) {
        Arrays.fill(b,0,3,Integer.MAX_VALUE);Arrays.fill(b,3,6,Integer.MIN_VALUE);
        for(int c=0;c<4;c++) for(int a=0;a<3;a++) {
            int value=PredictionDisplayGeometry.coordinate(w,p,c,a);
            b[a]=Math.min(b[a],value);b[a+3]=Math.max(b[a+3],value);
        }
    }
    private static boolean eligible(int[] w,int p,int[] b) {
        int f=w[p+6];
        if(!orderable(w,p) || b[0]<0 || b[2]<0 || b[1]<-131072 || b[4]>131056) return false;
        int n=normal(f);
        if(b[n]!=b[n+3]) return false;
        for(int c=0;c<4;c++) for(int a=0;a<3;a++) {
            int v=PredictionDisplayGeometry.coordinate(w,p,c,a);
            if(v!=b[a]&&v!=b[a+3]) return false;
        }
        return true;
    }
    /** Stable worker cohort, also used by the source range arranger. Exact
     * exceptions must not alternate with every leaf face in command membership. */
    static boolean orderable(int[] w,int p) {
        int f=w[p+6];
        return (w[p+10]&(PredictionPackedMesh.FEATURE|PredictionPackedMesh.PREVIEW))==PredictionPackedMesh.FEATURE
                && (f&(PredictionPackedMesh.FLAG_MODEL_UV|PredictionPackedMesh.FLAG_UNSHADED|(3<<PredictionPackedMesh.FLAGS_FLUID_SHIFT)))==0
                && (f>>>PredictionPackedMesh.FLAGS_AXIS_SHIFT&3)!=3 && (w[p+9]&0x06000000)==0;
    }
    private static long key(int x,int y,int z) { return (x&0x1fffffL)|((z&0x1fffffL)<<21)|((long)y<<42); }
    private static int x(long k) { return (int)(k&0x1fffff); }
    private static int z(long k) { return (int)(k>>>21&0x1fffff); }
    private static int y(long k) { return (int)(k>>42); }
    private static void checkCancelled() { if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException(); }
}
