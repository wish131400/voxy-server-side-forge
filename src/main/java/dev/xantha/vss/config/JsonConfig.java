package dev.xantha.vss.config;

import java.nio.file.Path;
import java.util.Map;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 配置模型与加载器目录适配；校验和字段说明由具体配置提供，JSON 文件工作交给存储器。
 * <p>保存是同步操作，仅由加载或显式设置流程触发，不能在逐帧渲染中调用。
 */
public abstract class JsonConfig {
    protected abstract String getFileName();
    protected void validate() { }
    protected Map<String, String> getConfigHelp() { return Map.of(); }

    public void save() {
        new JsonConfigStore(configDirectory()).save(this);
    }

    private static Path configDirectory() {
        try {
            Path directory = FMLPaths.CONFIGDIR.get();
            if (directory != null) return directory;
        } catch (Throwable unavailable) {
            // 无加载器的测试和工具使用同一回退路径，读写必须一致。
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "vss-config");
    }

    protected static <T extends JsonConfig> T load(Class<T> type, String fileName) {
        return new JsonConfigStore(configDirectory()).load(type, fileName);
    }
}
