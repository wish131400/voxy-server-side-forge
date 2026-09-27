package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.Function;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Retains draw objects and distance order. Rotation only changes frustum membership. */
final class PredictionVisiblePlan<K,T> {
    private static final class Entry<T> {
        final long id;
        final Object key;
        T value;
        double distance;
        boolean visible;
        Entry(long id,Object key,T value) { this.id=id;this.key=key;this.value=value; }
    }
    private final Map<K,Entry<T>> entries=new HashMap<>();
    private final Set<Entry<T>> dirty=new HashSet<>();
    private final TreeSet<Entry<T>> visible=new TreeSet<>(Comparator
            .<Entry<T>>comparingDouble(entry->entry.distance).thenComparingLong(entry->entry.id));
    private final Function<T,AABB> bounds;
    private final Matrix4f modelView=new Matrix4f(),projection=new Matrix4f();
    private Vec3 camera;
    private long nextId, visited, orderEdits, publications;
    private boolean changed;
    private List<T> result=List.of();

    PredictionVisiblePlan(Function<T,AABB> bounds) { this.bounds=bounds; }

    void put(K key,T value) {
        var entry=entries.get(key);
        if(entry!=null && entry.value==value) return;
        if(entry==null) { entry=new Entry<>(nextId++,key,value);entries.put(key,entry); }
        else {
            if(entry.visible) { visible.remove(entry);changed=true;entry.visible=false; }
            entry.value=value;
        }
        dirty.add(entry);
    }

    void remove(K key) {
        var entry=entries.remove(key);
        if(entry==null) return;
        dirty.remove(entry);
        if(entry.visible) { visible.remove(entry);changed=true; }
    }

    List<T> select(Vec3 nextCamera,Matrix4f nextView,Matrix4f nextProjection,Frustum frustum) {
        return select(nextCamera,nextView,nextProjection,frustum,null);
    }

    List<T> select(Vec3 nextCamera,Matrix4f nextView,Matrix4f nextProjection,Frustum frustum,
                   Set<K> candidates) {
        // Vertical motion changes frustum membership but not horizontal order.
        boolean translated=camera == null || !nextCamera.equals(camera);
        boolean moved=camera == null || nextCamera.x != camera.x || nextCamera.z != camera.z;
        boolean turned=camera==null || !modelView.equals(nextView) || !projection.equals(nextProjection);
        if(translated || turned) {
            if (candidates == null) {
                for(var entry:entries.values())
                    update(entry,nextCamera,frustum,moved || dirty.contains(entry));
            } else {
                // A conservative broad phase is supplied by the spatial
                // index. Remove stale visible entries, then visit candidates
                // directly instead of scanning every resident tile on turns.
                if (!visible.isEmpty()) {
                    var stale = new java.util.ArrayList<Entry<T>>();
                    for (var entry : visible) if (!candidates.contains(entry.key)) stale.add(entry);
                    for (var entry : stale) {
                        visible.remove(entry); entry.visible=false; changed=true;
                    }
                }
                for (K key : candidates) {
                    var entry = entries.get(key);
                    if (entry != null) update(entry,nextCamera,frustum,moved || dirty.contains(entry));
                }
            }
        } else {
            if (candidates != null) for (var mapEntry : entries.entrySet()) {
                if (candidates.contains(mapEntry.getKey())) continue;
                var entry = mapEntry.getValue();
                if (entry.visible) { visible.remove(entry);entry.visible=false;changed=true; }
            }
            for(var entry:dirty) {
                if(candidates == null || candidates.contains(entry.key)) update(entry,nextCamera,frustum,true);
                else if(entry.visible) { visible.remove(entry);entry.visible=false;changed=true; }
            }
        }
        dirty.clear();camera=nextCamera;modelView.set(nextView);projection.set(nextProjection);
        if(changed) {
            var next=new ArrayList<T>(visible.size());for(var entry:visible) next.add(entry.value);
            result=List.copyOf(next);changed=false;publications++;
        }
        return result;
    }

    private void update(Entry<T> entry,Vec3 camera,Frustum frustum,boolean distanceChanged) {
        visited++;
        var box=bounds.apply(entry.value);
        boolean inView=frustum==null || frustum.isVisible(box);
        if(entry.visible && (!inView || distanceChanged)) { visible.remove(entry);entry.visible=false;changed=true; }
        if(distanceChanged) {
            double dx=(box.minX+box.maxX)*.5-camera.x,dz=(box.minZ+box.maxZ)*.5-camera.z;
            entry.distance=dx*dx+dz*dz;
        }
        if(!inView) return;
        if(!entry.visible) { visible.add(entry);entry.visible=true;changed=true;orderEdits++; }
    }

    long visited() { return visited; }
    long orderEdits() { return orderEdits; }
    long publications() { return publications; }
    void clear() { entries.clear();dirty.clear();visible.clear();result=List.of();camera=null;changed=false; }
}
