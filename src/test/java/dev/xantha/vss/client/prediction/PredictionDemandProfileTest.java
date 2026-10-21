package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionDemandProfileTest {
    @Test void optInRowsHaveFrameContextAndDoNotSampleTwiceInAnInterleavedFrame() throws Exception {
        assertTrue(PredictionDemandProfile.enabled());
        assertTrue(PredictionDemandProfile.due(1,1));
        assertFalse(PredictionDemandProfile.due(1,2_000_000_001L));
        assertFalse(PredictionDemandProfile.due(2,900_000_000L));
        assertTrue(PredictionDemandProfile.due(2,1_000_000_001L));
        var value = new LinkedHashMap<String,Object>();
        value.put("camera",new double[]{64,80,128}); value.put("session",9); value.put("frame",2);
        value.put("generation",Map.of("supersededBuilds",3));
        PredictionDemandProfile.publish(value);
        Path dir=Path.of(System.getProperty("vss.detailProfileDirectory"));
        String text=""; long deadline=System.nanoTime()+5_000_000_000L;
        while (text.isBlank() && System.nanoTime()<deadline) {
            if (Files.exists(dir)) try (var files=Files.list(dir)) {
                for (var file:files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                    text=Files.readString(file); if (!text.isBlank()) break;
                }
            }
            if (text.isBlank()) Thread.sleep(5);
        }
        assertFalse(text.isBlank());
        var parsed=com.google.gson.JsonParser.parseString(text.strip()).getAsJsonObject();
        assertEquals(1,parsed.get("schema").getAsInt()); assertEquals(0,parsed.get("droppedRows").getAsInt());
        assertEquals(3,parsed.get("generation").getAsJsonObject().get("supersededBuilds").getAsInt());
        assertEquals(80,parsed.getAsJsonArray("camera").get(1).getAsDouble());
    }
}
