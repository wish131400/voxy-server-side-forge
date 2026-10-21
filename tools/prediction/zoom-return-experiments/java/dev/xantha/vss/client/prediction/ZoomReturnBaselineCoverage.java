package dev.xantha.vss.client.prediction;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import dev.xantha.vss.client.prediction.PredictionRenderer.TileCoverage;
import static dev.xantha.vss.client.prediction.PredictionRenderer.*;
/** Exact pre-fix coverage calculation, with independent caches. */
final class ZoomReturnBaselineCoverage {
    private static final long COVERAGE_RECHECK_NANOS = 250_000_000L;
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
        boolean ordinary = !snapshot.hasScopedTiles() && snapshot.tiles().get(tile.key()) == tile;
        int groupAxis = Math.max(1, (ordinary ? snapshot.layout().tileBlocks(0) : 16) / stepBlocks);
        // Keep prediction ownership independent from optional Voxy suppression.
        // This immutable mask is safe to draw while a Voxy refresh is pending.
        Long2ByteOpenHashMap decisions = ordinary ? null : decisionCacheFor(playerChunkX, playerChunkZ,
                pixelsPerBlock, focus);
        VssLodFocus terrainFocus = ordinary ? null : PredictionWorkOrder.terrainFocus(focus);
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
                    double dx = cellChunkX - playerChunkX;
                    double dz = cellChunkZ - playerChunkZ;
                    double chunkDistanceSquared = dx * dx + dz * dz;
                    double distanceBlocks = Math.max(64.0D, Math.sqrt(chunkDistanceSquared) * 16.0D);
                    desiredLod = (byte) lodForBlocks(distanceBlocks, pixelsPerBlock,
                            pixelsPerQuad());
                    if (distanceBlocks < PredictionDetailBands.fineRadius(snapshot.layout().maxDistanceBlocks(), snapshot.dimension()))
                        desiredLod = 0;
                    if (focus != null) {
                        int cellCenterX = (cellChunkX << 4) + 8;
                        int cellCenterZ = (cellChunkZ << 4) + 8;
                        if (focus.contains(cellCenterX, cellCenterZ)) {
                            int floor = lodForBlocks(distanceBlocks,
                                    focus.selectionScale(pixelsPerBlock), pixelsPerQuad());
                            desiredLod = (byte) Math.min(desiredLod, floor);
                        }
                        // The active terrain patch keeps its best uploaded detail,
                        // including while a new child still contains only a preview.
                        if (terrainFocus.intersects(cellChunkX * 16D, cellChunkZ * 16D,
                                (cellChunkX + 1D) * 16, (cellChunkZ + 1D) * 16)) desiredLod = 0;
                    }
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


}
