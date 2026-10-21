package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Independent writer for the historical canonical wire format. */
final class PredictionMeshCacheTestRecords {
    static byte[] legacy(PredictionMesh mesh, byte[] full, byte[] base, boolean safe,
                         boolean complete, int version) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            var p = mesh.gpuPayload(); int[] words = p.cacheWords();
            out.writeInt(version); out.write(full); out.write(base); out.writeBoolean(safe);
            out.writeInt(mesh.cellAxis()); out.writeInt(mesh.vertexCount()); out.writeInt(mesh.waterVertexCount());
            out.writeInt(p.terrainQuadCount()); out.writeInt(p.spriteQuadCount()); out.writeBoolean(p.downFaces());
            out.writeInt(p.morphMinY()); out.writeInt(p.morphMaxY());
            var materials = new BitSet(256);
            for (int i = 6; i < words.length; i += 12) if ((words[i] & 65535) > 0 && (words[i] & 65535) < 255) materials.set(words[i] & 65535);
            mesh.seamMesh().materialRows(materials);
            out.writeInt(materials.cardinality());
            for (int row = materials.nextSetBit(0); row >= 0; row = materials.nextSetBit(row + 1)) {
                out.writeInt(row); VssLodSpriteTable.writeMaterial(out, row);
            }
            out.writeInt(words.length); for (int word : words) out.writeInt(word);
            for (int g = 0; g < VssLodFaceGroup.COUNT; g++) {
                out.writeInt(p.terrainRangeFirst(g)); out.writeInt(p.terrainRangeCount(g));
                out.writeInt(p.waterRangeFirst(g)); out.writeInt(p.waterRangeCount(g));
            }
            mesh.seamMesh().writeCache(out);
            if (version != 8) out.writeBoolean(complete);
        }
        return bytes.toByteArray();
    }

    static byte[] recode(byte[] record, int version) throws IOException {
        byte[] full = Arrays.copyOfRange(record, 4, 36), base = Arrays.copyOfRange(record, 36, 68);
        var decoded = PredictionMeshCodec.decodeBaseRecord(record, null, ByteBuffer.wrap(record).getInt(69));
        return legacy(decoded.mesh(), full, base, record[68] != 0, decoded.surfaceCompleted(), version);
    }
}
