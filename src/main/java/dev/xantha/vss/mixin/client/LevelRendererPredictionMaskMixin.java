package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidate only the affected coverage column; section compilation is still polled. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPredictionMaskMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), require = 0)
    private void vss$coverageColumnDirty(int x, int y, int z, boolean immediate, CallbackInfo ci) {
        PredictionRenderer.invalidateVanillaChunk(x, z);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void vss$coverageReset(CallbackInfo ci) {
        PredictionRenderer.invalidateVanillaCoverage();
    }
}
