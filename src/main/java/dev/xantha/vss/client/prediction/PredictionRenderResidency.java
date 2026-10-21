package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;

/** Coverage follows completed GPU uploads, independently of worker publication. */
final class PredictionRenderResidency {
    private final Map<PredictionTileKey, PredictionTile> tiles = new HashMap<>();
    private final PredictionSpatialIndex<PredictionTileKey> index = new PredictionSpatialIndex<>();
    private final java.util.Set<PredictionTileKey> changes = new java.util.HashSet<>();
    private final java.util.Set<PredictionTileKey> retiring = new java.util.HashSet<>();
    private final Map<PredictionTileKey, Long> epochs = new HashMap<>();
    private final PredictionSpatialIndex<PredictionTileKey> scopeIndex=new PredictionSpatialIndex<>();
    private final java.util.Set<PredictionTileKey> snapshotChanges=new java.util.HashSet<>();
    private PredictionTileTable<PredictionTile> publishedTiles;
    private PredictionTileTable<Long> publishedEpochs;
    private PredictionTileTable<Boolean> publishedScopes;
    private RenderSnapshot snapshot;
    private VssLodLayout layout;
    private long revision;
    private RenderSnapshot retainedSource;
    private long retainedRevision = -1;
    private RenderSnapshot pendingSource;
    private long pendingRevision = -1;
    private final java.util.ArrayList<PredictionTile> pendingUploads = new java.util.ArrayList<>();
    private final Map<PredictionTileKey, Integer> pendingSlots = new HashMap<>();
    private boolean incrementalPending;
    private RenderSnapshot deltaFrom, deltaTo;
    private final java.util.ArrayList<PredictionTileKey> deltaKeys = new java.util.ArrayList<>();

    private void sourceChanges(RenderSnapshot source, RenderSnapshot previous, java.util.function.Consumer<PredictionTileKey> action) {
        if (deltaFrom != previous || deltaTo != source) {
            deltaKeys.clear(); source.forEachChangedTile(previous, deltaKeys::add);
            deltaFrom = previous; deltaTo = source;
        }
        deltaKeys.forEach(action);
    }

    /** Changes whenever a tile becomes drawable or is retired from the GPU set. */
    long revision() { return revision; }

    void retain(RenderSnapshot source) {
        if (source == retainedSource && revision == retainedRevision) return;
        boolean distanceReduced = layout != null && source.layout().maxDistanceBlocks() < layout.maxDistanceBlocks();
        if (retainedSource != null && !retainedSource.dimension().equals(source.dimension())) {
            var oldKeys=java.util.Set.copyOf(tiles.keySet());
            clear();changes.addAll(oldKeys);
        }
        boolean layoutChanged = !source.layout().equals(layout);
        if (layoutChanged) {
            // Tile coordinates and uploaded payloads do not change with radius
            // or selection quality. Re-evaluate ownership, retaining useful GPU data.
            layout = source.layout();
            snapshot = null;
            long epoch = ++revision;
            tiles.keySet().forEach(key -> { epochs.put(key, epoch); changes.add(key);snapshotChanges.add(key); });
        }
        if (source != retainedSource) {
            if (source.tiles().size() < 512) {
                for (var key : tiles.keySet()) if (key.lod() >= source.layout().levelCount() || !source.tiles().containsKey(key)) retiring.add(key);
                retiring.removeIf(key -> key.lod() < source.layout().levelCount() && source.tiles().containsKey(key));
            } else if (retainedSource == null || layoutChanged) {
                for (var key : tiles.keySet()) updateRetirement(source, key);
            } else sourceChanges(source, retainedSource, key -> updateRetirement(source, key));
        }
        var iterator = retiring.iterator();
        while (iterator.hasNext()) {
            var key = iterator.next();
            if (key.lod() >= source.layout().levelCount()
                    || distanceReduced && !source.tiles().containsKey(key)) {
                iterator.remove();
                tiles.remove(key); index.remove(key);scopeIndex.remove(key);
                changed(key);
                epochs.remove(key);
                continue;
            }
            if (source.tiles().containsKey(key)) continue;
            // CPU eviction can precede the fallback parent's GPU upload.
            // Keep the old detail until that replacement can cover it.
            boolean pendingAncestor = false;
            for (int lod = key.lod() + 1; lod < source.layout().levelCount(); lod++) {
                int shift = lod - key.lod();
                var parent = new PredictionTileKey(key.dimension(), key.tileX() >> shift, key.tileZ() >> shift, lod);
                if (tiles.containsKey(parent)) { pendingAncestor = false; break; }
                if (source.tiles().containsKey(parent)) pendingAncestor = true;
            }
            if (pendingAncestor) continue;
            if (pendingFeatureReplacement(source, key)) continue;
            iterator.remove();
            tiles.remove(key); index.remove(key);scopeIndex.remove(key);
            changed(key);
            epochs.remove(key);
        }
        retainedSource = source;
        retainedRevision = revision;
        releaseDelta(source);
    }

    private void releaseDelta(RenderSnapshot source) {
        if (retainedSource == source && pendingSource == source) {
            deltaKeys.clear(); deltaFrom = deltaTo = null;
        }
    }

    private void updateRetirement(RenderSnapshot source, PredictionTileKey key) {
        if (!tiles.containsKey(key)) return;
        if (key.lod() >= source.layout().levelCount() || !source.tiles().containsKey(key)) retiring.add(key);
        else retiring.remove(key);
    }

    boolean contains(PredictionTile tile) { return tiles.get(tile.key()) == tile; }

    private boolean pendingFeatureReplacement(RenderSnapshot source, PredictionTileKey key) {
        var retiringTile = tiles.get(key);
        if (retiringTile == null || !PredictionFeatureHandoff.available(retiringTile)) return false;
        var drawable = snapshot(source);
        int chunks = retiringTile.spanBlocks() / 16;
        int baseX = Math.floorDiv(retiringTile.baseBlockX(), 16), baseZ = Math.floorDiv(retiringTile.baseBlockZ(), 16);
        int stride = Math.max(1, source.layout().tileBlocks(0) / 16);
        for (int z = baseZ; z < baseZ + chunks; z += stride) for (int x = baseX; x < baseX + chunks; x += stride) {
            if (source.coveringTile(x, z, 0) != null && drawable.coveringFeatureTileAtDetail(x, z, 0) == retiringTile)
                return true;
        }
        return false;
    }

    boolean hasResidentCover(PredictionTileKey key, VssLodLayout layout) {
        var resident = tiles.get(key);
        return resident != null && !resident.scopeOnly() && resident.mesh().gpuPayload() != null
                && resident.mesh().gpuPayload().quadCount() > 0 || hasFallback(key);
    }

    PredictionTile resident(PredictionTileKey key) { return tiles.get(key); }
    boolean hasFallback(PredictionTileKey key) {
        return hasFallback(key, false);
    }

    /** A budget eviction must not replace completed decoration/water with an unfinished parent. */
    boolean hasEvictionFallback(PredictionTileKey key) {
        return hasEvictionFallback(tiles.get(key), key);
    }

    boolean hasEvictionFallback(PredictionTile tile) {
        return hasEvictionFallback(tile, tile.key());
    }

    private boolean hasEvictionFallback(PredictionTile tile, PredictionTileKey key) {
        boolean complete = PredictionFeatureHandoff.available(tile);
        var packed = tile == null ? null : tile.mesh().gpuPayload();
        if (packed != null) for (int group = 0; group < VssLodFaceGroup.COUNT; group++)
            complete |= packed.waterRangeCount(group) > 0;
        return hasFallback(key, complete);
    }

    private boolean hasFallback(PredictionTileKey key, boolean complete) {
        if (layout == null) return false;
        for (int lod = key.lod() + 1; lod < layout.levelCount(); lod++) {
            int shift = lod - key.lod();
            var parent = tiles.get(new PredictionTileKey(key.dimension(), key.tileX() >> shift, key.tileZ() >> shift, lod));
            if (parent != null && !parent.scopeOnly() && parent.mesh().gpuPayload() != null
                    && parent.mesh().gpuPayload().quadCount() > 0
                    && (!complete || parent.mesh().surfaceReadyForRendering)) return true;
        }
        return false;
    }
    void evict(PredictionTileKey key) {
        if (tiles.remove(key) == null) return;
        index.remove(key); scopeIndex.remove(key); retiring.remove(key);
        changed(key); epochs.remove(key);
        // An evicted tile must reappear even when the worker source is unchanged.
        if (pendingSource != null && incrementalPending) updatePending(pendingSource, key);
        else { pendingSource = null; pendingRevision = -1; }
    }

    /**
     * Returns whether a pending upload that matters to the current view is
     * still waiting. The renderer uses this before reusing a stable frame
     * plan; uploads outside the horizon do not invalidate that plan.
     */
    boolean hasPendingUploads(RenderSnapshot source,
                              java.util.function.Predicate<PredictionTile> relevant) {
        for (PredictionTile tile : pendingUploads(source)) {
            if (relevant.test(tile)) return true;
        }
        return false;
    }

    java.util.List<PredictionTile> pendingUploads(RenderSnapshot source) {
        if (source.tiles().size() < 512) {
            if (source != pendingSource || incrementalPending) {
                pendingUploads.clear(); pendingSlots.clear(); pendingUploads.addAll(source.tiles().values());
                pendingSource = source; pendingRevision = -1; incrementalPending = false;
            }
            if (pendingRevision != revision) {
                pendingUploads.removeIf(tile -> contains(tile) || tile.mesh().gpuPayload() == null); pendingRevision = revision;
            }
            releaseDelta(source); return pendingUploads;
        }
        if (source != pendingSource) {
            if (pendingSource == null || !incrementalPending || !source.dimension().equals(pendingSource.dimension())) {
                pendingUploads.clear(); pendingSlots.clear();
                source.tiles().keySet().forEach(key -> updatePending(source, key));
            } else sourceChanges(source, pendingSource, key -> updatePending(source, key));
            pendingSource = source; incrementalPending = true;
            pendingRevision = revision;
            releaseDelta(source);
        }
        return pendingUploads;
    }

    private void updatePending(RenderSnapshot source, PredictionTileKey key) {
        var tile = source.tiles().get(key);
        if (tile == null || tile.mesh().gpuPayload() == null || contains(tile)) { removePending(key); return; }
        var slot = pendingSlots.get(key);
        if (slot == null) { pendingSlots.put(key, pendingUploads.size()); pendingUploads.add(tile); }
        else pendingUploads.set(slot, tile);
    }

    private void removePending(PredictionTileKey key) {
        var slot = pendingSlots.remove(key); if (slot == null) return;
        int last = pendingUploads.size() - 1;
        var moved = pendingUploads.remove(last);
        if (slot != last) { pendingUploads.set(slot, moved); pendingSlots.put(moved.key(), slot); }
    }

    void uploaded(PredictionTile tile) {
        if (incrementalPending) {
            var slot = pendingSlots.get(tile.key());
            if (slot != null && pendingUploads.get(slot) == tile) removePending(tile.key());
        }
        if (tiles.put(tile.key(), tile) != tile) {
            index.put(tile.key(),tile.key());
            if(tile.scopeOnly()) scopeIndex.put(tile.key(),tile.key());else scopeIndex.remove(tile.key());
            changed(tile.key());
        }
    }

    private void changed(PredictionTileKey changed) {
        snapshot = null;
        long epoch = ++revision;
        changes.add(changed);snapshotChanges.add(changed);
        long span=64L<<changed.lod(), x=changed.tileX()*span,z=changed.tileZ()*span;
        // A sub-cell neighbor also changes the adjacent coarse wall's mask.
        // Keep that one-block halo local; ordinary fine neighbors need no resolve.
        index.intersect(x-1,z-1,x+span+1,z+span+1,key->{
            var tile=tiles.get(key);
            if (tile.spacingBlocks()<=span && (tile.baseBlockX()>=x+span || tile.baseBlockZ()>=z+span
                    || tile.baseBlockX()+(long)tile.spanBlocks()<=x || tile.baseBlockZ()+(long)tile.spanBlocks()<=z)) return;
            epochs.put(key,epoch);changes.add(key);snapshotChanges.add(key);
        });
    }

    java.util.Set<PredictionTileKey> drainChanges() {
        if(changes.isEmpty()) return java.util.Set.of();
        var result=java.util.Set.copyOf(changes);changes.clear();return result;
    }

    RenderSnapshot snapshot(RenderSnapshot source) {
        if(snapshot==null) {
            if(publishedTiles==null) {
                publishedTiles=new PredictionTileTable<>(source.dimension());
                publishedEpochs=new PredictionTileTable<>(source.dimension());
                publishedScopes=new PredictionTileTable<>(source.dimension());
            }
            var tileUpdates=new HashMap<PredictionTileKey,PredictionTile>();
            var epochUpdates=new HashMap<PredictionTileKey,Long>();
            var scopeUpdates=new HashMap<PredictionTileKey,Boolean>();
            var removed=new java.util.HashSet<PredictionTileKey>();
            var unscoped=new java.util.HashSet<PredictionTileKey>();
            for(var key:snapshotChanges) {
                var tile=tiles.get(key);
                if(tile==null) { removed.add(key);unscoped.add(key);continue; }
                tileUpdates.put(key,tile);epochUpdates.put(key,epochs.getOrDefault(key,0L));
                long span=64L<<key.lod(),x=key.tileX()*span,z=key.tileZ()*span;
                if(scopeIndex.any(x,z,x+span,z+span)) scopeUpdates.put(key,Boolean.TRUE);else unscoped.add(key);
            }
            publishedTiles=publishedTiles.changed(tileUpdates,removed);
            publishedEpochs=publishedEpochs.changed(epochUpdates,removed);
            publishedScopes=publishedScopes.changed(scopeUpdates,unscoped);
            snapshotChanges.clear();
            snapshot=new RenderSnapshot(source.dimension(),source.layout(),publishedTiles,publishedEpochs,publishedScopes.keySet());
        }
        return snapshot;
    }

    void clear() {
        pendingSource = null;
        pendingRevision = -1;
        pendingUploads.clear();
        pendingSlots.clear();
        incrementalPending = false;
        deltaKeys.clear(); deltaFrom = deltaTo = null;
        tiles.clear();
        index.clear();scopeIndex.clear();changes.clear();snapshotChanges.clear();retiring.clear();
        publishedTiles=null;publishedEpochs=null;publishedScopes=null;
        epochs.clear();
        snapshot = null;
        layout = null;
        retainedSource = null;
        retainedRevision = -1;
    }
}
