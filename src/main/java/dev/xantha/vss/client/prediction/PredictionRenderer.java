package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.VSSApi;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.config.VSSClientConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * 预测 GPU 场景的渲染线程入口，拥有上传驻留、静态提交计划、深度交接和绘制状态。
 * <p>消费 PredictionTileManager 的带版本 CPU 快照；网格按编码模式上传，着色器展开四边形。
 * 相机/时间变化通过动态参数更新，覆盖依据 Voxy 已上传节点与当前深度；请求完成不等于可绘制。
 * OpenGL 创建、上传、绘制和释放均限渲染线程；世界关闭/资源重载应释放缓冲、查询和暂存。
 * 不得在此等待生成任务、读取磁盘或复制网络会话状态；异步结果先验证版本再进入 GPU 生命周期。
 */
public final class PredictionRenderer {
    public static void invalidateVanillaChunk(int x, int z) { vanillaMask.markDirty(x, z); }
    public static void invalidateVanillaCoverage() { vanillaMask.invalidatePending(); }
    private static final PredictionRenderTarget predictionTarget = new PredictionRenderTarget();
    private static final PredictionEdgeFilter edgeFilter = new PredictionEdgeFilter();
    private static boolean resetEdgeFilter;
    private static final PredictionVanillaMask vanillaMask = new PredictionVanillaMask();
    private static final PredictionExactCoverageMask exactMask = new PredictionExactCoverageMask();
    private static final AtomicLong frameStarts = new AtomicLong();
    private static final AtomicLong renderFrames = new AtomicLong();
    private static long detailProfileSession;
    private static final AtomicLong seenTiles = new AtomicLong();
    private static final AtomicLong consideredCells = new AtomicLong();
    private static final AtomicLong renderedCells = new AtomicLong();
    private static final AtomicLong culledTiles = new AtomicLong();
    private static final AtomicLong skippedCoverage = new AtomicLong();
    private static final AtomicLong skippedAuthoritative = new AtomicLong();
    private static final AtomicLong meshUploads = new AtomicLong();
    private static final AtomicLong coverageCacheHits = new AtomicLong();
    private static final AtomicLong coverageResolves = new AtomicLong();
    private static final AtomicLong coverageNanos = new AtomicLong();
    private static final AtomicLong drawCalls = new AtomicLong();
    private static final AtomicLong indirectCalls = new AtomicLong();
    private static final AtomicLong indirectTiles = new AtomicLong();
    private static final AtomicLong submittedQuads = new AtomicLong();
    private static final AtomicLong groupedDrawCalls = new AtomicLong();
    private static final PredictionOpaqueBatches opaqueBatches = new PredictionOpaqueBatches();
    private static int frameOpaqueBuckets;
    private static int frameOpaquePageGroups;
    private static int frameOpaqueFallbacks;
    private static java.nio.IntBuffer rangeCounts = org.lwjgl.BufferUtils.createIntBuffer(VssLodFaceGroup.COUNT);
    private static org.lwjgl.PointerBuffer rangeOffsets = org.lwjgl.BufferUtils.createPointerBuffer(VssLodFaceGroup.COUNT);
    /**
     * Frame-local atlas availability. Transient sampler failures may recover
     * on a later frame; they must not disable textures for the whole session.
     */
    private static volatile boolean detailPathEnabled = true;
    private static volatile int boundSpriteTableId = -1;
    private static long lastSamplerErrorNanos;
    private static volatile boolean programBroken;
    private static PredictionTerrainProgram program;
    private static PredictionIrisBridge.Pass irisPass;
    private static DeferredWater deferredWater;
    private static final AtomicLong planGeneration = new AtomicLong();
    private static final AtomicLong preparedPlans = new AtomicLong(), reusedPlans = new AtomicLong();
    private static final PredictionFramePlan<PreparedFrame> normalFramePlan = new PredictionFramePlan<>();
    record PreparedFrame(List<Draw> draws, long coverageRevision, long meshRevision) {
        boolean current(long coverageRevision, long meshRevision) {
            return this.coverageRevision == coverageRevision && this.meshRevision == meshRevision;
        }
    }
    private static final PreparedPass opaquePass = new PreparedPass(false);
    private static final PreparedPass residentOpaquePass = new PreparedPass(false, true);
    private static final PredictionGpuSelectionInput gpuSelectionInput = new PredictionGpuSelectionInput();
    private static long lastGpuSelectionUse;
    private static final PreparedPass waterPass = new PreparedPass(true);
    private static List<Draw> lastUsedDraws;
    private static long nextUsedRefresh;
    private static PredictionWorkView displayWorkView;
    private record PlanRequest(PredictionFramePlan<PreparedFrame> cache, Object viewport, int frame) { }
    private record DeferredWater(Frame frame, VssLodProjection.MatrixData projection,
                                 List<Draw> draws, ClientLevel level, RenderTarget target) { }
    record Frame(Matrix4f modelView, Matrix4f projection, Vec3 camera) { }
    private static final class PreparedDraw {
        long usedAt;
        private Draw draw;
        private PredictionDrawRanges ranges;
        private PredictionDrawRanges selectedRanges;
        private int directionMask = -1;
        private PredictionPackedMesh packed;
        private long meshVersion, gpuVersion;
        private int aquaticTier, displayTier, vegetationTier, groundCoverTier, fullQuads, terrainQuads, displayQuads, orderIndex = -1;
        private int waterSortIndex;
        Draw draw() { return draw; }
        PredictionDrawRanges ranges() { return ranges; }
    }

    /**
     * Render-thread metadata for one pass. Range construction is cheap after
     * the mesh cache is warm, but scanning the complete visible list happened
     * several times per frame (has-content, max-quads, then submission). Keep
     * those results together and check visible payloads when GPU residency
     * advances. Unrelated uploads keep the pass cached; ownership and display
     * revisions still invalidate it immediately.
     */
    private static final class PreparedPass {
        private final boolean water;
        private final boolean gpuResident;
        private List<Draw> source;
        private final ArrayList<PredictionPackedMesh> packedSources = new ArrayList<>();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet meshMembers = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet gpuMembers = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private long preparedResidencyRevision = Long.MIN_VALUE;
        private long directionSkipped;
        private long aquaticSkipped;
        private long sourceCandidates, terrainCandidates, displayCandidates, selectedCandidates;
        private int geometricDisplayTiles;
        private int retainedMorphTiles, activeMorphTiles, mixedOwnershipTiles, noTerrainVariantTiles;
        private long morphCompletedRefreshes;
        private Vec3 aquaticCamera;
        private int aquaticDistance;
        private boolean aquaticScoping;
        private double displayScale;
        private double displayErrorPixels;
        private int displayFullDistance;
        private PredictionWorkView previousDisplayView;
        private long nextMorphDeadline = Long.MAX_VALUE;
        private long preparedSubmissionEpoch = Long.MIN_VALUE, aquaticSubmissionEpoch = Long.MIN_VALUE;
        private long preparedGpuEpoch = Long.MIN_VALUE;
        private boolean membersValid;
        private final ArrayList<PreparedDraw> entries = new ArrayList<>();
        private IdentityHashMap<Draw, PreparedDraw> cachedEntries = new IdentityHashMap<>();
        private IdentityHashMap<Draw, PreparedDraw> nextEntries = new IdentityHashMap<>();
        private long rangeBuilds, rangeReuses, workloadRevision;
        private final PredictionCommandDirections.Region directionRegion = new PredictionCommandDirections.Region();
        private final IdentityHashMap<Draw, PredictionDrawRanges> rangeScratch = new IdentityHashMap<>();
        private IdentityHashMap<Draw, PredictionDrawRanges> byDraw = new IdentityHashMap<>();
        // Distance-band counters make the effect of display LOD observable in
        // the live diagnostics without walking every packed record again.
        private final long[] distanceSource = new long[4];
        private final long[] distanceTerrain = new long[4];
        private final long[] distanceDisplay = new long[4];
        private final long[] distanceSelected = new long[4];
        private Vec3 distanceCamera;
        private long nextDistanceRefresh;
        private int maxQuads;
        private boolean hasDraws;
        private boolean batchWorthwhile;
        private long hits;
        private long misses;
        private long revision;
        private boolean waterOrderDirty = true, waterOrderChanged;

        private PreparedPass(boolean water) { this(water, false); }
        private PreparedPass(boolean water, boolean gpuResident) { this.water = water; this.gpuResident = gpuResident; }

        private boolean irisBatchWorthwhile;
        private long irisBatchRevision = Long.MIN_VALUE;

        private double preparedCameraX = Double.NaN;
        private double preparedCameraY = Double.NaN;
        private double preparedCameraZ = Double.NaN;

        private void prepare(List<Draw> draws, long residencyRevision, Vec3 camera) {
            prepare(draws, residencyRevision, camera, System.nanoTime());
        }

        private long nextRecentPrune;
        private void prepare(List<Draw> draws, long residencyRevision, Vec3 camera, long now) {
            if (now >= nextRecentPrune) {
                nextRecentPrune = now + 500_000_000L;
                cachedEntries.entrySet().removeIf(e -> !byDraw.containsKey(e.getKey())
                        && (now - e.getValue().usedAt > 2_000_000_000L
                            || e.getKey().gpu().packed() != e.getValue().packed));
            }
            long gpuEpoch = PredictionGpuTile.submissionEpoch();
            long meshEpoch = PredictionPackedMesh.submissionEpoch();
            if (source == draws && (preparedSubmissionEpoch != meshEpoch || preparedGpuEpoch != gpuEpoch))
                ensureMembers(draws);
            // Resolve unrelated global invalidations before aquatic/direction and
            // range assembly. Actual members and ownership withdrawals still
            // take the exact local-version path in the current frame.
            if (source == draws && !PredictionSubmissionChanges.MESH.affects(preparedSubmissionEpoch, meshEpoch, meshMembers)) {
                if (aquaticSubmissionEpoch == preparedSubmissionEpoch) aquaticSubmissionEpoch = meshEpoch;
                preparedSubmissionEpoch = meshEpoch;
            }
            boolean unrelatedGpu = source == draws && preparedGpuEpoch != gpuEpoch
                    && !PredictionSubmissionChanges.GPU.affects(preparedGpuEpoch, gpuEpoch, gpuMembers);
            boolean sameGeometry = source == draws && (preparedResidencyRevision == residencyRevision
                    && preparedGpuEpoch == gpuEpoch || unrelatedGpu || packedSourcesMatch(draws, preparedGpuEpoch != gpuEpoch));
            boolean aquaticChanged = prepareAquatic(draws, camera, sameGeometry, now);
            boolean directionsChanged = !water && !gpuResident && prepareDirections(draws, camera, sameGeometry);
            if (sameGeometry
                    && preparedSubmissionEpoch == PredictionPackedMesh.submissionEpoch() && !aquaticChanged && !directionsChanged) {
                if (water && camera != null && (camera.x != preparedCameraX || camera.z != preparedCameraZ)) {
                    sortWater(camera);
                }
                preparedResidencyRevision = residencyRevision;
                preparedGpuEpoch = gpuEpoch;
                refreshDistanceBands(draws, camera, false);
                hits++;
                return;
            }
            if (source != draws) membersValid = false;
            boolean validatePackedMembers = membersValid && preparedGpuEpoch != gpuEpoch;
            misses++;
            revision++;
            int previousEntryCount = entries.size();
            entries.clear();
            nextEntries.clear();
            packedSources.clear();
            rangeScratch.clear();
            boolean rangesChanged = false;
            boolean workloadChanged = source == null || source.size() != draws.size();
            int sourceIndex = -1;
            int maximum = 0;
            boolean any = false;
            int arenaBacked = 0;
            directionSkipped = 0;
            aquaticSkipped = 0;
            sourceCandidates=terrainCandidates=displayCandidates=selectedCandidates=0;
            geometricDisplayTiles = 0;
            retainedMorphTiles = mixedOwnershipTiles = noTerrainVariantTiles = 0;
            Arrays.fill(distanceSource, 0L);
            Arrays.fill(distanceTerrain, 0L);
            Arrays.fill(distanceDisplay, 0L);
            Arrays.fill(distanceSelected, 0L);
            for (Draw draw : draws) {
                sourceIndex++;
                PredictionPackedMesh packed = draw.gpu().packed();
                packedSources.add(packed);
                if (packed == null) { if (validatePackedMembers) membersValid = false; continue; }
                packed.trackSubmission();
                var entry = cachedEntries.get(draw);
                if (validatePackedMembers && (entry == null || entry.packed != packed)) membersValid = false;
                if (entry == null) { entry = new PreparedDraw(); entry.draw = draw; }
                entry.usedAt = now;
                long meshVersion = packed.submissionRevision();
                boolean gpuVariants = gpuResident && !draw.seam() && !draw.coarseProxy()
                        && draw.gpu().arenaSlice() != null && draw.completeOwnership() && !draw.gpu().morphActive(now);
                int aquaticTier = water || draw.seam() || gpuResident ? 0 : packed.aquaticLod.tier();
                int displayTier = gpuVariants ? -1 : water || draw.seam() || gpuResident ? 0 : packed.displayLod.tier();
                int vegetationTier = water || draw.seam() || gpuResident || !draw.completeOwnership()
                        || draw.morph() != 0 && draw.gpu().morphActive(now) ? 0
                        : Math.max(aquaticTier, packed.vegetationLod.tier());
                int groundCoverTier = water || draw.seam() || gpuResident || !draw.completeOwnership()
                        || draw.morph() != 0 && draw.gpu().morphActive(now) ? 0 : packed.groundCoverLod.tier();
                if (!water && !draw.seam()) {
                    if (packed.morph() != null) retainedMorphTiles++;
                    if (!draw.completeOwnership()) mixedOwnershipTiles++;
                    if (!packed.hasTerrainDisplayLod()) noTerrainVariantTiles++;
                }
                boolean rebuild = entry.packed != packed || entry.meshVersion != meshVersion
                        || entry.aquaticTier != aquaticTier || entry.displayTier != displayTier
                        || entry.vegetationTier != vegetationTier || entry.groundCoverTier != groundCoverTier;
                if (rebuild) {
                    boolean terrainOnly = !draw.completeOwnership() && draw.completeTerrainOwnership()
                            && packed.hasTerrainOnlyDisplayLod();
                    var previousRanges = entry.ranges;
                    boolean payloadChanged = entry.packed != packed || entry.meshVersion != meshVersion;
                    PredictionDrawRanges selected = packed.drawRanges(water, draw.faces());
                    entry.fullQuads = selected.quads;
                    entry.terrainQuads = !water && !draw.seam()
                            ? packed.drawRanges(false, draw.faces(), 0, displayTier, 0, terrainOnly).quads : selected.quads;
                    if (gpuVariants) selected = packed.gpuRepresentations().union;
                    else if (!water && !draw.seam()) selected = packed.drawRanges(false, draw.faces(), aquaticTier, displayTier, vegetationTier, groundCoverTier, terrainOnly);
                    entry.displayQuads = selected.quads;
                    // Residency does not establish visibility. Keep the selected geometry
                    // for the current-frame depth/vanilla-mask test in the terrain program.
                    entry.selectedRanges = selected;
                    entry.packed = packed; entry.meshVersion = meshVersion;
                    entry.aquaticTier = aquaticTier; entry.displayTier = displayTier; entry.vegetationTier = vegetationTier; entry.groundCoverTier = groundCoverTier;
                    rangeBuilds++;
                    entry.ranges = entry.selectedRanges;
                    workloadChanged |= entry.ranges.quads > 0 && (payloadChanged || previousRanges != entry.ranges);
                } else rangeReuses++;
                int directionMask = water || gpuResident ? 127 : PredictionCommandDirections.mask(draw,camera);
                if (rebuild || entry.directionMask != directionMask) {
                    var previous = entry.ranges;
                    entry.ranges = PredictionCommandDirections.filter(packed.occlusionBounds,entry.selectedRanges,directionMask);
                    entry.directionMask = directionMask;
                    workloadChanged |= previous != entry.ranges;
                }
                nextEntries.put(draw, entry);
                PredictionDrawRanges ranges = entry.ranges;
                sourceCandidates += entry.fullQuads;
                terrainCandidates += entry.terrainQuads;
                aquaticSkipped += entry.fullQuads - entry.displayQuads;
                displayCandidates += entry.displayQuads;
                directionSkipped += entry.selectedRanges.quads - ranges.quads;
                selectedCandidates += ranges.quads;
                if (displayTier > 0) geometricDisplayTiles++;
                if (ranges.quads == 0) { entry.orderIndex = -1; continue; }
                if (draw.gpu().arenaSlice(water) != null) arenaBacked++;
                workloadChanged |= entry.orderIndex != sourceIndex || entry.gpuVersion != draw.gpu().submissionRevision();
                entry.orderIndex = sourceIndex;
                entry.gpuVersion = draw.gpu().submissionRevision();
                entries.add(entry);
                rangeScratch.put(draw, ranges);
                rangesChanged |= byDraw.get(draw) != ranges;
                maximum = Math.max(maximum, packed.quadCount());
                any = true;
            }
            source = draws;
            preparedResidencyRevision = residencyRevision;
            preparedSubmissionEpoch = PredictionPackedMesh.submissionEpoch();
            preparedGpuEpoch = gpuEpoch;
            // Keep the downstream page-group token when camera/coverage revisions
            // did not actually change the selected ranges. Never mutate a published map.
            if (rangesChanged || byDraw.size() != rangeScratch.size()) byDraw = new IdentityHashMap<>(rangeScratch);
            rangeScratch.clear();
            long recentBytes = 0;
            int recentCount = 0;
            for (var entry : cachedEntries.values()) {
                if (nextEntries.containsKey(entry.draw)) continue;
                if (now - entry.usedAt > 2_000_000_000L || entry.draw.gpu().packed() != entry.packed
                        || entry.meshVersion != entry.packed.submissionRevision()) continue;
                long bytes = entry.draw.tile().retainedHeapBytes();
                if (++recentCount > 128 || recentBytes + bytes > 16L * 1024 * 1024) continue;
                recentBytes += bytes; nextEntries.put(entry.draw, entry);
            }
            var oldEntries = cachedEntries; cachedEntries = nextEntries; nextEntries = oldEntries;
            nextEntries.clear();
            refreshDistanceBands(draws, camera, true);
            if (workloadChanged || previousEntryCount != entries.size()) workloadRevision++;
            maxQuads = maximum;
            hasDraws = any;
            batchWorthwhile = arenaBacked >= 64;
            if (camera != null) {
                preparedCameraX = camera.x;
                preparedCameraY = camera.y;
                preparedCameraZ = camera.z;
            }
            if (water && camera != null) { waterOrderDirty = true; sortWater(camera); }
        }

        private boolean prepareDirections(List<Draw> draws,Vec3 camera,boolean sameGeometry) {
            if (sameGeometry && preparedSubmissionEpoch == PredictionPackedMesh.submissionEpoch()
                    && directionRegion.contains(camera)) return false;
            directionRegion.begin(camera);
            boolean changed = false;
            for (Draw draw : draws) {
                if (draw.gpu().packed() == null) continue;
                directionRegion.include(draw,camera);
                var entry=cachedEntries.get(draw);
                changed |= entry == null || entry.directionMask != PredictionCommandDirections.mask(draw,camera);
            }
            return changed;
        }

        private void ensureMembers(List<Draw> draws) {
            if (membersValid) return;
            meshMembers.clear(); gpuMembers.clear();
            for (var draw : draws) {
                gpuMembers.add(draw.gpu().submissionId());
                var packed = draw.gpu().packed();
                if (packed != null) meshMembers.add(packed.submissionId());
            }
            membersValid = true;
        }

        private boolean packedSourcesMatch(List<Draw> draws, boolean gpuChanged) {
            if (packedSources.size() != draws.size()) return false;
            for (int i = 0; i < draws.size(); i++) {
                var draw = draws.get(i);
                if (packedSources.get(i) != draw.gpu().packed()) return false;
                if (gpuChanged) {
                    var entry = cachedEntries.get(draw);
                    if (entry != null && entry.ranges.quads > 0 && entry.gpuVersion != draw.gpu().submissionRevision()) return false;
                }
            }
            return true;
        }

        private boolean prepareAquatic(List<Draw> draws, Vec3 camera, boolean sameGeometry, long now) {
            if (water || camera == null) return false;
            if (gpuResident) {
                // GPU evaluates distance, projection, detail and hierarchy every frame.
                // CPU only rebuilds static choices after the upload's morph expires.
                if (sameGeometry && now < nextMorphDeadline) return false;
                nextMorphDeadline = Long.MAX_VALUE;
                for (var draw : draws) if (draw.gpu().morphActive(now))
                    nextMorphDeadline = Math.min(nextMorphDeadline, draw.gpu().morphEndsAt());
                return true;
            }
            boolean morphExpired = now >= nextMorphDeadline;
            int distance = (int) Math.ceil(Math.max(VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks,
                    Math.min(4096, selectionScale * .5)));
            double terrainError = PredictionDisplayLod.terrainPixelError(VSSClientConfig.CONFIG.predictionDetail);
            int fullDistance = VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks;
            boolean contextStable = camera.equals(aquaticCamera) && distance == aquaticDistance
                    && selectionScoping == aquaticScoping && displayScale == selectionScale
                    && displayErrorPixels == terrainError && displayFullDistance == fullDistance
                    && (displayWorkView == null ? previousDisplayView == null : !displayWorkView.changedFrom(previousDisplayView));
            if (sameGeometry && contextStable && !morphExpired
                    && aquaticSubmissionEpoch == PredictionPackedMesh.submissionEpoch()) return false;
            boolean changed = false;
            if (morphExpired) morphCompletedRefreshes++;
            long nextDeadline = Long.MAX_VALUE;
            activeMorphTiles = 0;
            for (Draw draw : draws) {
                var packed = draw.gpu().packed();
                if (draw.seam() || packed == null || !packed.hasDisplayLod()) continue;
                boolean morphActive = draw.morph() != 0 && draw.gpu().morphActive(now);
                if (morphActive) activeMorphTiles++;
                if (morphActive) nextDeadline = Math.min(nextDeadline, draw.gpu().morphEndsAt());
                if (contextStable && !morphExpired) {
                    var cached = cachedEntries.get(draw);
                    if (cached != null && cached.packed == packed && cached.meshVersion == packed.submissionRevision()) continue;
                }
                var tile = draw.tile();
                double horizontalSquared = PredictionAquaticLod.distanceSquared(camera.x, camera.z,
                        tile.baseBlockX(), tile.baseBlockZ(), tile.spanBlocks());
                double horizontal = Math.sqrt(horizontalSquared);
                changed |= packed.aquaticLod.update(horizontal, distance, selectionScoping);
                double vertical = Math.max(0, Math.max(draw.bounds().minY - camera.y, camera.y - draw.bounds().maxY));
                double eyeDepth = displayWorkView == null ? Math.sqrt(horizontalSquared + vertical * vertical) : displayWorkView.nearestDepth(draw.bounds());
                double pixelScale = selectionScale;
                if (displayWorkView != null) {
                    // Perspective error includes displacement toward the eye and
                    // off-axis projection. Use the farthest box corner as a bound.
                    double farX = Math.max(Math.abs(draw.bounds().minX - camera.x), Math.abs(draw.bounds().maxX - camera.x));
                    double farY = Math.max(Math.abs(draw.bounds().minY - camera.y), Math.abs(draw.bounds().maxY - camera.y));
                    double farZ = Math.max(Math.abs(draw.bounds().minZ - camera.z), Math.abs(draw.bounds().maxZ - camera.z));
                    pixelScale *= 1 + Math.sqrt(farX * farX + farY * farY + farZ * farZ) / Math.max(1, eyeDepth);
                }
                // The actual projection already magnifies the screen error when
                // zooming. Keep source detail only when a safe representation or
                // a real scoped projection is unavailable, not for every scoped tile.
                boolean forceSource = morphActive || !(draw.completeOwnership()
                        || packed.hasTerrainOnlyDisplayLod() && draw.completeTerrainOwnership())
                        || selectionScoping && displayWorkView == null;
                changed |= packed.displayLod.update(packed.displayErrors(), eyeDepth, pixelScale,
                        VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks, forceSource, terrainError);
                if (packed.hasVegetationLod()) changed |= packed.vegetationLod.update(PredictionVegetationRanges.ERRORS,
                        eyeDepth, pixelScale, VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks,
                        forceSource || !draw.completeOwnership());
                if (packed.hasGroundCoverLod()) changed |= packed.groundCoverLod.update(eyeDepth, pixelScale,
                        VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks, forceSource || !draw.completeOwnership());
            }
            aquaticCamera = camera;
            aquaticDistance = distance;
            aquaticScoping = selectionScoping;
            displayScale = selectionScale;
            displayErrorPixels = terrainError;
            displayFullDistance = fullDistance;
            previousDisplayView = displayWorkView;
            nextMorphDeadline = nextDeadline;
            aquaticSubmissionEpoch = PredictionPackedMesh.submissionEpoch();
            return changed;
        }

        /**
         * Opaque submission uses the stable visible-plan order. Water still
         * needs an exact back-to-front order, so reorder the already prepared
         * entries in place when only the camera moved. This keeps ownership
         * filtering and range construction cached while preserving blending.
         */
        private void sortWater(Vec3 camera) {
            waterOrderChanged = waterOrderDirty;
            final double cx = camera.x, cz = camera.z;
            entries.sort((left, right) -> {
                Draw a = left.draw(), b = right.draw();
                double ax = a.tile().baseBlockX() + a.tile().spanBlocks() * 0.5D - cx;
                double az = a.tile().baseBlockZ() + a.tile().spanBlocks() * 0.5D - cz;
                double bx = b.tile().baseBlockX() + b.tile().spanBlocks() * 0.5D - cx;
                double bz = b.tile().baseBlockZ() + b.tile().spanBlocks() * 0.5D - cz;
                int compared = Double.compare(ax * ax + az * az, bx * bx + bz * bz);
                // TimSort compares every existing run. A strict inversion of
                // the last stable ranks means the output order must change;
                // equal distances retain their stable order. No extra pre-scan.
                if (!waterOrderChanged && (compared < 0 && left.waterSortIndex > right.waterSortIndex
                        || compared > 0 && left.waterSortIndex < right.waterSortIndex)) waterOrderChanged = true;
                return compared;
            });
            if (waterOrderChanged) {
                revision++;
                for (int i = 0; i < entries.size(); i++) entries.get(i).waterSortIndex = i;
            }
            waterOrderDirty = false;
            preparedCameraX = camera.x;
            preparedCameraY = camera.y;
            preparedCameraZ = camera.z;
        }

        private PredictionDrawRanges ranges(Draw draw) {
            return byDraw.get(draw);
        }

        // Refresh diagnostics on the render thread at most once per second
        // when submission ranges are unchanged. Band crossings must not
        // invalidate the static MDI plan or scan individual geometry records.
        private void refreshDistanceBands(List<Draw> draws, Vec3 camera, boolean force) {
            if (camera == null) return;
            long now = System.nanoTime();
            if (!force && (camera.equals(distanceCamera) || now < nextDistanceRefresh)) return;
            Arrays.fill(distanceSource, 0L);
            Arrays.fill(distanceTerrain, 0L);
            Arrays.fill(distanceDisplay, 0L);
            Arrays.fill(distanceSelected, 0L);
            for (Draw draw : draws) {
                var entry = cachedEntries.get(draw);
                if (entry == null || entry.ranges == null) continue;
                int band = distanceBand(PredictionAquaticLod.distance(camera.x, camera.z,
                        draw.tile().baseBlockX(), draw.tile().baseBlockZ(), draw.tile().spanBlocks()));
                distanceSource[band] += entry.fullQuads;
                distanceTerrain[band] += entry.terrainQuads;
                distanceDisplay[band] += entry.displayQuads;
                distanceSelected[band] += entry.ranges.quads;
            }
            distanceCamera = camera;
            nextDistanceRefresh = now + 1_000_000_000L;
        }

        private List<PreparedDraw> entries() { return entries; }
        private int maxQuads() { return maxQuads; }
        private boolean hasDraws() { return hasDraws; }
        private boolean batchWorthwhile() { return batchWorthwhile; }
        private boolean irisBatchWorthwhile() {
            if (irisBatchRevision == revision) return irisBatchWorthwhile;
            irisBatchRevision = revision;
            int arenaBacked = 0, pageRuns = 0;
            PredictionTerrainArena.Page previousPage = null;
            for (var entry : entries) {
                var slice = entry.draw.gpu().arenaSlice(false);
                if (slice == null) { previousPage = null; continue; }
                arenaBacked++;
                if (slice.page != previousPage) pageRuns++;
                previousPage = slice.page;
            }
            // Iris preserves order. Compute this only for its own submission;
            // normal pass preparation must not pay for adapter layout checks.
            return irisBatchWorthwhile = arenaBacked >= 256 && pageRuns > 0 && arenaBacked / pageRuns >= 8;
        }
        private IdentityHashMap<Draw, PredictionDrawRanges> rangesByDraw() { return byDraw; }
        private void clear() {
            source = null;
            preparedResidencyRevision = Long.MIN_VALUE;
            directionSkipped = 0;
            aquaticSkipped = 0;
            sourceCandidates=terrainCandidates=displayCandidates=selectedCandidates=0;
            aquaticCamera = null;
            previousDisplayView = null;
            nextMorphDeadline = Long.MAX_VALUE;
            preparedSubmissionEpoch = aquaticSubmissionEpoch = Long.MIN_VALUE;
            preparedGpuEpoch = Long.MIN_VALUE;
            directionRegion.clear();
            geometricDisplayTiles = 0;
            retainedMorphTiles = activeMorphTiles = mixedOwnershipTiles = noTerrainVariantTiles = 0;
            Arrays.fill(distanceSource, 0L);
            Arrays.fill(distanceTerrain, 0L);
            Arrays.fill(distanceDisplay, 0L);
            Arrays.fill(distanceSelected, 0L);
            distanceCamera = null;
            nextDistanceRefresh = 0L;
            entries.clear();
            cachedEntries.clear(); nextEntries.clear(); workloadRevision++;
            packedSources.clear();
            meshMembers.clear(); gpuMembers.clear(); membersValid = false;
            rangeScratch.clear();
            byDraw = new IdentityHashMap<>();
            maxQuads = 0;
            hasDraws = false;
            batchWorthwhile = false;
            irisBatchWorthwhile = false;
            irisBatchRevision = Long.MIN_VALUE;
            preparedCameraX = Double.NaN;
            preparedCameraY = Double.NaN;
            preparedCameraZ = Double.NaN;
        }
        private String diagnostics() { return "hits=" + hits + ",misses=" + misses
                + ",rangeBuilds=" + rangeBuilds + ",rangeReuses=" + rangeReuses + ",workloadRevision=" + workloadRevision
                + ",directionExcludedQuads=" + directionSkipped
                + ",displayExcludedQuads=" + aquaticSkipped + ",geometricDisplayTiles=" + geometricDisplayTiles
                + ",retainedMorphTiles=" + retainedMorphTiles + ",activeMorphTiles=" + activeMorphTiles
                + ",morphCompletedRefreshes=" + morphCompletedRefreshes
                + ",mixedOwnershipTiles=" + mixedOwnershipTiles + ",noTerrainVariantTiles=" + noTerrainVariantTiles
                + ",sourceCandidates=" + sourceCandidates + ",displayCandidates=" + displayCandidates
                + ",terrainCandidates=" + terrainCandidates + ",vegetationExcludedQuads=" + (terrainCandidates - displayCandidates)
                + ",afterVoxyCandidates=" + selectedCandidates
                + ",distanceBands=" + distanceSource[0] + "/" + distanceSource[1] + "/" + distanceSource[2] + "/" + distanceSource[3]
                + "->" + distanceTerrain[0] + "/" + distanceTerrain[1] + "/" + distanceTerrain[2] + "/" + distanceTerrain[3]
                + "->" + distanceDisplay[0] + "/" + distanceDisplay[1] + "/" + distanceDisplay[2] + "/" + distanceDisplay[3]
                + "->" + distanceSelected[0] + "/" + distanceSelected[1] + "/" + distanceSelected[2] + "/" + distanceSelected[3]
                + ",screenErrorPixels=" + displayErrorPixels; }

        private Map<String,Object> demandSnapshot() {
            var result = new LinkedHashMap<String,Object>();
            result.put("sourceQuads",sourceCandidates); result.put("terrainQuads",terrainCandidates);
            result.put("displayQuads",displayCandidates); result.put("selectedQuads",selectedCandidates);
            result.put("directionExcluded",directionSkipped);
            result.put("noTerrainVariantTiles",noTerrainVariantTiles); result.put("mixedOwnershipTiles",mixedOwnershipTiles);
            result.put("activeMorphTiles",activeMorphTiles); result.put("hits",hits); result.put("misses",misses);
            result.put("rangeBuilds",rangeBuilds); result.put("rangeReuses",rangeReuses);
            result.put("distanceSource",distanceSource.clone()); result.put("distanceTerrain",distanceTerrain.clone());
            result.put("distanceDisplay",distanceDisplay.clone()); result.put("distanceSelected",distanceSelected.clone());
            result.put("screenErrorPixels",displayErrorPixels);
            return result;
        }

        private static int distanceBand(double distance) {
            if (distance < 256.0) return 0;
            if (distance < 512.0) return 1;
            if (distance < 1024.0) return 2;
            return 3;
        }

    }
    private static int sharedVertexArray = -1;
    private static int sharedIndexBuffer = -1;
    private static int sharedIndexQuads;
    private static volatile boolean resetQuadPool;
    private static volatile boolean resetIndirectBatch;

    private static final Map<PredictionTileManager.PredictionTileKey, PredictionGpuTile> gpuTiles =
            new ConcurrentHashMap<>();
    private static final Map<PredictionTileManager.PredictionTileKey, CachedCoverage> coverageCache =
            new ConcurrentHashMap<>();
    private static final PredictionRenderResidency renderResidency = new PredictionRenderResidency();
    private static final PredictionGpuResidency<PredictionTileManager.PredictionTileKey> gpuResidency = PredictionGpuResidency.runtime();
    private static final Scene scene = new Scene();
    private static final SeamBatch seamBatch = new SeamBatch();
    private static final SeamBatch realSeamBatch = new SeamBatch();
    private static final PredictionUploadBudget uploadBudget = new PredictionUploadBudget();
    private static final long COVERAGE_RECHECK_NANOS = 250_000_000L;

    /**
     * Global per-chunk coverage decisions shared across tiles in a frame.
     * The same chunk's yield/desired-LOD computation ran once per owning
     * tile per resolve (a near-field chunk appears in lod 0..3 tiles at
     * once), each pass walking up to twenty ancestor lookups.  A shared
     * frame-scoped cache turns that into one computation per chunk.
     */
    private static final byte UNKNOWN_DECISION = Byte.MIN_VALUE;
    // Render-thread only. Primitive keys mix the entire packed coordinate,
    // avoid Long.hashCode's x^z collisions and allocate no per-chunk arrays.
    private static final Long2ByteOpenHashMap chunkDecisionCache = newDecisionCache();
    private static final PredictionCoverageOwners coverageOwners = new PredictionCoverageOwners();
    private static volatile long decisionCachePixelsBits = Long.MIN_VALUE;
    private static double decisionCachePixelThreshold;
    private static int decisionCachePlayerChunkX = Integer.MIN_VALUE;
    private static int decisionCachePlayerChunkZ = Integer.MIN_VALUE;
    private static volatile VssLodFocus decisionFocus;
    private static long decisionCacheExpiresAt;
    private static volatile long lastRenderLogNanos;

    private PredictionRenderer() {
    }

    /** Runs the packed-quad pass after vanilla/Voxy cutout terrain. */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            boolean active = VSSClientConfig.CONFIG.enablePrediction && VSSClientConfig.CONFIG.predictionAntialiasing
                    && !programBroken && !PredictionIrisBridge.shadersActive() && normalFogActive();
            if (resetEdgeFilter || !active) { edgeFilter.close(); resetEdgeFilter = false; }
            if (active) {
                long start = PredictionRenderTimings.start();
                int query = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.ANTIALIAS);
                try { edgeFilter.render(Minecraft.getInstance().getMainRenderTarget(), event.getProjectionMatrix()); }
                finally {
                    PredictionRenderTimings.gpuEnd(query);
                    PredictionRenderTimings.end(PredictionRenderTimings.Stage.ANTIALIAS, start);
                }
            }
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS)
            PredictionRenderCapture.terrainComplete();
        if (PredictionRenderCapture.active() && !PredictionIrisBridge.shadersActive()) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
                PredictionRenderCapture.checkpoint("vanilla-after-entity-submit");
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                PredictionRenderCapture.checkpoint("vanilla-after-level");
                PredictionRenderCapture.finish("vanilla-after-level");
            }
        }
        if (!VSSClientConfig.CONFIG.enablePrediction || programBroken || PredictionIrisBridge.shadersActive()) {
            return;
        }
        // Terrain supplies the background before vanilla translucent blocks.
        // Water waits for their depth so it cannot tint real water a second time.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            if (!PredictionNormalTranslucencyBridge.rendered(Minecraft.getInstance().getMainRenderTarget().frameBufferId))
                renderStage(new Frame(event.getPoseStack().last().pose(), event.getProjectionMatrix(), event.getCamera().getPosition()));
            PredictionRenderCapture.current("vanilla-after-cutout");
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            renderDeferredWater();
            PredictionRenderCapture.current("vanilla-after-translucent");
        }
    }

    /** Forwarded once per frame by the registered VSSClientNetworking subscriber. */
    public static void onRenderFrame(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
        PredictionFogBridge.beginFrame();
        PredictionRenderCapture.beginFrame();
        PredictionFramePace.recordFrame();
        PredictionRenderTimings.frame(frameStarts.incrementAndGet());
        if (PredictionDemandProfile.enabled()) PredictionDemandProfile.observeMenu(Minecraft.getInstance().screen != null);
        PredictionIrisBridge.beginFrame();
        deferredWater = null;
        PredictionVoxyDepth.clear();
        PredictionNormalTranslucencyBridge.beginFrame();
        uploadBudget.reset();
        drainRetiredTiles();
    }

    static void renderIris(Frame frame, PredictionIrisBridge.Pass pass,
                           PredictionTileManager.RenderSnapshot snapshot,
                           PredictionFramePlan<PreparedFrame> cache, Object viewport, int frameId) {
        if (!VSSClientConfig.CONFIG.enablePrediction) return;
        PredictionTerrainProgram previous = program;
        irisPass = pass;
        program = pass.program();
        try { renderStage(frame, snapshot, new PlanRequest(cache, viewport, frameId)); }
        finally { irisPass = null; program = previous; }
    }

    static int normalDepthTexture() { return predictionTarget.depthTextureId(); }
    static int normalSeedTexture() { return predictionTarget.seedTextureId(); }

    static boolean renderBeforeVoxyWater(Frame event) {
        if (programBroken || !VSSClientConfig.CONFIG.enablePrediction) return false;
        long before = renderFrames.get();
        renderStage(event);
        return !programBroken && renderFrames.get() != before;
    }

    private static void renderStage(Frame event) {
        ClientLevel level = Minecraft.getInstance().level;
        long start = PredictionRenderTimings.start();
        var snapshot = level == null ? null : ClientPredictionState.renderSnapshot(level.dimension());
        PredictionRenderTimings.end(PredictionRenderTimings.Stage.SNAPSHOT, start);
        renderStage(event, snapshot,
                new PlanRequest(normalFramePlan, Minecraft.getInstance().getMainRenderTarget(), 0));
    }

    private static void renderStage(Frame event, PredictionTileManager.RenderSnapshot snapshot) {
        renderStage(event, snapshot, null);
    }

    private static void renderStage(Frame event, PredictionTileManager.RenderSnapshot snapshot, PlanRequest request) {
        try (var unpack = PredictionPixelUnpack.begin()) {
            renderStageUnpacked(event, snapshot, request);
        }
    }

    private static void renderStageUnpacked(Frame event, PredictionTileManager.RenderSnapshot snapshot, PlanRequest request) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        selectionScale = VssLodProjection.selectionScale(event.projection(),
                minecraft.getMainRenderTarget().height);
        selectionScoping = minecraft.player.isScoping();
        viewRay = VssLodProjection.centerRay(event.projection(), event.modelView(), event.camera());
        double refinementScale = VssLodProjection.refinementScale(selectionPixelsPerBlock(minecraft),
                basePixelsPerBlock(minecraft), selectionScoping, VSSClientConfig.CONFIG.predictionSpyglassLoading);
        PredictionWorkView renderedWorkView = PredictionWorkView.rendered(event.projection(), event.modelView(),
                event.camera(), viewRay.direction(), refinementScale);
        displayWorkView = renderedWorkView;
        if (irisPass == null || !irisPass.translucent()) ClientPredictionState.publishWorkView(level.dimension(), renderedWorkView);
        if (irisPass != null) ClientPredictionState.publishVisibilityFeedback(
                level.dimension(), PredictionVisibilityFeedback.Snapshot.EMPTY);
        if (snapshot == null) return;
        StrictLodVisibility.updateRenderWindow(minecraft.level.dimension(), event.camera().x, event.camera().z,
                ModCompat.getVoxyViewDistanceChunks().orElse(0));
        Collection<PredictionTileManager.PredictionTile> tiles = snapshot.tiles().values();
        if (tiles.isEmpty()) {
            return;
        }
        if (!PredictionGraphicsSupport.available(irisPass != null)) return;
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (irisPass == null) predictionTarget.ensure(mainTarget);
        if (irisPass == null && !predictionTarget.available()) {
            return;
        }

        var sourceSnapshot = snapshot;
        long totalPrepareStart = PredictionRenderTimings.start();
        Vec3 camera = event.camera();
        long coverageStateStart = PredictionRenderTimings.start();
        exactMask.update(level, camera, handoffDistanceChunks(VSSApi.clientSession().getEffectiveLodDistanceChunks()));
        PredictionRenderTimings.end(PredictionRenderTimings.Stage.COVERAGE_STATE, coverageStateStart);
        Matrix4f vanillaProjection = new Matrix4f(event.projection());
        VssLodProjection.MatrixData projection = VssLodProjection.of(vanillaProjection);
        Frustum lodFrustum = new Frustum(event.modelView(), projection.culling());
        lodFrustum.prepare(camera.x, camera.y, camera.z);
        long residencyStart = PredictionRenderTimings.start();
        if (irisPass == null || !irisPass.translucent()) maintainGpuResidency(sourceSnapshot, camera, lodFrustum);
        PredictionRenderTimings.end(PredictionRenderTimings.Stage.GPU_RESIDENCY, residencyStart);
        PreparedFrame prepared = null;
        if (request != null) {
            int width = irisPass == null ? minecraft.getMainRenderTarget().width : irisPass.width();
            int height = irisPass == null ? minecraft.getMainRenderTarget().height : irisPass.height();
            if (irisPass == null) {
                // Moving cameras cannot reuse the previous matrices. Reject that
                // plan before walking every deferred upload outside the horizon.
                prepared = request.cache().getStable(request.viewport(), level, snapshot, planGeneration.get(),
                        renderResidency.revision(), width, height, event);
                if (prepared != null && (scene.boundaryPending() || renderResidency.hasPendingUploads(snapshot,
                        tile -> tile.mesh().gpuPayload() != null && tileWithinHorizon(tile, camera, lodFrustum)
                                && allowsGpuUpload(tile, camera, lodFrustum))))
                    prepared = null;
            } else if (irisPass.translucent()) {
                prepared = request.cache().get(request.viewport(), level, snapshot,
                        frameStarts.get(), planGeneration.get(), request.frame(), width, height, event);
            }
        }
        if (prepared != null && !prepared.current(ClientPredictionState.exactCoverageRemovalRevision(), PredictionPackedMesh.submissionEpoch()))
            prepared = null;
        long prepareStart = PredictionRenderTimings.start();
        if (irisPass == null || !irisPass.translucent()) {
            long maskStart = PredictionRenderTimings.start();
            var previousEdges = vanillaMask.groundEdges();
            vanillaMask.update(minecraft, camera);
            if (vanillaMask.groundEdges() != previousEdges) prepared = null;
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.VANILLA_MASK, maskStart);
        }
        int playerChunkX = minecraft.player.getBlockX() >> 4;
        int playerChunkZ = minecraft.player.getBlockZ() >> 4;
        int nearDistance = VSSApi.clientSession().getEffectiveLodDistanceChunks();
        if (nearDistance <= 0) {
            // During login (and in single-player before the local handshake)
            // the server radius is temporarily zero. Prediction already has
            // its own horizon and must continue rendering while the session
            // distance is negotiated; otherwise the first frame shows only
            // sky/flat fallback and never starts the progressive handoff.
            nearDistance = Math.max(1, ModCompat.getVoxyViewDistanceChunks()
                    .orElse(minecraft.options.renderDistance().get()));
        }
        double pixelsPerBlock = basePixelsPerBlock(minecraft);

        long considered = 0L;
        long rendered = 0L;
        long coverageSkipped = 0L;
        long authoritativeSkipped = 0L;
        long frameCoverageResolves = 0L;
        long frameDrawCalls = 0L;
        long frameQuads = 0L;
        long frameCoverageNanos = 0L;
        int minSampleY = Integer.MAX_VALUE;
        int maxSampleY = Integer.MIN_VALUE;
        List<Draw> draws = List.of();
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // drop errors raised before this pass; they are not ours
            }
            // Uploads must use a captured local unit, not an arbitrary Voxy pack unit.
            PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
            if (irisPass == null) {
                long depthCopyStart = PredictionRenderTimings.start();
                int depthCopyQuery = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.DEPTH_COPY);
                try {
                    // Seed the isolated reversed-depth target with the depth
                    // already produced by vanilla/Voxy.  The fixed-function
                    // test can then reject hidden prediction geometry before
                    // it reaches the ownership and material fragment path.
                    PredictionVoxyDepth.Frame voxyDepth = PredictionVoxyDepth.current(
                            mainTarget.frameBufferId, mainTarget.width, mainTarget.height, camera);
                    predictionTarget.beginOpaque(mainTarget, projection, voxyDepth,
                            new Matrix4f(event.projection()).mul(event.modelView()));
                    if (PredictionNormalTranslucencyBridge.preparing()) predictionTarget.captureSeed();
                } finally {
                    PredictionRenderTimings.gpuEnd(depthCopyQuery);
                    PredictionRenderTimings.end(PredictionRenderTimings.Stage.DEPTH_COPY, depthCopyStart);
                }
            }
            PredictionGlState.enableDepthTest();
            PredictionGlState.depthFunc(irisPass == null ? GL11.GL_GEQUAL : irisPass.depthFunc());
            PredictionGlState.disableCull();
            if (irisPass == null || !irisPass.translucent()) PredictionGlState.disableBlend();
            PredictionGlState.depthMask(true);

            if (prepared == null) {
                // Any preparation can replace shared GPU resources, including an interleaved view.
                planGeneration.incrementAndGet();
                long uploadPreparationStart = System.nanoTime();
                renderResidency.retain(snapshot);
                if (irisPass == null || !irisPass.translucent()) {
                    // A completed handoff frees its upload window in this frame.
                    // Poll before admitting new GPU/ownership changes, or a new
                    // vanilla edge snapshot can keep invalidating useful work.
                    scene.pollBoundaries();
                    // Establish coverage before allocating small descendants. The
                    // old same-key mesh remains resident until its upgrade uploads.
                    VssLodLayout uploadLayout = snapshot.layout();
                    VssLodFocus uploadFocus = ClientPredictionState.currentFocus();
                    var uploadOrder = PredictionUploadSelection.order(renderResidency, uploadLayout,
                            camera.x, camera.z, uploadFocus);
                    var uploads = PredictionUploadSelection.select(renderResidency.pendingUploads(snapshot),
                            tile -> tileWithinHorizon(tile, camera, lodFrustum)
                                    && (!PredictionMotionPace.fastMoving() || tile.scopeOnly()
                                        || !renderResidency.hasResidentCover(tile.key(), uploadLayout))
                                    && allowsGpuUpload(tile, camera, lodFrustum),
                            uploadOrder, PredictionUploadBudget.MAX_TILES + 4);
                    uploadBudget.recordPreparation(System.nanoTime() - uploadPreparationStart);
                    int coldRequests = 0;
                    for (var tile : uploads) {
                        // Keep at most one old/new upload window while its
                        // atomic boundary transaction is prepared off-thread.
                        if (scene.boundaryPending()) break;
                        if (renderResidency.contains(tile) || tile.mesh().gpuPayload() == null) continue;
                        if (PredictionMotionPace.fastMoving() && !tile.scopeOnly()
                                && renderResidency.hasResidentCover(tile.key(), uploadLayout)) continue;
                        if (!allowsGpuUpload(tile, camera, lodFrustum)) continue;
                        long bytes = tile.mesh().gpuPayload().uploadBytes();
                        if (!uploadBudget.allows(bytes)) break;
                        if (!tile.mesh().gpuPayload().uploadReady()) {
                            if (++coldRequests >= 4) break;
                            continue;
                        }
                        long start = System.nanoTime();
                        var previousGpu = gpuTiles.get(tile.key());
                        var gpu = previousGpu == null || scene.references(previousGpu) && !previousGpu.hasMesh(tile)
                                ? new PredictionGpuTile(tile.key()) : previousGpu;
                        if (gpu.ensureMesh(tile)) meshUploads.incrementAndGet();
                        if (!gpu.hasMesh(tile)) { if (gpu != previousGpu) gpu.close(); continue; }
                        if (gpu != previousGpu) {
                            gpuTiles.put(tile.key(), gpu);
                            if (previousGpu != null) retiredTiles.add(previousGpu);
                        }
                        uploadBudget.record(bytes, System.nanoTime() - start);
                        PredictionRenderTimings.end(PredictionRenderTimings.Stage.UPLOAD, prepareStart == 0 ? 0 : start);
                        renderResidency.uploaded(tile);
                        gpuResidency.uploaded(tile.key(), tile.revision(), gpu.residentBytes());
                    }
                }
                snapshot = renderResidency.snapshot(snapshot);
                var changes=renderResidency.drainChanges();
                for(var key:changes) if(!snapshot.tiles().containsKey(key)) {
                    var retired=gpuTiles.remove(key);if(retired!=null) retiredTiles.add(retired);
                    if (!sourceSnapshot.tiles().containsKey(key) || !gpuResidency.cold(key)) gpuResidency.removed(key);
                    coverageCache.remove(key);
                }
                ClientPredictionState.drainCoverageHot(level.dimension());
                CoverageView view = new CoverageView(playerChunkX, playerChunkZ,
                        pixelsPerBlock, ClientPredictionState.currentFocus());
                draws=scene.prepare(snapshot,changes,view,camera,predictionHorizonBlocks(),nearDistance,
                        event.modelView(),projection.culling(),lodFrustum);
                if (!scene.boundaryPending()) ClientPredictionState.publishDrawableSnapshot(snapshot);
                considered=scene.considered;rendered=scene.rendered;
                coverageSkipped=scene.coverageSkipped;authoritativeSkipped=scene.authoritativeSkipped;
                minSampleY=scene.minY();maxSampleY=scene.maxY();
                frameCoverageResolves=scene.resolves;frameCoverageNanos=scene.resolveNanos;
                if (request != null && !scene.boundaryPending() && (irisPass == null || !irisPass.translucent())) request.cache().put(request.viewport(), level,
                        sourceSnapshot, frameStarts.get(), planGeneration.get(), request.frame(),
                        renderResidency.revision(),
                        irisPass == null ? mainTarget.width : irisPass.width(),
                        irisPass == null ? mainTarget.height : irisPass.height(),
                        event, new PreparedFrame(draws, ClientPredictionState.exactCoverageRemovalRevision(), PredictionPackedMesh.submissionEpoch()));
                preparedPlans.incrementAndGet();
            } else {
                draws = prepared.draws();
                reusedPlans.incrementAndGet();
            }
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.PREPARE, prepareStart);
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.PREPARE_TOTAL, totalPrepareStart);
            long usedAt = System.nanoTime();
            if (draws != lastUsedDraws || usedAt >= nextUsedRefresh) {
                for (var draw : draws) if (!draw.seam()) gpuResidency.used(draw.tile().key(), usedAt);
                lastUsedDraws = draws;
                nextUsedRefresh = usedAt + PredictionGpuResidency.SWEEP_NANOS;
            }
            if (!draws.isEmpty() && ensureProgram()) {
                long quadsBeforePass = submittedQuads.get();
                var timingStage = irisPass != null && irisPass.translucent()
                        ? PredictionRenderTimings.Stage.WATER : PredictionRenderTimings.Stage.OPAQUE;
                long drawStart = PredictionRenderTimings.start();
                int query = PredictionRenderTimings.gpuStart(timingStage);
                try {
                    frameDrawCalls = irisPass == null ? drawPasses(minecraft, event, draws, camera, projection, false)
                            : drawIris(minecraft, event, draws, camera, projection);
                } finally {
                    PredictionRenderTimings.gpuEnd(query);
                    PredictionRenderTimings.end(timingStage, drawStart);
                }
                frameQuads = submittedQuads.get() - quadsBeforePass;
                if (irisPass == null && preparePass(draws, true, camera).hasDraws()) deferredWater = new DeferredWater(
                        new Frame(new Matrix4f(event.modelView()), new Matrix4f(event.projection()), camera),
                        projection, draws, level, mainTarget);
            }
            if (irisPass == null) {
                if (refinementOcclusion == null) refinementOcclusion = new PredictionRefinementOcclusion();
                var visibility = refinementOcclusion.sample(sourceSnapshot, renderedWorkView,
                        new Matrix4f(projection.matrix()).mul(event.modelView()), camera,
                        predictionTarget.depthTextureId(), mainTarget.width, mainTarget.height, System.nanoTime());
                ClientPredictionState.publishVisibilityFeedback(level.dimension(), visibility);
                predictionTarget.restore(mainTarget);
                predictionTarget.writeDepth(mainTarget, projection, deferredWater != null);
            }
        } catch (Throwable failure) {
            deferredWater = null;
            dev.xantha.vss.common.VSSLogger.warn(
                    "VSS prediction pass disabled after a render error", failure);
            if (irisPass != null) throw new IllegalStateException("VSS Iris draw failed", failure);
            programBroken = true;
        } finally {
            if (irisPass == null) predictionTarget.restore(mainTarget);
            PredictionGlState.depthFunc(GL11.GL_LEQUAL);
            PredictionGlState.depthMask(true);
            PredictionGlState.enableCull();
            PredictionGlState.disableBlend();
        }
        renderFrames.incrementAndGet();
        seenTiles.addAndGet(tiles.size());
        consideredCells.addAndGet(considered);
        renderedCells.addAndGet(rendered);
        skippedCoverage.addAndGet(coverageSkipped);
        skippedAuthoritative.addAndGet(authoritativeSkipped);
        long now = System.nanoTime();
        profileDemand(event, level, frameQuads, frameDrawCalls, now);
        if (VSSClientConfig.CONFIG.debugLogging && now - lastRenderLogNanos > 5_000_000_000L) {
            lastRenderLogNanos = now;
            dev.xantha.vss.common.VSSLogger.debug("VSS prediction render: tiles=" + draws.size()
                    + ", considered=" + considered
                    + ", rendered=" + rendered
                    + ", coverageSkipped=" + coverageSkipped
                    + ", authoritativeSkipped=" + authoritativeSkipped
                    + ", meshUploads=" + meshUploads.get()
                    + ", " + uploadBudget.diagnostics()
                    + ", preparedPlans=" + preparedPlans.get() + ", reusedPlans=" + reusedPlans.get()
                    + ", timings={" + PredictionRenderTimings.diagnostics() + "}"
                    + ", drawCallsCurrentPass=" + frameDrawCalls
                    + ", quadsCurrentPass=" + frameQuads
                    + ", opaqueBuckets=" + frameOpaqueBuckets
                    + ", opaquePageGroups=" + frameOpaquePageGroups
                    + ", opaqueFallbacks=" + frameOpaqueFallbacks
                    + ", indirect={" + (indirectBatch == null ? "none" : indirectBatch.diagnostics()) + "}"
                    + ", passCache={opaque=" + opaquePass.diagnostics() + ",water=" + waterPass.diagnostics() + "}"
                    + ", " + (refinementOcclusion == null ? "visibilityHints=none" : refinementOcclusion.diagnostics())
                    + ", coverageResolves=" + frameCoverageResolves
                    + ", coverageMs=" + String.format(java.util.Locale.ROOT, "%.2f",
                    frameCoverageNanos / 1_000_000.0D)
                    + ", " + vanillaMask.diagnostics() + ", boundaryIndex={" + scene.boundaries.diagnostics() + "," + PredictionSeamMasks.diagnostics() + "}"
                    + ", ownership={" + scene.ownershipDiagnostics() + "}"
                    + ", gpuTiles=" + gpuTiles.size()
                    + ", gpuResidency={" + gpuResidency.diagnostics() + "}"
                    + ", " + PredictionTerrainArena.SHARED.diagnostics()
                    + ", textures=" + textureDiagnostics(draws)
                    + ", memory={" + PredictionMemoryBudget.SHARED.diagnostics() + "}"
                    + ", buildThrottled=" + PredictionMemoryBudget.SHARED.exhausted()
                    + ", activeBuilds=" + PredictionMemoryBudget.SHARED.activeBuildCount()
                    + "/" + PredictionMemoryBudget.SHARED.buildLimit()
                    + ", surface={" + ClientPredictionState.surfaceDiagnostics(level.dimension()) + "}"
                    + ", sampleY=" + (minSampleY == Integer.MAX_VALUE ? "none"
                    : minSampleY + ".." + maxSampleY));
        }
    }

    private static void profileDemand(Frame event, ClientLevel level, long quads, long draws, long now) {
        if (irisPass != null && irisPass.translucent() || !PredictionDemandProfile.due(frameStarts.get(),now)) return;
        Minecraft mc = Minecraft.getInstance();
        var row = new LinkedHashMap<String,Object>();
        row.put("session",detailProfileSession); row.put("monotonicNanos",now); row.put("frame",frameStarts.get());
        row.put("dimension",level.dimension().location().toString()); row.put("gameVersion",net.minecraft.SharedConstants.getCurrentVersion().getName());
        row.put("menuOpen",mc.screen != null); row.put("scoping",selectionScoping); row.put("iris",irisPass != null);
        row.put("camera",new double[]{event.camera().x,event.camera().y,event.camera().z});
        row.put("projection",event.projection().get(new float[16])); row.put("modelView",event.modelView().get(new float[16]));
        row.put("viewport",new int[]{mc.getMainRenderTarget().width,mc.getMainRenderTarget().height});
        row.put("selectionScale",selectionScale); row.put("detail",VSSClientConfig.CONFIG.predictionDetail);
        row.put("horizonBlocks",predictionHorizonBlocks()); row.put("surfaceDistanceBlocks",VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks);
        var frames = PredictionFramePace.snapshot();
        row.put("frameWindow",Map.of("samples",frames.samples(),"durationNanos",frames.durationNanos(),
                "p50Nanos",frames.p50Nanos(),"p95Nanos",frames.p95Nanos(),"p99Nanos",frames.p99Nanos(),
                "maxNanos",frames.maxNanos(),"over33ms",frames.over33ms(),"over50ms",frames.over50ms()));
        row.put("frameCounters",PredictionFramePace.counters());
        row.put("preparedPlans",preparedPlans.get()); row.put("reusedPlans",reusedPlans.get()); row.put("meshUploads",meshUploads.get());
        row.put("submittedQuads",submittedQuads.get()); row.put("currentOpaqueQuads",quads); row.put("currentOpaqueDraws",draws);
        row.put("opaque",opaquePass.demandSnapshot()); row.put("water",waterPass.demandSnapshot());
        row.put("gpuResidency",gpuResidency.snapshot()); row.put("timings",PredictionRenderTimings.snapshot());
        row.put("generation",ClientPredictionState.demandSnapshot(level.dimension()));
        PredictionDemandProfile.publish(row);
    }

    private static volatile double selectionScale;
    private static volatile VssLodProjection.ViewRay viewRay;

    static VssLodProjection.ViewRay viewRay() { return viewRay; }

    private static void updateCoverage(PredictionGpuTile gpu, boolean[] allowed) {
        updateCoverage(gpu, allowed, null);
    }

    private static void updateCoverage(PredictionGpuTile gpu, boolean[] allowed, PredictionCoverageGrid grid) {
        long start = System.nanoTime();
        int bytes = gpu.updatePublishedCoverage(allowed, grid);
        if (bytes != 0) uploadBudget.recordRequired(bytes, System.nanoTime() - start);
    }

    private static boolean allowsGpuUpload(PredictionTileManager.PredictionTile tile, Vec3 camera, Frustum frustum) {
        if (!gpuResidency.cold(tile.key())) return true;
        var packed = tile.mesh().gpuPayload();
        // Uploaded Voxy nodes can be unselected or occluded; only a resident
        // prediction parent may back a budget eviction.
        boolean owned = false;
        double dx = Math.max(Math.max(tile.baseBlockX() - camera.x,
                camera.x - tile.baseBlockX() - tile.spanBlocks()), 0);
        double dz = Math.max(Math.max(tile.baseBlockZ() - camera.z,
                camera.z - tile.baseBlockZ() - tile.spanBlocks()), 0);
        VssLodFocus focus = ClientPredictionState.currentFocus();
        boolean required = !renderResidency.hasEvictionFallback(tile) || dx * dx + dz * dz <= 256.0 * 256.0
                || focus != null && focus.intersects(tile.baseBlockX(), tile.baseBlockZ(),
                        tile.baseBlockX() + tile.spanBlocks(), tile.baseBlockZ() + tile.spanBlocks())
                || seamBatch.requiresTerrain(tile.key(), frustum) || realSeamBatch.requiresTerrain(tile.key(), frustum);
        return gpuResidency.allowsUpload(tile.key(), tile.revision(), tileWithinHorizon(tile, camera, frustum), owned, required);
    }

    private static void maintainGpuResidency(PredictionTileManager.RenderSnapshot source, Vec3 camera, Frustum frustum) {
        gpuResidency.retainColdStep(source.tiles()::containsKey);
        if (!gpuResidency.beginObservation()) return;
        var observed = new ArrayList<PredictionTileManager.PredictionTileKey>(64);
        VssLodFocus focus = ClientPredictionState.currentFocus();
        long until = System.nanoTime() + 500_000L;
        for (int checked = 0; checked < 64 && System.nanoTime() < until; checked++) {
            var key = gpuResidency.nextObservation(); if (key == null) break;
            var tile = renderResidency.resident(key); var gpu = gpuTiles.get(key);
            if (tile == null || gpu == null || gpu.packed() == null) continue;
            var geometry = scene.geometry.get(key);
            var bounds = geometry == null ? new PredictionRenderGeometry.Entry(tile).culling() : geometry.culling();
            double dx = Math.max(Math.max(bounds.minX - camera.x, camera.x - bounds.maxX), 0);
            double dz = Math.max(Math.max(bounds.minZ - camera.z, camera.z - bounds.maxZ), 0);
            boolean protectedTile = key.lod() >= source.layout().levelCount() - 2
                    || dx * dx + dz * dz <= 256.0 * 256.0
                    || focus != null && focus.intersects(bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ)
                    || seamBatch.requiresTerrain(key, frustum) || realSeamBatch.requiresTerrain(key, frustum);
            boolean visible = dx * dx + dz * dz <= predictionHorizonBlocks() * predictionHorizonBlocks()
                    && frustum.isVisible(bounds.inflate(64));
            var packed = gpu.packed();
            boolean owned = false;
            gpuResidency.observe(key, gpu.residentBytes(), visible, protectedTile,
                    renderResidency.hasEvictionFallback(key), owned, ClientPredictionState.exactCoverageRemovalRevision());
            var stats = packed.geometryStats();
            gpuResidency.contribution(key, new long[]{packed.opaqueStorageBytes(), packed.waterStorageBytes(),
                    packed.storageBytes() + packed.waterStorageBytes(), stats.quads(), stats.rectangles(), stats.waterRectangles(),
                    stats.sourceQuads(), stats.displayVariantQuads(), stats.sourceTerrain(), stats.sourceFeatures(),
                    stats.sourcePreviews(), stats.sourceWater(), gpu.standaloneBytes()});
            observed.add(key);
        }
        long committed = PredictionTerrainArena.committedBytes() + PredictionQuadBufferPool.SHARED.retainedBytes()
                + gpuResidency.standaloneBytes() + seamBatch.standaloneBytes + realSeamBatch.standaloneBytes;
        for (var retirement : gpuResidency.retirements(committed, observed)) {
            // Only candidates observed with this frame's camera and coverage are
            // eligible. Recheck fallback after each preceding retirement.
            if (!renderResidency.hasEvictionFallback(retirement.key())) continue;
            renderResidency.evict(retirement.key()); gpuResidency.evicted(retirement);
        }
    }

    /** Applies only changed patches; stable neighbors retain their Draw and GPU binding. */
    static final class SeamBatch {
        private final Map<PredictionTileManager.PredictionTileKey,PredictionGpuTile> tiles=new java.util.HashMap<>();
        private final Map<PredictionTileManager.PredictionTileKey,PredictionLodSeams.Patch> sources=new java.util.HashMap<>();
        private final Map<PredictionTileManager.PredictionTileKey,Draw> draws=new java.util.HashMap<>();
        private final PredictionSeamIndex<PredictionTileManager.PredictionTileKey> spatial = new PredictionSeamIndex<>();
        private long updates, standaloneBytes;
        long updates() { return updates; }

        boolean requiresTerrain(PredictionTileManager.PredictionTileKey key, Frustum frustum) {
            var draw = draws.get(key);
            // CPU patch retention is independent of GPU protection. Include the
            // actual connector height and a generous turn/edge margin.
            return draw != null && (frustum == null || frustum.isVisible(draw.bounds().inflate(64)));
        }

        Set<PredictionTileManager.PredictionTileKey> apply(PredictionLodSeams.Changes changes) {
            if(changes.patches().isEmpty() && changes.removed().isEmpty()) return Set.of();
            var changed=new HashSet<PredictionTileManager.PredictionTileKey>();
            for(var key:changes.removed()) {
                sources.remove(key);draws.remove(key);spatial.remove(key);
                var gpu=tiles.remove(key);if(gpu!=null) { standaloneBytes -= gpu.standaloneBytes(); retiredTiles.add(gpu); }
                changed.add(key);
            }
            for(var entry:changes.patches().entrySet()) {
                var patch=entry.getValue();var key=entry.getKey();
                if(sources.get(key)==patch) continue;
                updates++;changed.add(key);sources.put(key,patch);
                var tile=patch.surface().tile();
                var gpu=tiles.computeIfAbsent(key,PredictionGpuTile::new);
                long before = gpu.standaloneBytes();
                long start=System.nanoTime(),bytes=gpu.ensureSeams(patch.mesh());
                standaloneBytes += gpu.standaloneBytes() - before;
                if(bytes!=0) uploadBudget.recordRequired(bytes,System.nanoTime()-start);
                updateCoverage(gpu,patch.surface().allowed());
                draws.put(key,new Draw(tile,gpu,patch.surface().allowed(),true,VssLodFaceGroup.ALL,0,patch.bounds()));
                spatial.put(key, key, patch.bounds());
            }
            return changed;
        }

        void clear() {
            retiredTiles.addAll(tiles.values());tiles.clear();sources.clear();draws.clear();spatial.clear();standaloneBytes=0;
        }
    }

    private record DrawKey(PredictionTileManager.PredictionTileKey tile,int kind) { }

    /** Stable prediction scene. Voxy uploads affect scheduling, never draw membership. */
    static final class Scene {
        private final PredictionSeamPreparation boundaries;
        private final Map<PredictionTileManager.PredictionTileKey, Draw> publishedTerrain = new java.util.HashMap<>();
        private final IdentityHashMap<PredictionGpuTile, Integer> publishedGpu = new IdentityHashMap<>();
        private final Set<PredictionTileManager.PredictionTileKey> stagedKeys = new HashSet<>();
        private long boundaryVersion, publishedBoundaryVersion;
        private List<PredictionRealBoundarySeams.Edge> boundaryEdges = List.of();
        Scene() { this(new PredictionSeamPreparation()); }
        Scene(java.util.concurrent.Executor executor) { this(new PredictionSeamPreparation(executor)); }
        private Scene(PredictionSeamPreparation preparation) {
            visible = new PredictionVisiblePlan<>(Draw::bounds);
            boundaries = preparation;
        }
        private List<Draw> select(Vec3 camera, Matrix4f modelView, Matrix4f projection,
                                  Frustum frustum, Set<DrawKey> candidates) {
            return visible.selectStable(camera, modelView, projection, frustum, candidates);
        }
        boolean boundaryPending() { return boundaries.pending() || boundaryVersion != publishedBoundaryVersion; }
        boolean references(PredictionGpuTile gpu) { return publishedGpu.containsKey(gpu) || gpuAlternatives.containsKey(gpu); }
        private final IdentityHashMap<PredictionGpuTile, Boolean> gpuAlternatives = new IdentityHashMap<>();
        private List<Draw> gpuBase, gpuCandidates = List.of();
        private PredictionTileManager.RenderSnapshot gpuSnapshot;
        private long gpuEpoch = -1, gpuRetryAt;
        List<Draw> residentDraws() {
            var base = visible.residents();
            if (snapshot == null || boundaryPending()) {
                gpuBase = null; gpuAlternatives.clear(); return gpuCandidates = base;
            }
            long epoch = PredictionGpuTile.submissionEpoch();
            long now = System.nanoTime();
            if (base == gpuBase && snapshot == gpuSnapshot
                    && epoch == gpuEpoch && now < gpuRetryAt) return gpuCandidates;
            gpuBase = base; gpuSnapshot = snapshot; gpuEpoch = epoch;
            gpuRetryAt = Long.MAX_VALUE;
            gpuAlternatives.clear();
            var byKey = new java.util.HashMap<PredictionTileManager.PredictionTileKey, Draw>();
            for (var draw : base) if (!draw.seam()) byKey.put(draw.tile().key(), draw);
            var result = new java.util.ArrayList<>(base);
            var parents = new java.util.ArrayList<>(snapshot.tiles().values());
            parents.sort(java.util.Comparator.comparingInt(tile -> tile.key().lod()));
            for (var tile : parents) {
                var proof = tile.mesh().parentLod;
                if (proof == null || !proof.sealedEdges() || !Double.isFinite(proof.error()) || byKey.containsKey(tile.key())) continue;
                var gpu = gpuTiles.get(tile.key());
                if (gpu == null || !gpu.hasMesh(tile) || gpu.arenaSlice() == null || gpu.packed().waterQuadCount() != 0
                        || gpu.packed().hasFeatures) continue;
                var children = new PredictionTileManager.PredictionTile[4]; boolean complete = true;
                for (int i = 0; i < 4; i++) {
                    var child = byKey.get(proof.child(i).key());
                    if (child == null || !child.completeOwnership() && !child.coarseProxy() || child.tile().scopeOnly()
                            || child.gpu().arenaSlice() == null || child.gpu().packed().waterQuadCount() != 0
                            || child.gpu().packed().hasFeatures) { complete = false; break; }
                    if (child.gpu().morphActive(now)) {
                        gpuRetryAt = Math.min(gpuRetryAt, child.gpu().morphEndsAt()); complete = false; break;
                    }
                    children[i] = child.tile();
                }
                if (!complete || !proof.describes(children)) continue;
                var geo = geometry.get(tile.key());
                if (geo == null) continue;
                // The parent is a GPU alternative only. Its ordinary cell mask remains unchanged.
                boolean[] cells = new boolean[tile.cellAxis() * tile.cellAxis()]; java.util.Arrays.fill(cells, true);
                var draw = new Draw(tile, gpu, cells, cells, false, VssLodFaceGroup.ALL, 0, geo.culling(), true, true, true);
                result.add(draw); byKey.put(tile.key(), draw); gpuAlternatives.put(gpu, Boolean.TRUE);
            }
            return gpuCandidates = result.size() == base.size() ? base : List.copyOf(result);
        }
        int residentDrawCount() { return visible.residentCount(); }
        private final Map<PredictionTileManager.PredictionTileKey,PredictionRenderGeometry.Entry> geometry=new java.util.HashMap<>();
        private final PredictionSpatialIndex<PredictionTileManager.PredictionTileKey> spatial=new PredictionSpatialIndex<>();
        private final Map<PredictionTileManager.PredictionTileKey,Draw> terrain=new java.util.HashMap<>();
        private final Set<PredictionTileManager.PredictionTileKey> emptyCoverage=new HashSet<>();
        private final Map<PredictionTileManager.PredictionTileKey,TileCoverage> totals=new java.util.HashMap<>();
        private final java.util.TreeMap<Integer,Integer> lower=new java.util.TreeMap<>(),upper=new java.util.TreeMap<>();
        private final PredictionVisiblePlan<DrawKey,Draw> visible;
        private final Set<PredictionTileManager.PredictionTileKey> broadCandidateBuffer =
                new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>();
        private final Set<DrawKey> drawCandidateBuffer = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>();
        private final Map<PredictionTileManager.PredictionTileKey, DrawKey[]> drawKeys = new java.util.HashMap<>();

        private DrawKey drawKey(PredictionTileManager.PredictionTileKey key, int kind) {
            var keys = drawKeys.computeIfAbsent(key, ignored -> new DrawKey[3]);
            if (keys[kind] == null) keys[kind] = new DrawKey(key, kind);
            return keys[kind];
        }

        private void forgetDrawKeys(PredictionTileManager.PredictionTileKey key) {
            if (!geometry.containsKey(key) && !terrain.containsKey(key)
                    && !seamBatch.draws.containsKey(key) && !realSeamBatch.draws.containsKey(key)) drawKeys.remove(key);
        }

        private Set<DrawKey> drawCandidates(Set<PredictionTileManager.PredictionTileKey> candidates, Frustum frustum) {
            drawCandidateBuffer.clear();
            for (var key : candidates) if (terrain.containsKey(key)) drawCandidateBuffer.add(drawKey(key, 0));
            seamBatch.spatial.intersectFrustum(frustum, key -> drawCandidateBuffer.add(drawKey(key, 1)));
            realSeamBatch.spatial.intersectFrustum(frustum, key -> drawCandidateBuffer.add(drawKey(key, 2)));
            return drawCandidateBuffer;
        }
        private PredictionTileManager.RenderSnapshot snapshot;
        private CoverageView view;
        private Vec3 camera;
        private double horizon;
        private double horizonScanX = Double.NaN, horizonScanZ = Double.NaN;
        private Matrix4f cullingModelView, cullingProjection;
        private long considered,rendered,coverageSkipped,authoritativeSkipped,resolves,resolveNanos;
        private long ownerTiles, suppressedParents, partialCoverageParents, transitionTiles;
        private final Set<PredictionTileManager.PredictionTileKey> suppressedParentsSet = new HashSet<>();
        private final Set<PredictionTileManager.PredictionTileKey> partialOwners = new HashSet<>();
        private final Set<PredictionTileManager.PredictionTileKey> transitionOwners = new HashSet<>();
        private long worldVisits;
        long worldVisits() { return worldVisits; }
        long visibilityVisits() { return visible.visited(); }
        String diagnostics() { return "worldVisits="+worldVisits+",visibilityVisits="+visible.visited()
                +",orderEdits="+visible.orderEdits()+",listPublications="+visible.publications()
                +",handoff=region-index+frame-depth," + boundaries.diagnostics(); }
        String ownershipDiagnostics() { return "ownerTiles=" + ownerTiles
                + ",suppressedParents=" + suppressedParents
                + ",partialCoverageParents=" + partialCoverageParents
                + ",transitionTiles=" + transitionTiles; }

        List<Draw> prepare(PredictionTileManager.RenderSnapshot next,Set<PredictionTileManager.PredictionTileKey> changes,
                          CoverageView nextView,Vec3 nextCamera,double nextHorizon,int nearDistance,
                          Matrix4f modelView,Matrix4f projection,Frustum frustum) {
            resolves=0;resolveNanos=0;
            if(snapshot!=null && !snapshot.dimension().equals(next.dimension())) clear();
            // Commit the captured version while its terrain is still intact.
            // This never publishes a result for a newer geometry version.
            pollBoundaries();
            if (boundaryInputs == null) boundaryInputs = new PredictionTileTable<>(next.dimension());
            boolean reset=snapshot==null || !snapshot.layout().equals(next.layout());
            if (reset) coverageCache.clear();
            boolean moved=!nextCamera.equals(camera) || nextHorizon!=horizon;
            boolean horizontalMove=camera==null || nextCamera.x!=camera.x || nextCamera.z!=camera.z;
            boolean turned=cullingModelView==null || cullingProjection==null
                    || !cullingModelView.equals(modelView) || !cullingProjection.equals(projection);
            // Residency changes already include every intersecting parent/child
            // epoch. A local upload must not dirty all other visible tiles.
            boolean stableOwnership=!reset && nextHorizon==horizon
                    && java.util.Objects.equals(view,nextView);
            if(!reset && !moved && !turned && changes.isEmpty()
                    && java.util.Objects.equals(view,nextView)) {
                finishBoundaries();
                snapshot=next;
                return select(nextCamera,modelView,projection,frustum,null);
            }
            var dirty=new HashSet<PredictionTileManager.PredictionTileKey>();
            if(reset) { dirty.addAll(geometry.keySet());dirty.addAll(next.tiles().keySet()); }
            else dirty.addAll(changes);
            for(var key:dirty) {
                emptyCoverage.remove(key);
                var tile=next.tiles().get(key);
                if(tile==null) { geometry.remove(key);spatial.remove(key); }
                else {
                    var old=geometry.get(key);
                    if(old==null || old.tile()!=tile) {
                        var entry=new PredictionRenderGeometry.Entry(tile);
                        geometry.put(key,entry);
                        spatial.put(key,key,(int)Math.floor(entry.bounds().minY),(int)Math.ceil(entry.bounds().maxY));
                    }
                }
            }
            Set<PredictionTileManager.PredictionTileKey> broadCandidates = null;
            if(moved || turned) {
                broadCandidateBuffer.clear();
                broadCandidates = broadCandidateBuffer;
                // The spatial index rejects whole quadtree nodes before the
                // coverage pass. Existing terrain outside the new horizon is
                // removed on the next residency snapshot; keeping it here is
                // safe because the draw candidate set below excludes it.
                spatial.intersectFrustum(frustum, nextCamera, nextHorizon,
                        -1_000_000, 1_000_000, broadCandidates::add);
                if(!stableOwnership) dirty.addAll(broadCandidates);
                if (moved && nextHorizon != horizon) dirty.addAll(terrain.keySet());
            }
            // Camera motion alone changes face selection, horizon membership
            // and frustum membership, but does not change resident ownership.
            // Resolve only tiles entering the horizon; keep existing masks and
            // seam surfaces until a tile actually leaves it.
            if (stableOwnership && broadCandidates != null) {
                for (var key : broadCandidates) {
                    if (dirty.contains(key)) continue;
                    var old = terrain.get(key);
                    var geo = geometry.get(key);
                    if (geo == null) continue;
                    if (suppressedParentsSet.contains(key)) continue;
                    if (old == null) {
                        if (!emptyCoverage.contains(key) && geo.visible(nextCamera,null,nextHorizon)) dirty.add(key);
                        continue;
                    }
                    if (!geo.visible(nextCamera,null,nextHorizon)) { dirty.add(key); continue; }
                    int faces = VssLodFaceGroup.visibleMask(geo.bounds(), nextCamera);
                    var packed = old.gpu().packed();
                    if (packed != null && packed.downFaces()) faces |= 1 << VssLodFaceGroup.HORIZONTAL;
                    if (faces == old.faces()) continue;
                    var nextDraw = new Draw(old.tile(), old.gpu(), old.allowed(), old.features(), false,
                            faces, old.morph(), old.bounds(), old.completeOwnership(), old.completeTerrainOwnership(), false, old.coverageGrid());
                    terrain.put(key, nextDraw);
                    stagedKeys.add(key);
                }
                if (horizontalMove && (!Double.isFinite(horizonScanX)
                        || Math.abs(nextCamera.x - horizonScanX) >= 64 || Math.abs(nextCamera.z - horizonScanZ) >= 64)) {
                horizonScanX = nextCamera.x; horizonScanZ = nextCamera.z;
                for (var entry : terrain.entrySet()) {
                    var geo=geometry.get(entry.getKey());
                    if (geo==null || !geo.visible(nextCamera,null,nextHorizon)) dirty.add(entry.getKey());
                }
                }
            }
            if (stableOwnership && broadCandidates != null && dirty.isEmpty()) {
                finishBoundaries();
                snapshot = next;
                view = nextView;
                camera = nextCamera;
                horizon = nextHorizon;
                if (cullingModelView == null) cullingModelView = new Matrix4f();
                if (cullingProjection == null) cullingProjection = new Matrix4f();
                cullingModelView.set(modelView);
                cullingProjection.set(projection);
                var drawCandidates = boundaryPending() ? null : drawCandidates(broadCandidates, frustum);
                return select(nextCamera, modelView, projection, frustum, drawCandidates);
            }
            if(!java.util.Objects.equals(view,nextView)) {
                dirty.addAll(next.scopedFamilies());
                if(snapshot!=null) dirty.addAll(snapshot.scopedFamilies());
                focus(view==null?null:view.focus(),dirty);focus(nextView.focus(),dirty);
            }
            boolean boundaryChanged = false;
            long hits=0;
            worldVisits+=dirty.size();
            for(var key:dirty) {
                var geo=geometry.get(key);var old=terrain.get(key);
                if(geo==null || !geo.visible(nextCamera,null,nextHorizon)) {
                    if (geo == null) coverageCache.remove(key);
                    emptyCoverage.remove(key);boundaryChanged |= remove(key,old);continue;
                }
                var tile=geo.tile();
                var ownerView=nextView.ownership(next.scopeAffects(key));
                var cached=coverageCache.get(key);
                TileCoverage coverage;
                if(cached!=null && cached.matchesOwnership(tile.revision(),next.epoch(key),ownerView)) {
                    coverage=cached.coverage();hits++;
                } else {
                    // CPU masks choose between resident prediction meshes only.
                    // An uploaded Voxy ancestor may be empty, occluded or replaced
                    // by children in this frame's traversal. Its metadata cannot
                    // remove fallback before the current pixel-depth test.
                    long timingStart = PredictionRenderTimings.start(), start = System.nanoTime();
                    coverage=resolveCoverage(tile,nextView.playerChunkX(),nextView.playerChunkZ(),
                            nextView.pixelsPerBlock(),next,nextView.focus());
                    resolves++; resolveNanos+=System.nanoTime()-start;
                    PredictionRenderTimings.end(PredictionRenderTimings.Stage.COVERAGE,timingStart);
                    if (old != null) {
                        var ground = java.util.Arrays.equals(old.allowed(), coverage.allowed()) ? old.allowed() : coverage.allowed();
                        var features = java.util.Arrays.equals(old.features(), coverage.features()) ? old.features() : coverage.features();
                        var grid = PredictionCoverageGrid.same(old.coverageGrid(), coverage.grid()) ? old.coverageGrid() : coverage.grid();
                        coverage = new TileCoverage(ground, features, coverage.considered(), coverage.rendered(), coverage.coverageSkipped(), 0, grid);
                    }
                    coverageCache.put(key,new CachedCoverage(tile.revision(),next.epoch(key),ownerView,coverage));
                }
                var gpu=gpuTiles.get(key);
                if(coverage.rendered()==0) {
                    emptyCoverage.add(key);boundaryChanged |= remove(key,old);continue;
                }
                emptyCoverage.remove(key);
                if(gpu==null || !gpu.drawable()) { boundaryChanged |= remove(key,old);continue; }
                int faces=VssLodFaceGroup.visibleMask(geo.bounds(),nextCamera);
                if(tile.mesh().gpuPayload()!=null && tile.mesh().gpuPayload().downFaces()) faces|=1<<VssLodFaceGroup.HORIZONTAL;
                float morph=coverage.coverageSkipped()!=0 || PredictionWorkOrder.scoped(key,next.layout(),nextView.focus())?0:1;
                if(old!=null && old.tile()==tile && old.gpu()==gpu && old.allowed()==coverage.allowed()
                        && old.features()==coverage.features() && old.coverageGrid()==coverage.grid() && old.faces()==faces && old.morph()==morph) {
                    continue;
                }
                var grid=coverage.grid();
                var draw=grid==null ? new Draw(tile,gpu,coverage.allowed(),coverage.features(),false,faces,morph,geo.culling())
                        : new Draw(tile,gpu,coverage.allowed(),coverage.features(),false,faces,morph,geo.culling(),
                                grid.complete(128) && (!gpu.packed().hasFeatures || grid.complete(64)), grid.complete(128), false, grid);
                terrain.put(key,draw);
                statistics(key,old,draw,coverage);
                boundaryChanged |= old == null || old.tile() != tile || old.allowed() != coverage.allowed()
                        || old.features() != coverage.features() || old.coverageGrid() != coverage.grid();
            }
            coverageCacheHits.addAndGet(hits);coverageResolves.addAndGet(resolves);coverageNanos.addAndGet(resolveNanos);
            long seamStart=PredictionRenderTimings.start();
            stagedKeys.addAll(dirty);
            if (boundaryChanged) boundaryVersion++;
            finishBoundaries();
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.SEAMS,seamStart);
            snapshot=next;view=nextView;camera=nextCamera;horizon=nextHorizon;
            if(cullingModelView==null) cullingModelView=new Matrix4f();
            if(cullingProjection==null) cullingProjection=new Matrix4f();
            cullingModelView.set(modelView);cullingProjection.set(projection);
            Set<DrawKey> drawCandidates = null;
            if (broadCandidates != null && !boundaryPending()) {
                // Include changed/new owners that were not present in the
                // spatial index when the broad phase started.
                broadCandidates.addAll(dirty);
                drawCandidates = drawCandidates(broadCandidates, frustum);
                // Patch subtree bounds include their real connector extents;
                // terrain visibility never substitutes for seam visibility.
            }
            return select(nextCamera,modelView,projection,frustum,drawCandidates);
        }

        private void focus(VssLodFocus focus,Set<PredictionTileManager.PredictionTileKey> dirty) {
            if(focus==null) return;
            double radius=Math.min(focus.radius(),PredictionWorkOrder.SCOPED_RADIUS_BLOCKS);
            spatial.intersect((long)Math.floor(focus.x()-radius),(long)Math.floor(focus.z()-radius),
                    (long)Math.ceil(focus.x()+radius)+1,(long)Math.ceil(focus.z()+radius)+1,dirty::add);
        }
        private void recomputeRenderOwners(Set<PredictionTileManager.PredictionTileKey> dirty) {
            // A changed child can affect only its immediate parent; a changed
            // parent can affect itself. Keep the rest of the owner set intact.
            var affected = new HashSet<PredictionTileManager.PredictionTileKey>();
            for (var key : dirty) {
                affected.add(key);
                if (key.lod() < PredictionTileManager.MAX_LOD_LEVEL)
                    affected.add(new PredictionTileManager.PredictionTileKey(key.dimension(),
                            Math.floorDiv(key.tileX(), 2), Math.floorDiv(key.tileZ(), 2), key.lod() + 1));
            }
            for (var key : affected) {
                boolean was = suppressedParentsSet.contains(key);
                boolean now = fullyCoveredByChildren(key);
                if (was == now) continue;
                var draw = terrain.get(key);
                if (now) {
                    suppressedParentsSet.add(key);
                    if (draw != null) visible.remove(drawKey(key,0));
                } else {
                    suppressedParentsSet.remove(key);
                    if (draw != null) visible.put(drawKey(key,0), draw);
                }
            }
            suppressedParents = suppressedParentsSet.size();
            ownerTiles = terrain.size() - suppressedParents;
        }

        private boolean fullyCoveredByChildren(PredictionTileManager.PredictionTileKey parentKey) {
            var parent = terrain.get(parentKey);
            if (parent == null || parentKey.lod() <= 0 || parent.tile().scopeOnly()) return false;
            if (PredictionFeatureHandoff.any(parent.features())) return false;
            int childX = parentKey.tileX() << 1, childZ = parentKey.tileZ() << 1;
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                var child = terrain.get(new PredictionTileManager.PredictionTileKey(
                        parentKey.dimension(), childX + dx, childZ + dz, parentKey.lod() - 1));
                if (child == null || child.tile().scopeOnly() || !child.completeTerrainOwnership()) return false;
            }
            return true;
        }
        private boolean remove(PredictionTileManager.PredictionTileKey key,Draw old) {
            if(old==null) { forgetDrawKeys(key); return false; }
            terrain.remove(key);
            statistics(key,old,null,null);forgetDrawKeys(key);
            return true;
        }
        private void finishBoundaries() {
            pollBoundaries();
            var edges = vanillaMask.groundEdges();
            if (edges != boundaryEdges) { boundaryEdges = edges; boundaryVersion++; }
            if (publishedBoundaryVersion == boundaryVersion && !boundaries.pending()) {
                publishTerrain(); return;
            }
            if (publishedBoundaryVersion != boundaryVersion && !boundaries.pending()) {
                boundaryInputs = PredictionSeamPreparation.updateSurfaces(boundaryInputs, stagedKeys, key -> {
                    var draw = terrain.get(key);
                    return draw == null ? null : new PredictionSeamPreparation.Surface(draw.tile(), draw.allowed(), draw.features(), draw.coverageGrid());
                });
                boundaries.request(boundaryVersion, boundaryInputs, boundaryEdges);
                // Polling a completed transaction never waits. This also lets
                // deterministic executors validate the production handoff.
                commitBoundaries(boundaries.poll(boundaryVersion));
            }
        }

        void pollBoundaries() { commitBoundaries(boundaries.poll(boundaryVersion)); }

        private void commitBoundaries(PredictionSeamPreparation.Result result) {
            if (result == null) return;
            for (var key : stagedKeys) {
                var draw = terrain.get(key);
                if (draw == null) continue;
                updateCoverage(draw.gpu(), draw.allowed(), draw.coverageGrid());
                // Unchanged logical masks may still be missing from a new GPU allocation.
                // Initialize the complete state before publishing the replacement draw.
                updateBoundaryCoverage(draw, result.masks().get(key));
            }
            for (var entry : result.maskChanges().entrySet()) {
                if (stagedKeys.contains(entry.getKey())) continue;
                var draw = terrain.get(entry.getKey());
                if (draw != null) updateBoundaryCoverage(draw, entry.getValue());
            }
            patches(seamBatch, result.lodChanges(), 1);
            patches(realSeamBatch, result.realChanges(), 2);
            publishedBoundaryVersion = boundaryVersion;
            publishTerrain();
        }

        private void updateBoundaryCoverage(Draw draw, byte[] mask) {
            long start = System.nanoTime();
            int bytes = draw.gpu().updateBoundaryCoverage(mask);
            if (bytes != 0) uploadBudget.recordRequired(bytes, System.nanoTime() - start);
        }

        private PredictionTileTable<PredictionSeamPreparation.Surface> boundaryInputs;

        private void publishTerrain() {
            if (stagedKeys.isEmpty()) return;
            for (var key : stagedKeys) {
                var old = publishedTerrain.remove(key);
                if (old != null) {
                    int count = publishedGpu.getOrDefault(old.gpu(), 1) - 1;
                    if (count == 0) publishedGpu.remove(old.gpu()); else publishedGpu.put(old.gpu(), count);
                }
                var draw = terrain.get(key);
                if (draw == null) visible.remove(drawKey(key, 0));
                else {
                    publishedTerrain.put(key, draw); publishedGpu.merge(draw.gpu(), 1, Integer::sum);
                    visible.put(drawKey(key, 0), draw);
                }
            }
            recomputeRenderOwners(stagedKeys);
            stagedKeys.clear();
        }

        private void patches(SeamBatch batch,PredictionLodSeams.Changes delta,int kind) {
            for(var key:batch.apply(delta)) {
                var draw=batch.draws.get(key);
                if(draw==null) visible.remove(drawKey(key,kind));else visible.put(drawKey(key,kind),draw);
                forgetDrawKeys(key);
            }
        }
        private void statistics(PredictionTileManager.PredictionTileKey key,Draw old,Draw current,TileCoverage coverage) {
            var prior=totals.remove(key);
            if(prior!=null) { considered-=prior.considered();rendered-=prior.rendered();coverageSkipped-=prior.coverageSkipped();authoritativeSkipped-=prior.authoritativeSkipped(); }
            if(old!=null) { height(lower,old.tile().depthBound().minY(),-1);height(upper,old.tile().depthBound().maxY(),-1); }
            partialOwners.remove(key);transitionOwners.remove(key);
            if(current!=null) {
                totals.put(key,coverage);considered+=coverage.considered();rendered+=coverage.rendered();coverageSkipped+=coverage.coverageSkipped();authoritativeSkipped+=coverage.authoritativeSkipped();
                height(lower,current.tile().depthBound().minY(),1);height(upper,current.tile().depthBound().maxY(),1);
                if(coverage.rendered()<coverage.considered()) partialOwners.add(key);
                if(current.morph()==0.0F) transitionOwners.add(key);
            }
            partialCoverageParents=partialOwners.size();transitionTiles=transitionOwners.size();
        }
        private static void height(java.util.TreeMap<Integer,Integer> values,int y,int change) {
            int count=values.getOrDefault(y,0)+change;if(count==0) values.remove(y);else values.put(y,count);
        }
        int minY() { return lower.isEmpty()?Integer.MAX_VALUE:lower.firstKey(); }
        int maxY() { return upper.isEmpty()?Integer.MIN_VALUE:upper.lastKey(); }
        void clear() {
            boundaries.clear();
            gpuBase = null; gpuCandidates = List.of(); gpuSnapshot = null; gpuRetryAt = 0; gpuAlternatives.clear();
            publishedTerrain.clear(); publishedGpu.clear(); stagedKeys.clear();
            boundaryVersion = publishedBoundaryVersion = 0; boundaryEdges = List.of();
            boundaryInputs = null;
            geometry.clear();spatial.clear();terrain.clear();emptyCoverage.clear();totals.clear();lower.clear();upper.clear();visible.clear();suppressedParentsSet.clear();partialOwners.clear();transitionOwners.clear();
            broadCandidateBuffer.clear();drawCandidateBuffer.clear();drawKeys.clear();
            snapshot=null;view=null;camera=null;cullingModelView=null;cullingProjection=null;
            horizonScanX = horizonScanZ = Double.NaN;
            coverageCache.clear();
            considered=rendered=coverageSkipped=authoritativeSkipped=0;
            ownerTiles=suppressedParents=partialCoverageParents=transitionTiles=0;
            seamBatch.clear();realSeamBatch.clear();
        }
    }

    private static volatile boolean selectionScoping;

    static double selectionPixelsPerBlock(Minecraft minecraft) {
        double scale = selectionScale;
        if (scale > 0.0D && selectionScoping == minecraft.player.isScoping()) {
            // Projection animation and head bob must not invalidate every coverage mask each frame.
            return Math.max(16.0D, Math.rint(scale / 16.0D) * 16.0D);
        }
        return VssLodProjection.unscopedScale(minecraft.options.fov().get(),
                minecraft.getMainRenderTarget().height);
    }

    static double basePixelsPerBlock(Minecraft minecraft) {
        return minecraft.player.isScoping()
                ? VssLodProjection.unscopedScale(minecraft.options.fov().get(),
                    minecraft.getMainRenderTarget().height)
                : selectionPixelsPerBlock(minecraft);
    }

    private static boolean ensureProgram() {
        if (program != null) {
            program.use();
            return true;
        }
        try {
            program = PredictionTerrainProgram.create();
            program.use();
            return true;
        } catch (Throwable failure) {
            dev.xantha.vss.common.VSSLogger.error(
                    "VSS prediction terrain program failed to link", failure);
            programBroken = true;
            return false;
        }
    }

    /** Binds the frame-stable samplers and uniforms; returns false to degrade. */
    private static boolean bindFrame(Minecraft minecraft, Frame event,
                                     VssLodProjection.MatrixData projection, Vec3 camera) {
        int spriteRectId = -1;
        int atlasId = 0;
        try {
            PredictionMaterialPalette.refreshAtlas(minecraft.level);
            atlasId = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getId();
            spriteRectId = VssLodSpriteTable.texture();
            detailPathEnabled = spriteRectId > 0 && VssLodSpriteTable.available();
        } catch (Throwable failure) {
            long now = System.nanoTime();
            if (VSSClientConfig.CONFIG.debugLogging
                    && now - lastSamplerErrorNanos > 5_000_000_000L) {
                lastSamplerErrorNanos = now;
                dev.xantha.vss.common.VSSLogger.debug(
                        "VSS prediction atlas sampler unavailable: " + failure);
            }
            detailPathEnabled = false;
        }
        boundSpriteTableId = spriteRectId;
        boolean detail = detailPathEnabled && spriteRectId != -1;
        int lightmapId = 0;
        // Iris already installed the entity lightmap. Rebinding here changes
        // Minecraft texture caches and Iris deferred state inside a raw Voxy pass.
        if (irisPass == null) minecraft.gameRenderer.lightTexture().turnOnLightLayer();
        Integer bound = RenderSystem.getShaderTexture(2);
        if (bound != null) {
            lightmapId = bound;
        }
        // turnOnLightLayer binds the light texture on the currently active
        // unit. Bind ALL material inputs afterwards, including the atlas.
        bindMaterialTextures(atlasId, lightmapId, spriteRectId);
        program.setSamplers(0, 1, 2, 3, 4);
        program.bindVanillaMask(vanillaMask, camera);
        program.bindRealCoverage(minecraft.level.dimension(), camera);
        program.setRealRenderDistance(Math.max(vanillaMask.renderDistanceBlocks(),
                ModCompat.getVoxyViewDistanceChunks().orElse(0) * 16.0F));
        program.bindExactCoverage(exactMask, minecraft.level, camera, handoffDistanceChunks(VSSApi.clientSession().getEffectiveLodDistanceChunks()));
        program.setHorizon(predictionHorizonBlocks());
        program.bindMainDepth(irisPass == null ? minecraft.getMainRenderTarget().getDepthTextureId()
                : irisPass.depthTexture(), projection);
        if (irisPass == null) {
            var main = minecraft.getMainRenderTarget();
            program.bindVoxyDepth(PredictionVoxyDepth.current(main.frameBufferId, main.width, main.height, camera),
                    new Matrix4f(event.projection()).mul(event.modelView()));
        }
        var fog = PredictionFogBridge.fog(minecraft);
        float[] fogColor = PredictionFogBridge.color();
        program.setDirectionalLighting(minecraft.level);
        program.setLeaves(PredictionGraphicsQuality.fastLeaves(minecraft), VssLodSpriteTable.leafSprites());
        program.setFrame(fogColor, fog.start(), fog.end(), fog.density(),
                fog.hazeStart(), lightmapId != 0, 1.0F, fog.shape());
        // Material detail follows screen footprint independently of the geometry fine band.
        program.setDetailDistance(selectionScoping ? 0.0F : (float) Math.min(predictionHorizonBlocks(),
                Math.max(2048.0D, basePixelsPerBlock(minecraft) * 4.0D)));
        // Keep rasterized depth and the main-depth comparison in agreement.
        program.setCamera(event.modelView(), irisPass == null ? projection.matrix() : event.projection());
        if (irisPass != null) irisPass.bindFrame(event);
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionAtlasSampler.bind(atlasId);
        return detail;
    }

    static boolean normalFogActive() { return !programBroken && !gpuTiles.isEmpty(); }

    static PredictionFogBridge.Fog normalFog(Minecraft minecraft) {
        int vanillaDistance = minecraft.options.renderDistance().get() * 16;
        int realDistance = Math.max(vanillaDistance, ModCompat.getVoxyViewDistanceChunks().orElse(0) * 16);
        var fog = VssLodFog.shared(predictionHorizonBlocks(), realDistance, vanillaDistance,
                VSSClientConfig.CONFIG.predictionFog);
        return new PredictionFogBridge.Fog(fog.shaderStart(), fog.shaderEnd(), Math.min(vanillaDistance, fog.start()),
                fog.haze() ? fog.aerialDensity() : 0);
    }

    static void bindMaterialTextures(int atlasId, int lightmapId, int spriteRectId) {
        PredictionGlState.activeTexture(GL13.GL_TEXTURE1);
        PredictionGlState.bindTexture(Math.max(0, lightmapId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, lightmapId));
        PredictionGlState.activeTexture(GL13.GL_TEXTURE2);
        PredictionGlState.bindTexture(Math.max(0, spriteRectId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, spriteRectId));
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionGlState.bindTexture(Math.max(0, atlasId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, atlasId));
    }

    private static int handoffDistanceChunks(int nearDistance) {
        int voxyDistance = ModCompat.isVoxyLoaded()
                ? ModCompat.getVoxyViewDistanceChunks().orElse(nearDistance) : nearDistance;
        return ClientPredictionState.coverageRadiusChunks(Math.max(nearDistance, voxyDistance));
    }

    /** Binds the shared element buffer (two triangles per quad) and grows it. */
    private static void bindSharedIndices(int quadCount) {
        if (sharedVertexArray == -1) {
            sharedVertexArray = GL30.glGenVertexArrays();
            sharedIndexBuffer = GL15.glGenBuffers();
            sharedIndexQuads = 0;
        }
        if (quadCount > sharedIndexQuads) {
            int capacity = Math.max(4096, quadCount + quadCount / 2);
            java.nio.IntBuffer indices = MemoryUtil.memAllocInt(capacity * 6);
            for (int quad = 0; quad < capacity; quad++) {
                int base = quad * 4;
                indices.put(base).put(base + 1).put(base + 2)
                        .put(base).put(base + 2).put(base + 3);
            }
            indices.flip();
            GL30.glBindVertexArray(sharedVertexArray);
            GL15.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, sharedIndexBuffer);
            GL15.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            MemoryUtil.memFree(indices);
            sharedIndexQuads = capacity;
        }
        GL30.glBindVertexArray(sharedVertexArray);
    }


    private static String textureDiagnostics(List<Draw> draws) {
        int[] spacing = new int[4];
        long withSprite = 0;
        long flat = 0;
        for (Draw draw : draws) {
            int step = draw.tile().spacingBlocks();
            spacing[step <= 1 ? 0 : step <= 2 ? 1 : step <= 4 ? 2 : 3]++;
            PredictionPackedMesh packed = draw.gpu().packed();
            if (packed == null) continue;
            withSprite += packed.spriteQuadCount();
            flat += packed.quadCount() - packed.spriteQuadCount();
        }
        return "{enabled=" + detailPathEnabled + ",tableId=" + boundSpriteTableId
                + ",useAverage=" + !detailPathEnabled
                + ",scoping=" + selectionScoping + ",selectionScale=" + selectionScale
                + ",table=" + VssLodSpriteTable.diagnostics()
                + ",tilesAt1/2/4/8+=" + java.util.Arrays.toString(spacing)
                + ",spriteQuads=" + withSprite + ",flatQuads=" + flat + "}";
    }

    /**
     * The two GPU passes.  Opaque terrain draws every visible face group of
     * every tile front-to-back; water redraws the fluid ranges back-to-front
     * with blending on.  All vertex data comes from the bound texture buffers
     * -- the element buffer only exists so {@code gl_VertexID & 3} walks four
     * corners per quad.
     */
    private static void renderDeferredWater() {
        DeferredWater pending = deferredWater;
        deferredWater = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (pending == null || pending.level() != minecraft.level
                || pending.target() != minecraft.getMainRenderTarget()) return;
        try (var state = new PredictionIrisBridge.State(PredictionWaterDepthHistory.TEXTURE_UNITS, false)) {
            predictionTarget.beginWater();
            PredictionGlState.enableDepthTest();
            PredictionGlState.depthFunc(GL11.GL_GEQUAL);
            PredictionGlState.disableCull();
            long start = PredictionRenderTimings.start();
            int query = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.WATER);
            try {
                if (ensureProgram()) drawPasses(minecraft, pending.frame(), pending.draws(),
                        pending.frame().camera(), pending.projection(), true);
            } finally {
                PredictionRenderTimings.gpuEnd(query);
                PredictionRenderTimings.end(PredictionRenderTimings.Stage.WATER, start);
            }
        } catch (Throwable failure) {
            programBroken = true;
            dev.xantha.vss.common.VSSLogger.warn("VSS prediction water disabled after a render error", failure);
        }
    }

    private static long drawPasses(Minecraft minecraft, Frame event,
                                   List<Draw> draws, Vec3 camera,
                                   VssLodProjection.MatrixData projection, boolean water) {
        List<Draw> ordinaryDraws = draws;
        boolean residentSelection = false;
        long visibleSelectionQuads = opaquePass.selectedCandidates;
        if (!water && lastGpuSelectionUse != 0) {
            visibleSelectionQuads = gpuSelectionInput.visibleQuads(draws, residentOpaquePass.rangesByDraw());
        }
        if (!water && scene.residentDrawCount() >= PredictionGpuSelectionPolicy.MIN_TERRAIN_TILES
                && !selectionScoping
                && !batchProgramBroken && PredictionTerrainArena.supported()
                && PredictionGpuCommandSelector.supported()) {
            if (indirectBatch == null) indirectBatch = new PredictionIndirectBatch();
            var residents = scene.residentDraws();
            var main = minecraft.getMainRenderTarget();
            // Reuse the last visible estimate for the adaptive Hi-Z policy. A
            // resident set must not restart its stationary comparison on turns.
            indirectBatch.workload(visibleSelectionQuads);
            residentSelection = indirectBatch.prepareGpuSelection(predictionTarget.depthTextureId(),
                    main.width, main.height, new Matrix4f(projection.matrix()).mul(event.modelView()), camera,
                    opaquePass.workloadRevision, VssLodSpriteTable.materialRevision(),
                    gpuSelectionInput, residents, predictionHorizonBlocks());
            if (residentSelection) draws = gpuSelectionInput.candidates();
            else { residentSelection = false; indirectBatch.cancelGpuSelection(); }
        }
        PreparedPass pass;
        if (residentSelection) {
            residentOpaquePass.prepare(draws, renderResidency.revision(), camera);
            pass = residentOpaquePass;
            lastGpuSelectionUse = System.nanoTime();
        } else {
            if (!water) lastGpuSelectionUse = 0;
            pass = preparePass(draws, water, camera);
            if (!water) {
                residentOpaquePass.clear(); gpuSelectionInput.clear();
            }
        }
        if (!pass.hasDraws()) return 0L;
        int previousAtlasSampler = GL30.glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, 0);
        PredictionTerrainProgram original = program;
        try {
            if (!batchProgramBroken && PredictionTerrainArena.supported() && pass.batchWorthwhile()) {
                try { if (batchProgram == null) batchProgram = PredictionTerrainProgram.createBatch(); }
                catch (RuntimeException unavailable) { batchProgramBroken = true; }
                if (batchProgram != null) { program = batchProgram; program.use(); }
            }
            if (residentSelection && !program.supportsBatch()) {
                draws = ordinaryDraws;
                pass = preparePass(draws, false, camera);
                lastGpuSelectionUse = 0;
            }
            return drawNormalPass(minecraft, event, draws, ordinaryDraws, camera, projection, water, pass);
        } finally { program = original; org.lwjgl.opengl.GL33.glBindSampler(0, previousAtlasSampler); }
    }

    /**
     * Select MDI from storage residency only.  Calling drawRanges here used to
     * build the same visibility plans a second time before the actual draw;
     * that was visible in profiles as render-thread allocation in
     * PredictionDrawRanges.  The draw pass still performs the real range test
     * once, and empty water/terrain passes are rejected by hasPassDraws().
     */
    static boolean batchWorthwhile(List<Draw> draws, boolean water) {
        int tiles = 0;
        for (Draw draw : draws) {
            var packed = draw.gpu().packed(); if (packed == null) continue;
            if (draw.gpu().arenaSlice(water) == null) continue;
            if (++tiles >= 64) return true;
        }
        // A single range has a small fixed MDI setup cost.  Large prediction
        // scenes reach this threshold even when their face visibility mask
        // changes every frame; the cached drawRanges() objects are then built
        // only by the submission pass.
        return false;
    }

    private static boolean hasPassDraws(List<Draw> draws, boolean water) {
        return preparePass(draws, water, null).hasDraws();
    }

    private static PreparedPass preparePass(List<Draw> draws, boolean water, Vec3 camera) {
        PreparedPass pass = water ? waterPass : opaquePass;
        pass.prepare(draws, renderResidency.revision(), camera);
        return pass;
    }

    private static long drawNormalPass(Minecraft minecraft, Frame event,
                                      List<Draw> draws, List<Draw> ordinaryDraws, Vec3 camera,
                                      VssLodProjection.MatrixData projection, boolean water,
                                      PreparedPass pass) {
        // All tiles in a frame share one validated binding. Worker-side
        // material discovery must not switch fallback modes halfway through a pass.
        boolean useAverage = !bindFrame(minecraft, event, projection, camera);
        // Fabulous renders vanilla translucency into a separate depth target.
        // It contains the copied opaque depth plus the water/glass just drawn.
        if (water && Minecraft.useShaderTransparency()) {
            RenderTarget translucent = minecraft.levelRenderer.getTranslucentTarget();
            if (translucent != null) {
                program.bindMainDepth(translucent.getDepthTextureId(), projection);
                var main = minecraft.getMainRenderTarget();
                program.bindVoxyDepth(PredictionVoxyDepth.current(main.frameBufferId, main.width, main.height, camera),
                        new Matrix4f(event.projection()).mul(event.modelView()));
            }
        }
        if (water) program.bindWaterDepthHistory(predictionTarget.waterDepthHistory());
        int maxQuads = pass.maxQuads();
        if (maxQuads <= 0) {
            GlProgram.unuse();
            return 0L;
        }
        bindSharedIndices(maxQuads);
        long calls = 0L;
        long quads = 0L;
        boolean batch = program.supportsBatch();
        long frameTime = System.nanoTime();
        // Batch metadata is world-stable.  These two uniforms are the only
        // per-frame values needed by the batch vertex shader.
        program.setBatchCameraOffset((float) -camera.x, (float) -camera.y, (float) -camera.z);
        program.setBatchTimeSeconds(PredictionMorph.seconds(frameTime));
        long indirectBuildStart = batch ? PredictionRenderTimings.start() : 0L;
            if (batch) {
                if (indirectBatch == null) indirectBatch = new PredictionIndirectBatch();
                if (!water) {
                    var main = minecraft.getMainRenderTarget();
                    indirectBatch.workload(pass.selectedCandidates);
                    indirectBatch.begin(program, predictionTarget.depthTextureId(), main.width, main.height,
                            new Matrix4f(projection.matrix()).mul(event.modelView()), camera,
                            renderResidency.revision(), ClientPredictionState.exactCoverageRemovalRevision(),
                            pass.workloadRevision * 2 + (useAverage ? 1 : 0));
                } else indirectBatch.begin(program, false,
                        renderResidency.revision(), ClientPredictionState.exactCoverageRemovalRevision());
                if (!water && pass == residentOpaquePass && !indirectBatch.gpuSelection()) {
                    // A failed Hi-Z initialization or unsupported clip mode
                    // must submit the ordinary visible input, even when the
                    // earlier conservative policy allowed resident selection.
                    draws = ordinaryDraws;
                    pass = preparePass(draws, false, camera);
                    bindSharedIndices(pass.maxQuads());
                    lastGpuSelectionUse = 0;
                    residentOpaquePass.clear(); gpuSelectionInput.clear();
                }
            }
        try {
            PredictionGlState.depthMask(!water);
            program.setOpaqueAlpha(water ? 0.0F : 1.0F);
            if (water) {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            } else PredictionGlState.disableBlend();
            long persistent = batch && !water ? indirectBatch.submitScene(draws, pass.rangesByDraw(),
                    useAverage, camera, frameTime) : -1;
            if (persistent < 0 && pass == residentOpaquePass) {
                // Coarse alternatives require the complete GPU graph transaction.
                // An unavailable/oversized scene must use the ordinary ownership plan.
                draws = ordinaryDraws;
                pass = preparePass(draws, false, camera);
                bindSharedIndices(pass.maxQuads());
                lastGpuSelectionUse = 0;
            }
            long replayed = persistent >= 0 ? persistent
                    : batch ? indirectBatch.replayPass(pass, pass.revision, useAverage, camera, frameTime) : -1;
            if (replayed >= 0) {
                quads += replayed;
                if (persistent >= 0) for (var entry : pass.entries()) {
                    var draw = entry.draw();
                    if (draw.coarseProxy()) continue;
                    if (indirectBatch.sceneContains(draw)) continue;
                    indirectBatch.fallback(draw, entry.ranges(), camera);
                    drawSingle(draw, entry.ranges(), camera, useAverage, frameTime, false);
                    calls++; quads += entry.ranges().quads;
                }
            } else if (batch && !water) {
                /*
                 * Keep page batching, but only inside short distance buckets.
                 * Grouping a complete scene by page destroys the visible
                 * plan's front-to-back order: a far tile can be shaded before
                 * a nearer tile that would have rejected it. The bucket
                 * boundary is only an ordering approximation; geometry,
                 * coverage masks, materials and depth tests stay unchanged.
                 */
                int bucketCount = opaqueBatches.prepare(draws, renderResidency.revision(), pass.rangesByDraw(), camera);
                frameOpaqueBuckets = bucketCount;
                frameOpaquePageGroups = 0;
                frameOpaqueFallbacks = 0;
                for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
                    indirectBatch.nextOcclusionBucket();
                    PredictionOpaqueBatches.Bucket bucket = opaqueBatches.bucket(bucketIndex);
                    frameOpaquePageGroups += bucket.pages.size();
                    frameOpaqueFallbacks += bucket.fallback.size();
                    for (PredictionOpaqueBatches.PageGroup pageGroup : bucket.pages) {
                        indirectBatch.nextRegion(pageGroup.region);
                        for (Draw draw : pageGroup.draws) {
                            var ranges = pass.ranges(draw);
                            if (ranges == null) continue;
                            if (ranges.quads == 0) continue;
                            if (indirectBatch.add(draw, ranges, camera, false, useAverage, frameTime)) {
                                quads += ranges.quads;
                            } else {
                                // A tile can retire between planning and
                                // submission. Preserve the safe fallback.
                                indirectBatch.flush();
                                indirectBatch.fallback(draw, ranges, camera);
                                drawSingle(draw, ranges, camera, useAverage, frameTime, false);
                                calls++;
                                quads += ranges.quads;
                            }
                        }
                    }
                    // Legacy tiles have no shared page. Flush the pending MDI
                    // commands before drawing them so their depth state stays
                    // within this bucket's near-to-far window.
                    if (!bucket.fallback.isEmpty()) indirectBatch.flush();
                    for (Draw draw : bucket.fallback) {
                        var ranges = pass.ranges(draw);
                        if (ranges == null) continue;
                        if (ranges.quads == 0) continue;
                        indirectBatch.fallback(draw, ranges, camera);
                        drawSingle(draw, ranges, camera, useAverage, frameTime, false);
                        calls++;
                        quads += ranges.quads;
                    }
                }
            } else {
                frameOpaqueBuckets = 0;
                frameOpaquePageGroups = 0;
                List<PreparedDraw> entries = pass.entries();
                for (int index = 0; index < entries.size(); index++) {
                    PreparedDraw prepared = entries.get(water ? entries.size() - 1 - index : index);
                    Draw draw = prepared.draw();
                    var ranges = prepared.ranges();
                    if (batch && indirectBatch.add(draw, ranges, camera, true, useAverage, frameTime)) {
                        quads += ranges.quads;
                        continue;
                    }
                    if (batch) indirectBatch.fallback(draw, ranges, camera);
                    drawSingle(draw, ranges, camera, useAverage, frameTime, water);
                    calls++;
                    quads += ranges.quads;
                }
            }
        } finally {
            if (batch) {
                long batchTiles = indirectBatch.submittedTiles();
                long batchCalls = indirectBatch.end();
                calls += batchCalls;
                indirectCalls.addAndGet(batchCalls);
                indirectTiles.addAndGet(batchTiles);
            }
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.INDIRECT_BUILD, indirectBuildStart);
            PredictionGlState.depthMask(true);
            PredictionGlState.disableBlend();
            GL30.glBindVertexArray(0);
            PredictionVanillaMask.unbind(6);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE5);
            PredictionGlState.bindTexture(0);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE7);
            PredictionGlState.bindTexture(0);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
            GlProgram.unuse();
        }
        drawCalls.addAndGet(calls);
        submittedQuads.addAndGet(quads);
        return calls;
    }

    /** Emits one tile using the original stateful path when it cannot be batched. */
    private static void drawSingle(Draw draw, PredictionDrawRanges ranges, Vec3 camera,
                                   boolean useAverage, long frameTime, boolean water) {
        PredictionPackedMesh packed = draw.gpu().packed();
        // The batch shader shares the regular uniforms but reads per-draw
        // values only when this flag is true. Explicitly leave batch mode for
        // an independent-buffer fallback, including after a page flush.
        program.batch(false);
        // Water may live in its own compact arena slice.  Keep the direct
        // path on the same payload/base contract as MDI: the bind selects the
        // slice and palette, while the ranges are rebased to the local water
        // records when that slice is present.
        draw.gpu().bindTerrain(program, water);
        draw.gpu().bindYield(3);
        program.setTile((float) (draw.tile().baseBlockX() - camera.x), (float) -camera.y,
                (float) (draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(),
                packed.cellAxis(), useAverage);
        // Batch draws do not update the legacy morph uniform. Clear it for a
        // seam/fallback tile so a previous non-seam draw cannot leak its
        // displacement into this tile when the batch shader is reused.
        if (!water) program.setMorph(packed, draw.seam() ? 0.0F
                : draw.morph() * draw.gpu().morphAmount(frameTime));
        program.setBoundaryReplacement(!water && !draw.seam());
        submitRanges(ranges, packed, water);
    }

    private static long drawIris(Minecraft minecraft, Frame event, List<Draw> draws,
                                 Vec3 camera, VssLodProjection.MatrixData projection) {
        boolean water = irisPass.translucent();
        PreparedPass pass = preparePass(draws, water, camera);
        if (!pass.hasDraws()) return 0L;
        boolean useAverage = !bindFrame(minecraft, event, projection, camera);
        int maxQuads = pass.maxQuads();
        if (maxQuads == 0) return 0;
        bindSharedIndices(maxQuads);
        program.setOpaqueAlpha(water ? 0.0F : 1.0F);
        PredictionGlState.depthMask(true);
        PredictionRenderCapture.current(water ? "water-bound" : "opaque-bound");
        long frameTime = System.nanoTime();
        long calls = submitIris(pass, camera, water, useAverage, frameTime);
        drawCalls.addAndGet(calls);
        return calls;
    }

    /** Keep the adapter's exact order; only adjacent compatible pages are merged. */
    private static long submitIris(PreparedPass pass, Vec3 camera, boolean water,
                                   boolean useAverage, long frameTime) {
        boolean batch = !water && program.supportsBatch() && pass.irisBatchWorthwhile();
        if (batch) {
            if (indirectBatch == null) indirectBatch = new PredictionIndirectBatch();
            indirectBatch.begin(program, true, renderResidency.revision(), ClientPredictionState.exactCoverageRemovalRevision());
        }
        long calls = 0, quads = 0;
        try {
            long replayed = batch ? indirectBatch.replayPass(pass, pass.revision, useAverage, camera, frameTime) : -1;
            if (replayed >= 0) quads = replayed;
            else {
                List<PreparedDraw> entries = pass.entries();
                for (int i = 0; i < entries.size(); i++) {
                    PreparedDraw prepared = entries.get(water ? entries.size() - 1 - i : i);
                    Draw draw = prepared.draw();
                    if (draw.gpu().packed() == null) continue;
                    var ranges = prepared.ranges();
                    if (batch && indirectBatch.add(draw, ranges, camera, false, useAverage, frameTime)) {
                        quads += ranges.quads;
                        continue;
                    }
                    if (batch) {
                        indirectBatch.flush();
                        indirectBatch.fallback(draw, ranges, camera);
                    }
                    program.batch(false);
                    drawSingle(draw, ranges, camera, useAverage, frameTime, water);
                    calls++;
                    quads += ranges.quads;
                }
            }
        } finally {
            if (batch) calls += indirectBatch.end();
        }
        submittedQuads.addAndGet(quads);
        return calls;
    }

    /** Same ordered indices and tile bindings, one GL entry per tile/pass.
     * GL 1.4 multidraw is below the renderer's existing GL requirement. */
    static void submitRanges(PredictionDrawRanges ranges) {
        submitRanges(ranges, 0);
    }

    /**
     * Submit face ranges against either the canonical payload or a compact
     * pass-local payload.  A dedicated water page contains only water quads,
     * so its ranges start at zero after subtracting {@code rangeBase}; the
     * shared index buffer itself remains unchanged.  Keeping the rebase here
     * also makes the direct and Iris paths use the same offsets as MDI.
     */
    static void submitRanges(PredictionDrawRanges ranges, int rangeBase) {
        if (rangeBase < 0) throw new IllegalArgumentException("negative range base");
        if(ranges.first.length==1) {
            int first = ranges.first[0] - rangeBase;
            if (first < 0) throw new IllegalArgumentException("range base exceeds range start");
            GL11.glDrawElements(GL11.GL_TRIANGLES,ranges.count[0]*6,GL11.GL_UNSIGNED_INT,(long)first*24);
            return;
        }
        if (rangeCounts.capacity() < ranges.first.length) {
            rangeCounts = org.lwjgl.BufferUtils.createIntBuffer(ranges.first.length);
            rangeOffsets = org.lwjgl.BufferUtils.createPointerBuffer(ranges.first.length);
        }
        rangeCounts.clear();rangeOffsets.clear();
        for(int i=0;i<ranges.first.length;i++) {
            int first = ranges.first[i] - rangeBase;
            if (first < 0) throw new IllegalArgumentException("range base exceeds range start");
            rangeCounts.put(ranges.count[i]*6);rangeOffsets.put((long)first*24);
        }
        rangeCounts.flip();rangeOffsets.flip();
        org.lwjgl.opengl.GL14.glMultiDrawElements(GL11.GL_TRIANGLES,rangeCounts,GL11.GL_UNSIGNED_INT,rangeOffsets);
        groupedDrawCalls.incrementAndGet();
    }

    static void submitRanges(PredictionDrawRanges ranges, PredictionPackedMesh packed, boolean water) {
        if (ranges.first.length == 1) {
            int first = packed.gpuFirst(ranges.first[0], ranges.count[0], water);
            GL11.glDrawElements(GL11.GL_TRIANGLES, ranges.count[0] * 6,
                    GL11.GL_UNSIGNED_INT, (long) first * 24);
            return;
        }
        if (rangeCounts.capacity() < ranges.first.length) {
            rangeCounts = org.lwjgl.BufferUtils.createIntBuffer(ranges.first.length);
            rangeOffsets = org.lwjgl.BufferUtils.createPointerBuffer(ranges.first.length);
        }
        rangeCounts.clear(); rangeOffsets.clear();
        for (int i = 0; i < ranges.first.length; i++) {
            int first = packed.gpuFirst(ranges.first[i], ranges.count[i], water);
            rangeCounts.put(ranges.count[i] * 6); rangeOffsets.put((long) first * 24);
        }
        rangeCounts.flip(); rangeOffsets.flip();
        org.lwjgl.opengl.GL14.glMultiDrawElements(GL11.GL_TRIANGLES, rangeCounts, GL11.GL_UNSIGNED_INT, rangeOffsets);
        groupedDrawCalls.incrementAndGet();
    }

    static Long2ByteOpenHashMap newDecisionCache() {
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        cache.defaultReturnValue(UNKNOWN_DECISION);
        return cache;
    }

    private static Long2ByteOpenHashMap decisionCacheFor(int playerChunkX,
                                                      int playerChunkZ,
                                                      double pixelsPerBlock,
                                                      VssLodFocus focus) {
        long pixelsBits = Double.doubleToLongBits(pixelsPerBlock);
        double threshold = pixelsPerQuad();
        if (decisionCachePixelsBits == pixelsBits
                && decisionCachePixelThreshold == threshold
                && decisionCachePlayerChunkX == playerChunkX
                && decisionCachePlayerChunkZ == playerChunkZ
                && java.util.Objects.equals(decisionFocus, focus)
                && System.nanoTime() < decisionCacheExpiresAt) {
            return chunkDecisionCache;
        }
        chunkDecisionCache.clear();
        decisionCachePixelsBits = pixelsBits;
        decisionCachePixelThreshold = threshold;
        decisionCachePlayerChunkX = playerChunkX;
        decisionCachePlayerChunkZ = playerChunkZ;
        decisionFocus = focus;
        decisionCacheExpiresAt = System.nanoTime() + COVERAGE_RECHECK_NANOS;
        return chunkDecisionCache;
    }

    static TileCoverage resolveCoverage(PredictionTileManager.PredictionTile tile,
                                                int playerChunkX, int playerChunkZ,
                                                double pixelsPerBlock, PredictionTileManager.RenderSnapshot snapshot,
                                                VssLodFocus focus) {
        PredictionMesh mesh = tile.mesh();
        int stepBlocks = tile.spacingBlocks();
        int baseBlockX = tile.baseBlockX();
        int baseBlockZ = tile.baseBlockZ();
        long considered = (long) mesh.cellAxis() * mesh.cellAxis();
        long coverageSkipped = 0L;
        int cellAxis = mesh.cellAxis();
        boolean[] allowed = new boolean[cellAxis * cellAxis];
        boolean[] features = new boolean[allowed.length];
        // Resident ordinary tiles always have an owner and do not depend on
        // the view's requested detail. Resolve once per minimum tile footprint.
        boolean ordinary = !snapshot.scopeAffects(tile.key()) && snapshot.tiles().get(tile.key()) == tile;
        int groupAxis = Math.max(1, (ordinary ? snapshot.layout().tileBlocks(0) : 16) / stepBlocks);
        // Keep prediction ownership independent from optional Voxy suppression.
        // This immutable mask is safe to draw while a Voxy refresh is pending.
        Long2ByteOpenHashMap decisions = ordinary ? null : decisionCacheFor(playerChunkX, playerChunkZ,
                pixelsPerBlock, focus);
        VssLodFocus terrainFocus = ordinary ? null : PredictionWorkOrder.terrainFocus(focus);
        var grid = PredictionCoverageGrid.resolve(tile, snapshot.subCellTiles(tile), (worldX, worldZ) -> {
            int chunkX = Math.floorDiv(worldX, 16), chunkZ = Math.floorDiv(worldZ, 16);
            int detail = ordinary ? 0 : desiredCoverageLod(chunkX, chunkZ, playerChunkX, playerChunkZ,
                    pixelsPerBlock, snapshot, focus, terrainFocus);
            var cover = coverageOwners.coveringTileAtDetail(snapshot, chunkX, chunkZ, detail);
            int value = cover == tile || cover == null && detail >= tile.key().lod() ? 128 : 0;
            return value | (coverageOwners.coveringFeatureTileAtDetail(snapshot, chunkX, chunkZ, detail) == tile ? 64 : 0);
        });
        if (grid != null) {
            long count = 0, visible = 0, skipped = 0;
            for (int node = 0; node < grid.size(); node++) if (grid.leaf(node)) {
                count++;
                if (grid.ground(node) || grid.features(node)) visible++;
                if (!grid.ground(node)) skipped++;
            }
            return new TileCoverage(grid.roots(128), grid.roots(64), count, visible, skipped, 0, grid);
        }
        for (int z = 0; z < cellAxis; z += groupAxis) {
            for (int x = 0; x < cellAxis; x += groupAxis) {
                int endX = Math.min(cellAxis, x + groupAxis), endZ = Math.min(cellAxis, z + groupAxis);
                int cellChunkX = Math.floorDiv(baseBlockX + x * stepBlocks + stepBlocks / 2, 16);
                int cellChunkZ = Math.floorDiv(baseBlockZ + z * stepBlocks + stepBlocks / 2, 16);
                long chunkKey = (long) cellChunkX << 32 | cellChunkZ & 0xFFFFFFFFL;
                byte shared = ordinary ? 0 : decisions.get(chunkKey);
                byte desiredLod;
                if (shared != UNKNOWN_DECISION) {
                    // Selection depends on position, projection and focus.
                    desiredLod = shared;
                } else {
                    desiredLod = (byte) desiredCoverageLod(cellChunkX,cellChunkZ,playerChunkX,playerChunkZ,
                            pixelsPerBlock,snapshot,focus,terrainFocus);
                    if (decisions.size() > 96_000) {
                        decisions.clear();
                    }
                    decisions.put(chunkKey, desiredLod);
                }
                byte decision;
                PredictionTileManager.PredictionTile cover = coverageOwners.coveringTileAtDetail(snapshot, cellChunkX, cellChunkZ, desiredLod);
                if (cover == tile || (cover == null && desiredLod >= tile.key().lod())) {
                    decision = 0;
                } else {
                    decision = 2;
                }
                if (decision == 2) {
                    coverageSkipped += (long) (endX - x) * (endZ - z);
                } else {
                    for (int row = z; row < endZ; row++) java.util.Arrays.fill(allowed,
                            row * cellAxis + x, row * cellAxis + endX, true);
                }
                if (coverageOwners.coveringFeatureTileAtDetail(snapshot, cellChunkX, cellChunkZ, desiredLod) == tile) {
                    for (int row = z; row < endZ; row++) java.util.Arrays.fill(features,
                            row * cellAxis + x, row * cellAxis + endX, true);
                }
            }
        }
        long rendered = 0;
        for (int cell = 0; cell < allowed.length; cell++) if (allowed[cell] || features[cell]) rendered++;
        return new TileCoverage(allowed, features, considered, rendered,
                coverageSkipped, 0);
    }

    private static int desiredCoverageLod(int chunkX, int chunkZ, int playerX, int playerZ, double pixels,
            PredictionTileManager.RenderSnapshot snapshot, VssLodFocus focus, VssLodFocus terrainFocus) {
        double dx=chunkX-(double)playerX, dz=chunkZ-(double)playerZ;
        double distance=Math.max(64,Math.sqrt(dx*dx+dz*dz)*16);
        int detail=lodForBlocks(distance,pixels,pixelsPerQuad());
        if(distance<PredictionDetailBands.fineRadius(snapshot.layout().maxDistanceBlocks(),snapshot.dimension())) detail=0;
        if(focus!=null) {
            if(focus.contains(chunkX*16+8,chunkZ*16+8)) detail=Math.min(detail,lodForBlocks(distance,focus.selectionScale(pixels),pixelsPerQuad()));
            if(terrainFocus.intersects(chunkX*16D,chunkZ*16D,(chunkX+1D)*16,(chunkZ+1D)*16)) detail=0;
        }
        return detail;
    }

    public static String diagnostics() {
        return "frames=" + renderFrames.get()
                + ",frameStarts=" + frameStarts.get()
                + ",preparedPlans=" + preparedPlans.get() + ",reusedPlans=" + reusedPlans.get()
                + ",timings={" + PredictionRenderTimings.diagnostics() + "}"
                + "," + uploadBudget.diagnostics()
                + ",tiles=" + seenTiles.get()
                + ",considered=" + consideredCells.get()
                + ",rendered=" + renderedCells.get()
                + ",coverageSkipped=" + skippedCoverage.get()
                + ",authoritativeSkipped=" + skippedAuthoritative.get()
                + ",meshUploads=" + meshUploads.get()
                + ",meshRestore={" + PredictionMeshRestore.diagnostics() + "}"
                + ",gpuResidency={" + gpuResidency.diagnostics() + "}"
                + "," + PredictionQuadBufferPool.SHARED.diagnostics()
                + "," + PredictionTerrainArena.SHARED.diagnostics()
                + ",drawCalls=" + drawCalls.get()
                + ",indirectCalls=" + indirectCalls.get()
                + ",indirectTiles=" + indirectTiles.get()
                + ",indirect={" + (indirectBatch == null ? "none" : indirectBatch.diagnostics()) + "}"
                + "," + (refinementOcclusion == null ? "visibilityHints=none" : refinementOcclusion.diagnostics())
                + ",batchCache={hits=" + opaqueBatches.cacheHits()
                + ",misses=" + opaqueBatches.cacheMisses() + "}"
                + ",passCache={opaque=" + opaquePass.diagnostics()
                + ",water=" + waterPass.diagnostics() + "}"
                + ",multiDrawCalls=" + groupedDrawCalls.get()
                + ",quads=" + submittedQuads.get()
                + "," + vanillaMask.diagnostics() + ",boundaryIndex={" + scene.boundaries.diagnostics() + "," + PredictionSeamMasks.diagnostics() + "}"
                + ",scene={" + scene.diagnostics() + "}"
                + ",coverageOwners={" + coverageOwners.diagnostics() + "}"
                + ",gpuTiles=" + gpuTiles.size()
                + ",coverageCacheHits=" + coverageCacheHits.get()
                + ",coverageResolves=" + coverageResolves.get()
                + ",coverageMs=" + String.format(java.util.Locale.ROOT, "%.1f",
                coverageNanos.get() / 1_000_000.0D)
                + ",detailPath=" + detailPathEnabled
                + ",spriteTableId=" + boundSpriteTableId
                + ",spriteTable=" + VssLodSpriteTable.diagnostics();
    }

    /** Drops all GPU caches; called on world change and resource reload. */
    public static void resetOcclusion() {
        resetEdgeFilter = true;
        resetQuadPool = true;
        resetIndirectBatch = true;
        ClientPredictionState.clearVisibilityFeedback();
        deferredWater = null;
        planGeneration.incrementAndGet();
        normalFramePlan.clear();
        PredictionRenderTimings.reset();
        detailProfileSession++;
        selectionScale = 0.0D;
        lastUsedDraws = null;
        nextUsedRefresh = 0;
        displayWorkView = null;
        viewRay = null;
        PredictionVoxyDepth.clear();
        renderResidency.clear();
        gpuResidency.clear();
        PredictionMeshRestore.clear();
        scene.clear();
        uploadBudget.clear();
        vanillaMask.invalidate();
        exactMask.invalidate();
        coverageCache.clear();
        coverageOwners.clear();
        opaquePass.clear();
        residentOpaquePass.clear();
        gpuSelectionInput.clear();
        lastGpuSelectionUse = 0;
        waterPass.clear();
        List<PredictionGpuTile> released = new ArrayList<>(gpuTiles.values());
        gpuTiles.clear();
        retiredTiles.addAll(released);
    }

    private static final java.util.Queue<PredictionGpuTile> retiredTiles = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static PredictionIndirectBatch indirectBatch;
    private static PredictionRefinementOcclusion refinementOcclusion;
    private static PredictionTerrainProgram batchProgram;
    private static boolean batchProgramBroken;

    private static void drainRetiredTiles() {
        if (resetIndirectBatch) {
            opaqueBatches.clear();
            if (indirectBatch != null) indirectBatch.close();
            indirectBatch = null;
            if (refinementOcclusion != null) refinementOcclusion.close();
            refinementOcclusion = null;
            resetIndirectBatch = false;
        }
        if (resetQuadPool) { PredictionQuadBufferPool.SHARED.close(); resetQuadPool = false; }
        PredictionQuadBufferPool.SHARED.trim();
        if (PredictionTerrainArena.supported()) PredictionTerrainArena.SHARED.reap();
        long until = System.nanoTime() + 1_000_000L;
        for (int i = 0; i < 8; i++) {
            PredictionGpuTile tile = retiredTiles.poll();
            if (tile == null) break;
            if (scene.references(tile)) { retiredTiles.add(tile); continue; }
            tile.close();
            if (System.nanoTime() >= until) break;
        }
    }

    private static boolean tileWithinHorizon(PredictionTileManager.PredictionTile tile, Vec3 camera,
                                             Frustum frustum) {
        double horizon = predictionHorizonBlocks();
        double minX = tile.baseBlockX();
        double minZ = tile.baseBlockZ();
        double maxX = minX + tile.spanBlocks();
        double maxZ = minZ + tile.spanBlocks();
        double dx = camera.x < minX ? minX - camera.x : camera.x > maxX ? camera.x - maxX : 0.0D;
        double dz = camera.z < minZ ? minZ - camera.z : camera.z > maxZ ? camera.z - maxZ : 0.0D;
        double margin = Math.sqrt(2.0D) * tile.spanBlocks() * 0.5D;
        if (!(dx * dx + dz * dz <= (horizon + margin) * (horizon + margin))) return false;
        if (frustum != null) {
            AABB bounds = new AABB(minX, tile.depthBound().minY(), minZ,
                    maxX, tile.depthBound().maxY(), maxZ).inflate(2.0D);
            if (!frustum.isVisible(bounds)) return false;
        }
        return true;
    }

    private static int predictionHorizonBlocks() {
        return Math.max(VSSClientConfig.MIN_PREDICTION_DISTANCE_BLOCKS,
                Math.min(VSSClientConfig.MAX_PREDICTION_DISTANCE_BLOCKS,
                        VSSClientConfig.CONFIG.predictionDistanceBlocks));
    }

    record TileCoverage(boolean[] allowed, boolean[] features, long considered, long rendered,
                                long coverageSkipped, long authoritativeSkipped, PredictionCoverageGrid grid) {
        TileCoverage(boolean[] allowed, boolean[] features, long considered, long rendered, long coverageSkipped, long authoritativeSkipped) {
            this(allowed,features,considered,rendered,coverageSkipped,authoritativeSkipped,null);
        }
        TileCoverage(boolean[] allowed, long considered, long rendered, long coverageSkipped, long authoritativeSkipped) {
            this(allowed, allowed, considered, rendered, coverageSkipped, authoritativeSkipped);
        }
    }

    record Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu,
                        boolean[] allowed, boolean[] features, boolean seam, int faces, float morph, AABB bounds,
                        boolean completeOwnership, boolean completeTerrainOwnership, boolean coarseProxy, PredictionCoverageGrid coverageGrid) {
        Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu, boolean[] allowed, boolean[] features,
             boolean seam, int faces, float morph, AABB bounds, boolean completeOwnership, boolean completeTerrainOwnership, boolean coarseProxy) {
            this(tile,gpu,allowed,features,seam,faces,morph,bounds,completeOwnership,completeTerrainOwnership,coarseProxy,null);
        }
        Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu, boolean[] allowed, boolean[] features,
             boolean seam, int faces, float morph, AABB bounds, boolean completeOwnership, boolean completeTerrainOwnership) {
            this(tile, gpu, allowed, features, seam, faces, morph, bounds, completeOwnership, completeTerrainOwnership, false);
        }
        Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu, boolean[] allowed, boolean[] features,
             boolean seam, int faces, float morph, AABB bounds, boolean completeOwnership) {
            this(tile, gpu, allowed, features, seam, faces, morph, bounds, completeOwnership, seam || completeOwnership || allOwned(allowed));
        }
        Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu, boolean[] allowed, boolean[] features,
             boolean seam, int faces, float morph, AABB bounds) {
            this(tile, gpu, allowed, features, seam, faces, morph, bounds,
                    seam || allOwned(allowed) && (gpu.packed() == null || !gpu.packed().hasFeatures || allOwned(features)));
        }
        private static boolean allOwned(boolean[] cells) {
            if (cells == null) return false;
            for (boolean owned : cells) if (!owned) return false;
            return true;
        }
        Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu, boolean[] allowed,
             boolean seam, int faces, float morph, AABB bounds) {
            this(tile, gpu, allowed, allowed, seam, faces, morph, bounds);
        }
    }

    record CoverageView(int playerChunkX, int playerChunkZ, double pixelsPerBlock,
                        VssLodFocus focus, double pixelsPerQuad, int fineDistance) {
        private static final CoverageView ORDINARY = new CoverageView(0, 0, 0, null, 0, 0);
        CoverageView ownership(boolean scopedTiles) {
            // Ordinary ownership selects the finest resident data independently
            // of camera distance/FOV. Only telescope-only tiles depend on view.
            // Mesh revisions and GPU residency epochs still invalidate immediately.
            return scopedTiles ? this : ORDINARY;
        }
        CoverageView(int playerChunkX, int playerChunkZ, double pixelsPerBlock, VssLodFocus focus) {
            this(playerChunkX, playerChunkZ, pixelsPerBlock, focus, PredictionRenderer.pixelsPerQuad(),
                    VSSClientConfig.CONFIG.predictionFineDistanceBlocks);
        }
    }

    record CachedCoverage(long tileRevision, long tileEpoch,
                          CoverageView view, TileCoverage coverage) {
        boolean matchesOwnership(long nextTileRevision, long nextTileEpoch, CoverageView nextView) {
            return tileRevision == nextTileRevision && tileEpoch == nextTileEpoch && view.equals(nextView);
        }
    }

    static int lodForBlocks(double distanceBlocks, double pixelsPerBlock,
                            double pixelsPerQuad) {
        if (!(pixelsPerBlock > 0.0D) || !(pixelsPerQuad > 0.0D)
                || !(distanceBlocks > 0.0D)) {
            return 0;
        }
        double spacingMax = distanceBlocks * pixelsPerQuad / pixelsPerBlock;
        if (spacingMax < 1.0D) {
            return 0;
        }
        int lod = (int) Math.floor(Math.log(Math.min(spacingMax, 65536.0D)) / Math.log(2.0D));
        return Math.min(PredictionTileManager.MAX_LOD_LEVEL, Math.max(0, lod));
    }

    static double pixelsPerQuad() {
        return switch (VSSClientConfig.CONFIG.predictionDetail) {
            case "low" -> 12.0D;
            case "high" -> 3.0D;
            case "extreme" -> 1.5D;
            default -> 6.0D;
        };
    }

}
