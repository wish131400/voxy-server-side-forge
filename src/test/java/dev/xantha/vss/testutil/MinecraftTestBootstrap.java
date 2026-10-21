package dev.xantha.vss.testutil;

/** Uses each platform's existing registry fixture; never starts a game or opens a world. */
public final class MinecraftTestBootstrap {
    private MinecraftTestBootstrap() { }
    public static void initialize() {
        try {
            var method = Class.forName("dev.xantha.vss.client.prediction.ClientTerrainSamplerTest")
                    .getDeclaredMethod("bootstrapMinecraft");
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot initialize Minecraft test registries", error);
        }
    }
}
