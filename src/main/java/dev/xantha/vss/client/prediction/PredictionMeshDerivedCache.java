package dev.xantha.vss.client.prediction;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Static worker output only. Camera, coverage, selections and morphs are session state. */
final class PredictionMeshDerivedCache {
    // Bump when ordering, plant classification, display meshing or range semantics change.
    static final int VERSION = 2;
    private static final int GROUPS = VssLodFaceGroup.COUNT;
    private static final String[] SWITCHES = {
            "vss.disableVoxyScanMerge", "vss.disablePredictionSpatialOrder",
            "vss.disablePredictionAquaticLod", "vss.disablePredictionVegetationLod",
            "vss.disablePredictionDisplayLod", "vss.disablePredictionHeightProtection",
            "vss.disablePredictionVoxelLod"
    };

    record Plan(int sourceWords, int policy, byte[] materials, int[][] aquatic,
                PredictionVegetationRanges vegetation, PredictionDisplayGeometry.Result display) {
        boolean compatible(int[] remappedRows, java.util.BitSet referenced) throws IOException {
            for (int row = referenced.nextSetBit(0); row >= 0; row = referenced.nextSetBit(row + 1))
                if (materials[row] < 0) throw new IOException("mesh missing material kind");
            if (policy != PredictionMeshDerivedCache.policy()) return false;
            byte[] current = materialKinds();
            for (int old = 0; old < materials.length; old++) if (materials[old] >= 0) {
                int row = remappedRows[old] == VssLodSpriteTable.FLAT ? 0 : remappedRows[old];
                if (row < 0 || materials[old] != current[row]) return false;
            }
            return true;
        }
    }

    private PredictionMeshDerivedCache() { }

    static int policy() {
        int result = 0;
        for (int i = 0; i < SWITCHES.length; i++) if (Boolean.getBoolean(SWITCHES[i])) result |= 1 << i;
        return result;
    }

    /** Classifications survive row remapping, including shared or exhausted material tables. */
    static byte[] materialKinds() {
        boolean[][] kinds = {VssLodSpriteTable.aquaticSprites(), VssLodSpriteTable.landPlantSprites(),
                VssLodSpriteTable.vegetationSprites(), VssLodSpriteTable.groundCoverSprites(),
                VssLodSpriteTable.displayApproximationSprites()};
        byte[] result = new byte[256];
        for (int bit = 0; bit < kinds.length; bit++) for (int row = 0; row < kinds[bit].length; row++)
            if (kinds[bit][row]) result[row] |= (byte) (1 << bit);
        return result;
    }

    static void write(DataOutputStream out, Plan plan, int[] words) throws IOException {
        out.writeInt(VERSION); out.writeInt(plan.policy());
        var used = new java.util.BitSet(256);
        for (int p = 6; p < words.length; p += PredictionPackedMesh.STRIDE_INTS) used.set(words[p] & 65535);
        for (int row = 0; row < 256; row++) out.writeByte(used.get(row) ? plan.materials()[row] : -1);
        for (int[] tier : plan.aquatic()) for (int count : tier) out.writeInt(count);
        writeVegetation(out, plan.vegetation());
        var voxel = plan.display().voxelFeatures();
        var display = voxel == null ? plan.display() : voxel.base;
        for (float error : display.errors()) out.writeFloat(error);
        writeRanges(out, display.ranges());
        writeVegetationTiers(out, display.vegetation());
        var shared = display.sharedFeatures();
        out.writeBoolean(shared != null);
        if (shared != null) {
            writeRanges(out, shared.cacheFeatures());
            out.writeBoolean(shared.ownsProxyVegetation());
            if (!shared.ownsProxyVegetation()) writeRanges(out, shared.cacheProxies());
            else writeVegetationTiers(out, shared.cacheVegetation());
        }
        out.writeBoolean(voxel != null);
        if (voxel != null) {
            for (float error : voxel.errors) out.writeFloat(error);
            for (int tier : voxel.terrainTiers) out.writeByte(tier);
            out.writeInt(voxel.removed.first.length);
            for (int i=0;i<voxel.removed.first.length;i++) {
                out.writeInt(voxel.removed.first[i]);out.writeInt(voxel.removed.count[i]);
            }
            writeVegetationTiers(out,voxel.proxies);
        }
    }

    static Plan read(ByteBuffer in, int sourceWords, int totalQuads, int[] first, int[] count) throws IOException {
        if (in.getInt() != VERSION) throw new IOException("mesh derived version");
        int policy = in.getInt();
        if ((policy >>> SWITCHES.length) != 0) throw new IOException("mesh derived policy");
        byte[] materials = new byte[256]; in.get(materials);
        for (byte material : materials) if (material < -1 || material > 31) throw new IOException("mesh material kind");
        int[][] aquatic = new int[4][GROUPS];
        for (int tier = 0; tier < 4; tier++) for (int g = 0; g < GROUPS; g++) {
            int n = aquatic[tier][g] = in.getInt();
            if (n < 0 || n > count[g] || tier == 0 && n != count[g]
                    || tier > 0 && n > aquatic[tier - 1][g]) throw new IOException("mesh aquatic prefix");
        }
        int sourceQuads = sourceWords / PredictionPackedMesh.STRIDE_INTS;
        var vegetation = readVegetation(in, 0, sourceQuads);
        for (int g = 0; g < GROUPS; g++) {
            int start = vegetation.cacheFirst()[g * 4], size = 0;
            for (int k = 0; k < 4; k++) size += vegetation.cacheCounts()[0][g * 4 + k];
            if (size != count[g] || size != 0 && start != first[g]) throw new IOException("mesh vegetation group");
        }
        float[] errors = new float[4];
        for (int tier = 0; tier < 4; tier++) {
            float error = errors[tier] = in.getFloat();
            if (!Float.isNaN(error) && (!Float.isFinite(error) || error < 0)) throw new IOException("mesh display error");
        }
        var ranges = readRanges(in, sourceQuads, totalQuads);
        var tiers = readVegetationTiers(in, sourceQuads, totalQuads);
        if (!Float.isNaN(errors[0]) || tiers != null && tiers[0] != null)
            throw new IOException("mesh source display tier");
        PredictionTerrainDisplayRanges shared = null;
        if (flag(in)) {
            var features = readRangeLevel(in, 0, sourceQuads);
            if (features == null) throw new IOException("mesh missing feature ranges");
            boolean owns = flag(in);
            if (owns == (tiers != null)) throw new IOException("mesh proxy vegetation sharing");
            var proxyRanges = owns ? ranges : readRanges(in, sourceQuads, totalQuads);
            var proxyTiers = owns ? readVegetationTiers(in, sourceQuads, totalQuads) : tiers;
            if (proxyRanges == null || proxyTiers == null) throw new IOException("mesh missing terrain proxies");
            validateTiers(proxyRanges, errors, proxyTiers);
            for (var feature : features) if (feature == null) throw new IOException("mesh missing feature mask");
            if (!owns) for (int tier = 0; tier < 4; tier++) if (proxyTiers[tier] == null && proxyRanges[tier] != null)
                throw new IOException("mesh empty terrain proxy");
            shared = new PredictionTerrainDisplayRanges(features, vegetation, proxyRanges, proxyTiers, owns);
        }
        PredictionFeatureVoxelRanges voxel = null;
        if (flag(in)) {
            float[] voxelErrors = new float[4];
            for (int t=0;t<4;t++) {
                float e=voxelErrors[t]=in.getFloat();
                if (!Float.isNaN(e)&&(!Float.isFinite(e)||e<0))throw new IOException("mesh voxel error");
            }
            if(!Float.isNaN(voxelErrors[0]))throw new IOException("mesh voxel source error");
            int[] terrainTiers=new int[4];
            for(int t=0;t<4;t++) {
                int ground=terrainTiers[t]=in.get()&255;
                if(ground>t || t==0&&ground!=0 || ground!=0&&!Float.isFinite(errors[ground]))
                    throw new IOException("mesh voxel terrain tier");
            }
            int n=in.getInt();
            if(n<1||n>128||n>totalQuads||n>in.remaining()/8)throw new IOException("mesh voxel removal count");
            int[] removedFirst=new int[n],removedCount=new int[n];int end=0;
            for(int r=0;r<n;r++) {
                int f=removedFirst[r]=in.getInt(),c=removedCount[r]=in.getInt();
                if(f<end||f>totalQuads||c<1||c>totalQuads-f)throw new IOException("mesh voxel removal range");
                end=f+c;
            }
            var proxies=readVegetationTiers(in,sourceQuads,totalQuads);
            if(proxies==null||proxies[0]!=null)throw new IOException("mesh voxel proxies");
            boolean present=false;
            for(int t=1;t<4;t++) {
                present|=proxies[t]!=null;
                if((proxies[t]!=null||Float.isFinite(errors[t]))!=Float.isFinite(voxelErrors[t]))throw new IOException("mesh voxel tier");
                // A missing coarse level may alias the previous, finer level.
                // Its bound belongs to that geometry, not the nominal tier number.
                if(proxies[t]!=null&&voxelErrors[t]<(float)(Math.sqrt(3)*2)
                        || Float.isFinite(errors[t])&&voxelErrors[t]<errors[t])
                    throw new IOException("mesh voxel error bound");
            }
            if(!present)throw new IOException("mesh empty voxel hierarchy");
            voxel=new PredictionFeatureVoxelRanges(new PredictionDisplayGeometry.Result(null,ranges,errors,tiers,shared),
                    vegetation,new PredictionDrawRanges(removedFirst,removedCount),proxies,voxelErrors,terrainTiers);
        }
        if (ranges != null) {
            if (tiers != null) validateTiers(ranges, errors, tiers);
            else if (shared == null || !shared.ownsProxyVegetation()) throw new IOException("mesh missing display vegetation");
        } else if ((voxel == null && totalQuads != sourceQuads) || tiers != null || shared != null)
            throw new IOException("mesh orphan display geometry");
        return new Plan(sourceWords, policy, materials, aquatic, vegetation,
                voxel == null ? new PredictionDisplayGeometry.Result(null, ranges, errors, tiers, shared) : voxel.result(null));
    }

    private static void validateTiers(PredictionDrawRanges[][] ranges, float[] errors,
                                      PredictionVegetationRanges[] vegetation) throws IOException {
        for (int tier = 0; tier < 4; tier++) {
            boolean available = ranges[tier] != null && ranges[tier][31] != null;
            if (available != (vegetation[tier] != null) || available != Float.isFinite(errors[tier]))
                throw new IOException("mesh display tier");
            if (available) for (var range : ranges[tier]) if (range == null) throw new IOException("mesh missing display mask");
            if (!available && ranges[tier] != null) for (var range : ranges[tier])
                if (range != null) throw new IOException("mesh unavailable display mask");
        }
    }

    private static void writeVegetation(DataOutputStream out, PredictionVegetationRanges vegetation) throws IOException {
        for (int n : vegetation.cacheFirst()) out.writeInt(n);
        for (int[] tier : vegetation.cacheCounts()) for (int n : tier) out.writeInt(n);
    }

    private static PredictionVegetationRanges readVegetation(ByteBuffer in, int minimum, int maximum) throws IOException {
        int[] first = new int[GROUPS * 4]; int[][] count = new int[4][first.length];
        for (int i = 0; i < first.length; i++) first[i] = in.getInt();
        for (int tier = 0; tier < 4; tier++) for (int i = 0; i < first.length; i++) {
            int n = count[tier][i] = in.getInt();
            if (n < 0 || first[i] < minimum || first[i] > maximum || n > maximum - first[i]
                    || tier > 0 && (n > count[tier - 1][i] || i % 4 == 0 && n != count[0][i]))
                throw new IOException("mesh vegetation prefix");
        }
        int end = minimum;
        for (int g = 0; g < GROUPS; g++) for (int k = 0; k < 4; k++) {
            int i = g * 4 + k;
            if (k > 0 && first[i] != first[i - 1] + count[0][i - 1]
                    || count[0][i] != 0 && first[i] < end) throw new IOException("mesh vegetation order");
            if (count[0][i] != 0) end = first[i] + count[0][i];
        }
        return new PredictionVegetationRanges(first, count);
    }

    private static void writeVegetationTiers(DataOutputStream out, PredictionVegetationRanges[] tiers) throws IOException {
        out.writeBoolean(tiers != null);
        if (tiers == null) return;
        for (int tier = 0; tier < 4; tier++) {
            int tag = tiers[tier] == null ? 0 : tier > 0 && tiers[tier] == tiers[tier - 1] ? 1 : 2;
            out.writeByte(tag);
            if (tag == 2) writeVegetation(out, tiers[tier]);
        }
    }

    private static PredictionVegetationRanges[] readVegetationTiers(ByteBuffer in, int min, int max) throws IOException {
        if (!flag(in)) return null;
        var result = new PredictionVegetationRanges[4];
        for (int tier = 0; tier < 4; tier++) {
            int tag = in.get() & 255;
            if (tag == 2) result[tier] = readVegetation(in, min, max);
            else if (tag == 1 && tier > 0 && result[tier - 1] != null) result[tier] = result[tier - 1];
            else if (tag != 0) throw new IOException("mesh vegetation alias");
        }
        return result;
    }

    private static void writeRanges(DataOutputStream out, PredictionDrawRanges[][] ranges) throws IOException {
        out.writeBoolean(ranges != null);
        if (ranges != null) for (var level : ranges) writeRanges(out, level);
    }

    private static void writeRanges(DataOutputStream out, PredictionDrawRanges[] ranges) throws IOException {
        out.writeBoolean(ranges != null);
        if (ranges != null) for (var range : ranges) {
            out.writeBoolean(range != null);
            if (range == null) continue;
            out.writeInt(range.first.length);
            for (int i = 0; i < range.first.length; i++) { out.writeInt(range.first[i]); out.writeInt(range.count[i]); }
        }
    }

    private static PredictionDrawRanges[][] readRanges(ByteBuffer in, int min, int max) throws IOException {
        if (!flag(in)) return null;
        var result = new PredictionDrawRanges[4][];
        for (int tier = 0; tier < 4; tier++) result[tier] = readRangeLevel(in, min, max);
        return result;
    }

    private static PredictionDrawRanges[] readRangeLevel(ByteBuffer in, int min, int max) throws IOException {
        if (!flag(in)) return null;
        var result = new PredictionDrawRanges[32];
        for (int visible = 0; visible < 32; visible++) if (flag(in)) {
            int n = in.getInt();
            // The producer caps shared feature/terrain plans at 128 runs.
            if (n < 0 || n > 128 || n > in.remaining() / 8) throw new IOException("mesh display runs");
            int[] first = new int[n], count = new int[n]; int end = min;
            for (int i = 0; i < n; i++) {
                first[i] = in.getInt(); count[i] = in.getInt();
                if (first[i] < end || first[i] > max || count[i] <= 0 || count[i] > max - first[i])
                    throw new IOException("mesh display range");
                end = first[i] + count[i];
            }
            result[visible] = new PredictionDrawRanges(first, count);
        }
        return result;
    }

    private static boolean flag(ByteBuffer in) throws IOException {
        int value = in.get() & 255;
        if (value > 1) throw new IOException("mesh derived flag");
        return value != 0;
    }
}
