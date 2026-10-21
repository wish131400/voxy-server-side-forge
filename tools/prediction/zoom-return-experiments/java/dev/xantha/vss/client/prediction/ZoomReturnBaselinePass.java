package dev.xantha.vss.client.prediction;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.client.prediction.PredictionRenderer.Draw;
/** Exact production PreparedPass preceding the morph deadline fix. */
final class ZoomReturnBaselinePass {
    private static final class PreparedDraw {
        private Draw draw;
        private PredictionDrawRanges ranges;
        private PredictionDrawRanges ownershipRanges;
        private int directionMask = -1;
        private PredictionPackedMesh packed;
        private long meshVersion, ownershipVersion, gpuVersion;
        private int aquaticTier, displayTier, vegetationTier, fullQuads, terrainQuads, displayQuads, orderIndex = -1;
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
    static final class Pass {
        private final boolean water;
        private List<Draw> source;
        private final ArrayList<PredictionPackedMesh> packedSources = new ArrayList<>();
        private long preparedResidencyRevision = Long.MIN_VALUE;
        private long preparedOwnershipRevision = Long.MIN_VALUE;
        private long ownershipSkipped;
        private long directionSkipped;
        private long aquaticSkipped;
        private long sourceCandidates, terrainCandidates, displayCandidates, selectedCandidates;
        private int geometricDisplayTiles;
        private Vec3 aquaticCamera;
        private int aquaticDistance;
        private boolean aquaticScoping;
        private double displayScale;
        private double displayErrorPixels;
        private int displayFullDistance;
        private PredictionWorkView previousDisplayView;
        private long preparedSubmissionEpoch = Long.MIN_VALUE, aquaticSubmissionEpoch = Long.MIN_VALUE;
        private long preparedGpuEpoch = Long.MIN_VALUE;
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

        Pass(boolean water) { this.water = water; }

        private double preparedCameraX = Double.NaN;
        private double preparedCameraY = Double.NaN;
        private double preparedCameraZ = Double.NaN;

        private void prepare(List<Draw> draws, long residencyRevision, Vec3 camera) {
            long gpuEpoch = PredictionGpuTile.submissionEpoch();
            boolean sameGeometry = source == draws && (preparedResidencyRevision == residencyRevision
                    && preparedGpuEpoch == gpuEpoch || packedSourcesMatch(draws, preparedGpuEpoch != gpuEpoch));
            boolean aquaticChanged = prepareAquatic(draws, camera, sameGeometry);
            boolean directionsChanged = !water && prepareDirections(draws, camera, sameGeometry);
            if (sameGeometry
                    && preparedOwnershipRevision == submissionCoverage.revision()
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
            ownershipSkipped = 0;
            directionSkipped = 0;
            aquaticSkipped = 0;
            sourceCandidates=terrainCandidates=displayCandidates=selectedCandidates=0;
            geometricDisplayTiles = 0;
            Arrays.fill(distanceSource, 0L);
            Arrays.fill(distanceTerrain, 0L);
            Arrays.fill(distanceDisplay, 0L);
            Arrays.fill(distanceSelected, 0L);
            for (Draw draw : draws) {
                sourceIndex++;
                PredictionPackedMesh packed = draw.gpu().packed();
                packedSources.add(packed);
                if (packed == null) continue;
                packed.trackSubmission();
                var entry = cachedEntries.get(draw);
                if (entry == null) { entry = new PreparedDraw(); entry.draw = draw; }
                long meshVersion = packed.submissionRevision();
                long ownershipVersion = submissionCoverage.selectionVersion(packed,
                        draw.tile().baseBlockX(), draw.tile().baseBlockZ(), water);
                int aquaticTier = water || draw.seam() ? 0 : packed.aquaticLod.tier();
                int displayTier = water || draw.seam() ? 0 : packed.displayLod.tier();
                int vegetationTier = water || draw.seam() || !draw.completeOwnership() || packed.morph() != null ? 0
                        : Math.max(aquaticTier, packed.vegetationLod.tier());
                boolean rebuild = entry.packed != packed || entry.meshVersion != meshVersion
                        || entry.ownershipVersion != ownershipVersion
                        || entry.aquaticTier != aquaticTier || entry.displayTier != displayTier
                        || entry.vegetationTier != vegetationTier;
                if (rebuild) {
                    var previousRanges = entry.ranges;
                    boolean payloadChanged = entry.packed != packed || entry.meshVersion != meshVersion;
                    PredictionDrawRanges selected = packed.drawRanges(water, draw.faces());
                    entry.fullQuads = selected.quads;
                    entry.terrainQuads = !water && !draw.seam()
                            ? packed.drawRanges(false, draw.faces(), 0, displayTier, 0).quads : selected.quads;
                    if (!water && !draw.seam()) selected = packed.drawRanges(false, draw.faces(), aquaticTier, displayTier, vegetationTier);
                    entry.displayQuads = selected.quads;
                    entry.ownershipRanges = submissionCoverage.filter(packed, selected, draw.tile().baseBlockX(),
                            draw.tile().baseBlockZ(), water, draw.faces());
                    entry.packed = packed; entry.meshVersion = meshVersion; entry.ownershipVersion = ownershipVersion;
                    entry.aquaticTier = aquaticTier; entry.displayTier = displayTier; entry.vegetationTier = vegetationTier;
                    rangeBuilds++;
                    entry.ranges = entry.ownershipRanges;
                    workloadChanged |= entry.ranges.quads > 0 && (payloadChanged || previousRanges != entry.ranges);
                } else rangeReuses++;
                int directionMask = water ? 127 : PredictionCommandDirections.mask(draw,camera);
                if (rebuild || entry.directionMask != directionMask) {
                    var previous = entry.ranges;
                    entry.ranges = PredictionCommandDirections.filter(packed.occlusionBounds,entry.ownershipRanges,directionMask);
                    entry.directionMask = directionMask;
                    workloadChanged |= previous != entry.ranges;
                }
                nextEntries.put(draw, entry);
                PredictionDrawRanges ranges = entry.ranges;
                sourceCandidates += entry.fullQuads;
                terrainCandidates += entry.terrainQuads;
                aquaticSkipped += entry.fullQuads - entry.displayQuads;
                displayCandidates += entry.displayQuads;
                ownershipSkipped += entry.displayQuads - entry.ownershipRanges.quads;
                directionSkipped += entry.ownershipRanges.quads - ranges.quads;
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
            preparedOwnershipRevision = submissionCoverage.revision();
            preparedSubmissionEpoch = PredictionPackedMesh.submissionEpoch();
            preparedGpuEpoch = gpuEpoch;
            // Keep the downstream page-group token when camera/coverage revisions
            // did not actually change the selected ranges. Never mutate a published map.
            if (rangesChanged || byDraw.size() != rangeScratch.size()) byDraw = new IdentityHashMap<>(rangeScratch);
            rangeScratch.clear();
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
            if (water && camera != null) sortWater(camera);
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

        private boolean prepareAquatic(List<Draw> draws, Vec3 camera, boolean sameGeometry) {
            if (water || camera == null) return false;
            int distance = (int) Math.ceil(Math.max(VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks,
                    Math.min(4096, selectionScale * .5)));
            double terrainError = PredictionDisplayLod.terrainPixelError(VSSClientConfig.CONFIG.predictionDetail);
            int fullDistance = VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks;
            boolean contextStable = camera.equals(aquaticCamera) && distance == aquaticDistance
                    && selectionScoping == aquaticScoping && displayScale == selectionScale
                    && displayErrorPixels == terrainError && displayFullDistance == fullDistance
                    && (displayWorkView == null ? previousDisplayView == null : !displayWorkView.changedFrom(previousDisplayView));
            if (sameGeometry && contextStable && aquaticSubmissionEpoch == PredictionPackedMesh.submissionEpoch()) return false;
            boolean changed = false;
            for (Draw draw : draws) {
                var packed = draw.gpu().packed();
                if (draw.seam() || packed == null || !packed.hasDisplayLod()) continue;
                if (contextStable) {
                    var cached = cachedEntries.get(draw);
                    if (cached != null && cached.packed == packed && cached.meshVersion == packed.submissionRevision()) continue;
                }
                var tile = draw.tile();
                changed |= packed.aquaticLod.update(PredictionAquaticLod.distance(camera.x, camera.z,
                        tile.baseBlockX(), tile.baseBlockZ(), tile.spanBlocks()), distance, selectionScoping);
                double horizontal = PredictionAquaticLod.distance(camera.x, camera.z,
                        tile.baseBlockX(), tile.baseBlockZ(), tile.spanBlocks());
                double vertical = Math.max(0, Math.max(draw.bounds().minY - camera.y, camera.y - draw.bounds().maxY));
                double eyeDepth = displayWorkView == null ? Math.hypot(horizontal, vertical) : displayWorkView.nearestDepth(draw.bounds());
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
                boolean forceSource = packed.morph() != null || !draw.completeOwnership()
                        || selectionScoping && displayWorkView == null;
                changed |= packed.displayLod.update(packed.displayErrors(), eyeDepth, pixelScale,
                        VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks, forceSource, terrainError);
                if (packed.hasVegetationLod()) changed |= packed.vegetationLod.update(PredictionVegetationRanges.ERRORS,
                        eyeDepth, pixelScale, VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks,
                        forceSource);
            }
            aquaticCamera = camera;
            aquaticDistance = distance;
            aquaticScoping = selectionScoping;
            displayScale = selectionScale;
            displayErrorPixels = terrainError;
            displayFullDistance = fullDistance;
            previousDisplayView = displayWorkView;
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
            revision++;
            final double cx = camera.x, cz = camera.z;
            entries.sort((left, right) -> {
                Draw a = left.draw(), b = right.draw();
                double ax = a.tile().baseBlockX() + a.tile().spanBlocks() * 0.5D - cx;
                double az = a.tile().baseBlockZ() + a.tile().spanBlocks() * 0.5D - cz;
                double bx = b.tile().baseBlockX() + b.tile().spanBlocks() * 0.5D - cx;
                double bz = b.tile().baseBlockZ() + b.tile().spanBlocks() * 0.5D - cz;
                return Double.compare(ax * ax + az * az, bx * bx + bz * bz);
            });
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
        private IdentityHashMap<Draw, PredictionDrawRanges> rangesByDraw() { return byDraw; }
        private void clear() {
            source = null;
            preparedResidencyRevision = Long.MIN_VALUE;
            preparedOwnershipRevision = Long.MIN_VALUE;
            ownershipSkipped = 0;
            directionSkipped = 0;
            aquaticSkipped = 0;
            sourceCandidates=terrainCandidates=displayCandidates=selectedCandidates=0;
            aquaticCamera = null;
            previousDisplayView = null;
            preparedSubmissionEpoch = aquaticSubmissionEpoch = Long.MIN_VALUE;
            preparedGpuEpoch = Long.MIN_VALUE;
            directionRegion.clear();
            geometricDisplayTiles = 0;
            Arrays.fill(distanceSource, 0L);
            Arrays.fill(distanceTerrain, 0L);
            Arrays.fill(distanceDisplay, 0L);
            Arrays.fill(distanceSelected, 0L);
            distanceCamera = null;
            nextDistanceRefresh = 0L;
            entries.clear();
            cachedEntries.clear(); nextEntries.clear(); workloadRevision++;
            packedSources.clear();
            rangeScratch.clear();
            byDraw = new IdentityHashMap<>();
            maxQuads = 0;
            hasDraws = false;
            batchWorthwhile = false;
            preparedCameraX = Double.NaN;
            preparedCameraY = Double.NaN;
            preparedCameraZ = Double.NaN;
        }
        private String diagnostics() { return "hits=" + hits + ",misses=" + misses
                + ",rangeBuilds=" + rangeBuilds + ",rangeReuses=" + rangeReuses + ",workloadRevision=" + workloadRevision + ",voxyExcludedQuads=" + ownershipSkipped
                + ",directionExcludedQuads=" + directionSkipped
                + ",displayExcludedQuads=" + aquaticSkipped + ",geometricDisplayTiles=" + geometricDisplayTiles
                + ",sourceCandidates=" + sourceCandidates + ",displayCandidates=" + displayCandidates
                + ",terrainCandidates=" + terrainCandidates + ",vegetationExcludedQuads=" + (terrainCandidates - displayCandidates)
                + ",afterVoxyCandidates=" + selectedCandidates
                + ",distanceBands=" + distanceSource[0] + "/" + distanceSource[1] + "/" + distanceSource[2] + "/" + distanceSource[3]
                + "->" + distanceTerrain[0] + "/" + distanceTerrain[1] + "/" + distanceTerrain[2] + "/" + distanceTerrain[3]
                + "->" + distanceDisplay[0] + "/" + distanceDisplay[1] + "/" + distanceDisplay[2] + "/" + distanceDisplay[3]
                + "->" + distanceSelected[0] + "/" + distanceSelected[1] + "/" + distanceSelected[2] + "/" + distanceSelected[3]
                + ",screenErrorPixels=" + displayErrorPixels; }

        private static int distanceBand(double distance) {
            if (distance < 256.0) return 0;
            if (distance < 512.0) return 1;
            if (distance < 1024.0) return 2;
            return 3;
        }

    }
    static PredictionSubmissionCoverage submissionCoverage;
    static double selectionScale;
    static boolean selectionScoping;
    static PredictionWorkView displayWorkView;
    static void synchronizeRenderer() throws Exception {
        for(String name:new String[]{"submissionCoverage","selectionScale","selectionScoping","displayWorkView"}) {
            var source=PredictionRenderer.class.getDeclaredField(name);source.setAccessible(true);
            var dest=ZoomReturnBaselinePass.class.getDeclaredField(name);dest.setAccessible(true);dest.set(null,source.get(null));
        }
    }
}
