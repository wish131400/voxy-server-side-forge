package dev.xantha.vss.client.prediction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;

/** Read the applied leaf setting without a hard dependency on Sodium/Embeddium. */
final class PredictionGraphicsQuality {
    enum Leaves { DEFAULT, FAST, FANCY }

    private static final Reader OPTIONAL = discover();

    private PredictionGraphicsQuality() { }

    static boolean fastLeaves(Minecraft client) {
        return fastLeaves(client.options.graphicsMode().get(), OPTIONAL == null ? Leaves.DEFAULT : OPTIONAL.leaves());
    }

    static boolean fastLeaves(GraphicsStatus graphics, Leaves leaves) {
        return leaves == Leaves.FAST || leaves == Leaves.DEFAULT && graphics == GraphicsStatus.FAST;
    }

    private static Reader discover() {
        for (String name : new String[]{"net.caffeinemc.mods.sodium.client.SodiumClientMod",
                "me.jellysquid.mods.sodium.client.SodiumClientMod"}) {
            try { return reader(Class.forName(name, false, PredictionGraphicsQuality.class.getClassLoader())); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { }
        }
        return null;
    }

    static Reader reader(Class<?> entry) throws ReflectiveOperationException {
        Method options = entry.getMethod("options");
        Field quality = options.getReturnType().getField("quality");
        Field leaves = quality.getType().getField("leavesQuality");
        return new Reader(options, quality, leaves);
    }

    record Reader(Method options, Field quality, Field setting) {
        Leaves leaves() {
            try {
                Object value = setting.get(quality.get(options.invoke(null)));
                if (value instanceof Enum<?> mode) {
                    return switch (mode.name()) {
                        case "FAST" -> Leaves.FAST;
                        case "FANCY" -> Leaves.FANCY;
                        default -> Leaves.DEFAULT;
                    };
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { }
            return Leaves.DEFAULT;
        }
    }
}
