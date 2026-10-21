package dev.xantha.vss.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 与加载器无关的 JSON 持久化。目录由启动适配器提供，测试可以使用独立临时目录。
 * <p>同步文件 I/O，仅用于加载、显式保存和重载，不应放入渲染逐帧路径。
 * 读入后先校验再回写；已有坏文件保留供排查，不能用默认值覆盖。
 * 本类不缓存配置实例；调用方负责配置替换和读写时序。
 */
final class JsonConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    JsonConfigStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    void save(JsonConfig config) {
        try {
            Path path = directory.resolve(config.getFileName());
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(configWithHelp(config)));
        } catch (Exception e) {
            VSSLogger.error("Failed to save config " + config.getFileName(), e);
        }
    }

    private JsonObject configWithHelp(JsonConfig config) {
        JsonObject result = new JsonObject();
        Map<String, String> helpEntries = config.getConfigHelp();
        if (!helpEntries.isEmpty()) {
            JsonObject help = new JsonObject();
            helpEntries.forEach(help::addProperty);
            result.add("_help", help);
        }
        GSON.toJsonTree(config).getAsJsonObject().entrySet()
                .forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result;
    }

    <T extends JsonConfig> T load(Class<T> type, String fileName) {
        Path path = directory.resolve(fileName);
        boolean exists = Files.isRegularFile(path);
        if (exists) {
            try {
                T config = GSON.fromJson(Files.readString(path), type);
                if (config != null) {
                    config.validate();
                    save(config);
                    return config;
                }
                VSSLogger.warn("Config " + fileName + " was empty or invalid, using defaults");
            } catch (Exception e) {
                VSSLogger.error("Failed to read config " + fileName + ", using defaults", e);
            }
        }
        try {
            T config = type.getDeclaredConstructor().newInstance();
            config.validate();
            if (!exists) save(config);
            return config;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + type.getName(), e);
        }
    }
}
