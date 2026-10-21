package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/** Render-thread-owned, page-local fixed records. Visibility never renumbers live slots. */
final class PredictionGpuScene implements AutoCloseable {
    private static final int RECORD_LIMIT = 2048, COMMAND_LIMIT = 16384;
    private static final int REGION_BLOCKS = 2048;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_UPDATE_ITEMS = 32;
    private static final long UPDATE_NANOS = 2_000_000L, UPDATE_BYTES = 256 * 1024L;
    private final long maxBytes;
    private final IdentityHashMap<PredictionGpuTile, Entry> entries = new IdentityHashMap<>();
    private final HashMap<Region, ArrayList<Chunk>> regions = new HashMap<>();
    private final ArrayList<Chunk> ordered = new ArrayList<>();
    private final IdentityHashMap<PredictionGpuTile, Boolean> unique = new IdentityHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ArrayList<Entry>> meshOwners = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Entry> gpuOwners = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet changes = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final IdentityHashMap<Entry, Boolean> changedEntries = new IdentityHashMap<>();
    private final ByteBuffer record = MemoryUtil.memAlloc(64);
    private final ByteBuffer commands = MemoryUtil.memAlloc(COMMAND_LIMIT * 20);
    private final ByteBuffer bounds = MemoryUtil.memAlloc(COMMAND_LIMIT * 32);
    private final IdentityHashMap<PredictionGpuTile, SegmentPlan> plans = new IdentityHashMap<>();
    private List<PredictionRenderer.Draw> source;
    private IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> ranges;
    private long meshEpoch = Long.MIN_VALUE, gpuEpoch = Long.MIN_VALUE;
    private boolean rejected, building;
    private List<PredictionRenderer.Draw> admittedSource;
    private IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> admittedRanges;
    private final IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> admittedSelections = new IdentityHashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet admittedMembers = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long admittedGpu = Long.MIN_VALUE;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet rejectedMembers = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long frame, encodedItems, uploadedBytes, removedItems, preparationHits;
    private boolean average;
    private long cameraCellX = Long.MIN_VALUE, cameraCellZ = Long.MIN_VALUE;
    private final PredictionGpuLodGraph lod = new PredictionGpuLodGraph();
    private List<PredictionGpuLodGraph.Node> uploadedNodes = List.of();
    private int nodeBuffer, choiceBuffer, nodeCapacity;
    private int[] nodeHistory = new int[0];
    private PredictionGpuLodGraph.Node[] previousNodeScratch = new PredictionGpuLodGraph.Node[0];
    private int historyVersion;
    private ByteBuffer nodeBytes = MemoryUtil.memAlloc(64);
    private long nodeUploads, deferredUpdates;
    private int lastWorkItems;

    PredictionGpuScene() { this(MAX_BYTES); }
    PredictionGpuScene(long maxBytes) { this.maxBytes = maxBytes; }

    boolean update(List<PredictionRenderer.Draw> draws,
                   IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> selected, boolean nextAverage) {
        long nextMesh = PredictionPackedMesh.submissionEpoch();
        long nextGpu = PredictionGpuTile.submissionEpoch();
        var requestedDraws = draws;
        var requestedRanges = selected;
        var work = new UpdateWork();
        lastWorkItems = 0;
        if (rejected && source == draws && ranges == selected && average == nextAverage
                && !PredictionSubmissionChanges.GPU.affects(gpuEpoch, nextGpu, rejectedMembers)) {
            meshEpoch = nextMesh; gpuEpoch = nextGpu;
            return false;
        }
        boolean previouslyRejected = rejected;
        rejected = false;
        // Material discovery appends atlas mappings without changing existing
        // records. A real atlas/world reset already closes this scene through
        // PredictionRenderer.resetOcclusion(); it must not happen on discovery.
        frame++;
        if (!building && !previouslyRejected && source == draws && ranges == selected && average == nextAverage) {
            changes.clear(); changedEntries.clear();
            if (PredictionSubmissionChanges.MESH.collect(meshEpoch, nextMesh, meshOwners.keySet(), changes)
                    && PredictionSubmissionChanges.GPU.collect(gpuEpoch, nextGpu, gpuOwners.keySet(), changes)) {
                for (long id : changes) {
                    var owners = meshOwners.get(id);
                    if (owners != null) for (Entry entry : owners) changedEntries.put(entry, Boolean.TRUE);
                    Entry entry = gpuOwners.get(id);
                    if (entry != null) changedEntries.put(entry, Boolean.TRUE);
                }
                if (changedEntries.isEmpty()) {
                    meshEpoch = nextMesh; gpuEpoch = nextGpu; preparationHits++;
                    return true;
                }
                boolean local = true;
                for (Entry entry : changedEntries.keySet()) {
                    var gpu = entry.item.draw.gpu();
                    var slice = gpu.arenaSlice();
                    if (slice == null || slice.page != entry.chunk.page() || gpu.packed() != entry.item.packed) {
                        local = false; break;
                    }
                }
                if (local && changedEntries.size() <= MAX_UPDATE_ITEMS) {
                    lod.update(admittedSource, admittedRanges);
                    for (Entry entry : changedEntries.keySet()) {
                        var item = entry.item;
                        var slice = item.draw.gpu().arenaSlice();
                        if (entry.nodeIndex != nodeIndex(item.draw)
                                || !item.matches(item.draw, item.ranges, slice, false, nextAverage, true, item.commands)) {
                            if (!work.available()) { local = false; break; }
                            upload(entry, item.draw, item.ranges, slice, nextAverage);
                            work.add(uploadSize(entry.segments, entry.reserved));
                        }
                    }
                    if (local && (lod.nodes() == uploadedNodes || work.available())) {
                        uploadNodes();
                        meshEpoch = nextMesh; gpuEpoch = nextGpu; preparationHits++;
                        changes.clear(); changedEntries.clear();
                        lastWorkItems = work.items;
                        return true;
                    }
                }
            }
            changes.clear(); changedEntries.clear();
        }
        // Validate the entire input before changing any record. Standalone,
        // seam and duplicate payloads continue through the ordered fallback.
        // Incomplete preparations are never drawn. The caller keeps the exact
        // ordinary ownership pass until the latest complete scene is committed.
        building = true;
        unique.clear();
        for (var draw : draws) {
            var selectedRanges = selected.get(draw);
            if (selectedRanges == null || selectedRanges.quads == 0) continue;
            if (draw.gpu().packed() == null || draw.gpu().arenaSlice() == null) continue;
            if (unique.put(draw.gpu(), Boolean.TRUE) != null) {
                unique.clear();
                average = nextAverage;
                return reject(draws, selected, nextMesh, nextGpu);
            }
        }
        // Prune even when range preparation has to yield. Continuous movement
        // must not retain plans for every region visited during a cold build.
        plans.keySet().removeIf(gpu -> !unique.containsKey(gpu));
        unique.clear();
        for (var draw : draws) {
            var selectedRanges = selected.get(draw);
            if (selectedRanges == null || selectedRanges.quads == 0
                    || draw.gpu().packed() == null || draw.gpu().arenaSlice() == null) continue;
            var plan = plans.get(draw.gpu());
            if (plan == null || !plan.matches(draw, selectedRanges)) {
                if (!work.available()) return defer(work);
                var packed = draw.gpu().packed();
                var segments = packed.gpuRepresentations().clip(selectedRanges, packed.occlusionBounds, draw.seam());
                plan = new SegmentPlan(packed, selectedRanges, draw.seam(), segments);
                plans.put(draw.gpu(), plan);
                work.add(0);
            }
            if (plan.segments.masks().length > COMMAND_LIMIT) {
                average = nextAverage;
                return reject(draws, selected, nextMesh, nextGpu);
            }
        }
        // Prove the rounded page/region footprint BEFORE creating, clearing,
        // growing or uploading any GL buffer. A rejected forest must not pay
        // for a complete GPU scene on each newly published input version.
        boolean equivalent = matchesAdmission(draws, selected);
        boolean residencyChanged = PredictionSubmissionChanges.GPU.affects(admittedGpu, nextGpu, admittedMembers);
        if (!equivalent || residencyChanged) {
            if (!fitsBudget(draws, selected)) {
                average = nextAverage;
                return reject(draws, selected, nextMesh, nextGpu);
            }
            admittedSource = draws; admittedRanges = selected;
            admittedSelections.clear(); admittedMembers.clear();
            for (var draw : draws) {
                admittedSelections.put(draw, selected.get(draw));
                admittedMembers.add(draw.gpu().submissionId());
            }
        } else {
            // Keep a stable allocation order and graph token across equivalent
            // immutable publications. Equality is exact; no hash is a proof.
            draws = admittedSource; selected = admittedRanges;
        }
        admittedGpu = nextGpu;
        lod.update(draws, selected);
        for (var draw : draws) {
            var selectedRanges = selected.get(draw);
            if (selectedRanges == null || selectedRanges.quads == 0) continue;
            var slice = draw.gpu().arenaSlice();
            if (slice == null || draw.gpu().packed() == null) continue;
            var region = new Region(slice.page, Math.floorDiv(draw.tile().baseBlockX(), REGION_BLOCKS),
                    Math.floorDiv(draw.tile().baseBlockZ(), REGION_BLOCKS));
            Entry entry = entries.get(draw.gpu());
            var segments = plans.get(draw.gpu()).segments;
            int needed = segments.masks().length;
            if (entry != null && entry.chunk.region.equals(region) && needed <= entry.reserved
                    && entry.nodeIndex == nodeIndex(draw)
                    && entry.item.matches(draw, selectedRanges, slice, false, nextAverage, true, needed)) {
                entry.seen = frame;
                continue;
            }
            if (!work.available()) return defer(work);
            if (entry != null && !entry.chunk.region.equals(region)) {
                release(entry); entries.remove(draw.gpu()); entry = null;
            }
            if (entry != null && needed > entry.reserved) {
                // Only this tile moves if its command interval must grow.
                release(entry); entries.remove(draw.gpu()); entry = null;
            }
            if (entry == null) {
                entry = allocate(region, needed);
                entries.put(draw.gpu(), entry);
            }
            entry.seen = frame;
            upload(entry, draw, selectedRanges, slice, nextAverage);
            work.add(uploadSize(segments, entry.reserved));
        }
        for (var iterator = entries.values().iterator(); iterator.hasNext();) {
            Entry entry = iterator.next();
            if (entry.seen != frame) {
                if (!work.available()) return defer(work);
                work.add(entry.reserved * 80L);
                release(entry); iterator.remove();
            }
        }
        long bytes = nodeCapacity * 68L;
        ordered.clear();
        for (var iterator = regions.values().iterator(); iterator.hasNext();) {
            var chunks = iterator.next();
            for (var chunksIterator = chunks.iterator(); chunksIterator.hasNext();) {
                var chunk = chunksIterator.next();
                if (chunk.entries.isEmpty()) { chunk.slot.close(); chunksIterator.remove(); }
                else {
                    chunk.refresh(); ordered.add(chunk); bytes += chunk.slot.retainedBytes();
                }
            }
            if (chunks.isEmpty()) iterator.remove();
        }
        if (bytes > maxBytes) {
            average = nextAverage;
            return reject(draws, selected, nextMesh, nextGpu);
        }
        if (lod.nodes() != uploadedNodes && !work.available()) return defer(work);
        uploadNodes();
        // Rebuilding from the region map loses the previous sorted order even
        // when only the input objects/order changed and no GPU bytes changed.
        cameraCellX = cameraCellZ = Long.MIN_VALUE;
        meshOwners.clear(); gpuOwners.clear();
        for (Entry entry : entries.values()) {
            meshOwners.computeIfAbsent(entry.item.packed.submissionId(), unused -> new ArrayList<>()).add(entry);
            gpuOwners.put(entry.item.draw.gpu().submissionId(), entry);
        }
        source = requestedDraws; ranges = requestedRanges; average = nextAverage;
        rejectedMembers.clear();
        building = false;
        lastWorkItems = work.items;
        // Adopt the versions observed before validation. A publication racing
        // with this scan must invalidate the next update, not be swallowed.
        meshEpoch = nextMesh; gpuEpoch = nextGpu;
        return true;
    }

    private boolean reject(List<PredictionRenderer.Draw> draws,
                           IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> selected,
                           long mesh, long gpu) {
        boolean nextAverage = average;
        clear(); source = draws; ranges = selected; meshEpoch = mesh; gpuEpoch = gpu; rejected = true;
        average = nextAverage;
        for (var draw : draws) rejectedMembers.add(draw.gpu().submissionId());
        return false;
    }

    private boolean defer(UpdateWork work) {
        building = true;
        lastWorkItems = work.items;
        deferredUpdates++;
        unique.clear(); changes.clear(); changedEntries.clear();
        return false;
    }

    private static long uploadSize(PredictionGpuRepresentations.Segments segments, int reserved) {
        return 64L + segments.masks().length * 112L + reserved * 80L;
    }

    private boolean matchesAdmission(List<PredictionRenderer.Draw> draws,
                                     IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> selected) {
        if (admittedSource == null || draws.size() != admittedSource.size()) return false;
        if (draws == admittedSource && selected == admittedRanges) return true;
        for (var draw : draws) if (!admittedSelections.containsKey(draw)
                || !sameRanges(admittedSelections.get(draw), selected.get(draw))) return false;
        return true;
    }

    private static boolean sameRanges(PredictionDrawRanges a, PredictionDrawRanges b) {
        return a == b || a != null && b != null && a.quads == b.quads
                && java.util.Arrays.equals(a.first, b.first) && java.util.Arrays.equals(a.count, b.count);
    }

    /** A driver call cannot be interrupted; bound work between calls, with at
     * least one item of progress even when validation used the soft time slice. */
    private static final class UpdateWork {
        final long started = System.nanoTime();
        int items;
        long bytes;
        boolean available() {
            return items == 0 || items < MAX_UPDATE_ITEMS && bytes < UPDATE_BYTES
                    && System.nanoTime() - started < UPDATE_NANOS;
        }
        void add(long size) { items++; bytes += size; }
    }

    private record SegmentPlan(PredictionPackedMesh packed, PredictionDrawRanges ranges, boolean seam,
                               PredictionGpuRepresentations.Segments segments) {
        boolean matches(PredictionRenderer.Draw draw, PredictionDrawRanges selected) {
            return packed == draw.gpu().packed() && seam == draw.seam()
                    && sameRanges(ranges, selected);
        }
    }

    private boolean fitsBudget(List<PredictionRenderer.Draw> draws,
                               IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> selected) {
        var footprints = new HashMap<Region, ArrayList<Footprint>>();
        var existing = new IdentityHashMap<Chunk, Footprint>();
        long bytes = Math.max(nodeCapacity, powerOfTwo(draws.size() + 1)) * 68L;
        for (var chunks : regions.values()) for (var chunk : chunks) {
            var footprint = new Footprint(chunk);
            footprints.computeIfAbsent(chunk.region, unused -> new ArrayList<>()).add(footprint);
            existing.put(chunk, footprint); bytes += footprint.bytes();
        }
        for (var draw : draws) {
            var selectedRanges = selected.get(draw);
            var slice = draw.gpu().arenaSlice();
            if (selectedRanges == null || selectedRanges.quads == 0 || slice == null || draw.gpu().packed() == null) continue;
            var region = new Region(slice.page, Math.floorDiv(draw.tile().baseBlockX(), REGION_BLOCKS),
                    Math.floorDiv(draw.tile().baseBlockZ(), REGION_BLOCKS));
            int needed = plans.get(draw.gpu()).segments.masks().length;
            var entry = entries.get(draw.gpu());
            if (entry != null && entry.chunk.region.equals(region) && needed <= entry.reserved) continue;
            if (entry != null) {
                var old = existing.get(entry.chunk);
                old.records.release(entry.record, 1); old.commands.release(entry.first, entry.reserved);
            }
            int reserved = powerOfTwo(needed);
            var chunks = footprints.computeIfAbsent(region, unused -> new ArrayList<>());
            boolean placed = false;
            for (var chunk : chunks) {
                long before = chunk.bytes();
                if (chunk.allocate(reserved)) { bytes += chunk.bytes() - before; placed = true; break; }
            }
            if (!placed) {
                var chunk = new Footprint(null); chunks.add(chunk);
                if (!chunk.allocate(reserved)) return false;
                bytes += chunk.bytes();
            }
            if (bytes > maxBytes) return false;
        }
        return bytes <= maxBytes;
    }

    /** CPU-only copy of allocator state; mirrors buffer rounding, including safe commands. */
    private static final class Footprint {
        final SlotAllocator records, commands;
        int recordCapacity, commandCapacity;
        Footprint(Chunk chunk) {
            records = chunk == null ? new SlotAllocator(RECORD_LIMIT) : new SlotAllocator(chunk.records);
            commands = chunk == null ? new SlotAllocator(COMMAND_LIMIT) : new SlotAllocator(chunk.commands);
            recordCapacity = chunk == null ? 0 : chunk.slot.residentRecordCapacity();
            commandCapacity = chunk == null ? 0 : chunk.slot.residentCommandCapacity();
        }
        boolean allocate(int reserved) {
            int record = records.allocate(1);
            if (record < 0) return false;
            int first = commands.allocate(reserved);
            if (first < 0) { records.release(record, 1); return false; }
            recordCapacity = Math.max(recordCapacity, Math.max(64, powerOfTwo(record + 1)));
            commandCapacity = Math.max(commandCapacity, Math.max(256, powerOfTwo(first + reserved)));
            return true;
        }
        long bytes() {
            return 260L + recordCapacity * 64L + commandCapacity * 132L + ((commandCapacity + 63L) / 64) * 32;
        }
    }

    private void upload(Entry entry, PredictionRenderer.Draw draw, PredictionDrawRanges selected,
                        PredictionTerrainArena.Slice slice, boolean nextAverage) {
        var plan = plans.get(draw.gpu());
        var segments = plan != null && plan.matches(draw, selected) ? plan.segments : null;
        if (segments == null && entry.item != null && entry.item.draw == draw
                && entry.item.ranges == selected && entry.item.packed == draw.gpu().packed()) segments = entry.segments;
        if (segments == null) segments = draw.gpu().packed().gpuRepresentations().clip(selected,
                draw.gpu().packed().occlusionBounds, draw.seam());
        entry.segments = segments;
        int needed = segments.masks().length;
        entry.item = new PredictionIndirectBatch.PlanItem(draw, selected, slice, false, nextAverage, true, needed);
        record.clear(); commands.clear(); bounds.clear();
        PredictionSubmissionEncoding.write(draw, segments.ranges(), slice, false, nextAverage, true,
                entry.record, record, commands, bounds);
        entry.nodeIndex = nodeIndex(draw);
        for (int i = 0; i < needed; i++) {
            bounds.putInt(i * 32 + 28, entry.nodeIndex);
            bounds.putInt(i * 32 + 12, bounds.getInt(i * 32 + 12)
                    | segments.masks()[i] << 8 | segments.kinds()[i] << 12);
        }
        if (draw.coarseProxy()) record.putInt(48, record.getInt(48) | 2);
        record.flip(); commands.flip(); bounds.flip();
        entry.chunk.slot.uploadResident(entry.record, entry.first, record, commands, bounds);
        entry.chunk.slot.uploadFallback(entry.first, commands, segments, draw.coarseProxy());
        entry.chunk.slot.clearResident(entry.first + needed, entry.reserved - needed);
        entry.chunk.dirty = true;
        entry.chunk.updatedFrame = frame;
        encodedItems++;
        uploadedBytes += record.remaining() + commands.remaining() + bounds.remaining()
                + needed * 60L + (entry.reserved - needed) * 80L;
    }

    private Entry allocate(Region region, int needed) {
        var chunks = regions.computeIfAbsent(region, unused -> new ArrayList<>());
        int reserved = powerOfTwo(needed);
        for (Chunk chunk : chunks) {
            int metadata = chunk.records.allocate(1);
            if (metadata < 0) continue;
            int first = chunk.commands.allocate(reserved);
            if (first < 0) { chunk.records.release(metadata, 1); continue; }
            return entry(chunk, metadata, first, reserved);
        }
        var chunk = new Chunk(region);
        chunks.add(chunk);
        return entry(chunk, chunk.records.allocate(1), chunk.commands.allocate(reserved), reserved);
    }

    private static Entry entry(Chunk chunk, int record, int first, int reserved) {
        chunk.slot.ensureResidentCapacity(record + 1, first + reserved);
        var entry = new Entry(chunk, record, first, reserved);
        chunk.entries.add(entry);
        return entry;
    }

    private void release(Entry entry) {
        entry.chunk.slot.clearResident(entry.first, entry.reserved);
        uploadedBytes += entry.reserved * 80L;
        entry.chunk.updatedFrame = frame;
        entry.chunk.records.release(entry.record, 1);
        entry.chunk.commands.release(entry.first, entry.reserved);
        entry.chunk.entries.remove(entry);
        entry.chunk.dirty = true;
        removedItems++;
    }

    List<Chunk> ordered(Vec3 camera) {
        long x = (long) Math.floor(camera.x / 128), z = (long) Math.floor(camera.z / 128);
        if (cameraCellX != x || cameraCellZ != z) {
            double cx = x * 128 + 64, cz = z * 128 + 64;
            for (Chunk chunk : ordered) {
                double dx = Math.max(0, Math.max(chunk.minX - cx, cx - chunk.maxX));
                double dz = Math.max(0, Math.max(chunk.minZ - cz, cz - chunk.maxZ));
                chunk.distance = dx * dx + dz * dz;
                int bucket = 0;
                double edge = 256.0 * 256;
                while (bucket < 7 && chunk.distance >= edge) { bucket++; edge *= 4; }
                chunk.bucket = bucket + 1;
            }
            ordered.sort(Comparator.comparingInt((Chunk c) -> c.bucket).thenComparingDouble(c -> c.distance));
            cameraCellX = x; cameraCellZ = z;
        }
        return ordered;
    }

    long encodedItems() { return encodedItems; }
    long uploadedBytes() { return uploadedBytes; }
    long removedItems() { return removedItems; }
    long preparationHits() { return preparationHits; }
    int retainedRecords() { return entries.size(); }
    boolean contains(PredictionRenderer.Draw draw) { return entries.containsKey(draw.gpu()); }
    int nodeBuffer() { return nodeBuffer; }
    int choiceBuffer() { return choiceBuffer; }
    int nodeCount() { return lod.capacity(); }
    int lastWorkItems() { return lastWorkItems; }
    long nodeUploads() { return nodeUploads; }
    long deferredUpdates() { return deferredUpdates; }

    private int nodeIndex(PredictionRenderer.Draw draw) {
        var node = lod.node(draw.gpu());
        return node == null ? 0 : node.index();
    }

    private void uploadNodes() {
        var next = lod.nodes();
        if (next == uploadedNodes) return;
        int required = Math.max(1, powerOfTwo(lod.capacity()));
        if (required > nodeCapacity) {
            if (nodeBuffer == 0) { nodeBuffer = org.lwjgl.opengl.GL45C.glCreateBuffers(); choiceBuffer = org.lwjgl.opengl.GL45C.glCreateBuffers(); }
            org.lwjgl.opengl.GL45C.glNamedBufferData(nodeBuffer, required * 64L, org.lwjgl.opengl.GL45C.GL_DYNAMIC_DRAW);
            org.lwjgl.opengl.GL45C.glNamedBufferData(choiceBuffer, required * 4L, org.lwjgl.opengl.GL45C.GL_DYNAMIC_DRAW);
            org.lwjgl.opengl.GL45C.glClearNamedBufferData(choiceBuffer, org.lwjgl.opengl.GL45C.GL_R32UI,
                    org.lwjgl.opengl.GL45C.GL_RED_INTEGER, org.lwjgl.opengl.GL45C.GL_UNSIGNED_INT, new int[]{0});
            nodeHistory = java.util.Arrays.copyOf(nodeHistory, required);
            previousNodeScratch = new PredictionGpuLodGraph.Node[required];
            nodeCapacity = required;
        }
        // Publish one coherent graph, including zeroed holes for removed slots.
        // Cold forests used to issue a separate 64-byte GL call for every node.
        int size = Math.max(1, lod.capacity()) * 64;
        if (nodeBytes.capacity() < size) nodeBytes = MemoryUtil.memRealloc(nodeBytes, required * 64);
        nodeBytes.clear().limit(size);
        MemoryUtil.memSet(MemoryUtil.memAddress(nodeBytes), 0, size);
        for (var node : uploadedNodes) previousNodeScratch[node.index()] = node;
        for (var node : next) {
            // Reuse the node's spare word for an identity token. The GPU
            // validates history without a separate GL update per changed node.
            if (!node.equals(previousNodeScratch[node.index()])) {
                historyVersion = (historyVersion + 1) & 0x03ffffff;
                if (historyVersion == 0) historyVersion = 1;
                nodeHistory[node.index()] = historyVersion;
            }
            var b = node.draw().bounds();
            nodeBytes.position(node.index() * 64);
            nodeBytes.putFloat((float)b.minX).putFloat((float)b.minY).putFloat((float)b.minZ).putFloat(node.error());
            nodeBytes.putFloat((float)b.maxX).putFloat((float)b.maxY).putFloat((float)b.maxZ).putInt(node.parent());
            nodeBytes.putInt(node.children()?1:0).putInt(node.alternative()?1:0).putInt(1).putInt(node.representations());
            nodeBytes.putFloat(node.error1()).putFloat(node.error2()).putFloat(node.error3()).putInt(nodeHistory[node.index()]);
        }
        java.util.Arrays.fill(previousNodeScratch, null); // never pin retired tile/mesh records
        nodeBytes.position(0).limit(size);
        org.lwjgl.opengl.GL45C.glNamedBufferSubData(nodeBuffer, 0, nodeBytes);
        uploadedBytes += size;
        nodeUploads++;
        uploadedNodes = next;
    }
    boolean updated(Chunk chunk) { return chunk.updatedFrame == frame; }
    // Package-visible for exact slot/command invariants in GPU regression.
    Entry entry(PredictionGpuTile gpu) { return entries.get(gpu); }

    static int powerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    static final class SlotAllocator {
        private final BitSet used = new BitSet();
        private final int capacity;
        SlotAllocator(int capacity) { this.capacity = capacity; }
        SlotAllocator(SlotAllocator source) { this.capacity = source.capacity; used.or(source.used); }
        int allocate(int count) {
            if (count <= 0 || count > capacity || (count & (count - 1)) != 0)
                throw new IllegalArgumentException("Slot interval must be a bounded power of two");
            int at = 0;
            while (at <= capacity - count) {
                at = used.nextClearBit(at);
                at = (at + count - 1) & -count;
                if (at > capacity - count) break;
                int collision = used.nextSetBit(at);
                if (collision < 0 || collision >= at + count) { used.set(at, at + count); return at; }
                at = collision + 1;
            }
            return -1;
        }
        void release(int first, int count) { used.clear(first, first + count); }
        int extent() { return used.length(); }
    }

    private record Region(PredictionTerrainArena.Page page, int x, int z) { }

    static final class Entry {
        final Chunk chunk;
        final int record, first, reserved;
        PredictionIndirectBatch.PlanItem item;
        PredictionGpuRepresentations.Segments segments;
        long seen;
        int nodeIndex;
        Entry(Chunk chunk, int record, int first, int reserved) {
            this.chunk = chunk; this.record = record; this.first = first; this.reserved = reserved;
        }
    }

    static final class Chunk {
        final Region region;
        final PredictionIndirectBatch.BatchSlot slot = new PredictionIndirectBatch.BatchSlot();
        final SlotAllocator records = new SlotAllocator(RECORD_LIMIT);
        final SlotAllocator commands = new SlotAllocator(COMMAND_LIMIT);
        final ArrayList<Entry> entries = new ArrayList<>();
        boolean dirty = true;
        int bucket, commandCount, phases;
        long quads, activeCommands;
        long updatedFrame;
        double minX, minZ, maxX, maxZ, distance;
        Chunk(Region region) { this.region = region; }
        PredictionTerrainArena.Page page() { return region.page; }
        void refresh() {
            if (!dirty) return;
            minX = minZ = Double.POSITIVE_INFINITY;
            maxX = maxZ = Double.NEGATIVE_INFINITY;
            quads = activeCommands = 0; phases = 0;
            for (Entry entry : entries) {
                var b = entry.item.draw.bounds();
                minX = Math.min(minX, b.minX); minZ = Math.min(minZ, b.minZ);
                maxX = Math.max(maxX, b.maxX); maxZ = Math.max(maxZ, b.maxZ);
                quads += entry.item.ranges.quads;
                activeCommands += entry.item.commands;
                for (int kind : entry.segments.kinds()) phases |= 1 << (kind & 3);
            }
            commandCount = commands.extent();
            dirty = false;
        }
    }

    void clear() {
        for (var chunks : regions.values()) for (Chunk chunk : chunks) chunk.slot.close();
        entries.clear(); regions.clear(); ordered.clear(); unique.clear(); plans.clear();
        meshOwners.clear(); gpuOwners.clear(); changes.clear(); changedEntries.clear(); source = null; ranges = null;
        meshEpoch = gpuEpoch = Long.MIN_VALUE;
        admittedSource = null; admittedRanges = null; admittedGpu = Long.MIN_VALUE;
        admittedSelections.clear(); admittedMembers.clear();
        rejected = building = false;
        lastWorkItems = 0;
        rejectedMembers.clear();
        lod.clear(); uploadedNodes = List.of();
        if (nodeBuffer != 0) org.lwjgl.opengl.GL45C.glDeleteBuffers(nodeBuffer);
        if (choiceBuffer != 0) org.lwjgl.opengl.GL45C.glDeleteBuffers(choiceBuffer);
        nodeBuffer = choiceBuffer = nodeCapacity = 0;
        nodeHistory = new int[0]; historyVersion = 0;
        previousNodeScratch = new PredictionGpuLodGraph.Node[0];
        cameraCellX = cameraCellZ = Long.MIN_VALUE;
    }

    @Override public void close() {
        clear(); MemoryUtil.memFree(record); MemoryUtil.memFree(commands); MemoryUtil.memFree(bounds); MemoryUtil.memFree(nodeBytes);
    }
}
