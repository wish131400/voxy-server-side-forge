package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.TextureUtil;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * GPU-readable snapshot of the client sections that have finished compiling.
 *
 * <p>This is a second occluder beside the imported framebuffer
 * depth.  The depth buffer only describes the pixels that happened to be
 * visible this frame; this mask also prevents a prediction wall or canopy
 * from being drawn through a compiled section after the camera turns.</p>
 */
final class PredictionVanillaMask {
    private static final int SECTION_BLOCKS = 16;
    private static final long REFRESH_NANOS = 125_000_000L;

    // GL objects must be created lazily: PredictionRenderer is also loaded by
    // headless protocol/unit tests that do not have a render context.
    private int texture = -1;
    private ByteBuffer upload = ByteBuffer.allocateDirect(1);
    private byte[] states = new byte[0];
    private int sizeXZ;
    private int sizeY;
    private int originChunkX;
    private int originChunkZ;
    private int originSectionY;
    private long lastRefreshNanos;
    private int lastCameraChunkX = Integer.MIN_VALUE;
    private int lastCameraChunkZ = Integer.MIN_VALUE;
    private int lastRadius = -1;
    private boolean dirty = true;
    private LevelChunk[] chunks = new LevelChunk[0];
    private boolean[] compiledGround = new boolean[0], edgeDirty = new boolean[0];
    private java.util.List<PredictionRealBoundarySeams.Edge>[] edgesByColumn;
    private final java.util.Set<Long> dirtyColumns = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean resetPending;
    private int textureSizeXZ, textureSizeY;
    private int dirtyX, dirtyY, dirtyZ, dirtyXX, dirtyYY, dirtyZZ;
    private long scannedColumns, checkedSections, readBoundaryColumns, textureBytes;
    private int probeCursor, probesRemaining;
    private long identityChecks;
    private final java.util.BitSet priorityProbes = new java.util.BitSet();
    private final java.util.BitSet edgeProbes = new java.util.BitSet();
    private boolean edgeResultsDirty;
    private boolean edgePublicationDirty;
    private java.util.List<PredictionRealBoundarySeams.Edge> groundEdges = java.util.List.of();

    java.util.List<PredictionRealBoundarySeams.Edge> groundEdges() { return groundEdges; }
    void markDirty(int x, int z) {
        if (dirtyColumns.size() >= 4096) resetPending = true;
        else dirtyColumns.add((long) x << 32 | z & 0xffffffffL);
    }
    void invalidatePending() { resetPending = true; }

    String diagnostics() { return "maskColumns=" + scannedColumns + ",maskSections=" + checkedSections
            + ",boundaryReads=" + readBoundaryColumns + ",maskUploadBytes=" + textureBytes
            + ",maskIdentityChecks=" + identityChecks + ",maskProbesRemaining=" + probesRemaining; }

    /** Refreshes section state at most eight times per second unless reanchored. */
    void update(Minecraft minecraft, Vec3 camera) {
        ClientLevel level = minecraft.level;
        if (resetPending) { resetPending = false; invalidate(); }
        if (level == null || camera == null) {
            invalidate();
            return;
        }
        int radius = Math.max(1, minecraft.options.getEffectiveRenderDistance());
        int cameraChunkX = SectionPos.blockToSectionCoord(camera.x);
        int cameraChunkZ = SectionPos.blockToSectionCoord(camera.z);
        int nextOriginX = cameraChunkX - radius;
        int nextOriginZ = cameraChunkZ - radius;
        int nextSizeY = Math.max(1, level.getSectionsCount());
        int nextOriginY = level.getMinSection();
        boolean reanchored = sizeXZ != radius * 2 + 1
                || sizeY != nextSizeY
                || originChunkX != nextOriginX
                || originChunkZ != nextOriginZ
                || originSectionY != nextOriginY
                || cameraChunkX != lastCameraChunkX
                || cameraChunkZ != lastCameraChunkZ
                || radius != lastRadius;
        long now = System.nanoTime();
        if (!reanchored && dirtyColumns.isEmpty() && probesRemaining == 0 && priorityProbes.isEmpty()
                && !edgePublicationDirty && now - lastRefreshNanos < REFRESH_NANOS) {
            return;
        }
        reanchor(radius * 2 + 1, nextSizeY, nextOriginX, nextOriginZ, nextOriginY);
        LevelRenderer renderer = minecraft.levelRenderer;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        withdrawDirtyColumns();
        if (probesRemaining == 0 && now - lastRefreshNanos >= REFRESH_NANOS || reanchored) {
            probesRemaining = chunks.length; lastRefreshNanos = now;
        }
        long until = System.nanoTime() + 500_000L;
        for (int checked = 0; checked < 128 && System.nanoTime() < until; checked++) {
                int priority = priorityProbes.nextSetBit(0);
                int column;
                boolean modified = priority >= 0;
                if (modified) { column = priority; priorityProbes.clear(priority); }
                else {
                    if (probesRemaining == 0) break;
                    column = probeCursor; probeCursor = (probeCursor + 1) % chunks.length; probesRemaining--;
                }
                int x = column % sizeXZ, z = column / sizeXZ;
                int chunkX = originChunkX + x;
                int chunkZ = originChunkZ + z;
                LevelChunk chunk = level.getChunkSource().getChunk(
                        chunkX, chunkZ, ChunkStatus.FULL, false);
                identityChecks++;
                // A completed, unchanged chunk needs no repeated section probes.
                // Unknown/incomplete chunks are polled so async compilation
                // can finish even when no dirty callback is issued.
                if (chunk == chunks[column] && (compiledGround[column] || chunk == null) && !modified) continue;
                scannedColumns++;
                boolean surfaceCompiled = chunk != null
                        && surfaceCompiled(renderer, chunk, chunkX, chunkZ, position);
                if (chunks[column] != chunk || compiledGround[column] != surfaceCompiled || modified) {
                    markEdgeNeighbours(x, z);
                }
                chunks[column] = chunk; compiledGround[column] = surfaceCompiled;
                for (int y = 0; y < sizeY; y++) {
                    boolean compiled = surfaceCompiled;
                    if (!compiled && chunk != null) {
                        position.set(SectionPos.sectionToBlockCoord(chunkX),
                                SectionPos.sectionToBlockCoord(originSectionY + y),
                                SectionPos.sectionToBlockCoord(chunkZ));
                        compiled = renderer.isChunkCompiled(position);
                    }
                    setState(x, y, z, (byte) (compiled ? 1 : 0));
                }
        }
        refreshGroundEdges(position);
        lastCameraChunkX = cameraChunkX;
        lastCameraChunkZ = cameraChunkZ;
        lastRadius = radius;
    }

    private void withdrawDirtyColumns() {
        for (var it = dirtyColumns.iterator(); it.hasNext();) {
            long key = it.next(); it.remove();
            int x = (int) (key >> 32) - originChunkX, z = (int) key - originChunkZ;
            if (x < 0 || z < 0 || x >= sizeXZ || z >= sizeXZ) continue;
            int column = z * sizeXZ + x;
            // Withdrawal cannot wait for a polling slice. A dirty/unloaded
            // chunk conservatively restores prediction until compilation wins.
            compiledGround[column] = false; priorityProbes.set(column); markEdgeNeighbours(x, z);
            if (edgesByColumn[column] != null && !edgesByColumn[column].isEmpty()) {
                edgesByColumn[column] = java.util.List.of(); edgeResultsDirty = true;
            }
            for (int y = 0; y < sizeY; y++) setState(x, y, z, (byte) 0);
        }
    }

    private void markEdgeNeighbours(int x, int z) {
        int column = z * sizeXZ + x; markEdge(column);
        if (x > 0) markEdge(column - 1);
        if (x + 1 < sizeXZ) markEdge(column + 1);
        if (z > 0) markEdge(column - sizeXZ);
        if (z + 1 < sizeXZ) markEdge(column + sizeXZ);
        edgePublicationDirty = true;
    }
    private void markEdge(int column) { edgeDirty[column] = true; edgeProbes.set(column); }

    private void refreshGroundEdges(BlockPos.MutableBlockPos position) {
        if (!edgePublicationDirty) return;
        long until = System.nanoTime() + 500_000L;
        int inspected = 0;
        while (!edgeProbes.isEmpty() && inspected++ < 128 && System.nanoTime() < until) {
            int column = edgeProbes.nextSetBit(0); edgeProbes.clear(column);
            int x = column % sizeXZ, z = column / sizeXZ;
            if (!edgeDirty[column]) continue;
            edgeDirty[column] = false;
            var edges = new java.util.ArrayList<PredictionRealBoundarySeams.Edge>();
            LevelChunk chunk = compiledGround[column] ? chunks[column] : null;
            if (chunk != null)
            for (int direction = 0; direction < 4; direction++) {
                int nx = direction == 0 ? -1 : direction == 1 ? 1 : 0;
                int nz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                int ax = x + nx, az = z + nz;
                if (ax >= 0 && az >= 0 && ax < sizeXZ && az < sizeXZ
                        && compiledGround[az * sizeXZ + ax]) continue;
                for (int along = 0; along < 16; along++) {
                    int wx = (originChunkX + x) * 16 + (nx == 0 ? along : nx > 0 ? 15 : 0);
                    int wz = (originChunkZ + z) * 16 + (nz == 0 ? along : nz > 0 ? 15 : 0);
                    var ground = boundaryGround(chunk, wx, wz, position);
                    readBoundaryColumns++;
                    if (ground != null) edges.add(new PredictionRealBoundarySeams.Edge(wx, wz, nx, nz, ground));
                }
            }
            if (!edges.equals(edgesByColumn[column])) {
                edgesByColumn[column] = java.util.List.copyOf(edges); edgeResultsDirty = true;
            }
        }
        edgePublicationDirty = !edgeProbes.isEmpty();
        if (edgeResultsDirty) {
            edgeResultsDirty = false;
            var all = new java.util.ArrayList<PredictionRealBoundarySeams.Edge>();
            for (var edges : edgesByColumn) if (edges != null) all.addAll(edges);
            if (!groundEdges.equals(all)) groundEdges = java.util.List.copyOf(all);
        }
    }

    private static ClientColumnSample boundaryGround(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos pos) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        int water = top + 1, fluid = 0;
        // Read only perimeter blocks. No noise sampling, chunk generation or cave scan.
        for (int y = top; y >= Math.max(chunk.getMinBuildHeight(), top - 31); y--) {
            var state = chunk.getBlockState(pos.set(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                if (fluid == 0) fluid = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 2 : 1;
                continue;
            }
            if (ClientCaptureExtractor.isGround(state)) {
                var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
                int block = registry.getId(state.getBlock());
                var below = chunk.getBlockState(pos.set(x, y - 1, z));
                int under = ClientCaptureExtractor.isGround(below) ? registry.getId(below.getBlock()) : block;
                var deep = chunk.getBlockState(pos.set(x, y - 7, z));
                int deepId = ClientCaptureExtractor.isGround(deep) ? registry.getId(deep.getBlock()) : under;
                return new ClientColumnSample(y + 1, fluid == 0 ? y + 1 : water, 0, block,
                        0, 0, 0, 0, fluid, ClientColumnSample.FLAG_CAPTURED, 0, under, deepId,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            if (state.canOcclude()) return null;
        }
        return null;
    }

    /** Clears CPU state after a world change without issuing GL calls off-thread. */
    void invalidate() {
        sizeXZ = 0;
        sizeY = 0;
        states = new byte[0];
        chunks = new LevelChunk[0]; compiledGround = edgeDirty = new boolean[0]; edgesByColumn = null;
        dirtyColumns.clear();
        priorityProbes.clear(); edgeProbes.clear(); probeCursor = probesRemaining = 0; edgeResultsDirty = false;
        groundEdges = java.util.List.of();
        lastCameraChunkX = Integer.MIN_VALUE;
        lastCameraChunkZ = Integer.MIN_VALUE;
        lastRadius = -1;
        lastRefreshNanos = 0L;
        dirty = true;
    }

    void bind(int unit, Vec3 camera, int originUniform, int sizeUniform) {
        if (texture == -1) {
            texture = TextureUtil.generateTextureId();
        }
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        refresh();
        org.lwjgl.opengl.GL20.glUniform3f(originUniform,
                (float) (originChunkX * SECTION_BLOCKS - camera.x),
                (float) (originSectionY * SECTION_BLOCKS - camera.y),
                (float) (originChunkZ * SECTION_BLOCKS - camera.z));
        org.lwjgl.opengl.GL20.glUniform3i(sizeUniform, sizeXZ, sizeY, sizeXZ);
    }

    float renderDistanceBlocks() {
        return Math.max(0, lastRadius) * SECTION_BLOCKS;
    }

    static void unbind(int unit) {
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private boolean surfaceCompiled(LevelRenderer renderer,
                                             LevelChunk chunk, int chunkX, int chunkZ,
                                             BlockPos.MutableBlockPos position) {
        var sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            if (sections[index].hasOnlyAir()) continue;
            checkedSections++;
            int sectionY = chunk.getSectionYFromSectionIndex(index);
            position.set(SectionPos.sectionToBlockCoord(chunkX),
                    SectionPos.sectionToBlockCoord(sectionY),
                    SectionPos.sectionToBlockCoord(chunkZ));
            if (!renderer.isChunkCompiled(position)) {
                return false;
            }
        }
        return true;
    }

    private int index(int x, int y, int z) {
        return (z * sizeY + y) * sizeXZ + x;
    }

    private void setState(int x, int y, int z, byte state) {
        int i = index(x, y, z);
        if (states[i] == state) return;
        states[i] = state;
        if (!dirty) { dirtyX = dirtyXX = x; dirtyY = dirtyYY = y; dirtyZ = dirtyZZ = z; }
        else {
            dirtyX = Math.min(dirtyX, x); dirtyXX = Math.max(dirtyXX, x);
            dirtyY = Math.min(dirtyY, y); dirtyYY = Math.max(dirtyYY, y);
            dirtyZ = Math.min(dirtyZ, z); dirtyZZ = Math.max(dirtyZZ, z);
        }
        dirty = true;
    }

    @SuppressWarnings("unchecked")
    private void reanchor(int nextSizeXZ, int nextSizeY, int nextOriginX,
                          int nextOriginZ, int nextOriginY) {
        if (nextSizeXZ == sizeXZ && nextSizeY == sizeY
                && nextOriginX == originChunkX && nextOriginZ == originChunkZ
                && nextOriginY == originSectionY) {
            return;
        }
        int oldXZ = sizeXZ, oldY = sizeY, oldX = originChunkX, oldZ = originChunkZ;
        byte[] oldStates = states;
        LevelChunk[] oldChunks = chunks;
        boolean[] oldCompiled = compiledGround;
        boolean[] oldEdgeDirty = edgeDirty;
        var oldEdges = edgesByColumn;
        boolean compatible = nextSizeY == sizeY && nextOriginY == originSectionY;
        sizeXZ = nextSizeXZ;
        sizeY = nextSizeY;
        originChunkX = nextOriginX;
        originChunkZ = nextOriginZ;
        originSectionY = nextOriginY;
        states = new byte[sizeXZ * sizeY * sizeXZ];
        chunks = new LevelChunk[sizeXZ * sizeXZ];
        compiledGround = new boolean[chunks.length]; edgeDirty = new boolean[chunks.length];
        java.util.Arrays.fill(edgeDirty, true);
        edgesByColumn = new java.util.List[chunks.length];
        priorityProbes.clear(); edgeProbes.clear(); probeCursor = 0; probesRemaining = chunks.length;
        if (compatible) for (int z = 0; z < sizeXZ; z++) for (int x = 0; x < sizeXZ; x++) {
            int ox = originChunkX + x - oldX, oz = originChunkZ + z - oldZ;
            if (ox < 0 || oz < 0 || ox >= oldXZ || oz >= oldXZ) continue;
            int from = oz * oldXZ + ox, to = z * sizeXZ + x;
            chunks[to] = oldChunks[from]; compiledGround[to] = oldCompiled[from];
            edgesByColumn[to] = oldEdges[from];
            edgeDirty[to] = oldEdgeDirty[from];
            for (int y = 0; y < sizeY; y++) states[index(x, y, z)] = oldStates[(oz * oldY + y) * oldXZ + ox];
        }
        // A shifted window changes only newly exposed boundaries. Reuse
        // perimeter samples for overlap whose neighbouring coverage is equal.
        if (compatible) for (int z = 0; z < sizeXZ; z++) for (int x = 0; x < sizeXZ; x++) {
            int ox = originChunkX + x - oldX, oz = originChunkZ + z - oldZ;
            if (ox < 0 || oz < 0 || ox >= oldXZ || oz >= oldXZ) continue;
            if (exposedDirections(oldCompiled, oldXZ, ox, oz) != exposedDirections(compiledGround, sizeXZ, x, z))
                edgeDirty[z * sizeXZ + x] = true;
        }
        for (int column = 0; column < edgeDirty.length; column++) if (edgeDirty[column]) edgeProbes.set(column);
        dirty = edgePublicationDirty = edgeResultsDirty = true;
        dirtyX = dirtyY = dirtyZ = 0;
        dirtyXX = sizeXZ - 1; dirtyYY = sizeY - 1; dirtyZZ = sizeXZ - 1;
        int bytes = Math.max(1, states.length);
        if (upload.capacity() < bytes) {
            ByteBuffer replacement = ByteBuffer.allocateDirect(bytes);
            upload = replacement;
        }
    }

    private static int exposedDirections(boolean[] compiled, int size, int x, int z) {
        if (!compiled[z * size + x]) return 0;
        return (x == 0 || !compiled[z * size + x - 1] ? 1 : 0)
                | (x + 1 == size || !compiled[z * size + x + 1] ? 2 : 0)
                | (z == 0 || !compiled[(z - 1) * size + x] ? 4 : 0)
                | (z + 1 == size || !compiled[(z + 1) * size + x] ? 8 : 0);
    }

    private void refresh() {
        if (!dirty) {
            return;
        }
        boolean allocate = textureSizeXZ != Math.max(1, sizeXZ) || textureSizeY != Math.max(1, sizeY);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        try (var unpack = PredictionPixelUnpack.begin()) {
            if (allocate) {
                textureSizeXZ = Math.max(1, sizeXZ); textureSizeY = Math.max(1, sizeY);
                GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8,
                        textureSizeXZ, textureSizeY, textureSizeXZ, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                dirtyX = dirtyY = dirtyZ = 0; dirtyXX = sizeXZ - 1; dirtyYY = sizeY - 1; dirtyZZ = sizeXZ - 1;
            }
            if (states.length != 0) {
                upload.clear();
                for (int z = dirtyZ; z <= dirtyZZ; z++) for (int y = dirtyY; y <= dirtyYY; y++)
                    for (int x = dirtyX; x <= dirtyXX; x++) upload.put((byte) (states[index(x, y, z)] == 0 ? 0 : 255));
                upload.flip(); textureBytes += upload.remaining();
                GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, dirtyX, dirtyY, dirtyZ,
                        dirtyXX - dirtyX + 1, dirtyYY - dirtyY + 1, dirtyZZ - dirtyZ + 1,
                        GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, upload);
            }
        }
        dirty = false;
    }
}
