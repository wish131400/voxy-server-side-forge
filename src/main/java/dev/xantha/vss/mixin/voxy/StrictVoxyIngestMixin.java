package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyIngestTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public abstract class StrictVoxyIngestMixin {
    // The plugin binds verified native task identities and adds an outer exception
    // cleanup. Optional ports retain require=0 and use GPU readiness if unsupported.
    @Inject(method = "processJob()V", at = @At("RETURN"), require = 0)
    private void vss$committedIngest(CallbackInfo ci) {
        VoxyIngestTracker.workerDone();
    }
}
