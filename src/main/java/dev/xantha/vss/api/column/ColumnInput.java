package dev.xantha.vss.api.column;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Detached complete source column. Every section in [minSectionY, maxSectionY) is
 * represented, including source air omitted by the ordinary serializer. Light
 * presence describes serialized data, not proof that Minecraft's light engine is ready.
 * No player-dependent state is supplied: results are shared by all players and cached.
 */
public record ColumnInput(ResourceKey<Level> dimension, int chunkX, int chunkZ,
        int minSectionY, int maxSectionY, List<Section> sections) {
    public ColumnInput {
        Objects.requireNonNull(dimension, "dimension");
        sections = List.copyOf(sections);
        if (maxSectionY <= minSectionY || sections.size() != maxSectionY - minSectionY) {
            throw new IllegalArgumentException("Incomplete column filter snapshot");
        }
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).sectionY() != minSectionY + i) {
                throw new IllegalArgumentException("Unordered column filter snapshot");
            }
        }
    }

    public Section section(int sectionY) {
        return sections.get(sectionY - minSectionY);
    }

    /**
     * Detached section metadata; light arrays are copied on input and output.
     *
     * @param sectionY section coordinate, in units of 16 blocks
     * @param onlyAir whether the source section contains only air
     * @param maxBlockY absolute block Y of the highest non-air block, including
     *        foliage and fluids, not a ground height; {@link Integer#MIN_VALUE}
     *        means empty or unknown (use {@code onlyAir} to distinguish them).
     *        VSS computes this for every non-empty section supplied to a filter.
     * @param blockLight block light, 2048 bytes or null
     * @param skyLight sky light, 2048 bytes or null
     */
    public record Section(int sectionY, boolean onlyAir, int maxBlockY, byte[] blockLight, byte[] skyLight) {
        /** Retains the original constructor descriptor; non-air height is unknown. */
        public Section(int sectionY, boolean onlyAir, byte[] blockLight, byte[] skyLight) {
            this(sectionY, onlyAir, Integer.MIN_VALUE, blockLight, skyLight);
        }

        public Section {
            blockLight = copyLight(blockLight);
            skyLight = copyLight(skyLight);
        }

        @Override public byte[] blockLight() { return blockLight == null ? null : blockLight.clone(); }
        @Override public byte[] skyLight() { return skyLight == null ? null : skyLight.clone(); }
        public boolean hasBlockLight() { return blockLight != null; }
        public boolean hasSkyLight() { return skyLight != null; }

        private static byte[] copyLight(byte[] light) {
            if (light != null && light.length != 2048) throw new IllegalArgumentException("Invalid section light array");
            return light == null ? null : light.clone();
        }
    }
}
