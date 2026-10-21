package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;

/** Mutable patch quadtree. Subtree boxes contain actual patches, including extended boundary heights. */
final class PredictionSeamIndex<T> {
    private static final int TOP = PredictionTileManager.MAX_LOD_LEVEL;
    private static final class Node<T> {
        T value;
        AABB own, bounds;
        final Node<T>[] children;
        @SuppressWarnings("unchecked") Node() { children = new Node[4]; }
        void updateBounds() {
            bounds = own;
            for (var child : children) if (child != null && child.bounds != null)
                bounds = bounds == null ? child.bounds : bounds.minmax(child.bounds);
        }
    }
    private final Long2ObjectOpenHashMap<Node<T>> roots = new Long2ObjectOpenHashMap<>();

    void put(PredictionTileKey key, T value, AABB bounds) {
        if (value == null || bounds == null) throw new IllegalArgumentException("Missing seam entry");
        long root = root(key);
        var node = roots.computeIfAbsent(root, ignored -> new Node<>());
        update(node, TOP, key, value, bounds);
    }

    void remove(PredictionTileKey key) {
        long root = root(key);
        var node = roots.get(root);
        if (node == null) return;
        update(node, TOP, key, null, null);
        if (node.bounds == null) roots.remove(root);
    }

    private void update(Node<T> node, int lod, PredictionTileKey key, T value, AABB bounds) {
        if (lod == key.lod()) { node.value = value; node.own = bounds; }
        else {
            int shift = lod - key.lod() - 1;
            int at = ((key.tileX() >> shift) & 1) | (((key.tileZ() >> shift) & 1) << 1);
            var child = node.children[at];
            if (child == null) {
                if (value == null) return;
                node.children[at] = child = new Node<>();
            }
            update(child, lod - 1, key, value, bounds);
            if (child.bounds == null) node.children[at] = null;
        }
        node.updateBounds();
    }

    void intersectFrustum(Frustum frustum, Consumer<T> visit) {
        for (var node : roots.values()) intersect(node, frustum, visit);
    }

    private void intersect(Node<T> node, Frustum frustum, Consumer<T> visit) {
        if (node.bounds == null || frustum != null && !frustum.isVisible(node.bounds)) return;
        if (node.value != null) visit.accept(node.value);
        for (var child : node.children) if (child != null) intersect(child, frustum, visit);
    }

    private static long root(PredictionTileKey key) {
        int shift = TOP - key.lod();
        return (long)(key.tileX() >> shift) << 32 | (key.tileZ() >> shift) & 0xffffffffL;
    }
    void clear() { roots.clear(); }
}
