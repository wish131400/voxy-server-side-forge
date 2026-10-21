package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;

/** World-space records shared by ordered submission and the persistent GPU scene. */
final class PredictionSubmissionEncoding {
    private PredictionSubmissionEncoding() { }

    static void write(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                      PredictionTerrainArena.Slice slice, boolean water, boolean average,
                      boolean segmented, int recordIndex, ByteBuffer records,
                      ByteBuffer commands, ByteBuffer bounds) {
        var packed = draw.gpu().packed();
        records.putFloat((float) draw.tile().baseBlockX()).putFloat(0)
                .putFloat((float) draw.tile().baseBlockZ()).putFloat(draw.tile().spacingBlocks());
        records.putInt(packed.cellAxis()).putInt(average ? 1 : 0)
                .putInt(slice.offset / 16).putInt(slice.maskOffset / 4);
        float morph = !water && !draw.seam() && packed.morph() != null ? draw.morph() : 0;
        records.putFloat(packed.morphBaseTexel()).putFloat(morph)
                .putFloat(packed.morphMinY()).putFloat(packed.morphMaxY());
        records.putInt((!water && !draw.seam() ? 1 : 0) | (draw.coverageGrid() != null ? 4 : 0))
                .putInt(water ? packed.waterPaletteBaseTexel() : draw.gpu().paletteBaseTexel())
                .putInt(Float.floatToRawIntBits(!water && !draw.seam() && packed.morph() != null
                        ? draw.gpu().morphStartSeconds() : 0))
                .putInt(Float.floatToRawIntBits(morph));
        for (int i = 0; i < ranges.first.length; i++) {
            int end = ranges.first[i] + ranges.count[i];
            for (int first = ranges.first[i]; first < end;) {
                int next = segmented ? PredictionMeshletBounds.end(first, end) : end;
                int gpuFirst = packed.gpuFirst(first, next - first, water);
                commands.putInt((next - first) * 6).putInt(1).putInt(gpuFirst * 6)
                        .putInt(0).putInt(recordIndex);
                if (bounds != null) bounds(draw, packed, morph,
                        segmented ? first / PredictionMeshletBounds.QUADS : -1, bounds);
                first = next;
            }
        }
    }

    private static void bounds(PredictionRenderer.Draw draw, PredictionPackedMesh packed,
                               float morph, int meshlet, ByteBuffer bounds) {
        double x = draw.bounds().minX, z = draw.bounds().minZ;
        double xx = draw.bounds().maxX, zz = draw.bounds().maxZ;
        double y = draw.bounds().minY, yy = draw.bounds().maxY;
        if (meshlet >= 0) {
            float[] b = packed.occlusionBounds.values;
            int i = meshlet * 6;
            x = draw.tile().baseBlockX() + (double) b[i] - 2;
            xx = draw.tile().baseBlockX() + (double) b[i + 3] + 2;
            z = draw.tile().baseBlockZ() + (double) b[i + 2] - 2;
            zz = draw.tile().baseBlockZ() + (double) b[i + 5] + 2;
            y = b[i + 1] - 2; yy = b[i + 4] + 2;
        }
        if (morph > 0) {
            y += Math.min(0, packed.morphDeltaMin() * morph);
            yy += Math.max(0, packed.morphDeltaMax() * morph);
        }
        int directions = meshlet < 0 ? 0 : packed.occlusionBounds.directions[meshlet];
        bounds.putFloat((float) x).putFloat((float) y).putFloat((float) z).putInt(directions);
        bounds.putFloat((float) xx).putFloat((float) yy).putFloat((float) zz).putFloat(0);
    }
}
