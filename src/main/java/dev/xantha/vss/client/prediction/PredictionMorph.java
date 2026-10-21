package dev.xantha.vss.client.prediction;

/** Shared X/Z displacement: tops and walls can never disagree at one vertex. */
final class PredictionMorph {
    static final long DURATION_NANOS = 350_000_000L;
    /**
     * GPU batch records store a compact, stable start time instead of the
     * already evaluated animation amount.  Keeping the origin close to the
     * process start makes the value representable as a float for the whole
     * client session while the actual frame time remains a single uniform.
     */
    private static final long CLOCK_ORIGIN_NANOS = System.nanoTime();
    private PredictionMorph() { }

    static float seconds(long nanoTime) {
        return (float) ((nanoTime - CLOCK_ORIGIN_NANOS) * 1.0e-9D);
    }

    static float startSeconds(long uploadedAt) {
        return seconds(uploadedAt);
    }

    static float[] field(PredictionTileManager.PredictionTile child, PredictionTileManager.PredictionTile parent) {
        return field(child, parent, PredictionSimpleVegetation.Result.EMPTY);
    }

    static float[] field(PredictionTileManager.PredictionTile child, PredictionTileManager.PredictionTile parent,
                         PredictionSimpleVegetation.Result vegetation) {
        int axis = child.cellAxis() + 1;
        if (parent == null || parent.spacingBlocks() <= child.spacingBlocks()
                || parent.heights().length != (parent.cellAxis()+1)*(parent.cellAxis()+1)
                || child.heights().length != axis*axis || child.spacingBlocks() <= 2) return null;
        // Captured blocks and volumetric columns are not height-field terrain.
        // A river protects its banks locally instead of disabling all inland
        // transitions in the same tile. The water itself never morphs.
        for (var s : child.samples()) if (s == null || !s.hasSurface() || s.captured() || s.volume() != null) return null;
        int pa = parent.cellAxis() + 1;
        if (child.samples().length != axis * axis || parent.samples().length != pa * pa) return null;
        float[] delta = new float[axis * axis];
        boolean changed = false;
        int span = child.cellAxis() * child.spacingBlocks();
        for (int z = 1; z < axis-1; z++) for (int x = 1; x < axis-1; x++) {
            int localX = x * child.spacingBlocks(), localZ = z * child.spacingBlocks();
            int px = child.baseBlockX() + localX - parent.baseBlockX();
            int pz = child.baseBlockZ() + localZ - parent.baseBlockZ();
            double gx = px / (double)parent.spacingBlocks(), gz = pz / (double)parent.spacingBlocks();
            int ix = Math.min(parent.cellAxis()-1, Math.max(0,(int)Math.floor(gx)));
            int iz = Math.min(parent.cellAxis()-1, Math.max(0,(int)Math.floor(gz)));
            if (gx < 0 || gz < 0 || gx > parent.cellAxis() || gz > parent.cellAxis()) return null;
            if (protectedBank(child.samples(), axis, x, z)
                    || protectedParent(parent.samples(), pa, ix, iz)) continue;
            double tx=gx-ix,tz=gz-iz;
            int[] h=parent.heights();
            double top=(1-tz)*((1-tx)*h[iz*pa+ix]+tx*h[iz*pa+ix+1])
                    +tz*((1-tx)*h[(iz+1)*pa+ix]+tx*h[(iz+1)*pa+ix+1]);
            double edge=Math.min(Math.min(localX,span-localX),Math.min(localZ,span-localZ));
            float d=(float)(Math.max(-8,Math.min(8,top-child.heights()[z*axis+x]))
                    * Math.min(1,edge/(child.spacingBlocks()*2.0)));
            delta[z*axis+x]=d;
            changed |= d != 0;
        }
        if (!changed) return null;
        // Representatives remain fixed feature geometry. Pin every terrain
        // interpolation cell below their footprint rather than disabling a
        // whole coarse tile because it contains a tree or a grass cross.
        for (var form : vegetation.forms()) {
            int radius = form.tree() == null ? 1 : 3;
            int minX = Math.max(0, Math.floorDiv(form.x() - radius, child.spacingBlocks()));
            int minZ = Math.max(0, Math.floorDiv(form.z() - radius, child.spacingBlocks()));
            int maxX = Math.min(axis - 1, Math.floorDiv(form.x() + radius, child.spacingBlocks()) + 1);
            int maxZ = Math.min(axis - 1, Math.floorDiv(form.z() + radius, child.spacingBlocks()) + 1);
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) delta[z * axis + x] = 0;
        }
        for (float value : delta) if (value != 0) return delta;
        return null;
    }

    private static boolean protectedBank(ClientColumnSample[] samples, int axis, int x, int z) {
        // Every terrain corner touching a wet cell, including the opposite
        // shoreline corner, stays fixed while neighboring dry land moves.
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            if (samples[(z + dz) * axis + x + dx].hasFluid()) return true;
        return false;
    }

    private static boolean protectedParent(ClientColumnSample[] samples, int axis, int x, int z) {
        for (int dz = 0; dz <= 1; dz++) for (int dx = 0; dx <= 1; dx++) {
            var sample = samples[(z + dz) * axis + x + dx];
            if (sample == null || !sample.hasSurface() || sample.hasFluid() || sample.captured() || sample.volume() != null)
                return true;
        }
        return false;
    }

    static float amount(long ageNanos) {
        float t=Math.max(0,Math.min(1,ageNanos/(float)DURATION_NANOS));
        return 1-t*t*(3-2*t);
    }
}
