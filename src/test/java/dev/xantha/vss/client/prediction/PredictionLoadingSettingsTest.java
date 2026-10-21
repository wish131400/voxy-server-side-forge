package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.xantha.vss.config.VSSClientConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionLoadingSettingsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void existingConfigurationsKeepLoadingEnabled() {
        var config = new Gson().fromJson("{\"enablePrediction\":true}", VSSClientConfig.class);
        assertTrue(config.enablePredictionLoading);
        assertTrue(config.isPredictionLoadingEnabled());
    }

    @Test void savedPauseSurvivesNormalizationAndRoundTripWithoutDisablingDisplay() throws Exception {
        var gson = new Gson();
        var config = gson.fromJson("{\"enablePrediction\":true,\"enablePredictionLoading\":false}", VSSClientConfig.class);
        var validate = VSSClientConfig.class.getDeclaredMethod("validate");
        validate.setAccessible(true);
        validate.invoke(config);
        var restored = gson.fromJson(gson.toJson(config), VSSClientConfig.class);
        assertTrue(restored.enablePrediction);
        assertFalse(restored.enablePredictionLoading);
        assertFalse(restored.isPredictionLoadingEnabled());
        restored.enablePredictionLoading = true;
        assertTrue(restored.isPredictionLoadingEnabled());
    }

    @Test void disablingDisplayRetainsTheIndependentLoadingPreference() {
        var config = new VSSClientConfig();
        config.enablePrediction = false;
        assertTrue(config.enablePredictionLoading);
        assertFalse(config.isPredictionLoadingEnabled());
        config.enablePrediction = true;
        assertTrue(config.isPredictionLoadingEnabled());
        config.enablePredictionLoading = false;
        config.enablePrediction = false;
        config.enablePrediction = true;
        assertFalse(config.isPredictionLoadingEnabled());
    }
}
