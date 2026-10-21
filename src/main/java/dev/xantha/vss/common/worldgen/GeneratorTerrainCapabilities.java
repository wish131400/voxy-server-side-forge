package dev.xantha.vss.common.worldgen;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Conservative inheritance check. Custom algorithms need a projection or an explicit LOD source. */
public final class GeneratorTerrainCapabilities {
    private GeneratorTerrainCapabilities() { }

    public static boolean inheritsImplementation(Class<?> actual, Class<? extends ChunkGenerator> base) {
        if (!base.isAssignableFrom(actual)) return false;
        for (Class<?> type = actual; type != base; type = type.getSuperclass()) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.isBridge() || Modifier.isStatic(candidate.getModifiers())
                        || Modifier.isPrivate(candidate.getModifiers())) continue;
                for (Class<?> parent = base; parent != null && parent != Object.class; parent = parent.getSuperclass()) {
                    for (Method inherited : parent.getDeclaredMethods()) {
                        if (Modifier.isPrivate(inherited.getModifiers()) || Modifier.isStatic(inherited.getModifiers())) continue;
                        // Codec-only aliases do not change terrain. Discover signatures at runtime so Forge's
                        // SRG names are not embedded as reflective strings in the compatibility path.
                        String result = inherited.getReturnType().getName();
                        if (result.equals("com.mojang.serialization.Codec")
                                || result.equals("com.mojang.serialization.MapCodec")) continue;
                        if (inherited.getName().equals(candidate.getName())
                                && Arrays.equals(inherited.getParameterTypes(), candidate.getParameterTypes())) return false;
                    }
                }
            }
        }
        return true;
    }
}
