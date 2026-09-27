package dev.xantha.vss.client.prediction;

import java.nio.*;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;

/** Ordered page batches: never regroup transparent draws across other pages or fallback tiles. */
final class PredictionIndirectBatch implements AutoCloseable {
    // Voxy keeps one large GPU command stream. Prediction still has to split
    // when a terrain arena page changes, but the old 512-tile/2560-command
    // ceiling caused avoidable flushes inside each page on large horizons.
    // Keep the buffers bounded while matching the size of a normal arena page.
    private static final int LIMIT=2048, COMMAND_LIMIT=LIMIT*8;
    private int tiles,commands;
    private final ByteBuffer records=MemoryUtil.memAlloc(LIMIT*64);
    private final ByteBuffer draws=MemoryUtil.memAlloc(COMMAND_LIMIT*20);
    private final ArrayList<BatchSlot> opaqueSlots = new ArrayList<>();
    private final ArrayList<BatchSlot> waterSlots = new ArrayList<>();
    private ArrayList<BatchSlot> slots = opaqueSlots;
    private int slotIndex;
    private PredictionTerrainArena.Page page;
    private PredictionTerrainProgram program;
    private int oldStorage,oldIndirect;
    private int oldIndexed;
    private long oldStart, oldSize;
    private boolean opaque;
    private long calls;
    private long submittedTiles;
    private long reusedBatches, uploadedBatches, uploadedBytes;
    private final long[] queuedQuads = new long[5], lastOpaqueQueued = new long[5];
    private long lastOpaqueReused, lastOpaqueUploaded, lastOpaqueBytes;

    void begin(PredictionTerrainProgram program) {
        begin(program, true);
    }
    void begin(PredictionTerrainProgram program, boolean opaque) {
        this.program=program;calls=0;submittedTiles=0;tiles=commands=0;page=null;records.clear();draws.clear();
        reusedBatches=uploadedBatches=uploadedBytes=0;
        Arrays.fill(queuedQuads, 0L);
        this.opaque=opaque;
        slots = opaque ? opaqueSlots : waterSlots;
        slotIndex = 0;
        oldStorage=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);oldIndirect=glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        oldIndexed = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 7);
        oldStart = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 7);
        oldSize = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 7);
        program.batch(false);
    }
    boolean add(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,net.minecraft.world.phys.Vec3 camera,
                boolean water,boolean average,long now) {
        var slice=draw.gpu().arenaSlice();
        if(slice==null){flush();return false;}
        if(page!=slice.page || tiles==LIMIT || commands+ranges.first.length>COMMAND_LIMIT)flush();
        page=slice.page;
        var packed=draw.gpu().packed();
        records.putFloat((float)(draw.tile().baseBlockX()-camera.x)).putFloat((float)-camera.y)
                .putFloat((float)(draw.tile().baseBlockZ()-camera.z)).putFloat(draw.tile().spacingBlocks());
        records.putInt(packed.cellAxis()).putInt(average?1:0).putInt(slice.offset/16).putInt(slice.maskOffset/4);
        float morph=!water&&!draw.seam()?draw.morph()*draw.gpu().morphAmount(now):0;
        records.putFloat(packed.morphBaseTexel()).putFloat(morph).putFloat(packed.morphMinY()).putFloat(packed.morphMaxY());
        records.putInt(!water&&!draw.seam()?1:0).putInt(draw.gpu().paletteBaseTexel())
                .putInt(Float.floatToRawIntBits((float)(draw.bounds().minY - camera.y)))
                .putInt(Float.floatToRawIntBits((float)(draw.bounds().maxY - camera.y)));
        for(int i=0;i<ranges.first.length;i++) {
            draws.putInt(ranges.count[i]*6).putInt(1).putInt(ranges.first[i]*6).putInt(0).putInt(tiles);commands++;
        }
        int bucket = distanceBucket(draw, camera);
        queuedQuads[bucket] += ranges.quads;
        tiles++;submittedTiles++;return true;
    }
    void fallback(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                  net.minecraft.world.phys.Vec3 camera) {
        int bucket = distanceBucket(draw, camera);
        queuedQuads[bucket] += ranges.quads;
    }
    private static int distanceBucket(PredictionRenderer.Draw draw, net.minecraft.world.phys.Vec3 camera) {
        double dx = draw.tile().baseBlockX() + draw.tile().spanBlocks() * 0.5 - camera.x;
        double dz = draw.tile().baseBlockZ() + draw.tile().spanBlocks() * 0.5 - camera.z;
        double distance2 = dx * dx + dz * dz;
        return distance2 < 65536.0 ? 0 : distance2 < 1048576.0 ? 1
                : distance2 < 16777216.0 ? 2 : distance2 < 268435456.0 ? 3 : 4;
    }
    void flush() {
        if(commands==0)return;
        records.flip();draws.flip();
        if (slotIndex == slots.size()) slots.add(new BatchSlot());
        BatchSlot slot = slots.get(slotIndex++);
        if (slot.upload(records, draws)) {
            uploadedBatches++;
            uploadedBytes += records.remaining() + draws.remaining();
        } else reusedBatches++;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,slot.metadata);
        PredictionGlState.activeTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,page.texture);
        program.use();
        program.batch(true);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, slot.indirect);
        glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,commands,20);
        program.batch(false);
        calls++;records.clear();draws.clear();tiles=commands=0;page=null;
    }
    long end() {
        try {
            flush();
            if (opaque) {
                System.arraycopy(queuedQuads, 0, lastOpaqueQueued, 0, queuedQuads.length);
                lastOpaqueReused = reusedBatches;
                lastOpaqueUploaded = uploadedBatches;
                lastOpaqueBytes = uploadedBytes;
            }
            while (slots.size() > slotIndex) slots.remove(slots.size() - 1).close();
            return calls;
        }
        finally {
            program.batch(false);
            if (oldIndexed != 0 && oldSize > 0)
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 7, oldIndexed, oldStart, oldSize);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, oldIndexed);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,oldStorage);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,oldIndirect);
        }
    }
    long submittedTiles() { return submittedTiles; }
    long reusedBatches() { return reusedBatches; }
    long uploadedBatches() { return uploadedBatches; }
    long uploadedBytes() { return uploadedBytes; }
    String diagnostics() {
        return "queuedQuads=" + Arrays.toString(lastOpaqueQueued)
                + ",buckets=0-256|256-1024|1024-4096|4096-16384|16384+"
                + ",reusedBatches=" + lastOpaqueReused
                + ",uploadedBatches=" + lastOpaqueUploaded
                + ",uploadedBytes=" + lastOpaqueBytes;
    }
    @Override public void close(){
        for (BatchSlot slot : opaqueSlots) slot.close();
        for (BatchSlot slot : waterSlots) slot.close();
        opaqueSlots.clear();waterSlots.clear();
        MemoryUtil.memFree(records);MemoryUtil.memFree(draws);
    }

    private static final class BatchSlot implements AutoCloseable {
        private final int metadata = glGenBuffers();
        private final int indirect = glGenBuffers();
        private byte[] recordBytes, drawBytes;

        private boolean upload(ByteBuffer records, ByteBuffer draws) {
            boolean sameRecords = recordBytes != null && records.equals(ByteBuffer.wrap(recordBytes));
            boolean sameDraws = drawBytes != null && draws.equals(ByteBuffer.wrap(drawBytes));
            if (!sameRecords) {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, metadata);
                glBufferData(GL_SHADER_STORAGE_BUFFER, records, GL_STREAM_DRAW);
                recordBytes = new byte[records.remaining()];
                records.duplicate().get(recordBytes);
            }
            if (!sameDraws) {
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect);
                glBufferData(GL_DRAW_INDIRECT_BUFFER, draws, GL_STREAM_DRAW);
                drawBytes = new byte[draws.remaining()];
                draws.duplicate().get(drawBytes);
            }
            return !sameRecords || !sameDraws;
        }

        @Override public void close() {
            glDeleteBuffers(metadata);
            glDeleteBuffers(indirect);
        }
    }
}
