package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyIngestControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets VSS server data pass even when Voxy's ordinary ingest switch is off. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class VoxyClientInstanceServerIngestMixin {
    @Inject(
            method = "isIngestEnabled(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void vss$allowServerIngest(CallbackInfoReturnable<Boolean> cir) {
        if (VoxyIngestControl.isServerIngestActive()) {
            cir.setReturnValue(true);
        }
    }
}
