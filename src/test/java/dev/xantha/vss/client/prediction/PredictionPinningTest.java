package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * Voxy-style pinned residency: a built tile must stay resident anywhere
 * inside the prediction horizon, even after the focus or the plan moves
 * away, so turning the camera back re-renders it at full detail with zero
 * rebuild cost.  Retirement happens only beyond the horizon and only when
 * an ancestor keeps the region covered.
 */
class PredictionPinningTest {
    // Registry keys on Forge 1.20.1 require bootstrap even when this class
    // runs before the cache tests in a freshly started test JVM.
    static { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld"));

    /** Horizon 2048 blocks: lod 0 tiles at tileX 50+ sit past the margin. */
    private static final VssLodLayout LAYOUT =
            VssLodLayout.of(2048, 6.0D, true, false);

    private static PredictionTileManager.PredictionTileKey key(int x, int z, int lod) {
        return new PredictionTileManager.PredictionTileKey(DIMENSION, x, z, lod);
    }

    @Test
    void focusBuiltTilesStayPinnedAfterTheFocusMovesAway() {
        // The camera sits on a lod 0 tile built by an earlier focus sweep;
        // the focus has since moved and the tile left the desired set, but a
        // coarser ancestor is resident.  Pinned residency keeps it.
        var camera = 0;
        var pinned = key(1, 1, 0);
        var ancestor = key(0, 0, 3);
        assertFalse(PredictionTileResidencyPolicy.shouldRetirePinned(pinned,
                Set.of(ancestor, pinned), Set.of(), LAYOUT, camera, camera));
    }

    @Test
    void distantFocusTilesInsideTheHorizonStayPinned() {
        // A lod 2 tile refined by looking at a mountain ~2 km away: outside
        // the plan again, covered by its lod 3 parent, still inside the
        // horizon plus margin — nothing may retire it.
        var camera = 0;
        var distant = key(8, 8, 2);
        // Parent (4, 4, 3) spans blocks 2048..2560 and covers the tile.
        var parent = key(4, 4, 3);
        assertFalse(PredictionTileResidencyPolicy.shouldRetirePinned(distant,
                Set.of(parent, distant), Set.of(), LAYOUT, camera, camera));
    }

    @Test
    void desiredTilesBeyondTheHorizonAreKept() {
        var far = key(50, 50, 0);
        assertFalse(PredictionTileResidencyPolicy.shouldRetirePinned(far,
                Set.of(far, key(6, 6, 3)), Set.of(far), LAYOUT, 0, 0));
    }

    @Test
    void beyondHorizonTilesRetireOnlyWhenAnAncestorCoversThem() {
        var far = key(50, 50, 0);
        // Ancestor chain: lod 0 tileX 50 -> chunk 200; lod 3 spans 32 chunks
        // -> ancestor tile (6, 6, 3).  Resident ancestor retires the tile.
        assertTrue(PredictionTileResidencyPolicy.shouldRetirePinned(far,
                Set.of(far, key(6, 6, 3)), Set.of(), LAYOUT, 0, 0));
        // Without any resident ancestor the tile is the only coverage left
        // and must stay so the far field never opens a hole.
        assertFalse(PredictionTileResidencyPolicy.shouldRetirePinned(far,
                Set.of(far), Set.of(), LAYOUT, 0, 0));
    }

    @Test
    void outermostTileCanRetireWithoutAnAncestor() {
        var top = key(2, 2, LAYOUT.levelCount() - 1);
        assertTrue(PredictionTileResidencyPolicy.shouldRetirePinned(top, Set.of(top), Set.of(),
                LAYOUT, 0, 0),
                "the outermost tile has no parent and must not pin the old route forever");
    }

    @Test
    void missingFallbackAncestorIsTheImmediateParent() {
        var child = key(13, -7, 0);
        var parent = key(6, -4, 1);
        assertTrue(PredictionTileResidencyPolicy.firstMissingAncestor(child, Set.of(), LAYOUT).equals(parent));
        assertTrue(PredictionTileResidencyPolicy.firstMissingAncestor(child, Set.of(parent), LAYOUT) == null,
                "a resident parent already provides a safe hand-off");
    }

    @Test
    void queuedAncestorCannotRetireDetailBeforePublication() {
        var child = key(50, -51, 0);
        var parent = key(25, -26, 1);
        assertFalse(PredictionTileResidencyPolicy.shouldRetirePinned(child, Set.of(child),
                Set.of(parent), LAYOUT, 0, 0));
        assertTrue(PredictionTileResidencyPolicy.shouldRetirePinned(child, Set.of(child, parent),
                Set.of(parent), LAYOUT, 0, 0));
    }

    @Test
    void fallbackSearchStopsAtTheRootAndRejectsStaleLevels() {
        assertTrue(PredictionTileResidencyPolicy.firstMissingAncestor(
                key(0, 0, LAYOUT.levelCount() - 1), Set.of(), LAYOUT) == null);
        assertTrue(PredictionTileResidencyPolicy.firstMissingAncestor(
                key(0, 0, LAYOUT.levelCount()), Set.of(), LAYOUT) == null);
        assertTrue(PredictionTileResidencyPolicy.shouldRetirePinned(
                key(0, 0, -1), Set.of(), Set.of(), LAYOUT, 0, 0));
    }

    @Test
    void staleLevelFromAShrunkenLayoutRetiresInsteadOfThrowing() {
        // A profile reinstall can shrink levelCount while lod-7 tiles from
        // the previous layout stay resident; the retirement walk must drop
        // them instead of reaching the layout's level validation.
        var stale = key(1, 1, 7);
        assertTrue(PredictionTileResidencyPolicy.shouldRetirePinned(stale,
                Set.of(stale), Set.of(), LAYOUT, 0, 0));
        // And the fully-authoritative guard must treat it as unknown, not
        // throw (covered through shouldRetirePinned's early exit above).
    }

    @Test
    void explicitDistanceReductionDropsFarTilesWithoutAncestorsOrPersistence() {
        var layout = VssLodLayout.of(4096, 6, true, false);
        assertTrue(PredictionTileResidencyPolicy.retireAfterDistanceReduction(key(100, 0, 0), Set.of(), layout, 0, 0));
        assertTrue(PredictionTileResidencyPolicy.retireAfterDistanceReduction(key(-101, 0, 0), Set.of(), layout, 0, 0));
        assertTrue(PredictionTileResidencyPolicy.retireAfterDistanceReduction(key(0, 0, 10), Set.of(), layout, 0, 0));
        assertFalse(PredictionTileResidencyPolicy.retireAfterDistanceReduction(key(1, 1, 0), Set.of(), layout, 0, 0));
        assertFalse(PredictionTileResidencyPolicy.retireAfterDistanceReduction(key(63, 0, 0), Set.of(), layout, 0, 0),
                "tiles intersecting the new horizon still cover its edge");
        var desired = key(65, 0, 0);
        assertFalse(PredictionTileResidencyPolicy.retireAfterDistanceReduction(desired, Set.of(desired), layout, 0, 0),
                "retain any coverage explicitly required by the new planner");
    }

    @Test
    void horizonGeometryHonoursTileSpanAndMargin() {
        // Tile containing the camera.
        assertFalse(PredictionTileResidencyPolicy.beyondHorizon(0, 0, 64, 8, 8, 3072.0D));
        // Tile overlapping the margin ring (nearest point 3000 blocks).
        assertFalse(PredictionTileResidencyPolicy.beyondHorizon(3000, 0, 64, 0, 0, 3072.0D));
        // Tile fully past the horizon plus margin.
        assertTrue(PredictionTileResidencyPolicy.beyondHorizon(3200, 3200, 64, 0, 0, 3072.0D));
        // A big coarse tile counts as near while any part overlaps the ring.
        assertFalse(PredictionTileResidencyPolicy.beyondHorizon(2000, 2000, 2048, 0, 0, 3072.0D));
    }
}
