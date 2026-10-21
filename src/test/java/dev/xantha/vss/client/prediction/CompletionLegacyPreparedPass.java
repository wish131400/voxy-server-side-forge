package dev.xantha.vss.client.prediction;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.client.prediction.PredictionRenderer.Draw;
    final class CompletionLegacyPreparedPass {
        private final boolean water;
        private List<Draw> source;
        private final ArrayList<PredictionPackedMesh> packedSources = new ArrayList<>();
        private long preparedResidencyRevision = Long.MIN_VALUE;
        private long preparedOwnershipRevision = Long.MIN_VALUE;
        private long ownershipSkipped;
        private long aquaticSkipped;
        private long sourceCandidates, displayCandidates, selectedCandidates;
        private int geometricDisplayTiles;
        private Vec3 aquaticCamera;
        private int aquaticDistance;
        private boolean aquaticScoping;
        private double displayScale;
        private PredictionWorkView previousDisplayView;
        private long preparedSubmissionEpoch = Long.MIN_VALUE, aquaticSubmissionEpoch = Long.MIN_VALUE;
        private final ArrayList<PreparedDraw> entries = new ArrayList<>();
        private final ArrayList<PreparedDraw> reusableEntries = new ArrayList<>();
        private final IdentityHashMap<Draw, PredictionDrawRanges> rangeScratch = new IdentityHashMap<>();
        private IdentityHashMap<Draw, PredictionDrawRanges> byDraw = new IdentityHashMap<>();
        private int maxQuads;
        private boolean hasDraws;
        private boolean batchWorthwhile;
        private long hits;
        private long misses;
        private long revision;

        CompletionLegacyPreparedPass(boolean water) { this.water = water; }

        private double preparedCameraX = Double.NaN;
        private double preparedCameraY = Double.NaN;
        private double preparedCameraZ = Double.NaN;

        void prepare(List<Draw> draws, long residencyRevision, Vec3 camera) {
            boolean sameGeometry = source == draws && (preparedResidencyRevision == residencyRevision || packedSourcesMatch(draws));
            boolean aquaticChanged = prepareAquatic(draws, camera, sameGeometry);
            if (sameGeometry
                    && preparedOwnershipRevision == submissionCoverage.revision()
                    && preparedSubmissionEpoch == PredictionPackedMesh.submissionEpoch() && !aquaticChanged) {
                if (water && camera != null && (camera.x != preparedCameraX
                        || camera.y != preparedCameraY || camera.z != preparedCameraZ)) {
                    sortWater(camera);
                }
                preparedResidencyRevision = residencyRevision;
                hits++;
                return;
            }
            misses++;
            revision++;
            entries.clear();
            packedSources.clear();
            rangeScratch.clear();
            boolean rangesChanged = false;
            int maximum = 0;
            boolean any = false;
            int arenaBacked = 0;
            ownershipSkipped = 0;
            aquaticSkipped = 0;
            sourceCandidates=displayCandidates=selectedCandidates=0;
            geometricDisplayTiles = 0;
            for (Draw draw : draws) {
                PredictionPackedMesh packed = draw.gpu().packed();
                packedSources.add(packed);
                if (packed == null) continue;
                packed.trackSubmission();
                PredictionDrawRanges ranges = packed.drawRanges(water, draw.faces());
                int fullQuads = ranges.quads;
                sourceCandidates += fullQuads;
                if (!water && !draw.seam()) ranges = packed.drawRanges(false, draw.faces(),
                        packed.aquaticLod.tier(), packed.displayLod.tier());
                aquaticSkipped += fullQuads - ranges.quads;
                if (!water && !draw.seam() && packed.displayLod.tier() > 0) geometricDisplayTiles++;
                int originalQuads = ranges.quads;
                displayCandidates += originalQuads;
                ranges = submissionCoverage.filter(packed, ranges, draw.tile().baseBlockX(), draw.tile().baseBlockZ(), water, draw.faces());
                ownershipSkipped += originalQuads - ranges.quads;
                selectedCandidates += ranges.quads;
                if (ranges.quads == 0) continue;
                if (draw.gpu().arenaSlice(water) != null) arenaBacked++;
                int entryIndex = entries.size();
                if (entryIndex == reusableEntries.size()) reusableEntries.add(new PreparedDraw());
                PreparedDraw entry = reusableEntries.get(entryIndex);
                entry.draw = draw; entry.ranges = ranges;
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
            // Keep the downstream page-group token when camera/coverage revisions
            // did not actually change the selected ranges. Never mutate a published map.
            if (rangesChanged || byDraw.size() != rangeScratch.size()) byDraw = new IdentityHashMap<>(rangeScratch);
            rangeScratch.clear();
            for (int i = entries.size(); i < reusableEntries.size(); i++) {
                reusableEntries.get(i).draw = null;
                reusableEntries.get(i).ranges = null;
            }
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

        private boolean packedSourcesMatch(List<Draw> draws) {
            if (packedSources.size() != draws.size()) return false;
            for (int i = 0; i < draws.size(); i++)
                if (packedSources.get(i) != draws.get(i).gpu().packed()) return false;
            return true;
        }

        private boolean prepareAquatic(List<Draw> draws, Vec3 camera, boolean sameGeometry) {
            if (water || camera == null) return false;
            int distance = (int) Math.ceil(Math.max(VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks,
                    Math.min(4096, selectionScale * .5)));
            if (sameGeometry && camera.equals(aquaticCamera)
                    && distance == aquaticDistance && selectionScoping == aquaticScoping && displayScale == selectionScale
                    && aquaticSubmissionEpoch == PredictionPackedMesh.submissionEpoch()
                    && (displayWorkView == null || !displayWorkView.changedFrom(previousDisplayView))) return false;
            boolean changed = false;
            for (Draw draw : draws) {
                var packed = draw.gpu().packed();
                if (draw.seam() || packed == null || !packed.hasDisplayLod()) continue;
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
                changed |= packed.displayLod.update(packed.displayErrors(), eyeDepth, pixelScale,
                        VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks,
                        selectionScoping || packed.morph() != null || !draw.completeOwnership());
            }
            aquaticCamera = camera;
            aquaticDistance = distance;
            aquaticScoping = selectionScoping;
            displayScale = selectionScale;
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

        PredictionDrawRanges ranges(Draw draw) {
            return byDraw.get(draw);
        }

        private List<PreparedDraw> entries() { return entries; }
        private int maxQuads() { return maxQuads; }
        private boolean hasDraws() { return hasDraws; }
        private boolean batchWorthwhile() { return batchWorthwhile; }
        IdentityHashMap<Draw, PredictionDrawRanges> rangesByDraw() { return byDraw; }
        private void clear() {
            source = null;
            preparedResidencyRevision = Long.MIN_VALUE;
            preparedOwnershipRevision = Long.MIN_VALUE;
            ownershipSkipped = 0;
            aquaticSkipped = 0;
            sourceCandidates=displayCandidates=selectedCandidates=0;
            aquaticCamera = null;
            previousDisplayView = null;
            preparedSubmissionEpoch = aquaticSubmissionEpoch = Long.MIN_VALUE;
            geometricDisplayTiles = 0;
            entries.clear();
            reusableEntries.clear();
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
        private String diagnostics() { return "hits=" + hits + ",misses=" + misses + ",voxyExcludedQuads=" + ownershipSkipped
                + ",displayExcludedQuads=" + aquaticSkipped + ",geometricDisplayTiles=" + geometricDisplayTiles
                + ",sourceCandidates=" + sourceCandidates + ",displayCandidates=" + displayCandidates
                + ",afterVoxyCandidates=" + selectedCandidates
                + ",screenErrorPixels=" + PredictionDisplayLod.PIXEL_ERROR; }


    private static LegacyResidentCoverage submissionCoverage;
    private static double selectionScale;
    private static boolean selectionScoping;
    private static PredictionWorkView displayWorkView;
    private static Object rendererField(String name) {
        try {var f=PredictionRenderer.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
        catch (ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static void synchronizeRenderer() {
        submissionCoverage=new LegacyResidentCoverage();
        selectionScale=(Double)rendererField("selectionScale");selectionScoping=(Boolean)rendererField("selectionScoping");
        displayWorkView=(PredictionWorkView)rendererField("displayWorkView");
    }
    private static final class PreparedDraw {
        private Draw draw; private PredictionDrawRanges ranges;
        Draw draw(){return draw;} PredictionDrawRanges ranges(){return ranges;}
    }
}
