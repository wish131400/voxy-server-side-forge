package dev.xantha.vss.client.prediction;

import dev.xantha.vss.config.PredictionVegetationDensity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Display-only selection from complete placement maps, before tile clipping or voxel reduction. */
final class PredictionVegetationSelection {
    private PredictionVegetationSelection() { }

    static Map<BlockPos, BlockState> select(Map<BlockPos, BlockState> original,
                                            PredictionVegetationDensity density) {
        if (density == PredictionVegetationDensity.HIGH || original.isEmpty()) return original;
        var selected = new HashMap<BlockPos, BlockState>();
        var visited = new LongOpenHashSet();
        var component = new ArrayList<BlockPos>();
        var probe = new BlockPos.MutableBlockPos();
        for (var entry : original.entrySet()) {
            var pos = entry.getKey();
            var state = entry.getValue();
            var traits = PredictionVegetationTraits.of(state);
            if (!traits.woody()) {
                // All vertical segments of a plant share its X/Z decision.
                // Terrain edits, fluids, weather and structural blocks stay intact.
                if (!traits.vegetation() || density.keep(pos.getX(), pos.getZ())) selected.put(pos, state);
                continue;
            }
            if (!visited.add(pos.asLong())) continue;
            component.clear();
            component.add(pos);
            BlockPos root = null;
            boolean canopy = false, structural = false;
            for (int cursor = 0; cursor < component.size(); cursor++) {
                var current = component.get(cursor);
                var value = original.get(current);
                if (stem(value)) {
                    if (root == null || rootOrder(current, root) < 0) root = current;
                } else canopy = true;
                // Gather the complete source canopy before clipping. Touching crowns
                // may connect several trees, whose trunks are selected independently below.
                for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    probe.set(current.getX() + dx, current.getY() + dy, current.getZ() + dz);
                    var neighbour = original.get(probe);
                    if (neighbour == null) continue;
                    if (PredictionVegetationTraits.of(neighbour).woody()) {
                        if (visited.add(probe.asLong())) component.add(probe.immutable());
                    } else if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1
                            && (neighbour.is(BlockTags.PLANKS) || neighbour.is(BlockTags.STAIRS)
                            || neighbour.is(BlockTags.SLABS) || neighbour.is(BlockTags.DOORS)
                            || neighbour.is(BlockTags.FENCES))) structural = true;
                }
            }
            // Wood without a canopy and wood joined to building parts are not proven trees.
            if (root == null || !canopy || structural) {
                for (var block : component) selected.put(block, original.get(block));
            } else selectTrees(original, component, root, density, selected, probe);
        }
        return java.util.Collections.unmodifiableMap(selected);
    }

    private static void selectTrees(Map<BlockPos, BlockState> original, List<BlockPos> component,
            BlockPos root, PredictionVegetationDensity density, Map<BlockPos, BlockState> selected,
            BlockPos.MutableBlockPos probe) {
        boolean oneColumn = true;
        for (var pos : component) if (stem(original.get(pos))
                && (pos.getX() != root.getX() || pos.getZ() != root.getZ())) { oneColumn = false; break; }
        if (oneColumn) {
            if (density.keep(root.getX(), root.getZ()))
                for (var pos : component) selected.put(pos, original.get(pos));
            return;
        }

        var indices = new Long2IntOpenHashMap(component.size()); indices.defaultReturnValue(-1);
        var owners = new int[component.size()]; java.util.Arrays.fill(owners, -1);
        var stems = new boolean[component.size()];
        for (int i = 0; i < component.size(); i++) {
            indices.put(component.get(i).asLong(), i);
            stems[i] = stem(original.get(component.get(i)));
        }
        var roots = new ArrayList<BlockPos>();
        var groups = new ArrayList<IntArrayList>();
        // Only connected logs/stems determine a tree. Large trunks and diagonal
        // branches remain intact even when their crowns meet neighbouring trees.
        for (int i = 0; i < component.size(); i++) {
            if (!stems[i] || owners[i] >= 0) continue;
            int group = groups.size(); var members = new IntArrayList();
            members.add(i); owners[i] = group; BlockPos anchor = component.get(i);
            for (int cursor = 0; cursor < members.size(); cursor++) {
                var pos = component.get(members.getInt(cursor));
                if (rootOrder(pos, anchor) < 0) anchor = pos;
                for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    probe.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    int neighbour = indices.get(probe.asLong());
                    if (neighbour >= 0 && stems[neighbour] && owners[neighbour] < 0) {
                        owners[neighbour] = group; members.add(neighbour);
                    }
                }
            }
            roots.add(anchor); groups.add(members);
        }
        if (groups.size() == 1) {
            if (density.keep(root.getX(), root.getZ()))
                for (var pos : component) selected.put(pos, original.get(pos));
            return;
        }

        var order = new ArrayList<Integer>();
        for (int i = 0; i < groups.size(); i++) order.add(i);
        order.sort((first, second) -> rootOrder(roots.get(first), roots.get(second)));
        java.util.Arrays.fill(owners, -1);
        var queue = new IntArrayList(component.size()); var keep = new boolean[groups.size()];
        for (int i = 0; i < order.size(); i++) {
            int group = order.get(i); var anchor = roots.get(group);
            keep[i] = density.keep(anchor.getX(), anchor.getZ());
            var members = groups.get(group);
            for (int j = 0; j < members.size(); j++) {
                int index = members.getInt(j); owners[index] = i; queue.add(index);
            }
        }
        // Assign shared canopy to the closest connected trunk. Sorted seeds
        // break equal-distance ties independently of density and map iteration,
        // so each retained crown stays connected and low remains a subset of medium.
        for (int cursor = 0; cursor < queue.size(); cursor++) {
            int index = queue.getInt(cursor); var pos = component.get(index);
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                probe.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                int neighbour = indices.get(probe.asLong());
                if (neighbour >= 0 && owners[neighbour] < 0) {
                    owners[neighbour] = owners[index]; queue.add(neighbour);
                }
            }
        }
        for (int i = 0; i < component.size(); i++) if (keep[owners[i]]) {
            var pos = component.get(i); selected.put(pos, original.get(pos));
        }
    }

    private static boolean stem(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.CRIMSON_STEM) || state.is(Blocks.WARPED_STEM);
    }

    private static int rootOrder(BlockPos first, BlockPos second) {
        int result = Integer.compare(first.getY(), second.getY());
        if (result == 0) result = Integer.compare(first.getX(), second.getX());
        return result == 0 ? Integer.compare(first.getZ(), second.getZ()) : result;
    }

    static PredictionSimpleVegetation.Result select(PredictionSimpleVegetation.Result original,
            int baseX, int baseZ, PredictionVegetationDensity density) {
        if (density == PredictionVegetationDensity.HIGH || original.forms().isEmpty()) return original;
        var forms = new ArrayList<PredictionSimpleVegetation.Form>();
        int maxY = Integer.MIN_VALUE;
        for (var form : original.forms()) {
            if (!density.keep(baseX + form.x(), baseZ + form.z())) continue;
            forms.add(form);
            maxY = Math.max(maxY, form.y() + form.height());
        }
        return new PredictionSimpleVegetation.Result(List.copyOf(forms), original.forestTints(), maxY);
    }
}
