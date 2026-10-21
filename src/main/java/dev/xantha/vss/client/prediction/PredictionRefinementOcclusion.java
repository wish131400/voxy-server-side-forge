package dev.xantha.vss.client.prediction;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL45C.*;

/** Bounded, asynchronous HiZ probes for refinement priority. No geometry is culled by this history. */
final class PredictionRefinementOcclusion implements AutoCloseable {
    static final long INTERVAL_NANOS = 250_000_000L;
    static final int LIMIT = 1024;
    private final PredictionOcclusionCuller culler = new PredictionOcclusionCuller();
    private final PredictionVisibilityFeedback.History history = new PredictionVisibilityFeedback.History();
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private PredictionVisibilityFeedback.Snapshot latest = PredictionVisibilityFeedback.Snapshot.EMPTY;
    private FloatBuffer boxes;
    private IntBuffer results;
    private int input, cursor;
    private long lastProbe, submitted, completed;
    private boolean sampled, failed;

    PredictionVisibilityFeedback.Snapshot sample(PredictionTileManager.RenderSnapshot source,
            PredictionWorkView view, Matrix4f transform, Vec3 camera, int depth, int width, int height, long now) {
        if (failed || !org.lwjgl.opengl.GL.getCapabilities().OpenGL45) return latest;
        try {
            poll(now);
            if (view == null || depth <= 0 || width <= 0 || height <= 0
                    || sampled && now - lastProbe >= 0 && now - lastProbe < INTERVAL_NANOS) return latest;
            sampled = true; lastProbe = now;
            Slot free = null;
            for (var slot : slots) if (slot.fence == 0) { free = slot; break; }
            if (free == null) return latest; // Keep rendering; a busy GPU is never waited on.
            var candidates = new ArrayList<PredictionTileManager.PredictionTile>();
            for (var tile : source.tiles().values()) if (tile.mesh() != null
                    && view.foreground(tile.key(), source.layout(), null, tile.depthBound().minY() - 2,
                        tile.depthBound().maxY() + 64)) candidates.add(tile);
            if (candidates.isEmpty()) return latest;
            if (!culler.begin(depth, width, height, transform)) return latest;
            try {
                initialize(free);
                int count = Math.min(LIMIT, candidates.size());
                boxes.clear();
                var stamps = new ArrayList<PredictionVisibilityFeedback.Stamp>(count);
                for (int i = 0; i < count; i++) {
                    var tile = candidates.get(Math.floorMod(cursor + i, candidates.size()));
                    double minX = tile.baseBlockX(), minZ = tile.baseBlockZ(), span = tile.spanBlocks();
                    // Unknown decorations retain extra height. False positives only lower priority.
                    boxes.put((float)(minX - camera.x - 2)).put((float)(tile.depthBound().minY() - camera.y - 2))
                            .put((float)(minZ - camera.z - 2)).put(0);
                    boxes.put((float)(minX + span - camera.x + 2)).put((float)(tile.depthBound().maxY() - camera.y + 64))
                            .put((float)(minZ + span - camera.z + 2)).put(0);
                    stamps.add(new PredictionVisibilityFeedback.Stamp(tile.key(), tile.revision(), source.epoch(tile.key())));
                }
                cursor = (int)(((long)cursor + count) % candidates.size());
                boxes.flip();
                glNamedBufferSubData(free.bounds, 0, boxes);
                culler.setCameraOffset(0, 0, 0);
                // Refinement is only a priority hint. If the main opaque pass
                // already consumed the frame's finite Hi-Z budget, leave the
                // probe queued for a later frame instead of rebuilding a full
                // pyramid a second time on the render thread.
                if (!PredictionHiZBudget.acquireRefinement()) return latest;
                culler.rebuild();
                culler.filter(free.bounds, input, free.output, count);
                glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                free.stamps = stamps; free.view = view; free.observedAt = now;
                free.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                submitted++;
            } finally { culler.end(); }
        } catch (RuntimeException failure) {
            failed = true;
            latest = PredictionVisibilityFeedback.Snapshot.EMPTY;
            dev.xantha.vss.common.VSSLogger.warn("VSS refinement visibility hints unavailable; using frustum priority", failure);
        }
        return latest;
    }

    private void initialize(Slot slot) {
        if (boxes == null) boxes = MemoryUtil.memAllocFloat(LIMIT * 8);
        if (results == null) results = MemoryUtil.memAllocInt(LIMIT * 5);
        if (input == 0) {
            var commands = MemoryUtil.memAllocInt(LIMIT * 5);
            try {
                for (int i = 0; i < LIMIT; i++) commands.put(6).put(1).put(0).put(0).put(0);
                commands.flip(); input = glCreateBuffers(); glNamedBufferData(input, commands, GL_STATIC_DRAW);
            } finally { MemoryUtil.memFree(commands); }
        }
        if (slot.bounds == 0) {
            slot.bounds = glCreateBuffers(); glNamedBufferData(slot.bounds, LIMIT * 32L, GL_STREAM_DRAW);
            slot.output = glCreateBuffers(); glNamedBufferData(slot.output, LIMIT * 20L, GL_STREAM_READ);
        }
    }

    private void poll(long now) {
        for (var slot : slots) if (slot.fence != 0) {
            int status = glClientWaitSync(slot.fence, 0, 0);
            if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) {
                results.clear().limit(slot.stamps.size() * 5);
                glGetNamedBufferSubData(slot.output, 0, results);
                var hidden = new boolean[slot.stamps.size()];
                for (int i = 0; i < hidden.length; i++) hidden[i] = results.get(i * 5) == 0;
                latest = history.observe(slot.stamps, hidden, slot.view, slot.observedAt, now);
                completed++;
                releaseFence(slot);
            } else if (status == GL_WAIT_FAILED) releaseFence(slot);
        }
    }

    private static void releaseFence(Slot slot) {
        glDeleteSync(slot.fence); slot.fence = 0; slot.stamps = List.of(); slot.view = null;
    }

    String diagnostics() {
        return "visibilityHints{probes=" + submitted + ",completed=" + completed
                + ",records=" + latest.proofs().size() + ",fallback=" + failed + "}";
    }

    @Override public void close() {
        culler.close();
        for (var slot : slots) {
            if (slot.fence != 0) releaseFence(slot);
            if (slot.bounds != 0) glDeleteBuffers(slot.bounds);
            if (slot.output != 0) glDeleteBuffers(slot.output);
        }
        if (input != 0) glDeleteBuffers(input);
        if (boxes != null) MemoryUtil.memFree(boxes);
        if (results != null) MemoryUtil.memFree(results);
    }

    private static final class Slot {
        int bounds, output;
        long fence, observedAt;
        PredictionWorkView view;
        List<PredictionVisibilityFeedback.Stamp> stamps = List.of();
    }
}
