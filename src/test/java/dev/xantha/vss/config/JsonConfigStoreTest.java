package dev.xantha.vss.config;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonConfigStoreTest {
    @TempDir Path directory;

    @Test void missingFileCreatesValidatedDefaultsAndHelp() throws Exception {
        var config = new JsonConfigStore(directory).load(Fixture.class, "fixture.json");
        assertEquals(8, config.limit);
        var saved = JsonParser.parseString(Files.readString(directory.resolve("fixture.json"))).getAsJsonObject();
        assertEquals(8, saved.get("limit").getAsInt());
        assertEquals("capacity", saved.getAsJsonObject("_help").get("limit").getAsString());
    }

    @Test void loadValidatesAndPersistsWithoutReusingAnotherInstance() throws Exception {
        Files.writeString(directory.resolve("fixture.json"), "{\"limit\":-3}");
        var store = new JsonConfigStore(directory);
        var first = store.load(Fixture.class, "fixture.json");
        assertEquals(1, first.limit);
        assertEquals(1, JsonParser.parseString(Files.readString(directory.resolve("fixture.json")))
                .getAsJsonObject().get("limit").getAsInt());
        first.limit = 5;
        store.save(first);
        var second = store.load(Fixture.class, "fixture.json");
        assertNotSame(first, second);
        assertEquals(5, second.limit);
    }

    @Test void corruptAndNullFilesAreKeptForDiagnosis() throws Exception {
        var path = directory.resolve("fixture.json");
        for (String broken : new String[] {"{invalid", "null", ""}) {
            Files.writeString(path, broken);
            var defaults = new JsonConfigStore(directory).load(Fixture.class, "fixture.json");
            assertEquals(8, defaults.limit);
            assertEquals(broken, Files.readString(path));
        }
    }

    @Test void separateDirectoriesDoNotShareConfiguration() {
        var first = new JsonConfigStore(directory.resolve("first"));
        var second = new JsonConfigStore(directory.resolve("second"));
        var custom = new Fixture();
        custom.limit = 6;
        first.save(custom);
        assertEquals(6, first.load(Fixture.class, "fixture.json").limit);
        assertEquals(8, second.load(Fixture.class, "fixture.json").limit);
    }

    public static final class Fixture extends JsonConfig {
        public int limit = 8;
        public Fixture() { }
        @Override protected String getFileName() { return "fixture.json"; }
        @Override protected void validate() { limit = Math.max(1, limit); }
        @Override protected Map<String, String> getConfigHelp() { return Map.of("limit", "capacity"); }
    }
}
