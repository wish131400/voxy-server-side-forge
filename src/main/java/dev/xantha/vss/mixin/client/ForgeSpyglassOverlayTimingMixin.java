package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.SpyglassOverlayTiming;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Forge renders this overlay in its own layer instead of Gui.render. */
@Mixin(ForgeGui.class)
public abstract class ForgeSpyglassOverlayTimingMixin {
    @ModifyArg(method = "renderSpyglassOverlay(Lnet/minecraft/client/gui/GuiGraphics;)V", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 0, remap = true), index = 0)
    private float vss$boundScopeAnimation(float factor) {
        return SpyglassOverlayTiming.interpolation(factor);
    }
}
