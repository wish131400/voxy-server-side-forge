package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Optional features can obtain templates without reaching a live ServerLevel. */
public final class PredictionTemplateAccess {
    private PredictionTemplateAccess() { }

    public static boolean virtual(WorldGenLevel level) { return level instanceof PredictionDecorationLevel; }

    public static StructureTemplateManager manager(WorldGenLevel level) {
        if (!(level instanceof PredictionDecorationLevel prediction))
            throw new IllegalArgumentException("Not a prediction decoration level");
        return prediction.templateManager();
    }
}
