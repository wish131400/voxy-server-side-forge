package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelBakerySubsystem", remap = false)
public abstract class ModelBakerySubsystemShutdownMixin {
    @Unique
    private static final long vss$JOIN_TIMEOUT_MILLIS = 1000L;

    @Shadow
    private volatile boolean isRunning;

    @Shadow
    @Final
    private Thread processingThread;

    @Inject(method = "shutdown()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vss$shutdownWithoutUnboundedJoin(CallbackInfo ci) {
        isRunning = false;

        if (processingThread == null) {
            vss$freeVoxyModelResources();
            ci.cancel();
            return;
        }

        processingThread.interrupt();
        try {
            processingThread.join(vss$JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (processingThread.isAlive()) {
            VSSLogger.warn("Skipped Voxy model bakery resource free because its processor did not stop within "
                    + vss$JOIN_TIMEOUT_MILLIS + " ms");
            ci.cancel();
            return;
        }

        vss$freeVoxyModelResources();
        ci.cancel();
    }

    @Unique
    private void vss$freeVoxyModelResources() {
        vss$invokeNoArgFree("factory");
        vss$invokeNoArgFree("storage");
    }

    @Unique
    private void vss$invokeNoArgFree(String fieldName) {
        try {
            Object value = vss$getDeclaredFieldValue(fieldName);
            if (value == null) {
                return;
            }

            Method free = value.getClass().getDeclaredMethod("free");
            free.setAccessible(true);
            free.invoke(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VSSLogger.warn("Failed to free Voxy model bakery field " + fieldName + " during shutdown", e);
        }
    }

    @Unique
    private Object vss$getDeclaredFieldValue(String fieldName) throws ReflectiveOperationException {
        Class<?> type = ((Object) this).getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(this);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
