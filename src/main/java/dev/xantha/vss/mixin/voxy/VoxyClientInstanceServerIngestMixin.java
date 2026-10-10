package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyIngestControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets VSS server data pass even when Voxy's ordinary ingest switch is off. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class VoxyClientInstanceServerIngestMixin {
    @Redirect(
            method = "isIngestEnabled(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Z",
            at = @At(value = "FIELD", target = "Lme/cortex/voxy/client/config/VoxyConfig;ingestEnabled:Z"),
            remap = false,
            require = 0)
    private boolean vss$allowServerIngest(@Coerce Object config) {
        return VoxyIngestControl.isVoxyIngestEnabled(config);
    }
}
