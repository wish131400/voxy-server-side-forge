package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import org.joml.Matrix4f;

/** Compare complete opaque GPU intervals asynchronously, never reuse visibility. */
final class PredictionOcclusionPolicy {
    private static final int SAMPLES = 8, SETTLE = 4, RECHECK = 480;
    private final double[][] times = new double[2][SAMPLES];
    private final int[] counts = new int[2];
    private final Matrix4f view = new Matrix4f();
    private boolean initialized, culling = true, winner = true, comparing = true;
    private int age, stableAge, width, height;
    private long revision, ownership, epoch;
    private double x, y, z;
    private double directMs, culledMs;
    private long restarts, completedComparisons;
    private final Matrix4f previousView = new Matrix4f();
    private boolean settling;
    private int quietFrames;
    private long quads = -1;
    private double previousX, previousY, previousZ;

    /** Keep the last measured decision during motion; visibility itself is always current-frame. */
    boolean chooseStable(Matrix4f transform, double cx, double cy, double cz,
                         int w, int h, long candidateQuads, long materials) {
        if (!initialized || width != w || height != h || ownership != materials) {
            initialized = true; width = w; height = h; ownership = materials;
            x = cx; y = cy; z = cz; view.set(transform); previousView.set(transform);
            previousX = cx; previousY = cy; previousZ = cz; quads = candidateQuads;
            settling = false; quietFrames = 0; restart();
        } else {
            boolean moving = !previousView.equals(transform, .012f)
                    || Math.abs(cx - previousX) > 1 || Math.abs(cy - previousY) > 1 || Math.abs(cz - previousZ) > 1;
            boolean workloadChanged = Math.abs(candidateQuads - quads) > Math.max(256, quads / 4);
            previousView.set(transform); previousX = cx; previousY = cy; previousZ = cz;
            if (moving || workloadChanged) {
                if (!settling) { epoch++; comparing = false; culling = winner; settling = true; }
                quietFrames = 0; quads = candidateQuads;
            } else if (settling && ++quietFrames >= 12) {
                settling = false; x = cx; y = cy; z = cz; view.set(transform); quads = candidateQuads;
                restart();
            }
        }
        if (!settling && !comparing && ++stableAge >= RECHECK) restart();
        age++;
        return culling;
    }

    boolean choose(Matrix4f transform, double cx, double cy, double cz,
                   int w, int h, long residency, long coverage) {
        if (!initialized || width != w || height != h || revision != residency || ownership != coverage
                || Math.abs(cx - x) > 8 || Math.abs(cy - y) > 8 || Math.abs(cz - z) > 8
                || !view.equals(transform, .08f)) {
            initialized = true; width = w; height = h; revision = residency; ownership = coverage;
            x = cx; y = cy; z = cz; view.set(transform);
            restart();
        }
        if (!comparing && ++stableAge >= RECHECK) restart();
        age++;
        return culling;
    }

    private void restart() {
        epoch++; restarts++; Arrays.fill(counts, 0); comparing = true; culling = winner; age = stableAge = 0;
    }

    long epoch() { return epoch; }
    boolean sample() { return comparing && age > SETTLE && age % 4 == 0; }

    void accept(long sampleEpoch, boolean sampledCulling, long nanos) {
        if (sampleEpoch != epoch || !comparing || sampledCulling != culling || nanos <= 0) return;
        int mode = sampledCulling ? 1 : 0;
        if (counts[mode] == SAMPLES) return;
        times[mode][counts[mode]++] = nanos / 1e6;
        if (counts[mode] != SAMPLES) return;
        if (counts[1 - mode] != SAMPLES) { culling = !culling; age = 0; return; }
        directMs = trimmed(times[0]); culledMs = trimmed(times[1]);
        double margin = Math.max(.08, directMs * .04);
        if (culledMs > directMs + margin) winner = false;
        else if (culledMs < directMs - margin) winner = true;
        culling = winner; comparing = false; age = stableAge = 0;
        completedComparisons++;
    }

    private static double trimmed(double[] values) {
        Arrays.sort(values);
        double sum = 0;
        for (int i = 2; i < SAMPLES - 2; i++) sum += values[i];
        return sum / (SAMPLES - 4);
    }

    String diagnostics() {
        return ",occlusionPolicy=" + (settling ? "moving-" : comparing ? "measuring-" : "selected-") + (culling ? "hiz" : "direct")
                + ",directGpuMs=" + directMs + ",culledGpuMs=" + culledMs
                + ",occlusionRestarts=" + restarts + ",occlusionComparisons=" + completedComparisons;
    }
}
