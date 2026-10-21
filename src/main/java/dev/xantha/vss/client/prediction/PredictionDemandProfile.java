package dev.xantha.vss.client.prediction;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/** Opt-in scalar observations. No GPU readback or filesystem calls on the render thread. */
final class PredictionDemandProfile {
    private static final String DIRECTORY = System.getProperty("vss.detailProfileDirectory", "");
    private static final long INTERVAL_NANOS = 1_000_000_000L;
    private static final int MAX_SAMPLES = 1800;
    private static final ArrayBlockingQueue<Map<String,Object>> ROWS = new ArrayBlockingQueue<>(16);
    private static final String RUN = java.util.UUID.randomUUID().toString();
    private static volatile boolean failed;
    private static long nextSample, lastFrame = -1, dropped;
    private static int sampled;
    private static boolean started, menuDuringInterval;

    private PredictionDemandProfile() { }
    static boolean enabled() { return !DIRECTORY.isBlank() && !failed && sampled < MAX_SAMPLES; }
    static void observeMenu(boolean open) { menuDuringInterval |= open; }
    static boolean due(long frame, long now) {
        if (!enabled() || frame == lastFrame || now < nextSample) return false;
        lastFrame = frame; nextSample = now + INTERVAL_NANOS; sampled++;
        return true;
    }
    static void publish(Map<String,Object> row) {
        if (!started) {
            started = true;
            var writer = new Thread(PredictionDemandProfile::writeRows,"vss-detail-profile");
            writer.setDaemon(true); writer.start();
        }
        row.put("run",RUN); row.put("schema",1); row.put("droppedRows",dropped);
        row.put("menuDuringInterval",menuDuringInterval); menuDuringInterval=false;
        if (!ROWS.offer(row)) dropped++;
    }
    private static void writeRows() {
        try {
            Path directory = Path.of(DIRECTORY);
            Files.createDirectories(directory);
            Path file = directory.resolve("vss-detail-" + RUN + ".jsonl");
            var json = new com.google.gson.Gson();
            try (BufferedWriter writer = Files.newBufferedWriter(file,StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                while (true) {
                    Map<String,Object> row = ROWS.take();
                    writer.write(json.toJson(row)); writer.newLine(); writer.flush();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            failed = true; ROWS.clear();
            dev.xantha.vss.common.VSSLogger.warn("VSS detail profile could not be written",error);
        }
    }
}
