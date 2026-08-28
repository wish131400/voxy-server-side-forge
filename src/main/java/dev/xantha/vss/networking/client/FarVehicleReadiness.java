package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;

final class FarVehicleReadiness {
    private static final Probe GENERIC_PROBE = new Probe(null, null, false);
    private static final ConcurrentHashMap<Class<?>, Probe> PROBES = new ConcurrentHashMap<>();
    private static final Set<Class<?>> FAILED_PROBES = ConcurrentHashMap.newKeySet();

    private FarVehicleReadiness() {
    }

    static boolean isReady(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        Probe probe = PROBES.computeIfAbsent(entity.getClass(), FarVehicleReadiness::createProbe);
        if (probe == GENERIC_PROBE) {
            return true;
        }
        if (probe.failClosed && probe.getContraption == null && probe.isReadyForRender == null) {
            return false;
        }
        try {
            if (probe.getContraption != null && probe.getContraption.invoke(entity) == null) {
                return false;
            }
            if (probe.isReadyForRender != null) {
                Object ready = probe.isReadyForRender.invoke(entity);
                if (ready instanceof Boolean value && !value) {
                    return false;
                }
            }
            return true;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            if (FAILED_PROBES.add(entity.getClass())) {
                VSSLogger.warn("Unable to verify far contraption readiness for " + entity.getClass().getName(), e);
            }
            return false;
        }
    }

    private static Probe createProbe(Class<?> type) {
        Method getContraption = findNoArgMethod(type, "getContraption");
        Method isReadyForRender = findNoArgMethod(type, "isReadyForRender");
        if (getContraption == null && isReadyForRender == null) {
            if (isCreateContraptionHierarchy(type)) {
                VSSLogger.warn("Refusing to render far contraption without a usable readiness probe: " + type.getName());
                return new Probe(null, null, true);
            }
            return GENERIC_PROBE;
        }
        return new Probe(getContraption, isReadyForRender, false);
    }

    private static boolean isCreateContraptionHierarchy(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if ("AbstractContraptionEntity".equals(current.getSimpleName())
                    || (name.startsWith("com.simibubi.create.") && name.contains("contraption"))) {
                return true;
            }
        }
        return false;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() == 0) {
                return method;
            }
        } catch (NoSuchMethodException ignored) {
            // Fall through to protected/package-private methods in the class hierarchy.
        } catch (RuntimeException e) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                if (method.getParameterCount() == 0 && method.trySetAccessible()) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Continue through the hierarchy without a hard Create/CBC dependency.
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private record Probe(Method getContraption, Method isReadyForRender, boolean failClosed) {
    }
}
