package dev.xantha.vss.client.prediction;

import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Optional sampled diagnostics. Timestamp pairs coexist with a shader pack's elapsed queries. */
final class PredictionRenderTimings {
    enum Stage {
        PREPARE, COVERAGE, SEAMS, UPLOAD, OPAQUE, WATER, DEPTH_COPY, STATE, ANTIALIAS,
        HZB_REDUCE, GPU_FILTER, INDIRECT_BUILD, INDIRECT_UPLOAD, INDIRECT_SUBMIT,
        SNAPSHOT, COVERAGE_STATE, GPU_RESIDENCY, VANILLA_MASK, PREPARE_TOTAL, CLIENT_SCHEDULE, CPU_CACHE, GPU_LOD_SELECT, SEAM_DRAW, VEGETATION_DRAW
    }

    private static final Stage[] STAGES = Stage.values();
    private static final long[] cpu = new long[STAGES.length], cpuCount = new long[STAGES.length];
    private static final long[] gpu = new long[STAGES.length], gpuCount = new long[STAGES.length];
    private static final long[][] cpuRecent = new long[STAGES.length][128], gpuRecent = new long[STAGES.length][128];

    /*
     * Timestamp queries are asynchronous. A query that has not become
     * available yet must stay occupied, otherwise a moving camera can make a
     * long WATER sample consume the only slot needed by the opaque pass. Keep
     * separate bounded rings for the passes which are independently useful in
     * diagnostics. HZB reduction, GPU filtering and indirect submission also
     * have dedicated rings because those stages can be delayed independently.
     * The capacity is intentionally the old ring size so a stalled pass has
     * the same bounded behavior as before.
     */
    private static final int POOL_CAPACITY = 16;
    private static final int TOKEN_SLOT_BITS = 8;
    private static final int TOKEN_SLOT_MASK = (1 << TOKEN_SLOT_BITS) - 1;

    private static final int OPAQUE_POOL = 0;
    private static final int WATER_POOL = 1;
    private static final int POST_POOL = 2;
    private static final int HZB_POOL = 3;
    private static final int FILTER_POOL = 4;
    private static final int INDIRECT_POOL = 5;
    private static final int AUX_POOL = 6;
    private static final int LOD_POOL = 7, SEAM_POOL = 8, VEGETATION_POOL = 9;
    private static final Pool[] POOLS = {
            new Pool(), // OPAQUE
            new Pool(), // WATER
            new Pool(), // POST: DEPTH_COPY and ANTIALIAS
            new Pool(), // HZB_REDUCE
            new Pool(), // GPU_FILTER
            new Pool(), // INDIRECT_SUBMIT
            new Pool(), // other/future GPU stages
            new Pool(), // GPU_LOD_SELECT
            new Pool(), // SEAM_DRAW
            new Pool()  // VEGETATION_DRAW
    };

    private static boolean sampling;
    private static boolean budgetSampling;
    private static volatile boolean reset;
    private static long dropped;

    private static final class Pool {
        final Slot[] slots = new Slot[POOL_CAPACITY];
    }

    private static final class Slot {
        int start = -1;
        int end = -1;
        Stage stage;
        boolean pending;

        void allocate() {
            if (start < 0) {
                start = GL15.glGenQueries();
                end = GL15.glGenQueries();
            }
        }

        void delete() {
            if (start >= 0) GL15.glDeleteQueries(start);
            if (end >= 0) GL15.glDeleteQueries(end);
            start = -1;
            end = -1;
            stage = null;
            pending = false;
        }
    }

    static void frame(long frame) {
        sampling = (Boolean.getBoolean("vss.renderTimings")
                || PredictionDemandProfile.enabled()
                || dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging) && frame % 30 == 0;
        budgetSampling = frame % 30 == 0;

        // Called on the render thread. No query allocation when diagnostics are off.
        // Availability is deliberately polled without GL_QUERY_RESULT, so a
        // not-yet-finished GPU sample remains pending across frames.
        for (Pool pool : POOLS) {
            for (int i = 0; i < pool.slots.length; i++) {
                Slot slot = pool.slots[i];
                if (slot == null) continue;
                if (reset) {
                    slot.delete();
                    pool.slots[i] = null;
                } else if (slot.pending
                        && GL15.glGetQueryObjecti(slot.end, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                    Stage stage = slot.stage;
                    if (stage != null) {
                        int stageIndex = stage.ordinal();
                        long elapsed = Math.max(0,
                                GL33.glGetQueryObjectui64(slot.end, GL15.GL_QUERY_RESULT)
                                        - GL33.glGetQueryObjectui64(slot.start, GL15.GL_QUERY_RESULT));
                        gpu[stageIndex] += elapsed;
                        gpuRecent[stageIndex][(int) (gpuCount[stageIndex] % 128)] = elapsed;
                        gpuCount[stageIndex]++;
                        var lane = lane(stage);
                        if (lane != null) PredictionRenderWorkBudget.SHARED.sample(lane, elapsed, System.nanoTime());
                    }
                    slot.pending = false;
                    slot.stage = null;
                }
            }
        }
        if (reset) {
            java.util.Arrays.fill(cpu, 0);
            java.util.Arrays.fill(cpuCount, 0);
            java.util.Arrays.fill(gpu, 0);
            java.util.Arrays.fill(gpuCount, 0);
            for (long[] values : cpuRecent) java.util.Arrays.fill(values, 0);
            for (long[] values : gpuRecent) java.util.Arrays.fill(values, 0);
            dropped = 0;
            reset = false;
        }
    }

    static void reset() {
        // Query objects are owned by the render context. Deletion therefore
        // remains deferred to frame(), which is called on the render thread.
        reset = true;
        PredictionRenderWorkBudget.SHARED.clear();
    }

    static long start() {
        return sampling ? System.nanoTime() : 0;
    }

    static void end(Stage stage, long start) {
        if (start == 0) return;
        int i = stage.ordinal(); long elapsed = System.nanoTime() - start;
        cpu[i] += elapsed; cpuRecent[i][(int) (cpuCount[i] % 128)] = elapsed;
        cpuCount[i]++;
    }

    private static int poolFor(Stage stage) {
        return switch (stage) {
            case OPAQUE -> OPAQUE_POOL;
            case WATER -> WATER_POOL;
            case DEPTH_COPY, ANTIALIAS -> POST_POOL;
            case HZB_REDUCE -> HZB_POOL;
            case GPU_FILTER -> FILTER_POOL;
            case INDIRECT_SUBMIT -> INDIRECT_POOL;
            case GPU_LOD_SELECT -> LOD_POOL;
            case SEAM_DRAW -> SEAM_POOL;
            case VEGETATION_DRAW -> VEGETATION_POOL;
            default -> AUX_POOL;
        };
    }

    private static int token(int pool, int slot) {
        return (pool << TOKEN_SLOT_BITS) | slot;
    }

    static int gpuStart(Stage stage) {
        if (stage == null || reset || !(sampling || budgetSampling && lane(stage) != null)
                || !(GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) {
            return -1;
        }

        int poolIndex = poolFor(stage);
        Pool pool = POOLS[poolIndex];
        for (int i = 0; i < pool.slots.length; i++) {
            Slot slot = pool.slots[i];
            if (slot == null) {
                slot = new Slot();
                pool.slots[i] = slot;
                slot.allocate();
            }
            if (slot.pending || slot.stage != null) continue;
            slot.stage = stage;
            GL33.glQueryCounter(slot.start, GL33.GL_TIMESTAMP);
            return token(poolIndex, i);
        }
        dropped++;
        return -1;
    }

    private static PredictionRenderWorkBudget.Lane lane(Stage stage) {
        return switch (stage) {
            case WATER -> PredictionRenderWorkBudget.Lane.WATER;
            case VEGETATION_DRAW -> PredictionRenderWorkBudget.Lane.VEGETATION;
            case SEAM_DRAW -> PredictionRenderWorkBudget.Lane.SEAM;
            default -> null;
        };
    }

    static void gpuEnd(int queryToken) {
        if (queryToken < 0) return;
        int poolIndex = queryToken >>> TOKEN_SLOT_BITS;
        int slotIndex = queryToken & TOKEN_SLOT_MASK;
        if (poolIndex < 0 || poolIndex >= POOLS.length) return;
        Pool pool = POOLS[poolIndex];
        if (slotIndex < 0 || slotIndex >= pool.slots.length) return;
        Slot slot = pool.slots[slotIndex];
        if (slot == null || slot.stage == null || slot.pending || slot.end < 0) return;
        GL33.glQueryCounter(slot.end, GL33.GL_TIMESTAMP);
        slot.pending = true;
    }

    static String diagnostics() {
        StringBuilder result = new StringBuilder("sampleEvery=30");
        for (Stage stage : STAGES) {
            int i = stage.ordinal();
            if (cpuCount[i] != 0) result.append(',').append(stage).append("CpuMs=")
                    .append(String.format(Locale.ROOT, "%.3f", cpu[i] / (cpuCount[i] * 1e6)))
                    .append("(p95/p99=").append(percentiles(cpuRecent[i], cpuCount[i])).append(')');
            if (gpuCount[i] != 0) result.append(',').append(stage).append("GpuMs=")
                    .append(String.format(Locale.ROOT, "%.3f", gpu[i] / (gpuCount[i] * 1e6)))
                    .append("(n=").append(gpuCount[i]).append(",p95/p99=")
                    .append(percentiles(gpuRecent[i], gpuCount[i])).append(')');
        }
        return result.append(",dropped=").append(dropped).toString();
    }

    private static String percentiles(long[] values, long count) {
        int n = (int) Math.min(count, values.length);
        long[] sorted = java.util.Arrays.copyOf(values, n); java.util.Arrays.sort(sorted);
        return String.format(Locale.ROOT, "%.3f/%.3f", sorted[Math.max(0, (int) Math.ceil(n * .95) - 1)] / 1e6,
                sorted[Math.max(0, (int) Math.ceil(n * .99) - 1)] / 1e6);
    }

    /** Cumulative sampled counters. Consumers take differences only within a session. */
    static java.util.Map<String, Object> snapshot() {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (Stage stage : STAGES) {
            int i = stage.ordinal();
            result.put(stage.name(), java.util.Map.of("cpuNanos",cpu[i],"cpuSamples",cpuCount[i],
                    "gpuNanos",gpu[i],"gpuSamples",gpuCount[i]));
        }
        result.put("dropped",dropped);
        return result;
    }

    private PredictionRenderTimings() { }
}
