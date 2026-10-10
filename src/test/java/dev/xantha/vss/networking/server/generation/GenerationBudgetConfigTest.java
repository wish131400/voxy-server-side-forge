package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.xantha.vss.config.JsonConfig;
import dev.xantha.vss.config.VSSServerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GenerationBudgetConfigTest {
    @BeforeAll
    static void initializeConfigDirectory() {
        if (net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "generation-tests"));
    }

    @Test
    void oldConfigKeepsNewBudgetsDisabled() {
        var config = new Gson().fromJson("{\"enableChunkGeneration\":true}", VSSServerConfig.class);
        assertEquals(0, config.generationSnapshotsPerTickLimit);
        assertEquals(0, config.generationSnapshotBudgetMillis);
        assertEquals(0, config.generationPauseAboveMspt);
    }

    @Test
    void removedStartLimitIsIgnoredAndDroppedFromSavedConfig() throws Exception {
        var config = new Gson().fromJson("""
                {"generationStartsPerTickLimit":1,"generationSnapshotsPerTickLimit":6,
                 "generationSnapshotBudgetMillis":3,"generationPauseAboveMspt":45}
                """, VSSServerConfig.class);
        assertEquals(new VSSServerConfig().automaticGenerationStartsPerTick(), config.automaticGenerationStartsPerTick());
        assertEquals(6, config.generationSnapshotsPerTickLimit);
        assertEquals(3, config.generationSnapshotBudgetMillis);
        assertEquals(45, config.generationPauseAboveMspt);
        var serialize = JsonConfig.class.getDeclaredMethod("configWithHelp");
        serialize.setAccessible(true);
        var saved = (JsonObject) serialize.invoke(config);
        assertFalse(saved.has("generationStartsPerTickLimit"));
        assertFalse(saved.getAsJsonObject("_help").has("generationStartsPerTickLimit"));
        assertEquals(6, saved.get("generationSnapshotsPerTickLimit").getAsInt());
    }

    @Test
    void invalidBudgetsAreClampedDuringConfigValidation() throws Exception {
        var config = new VSSServerConfig();
        var validate = VSSServerConfig.class.getDeclaredMethod("validate");
        validate.setAccessible(true);
        config.generationSnapshotsPerTickLimit = -1;
        config.generationSnapshotBudgetMillis = config.generationPauseAboveMspt = -1;
        validate.invoke(config);
        assertEquals(0, config.generationSnapshotsPerTickLimit);
        assertEquals(0, config.generationSnapshotBudgetMillis);
        assertEquals(0, config.generationPauseAboveMspt);
        config.generationSnapshotsPerTickLimit = Integer.MAX_VALUE;
        config.generationSnapshotBudgetMillis = config.generationPauseAboveMspt = Integer.MAX_VALUE;
        validate.invoke(config);
        assertEquals(128, config.generationSnapshotsPerTickLimit);
        assertEquals(50, config.generationSnapshotBudgetMillis);
        assertEquals(1000, config.generationPauseAboveMspt);
    }
}
