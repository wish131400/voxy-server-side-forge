package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fml.loading.FMLPaths;

/** Locally available templates are used only after matching the server's exact resource digest. */
final class PredictionStructureTemplates extends StructureTemplateManager {
    static final long MAX_NBT_BYTES = 512L * 1024 * 1024;
    private static PredictionStructureTemplates shared;
    private final JsonObject encoded;
    private final PredictionTemplateRepository repository;
    private final Map<ResourceLocation, StructureTemplate> decoded = new LinkedHashMap<>(32, .75F, true);
    private final Map<ResourceLocation, Long> decodedWeights = new java.util.HashMap<>();
    private long decodedBytes;

    static synchronized PredictionStructureTemplates open(JsonObject encoded) throws IOException {
        PredictionTemplateRepository repository = encoded.has("_vss_catalog")
                ? PredictionTemplateRepository.forCatalog(encoded) : null;
        if (repository != null && shared != null && shared.repository == repository) return shared;
        // Vanilla's constructor requires a generated-directory handle. This is a
        // private disposable cache directory, closed immediately after construction;
        // get/getOrCreate below use only the immutable server snapshot.
        var storage = LevelStorageSource.createDefault(FMLPaths.GAMEDIR.get().resolve("vss/prediction/template-loader"));
        try (var handle = storage.createAccess("cache")) {
            var manager = new PredictionStructureTemplates(encoded, repository, handle);
            if (repository != null) shared = manager;
            return manager;
        }
    }

    private PredictionStructureTemplates(JsonObject encoded, PredictionTemplateRepository repository,
                                         LevelStorageSource.LevelStorageAccess handle) {
        super(ResourceManager.Empty.INSTANCE, handle, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK.asLookup());
        this.encoded = encoded;
        this.repository = repository;
    }

    static synchronized void clearShared() { shared = null; }

    @Override public synchronized Optional<StructureTemplate> get(ResourceLocation id) {
        if (repository != null) repository.checkOpen();
        StructureTemplate template = decoded.get(id);
        if (template != null) return Optional.of(template);
        if (!encoded.has(id.toString())) return Optional.empty();
        try {
            byte[] bytes = repository == null
                    ? java.util.Base64.getDecoder().decode(encoded.get(id.toString()).getAsString()) : repository.bytes(id);
            if (bytes == null) return Optional.empty();
            var accounting = new NbtAccounter(MAX_NBT_BYTES);
            var tag = readTemplate(bytes, accounting);
            // Prediction has no entities, block entities, loot or ticking state.
            tag.remove("entities");
            stripEndCityEntityMarkers(id, tag);
            stripOceanRuinEntityMarkers(id, tag);
            template = readStructure(tag);
            long weight = accounting.getUsage();
            while (!decoded.isEmpty() && (decoded.size() >= 256 || decodedBytes + weight > 64L * 1024 * 1024)) {
                var oldest = decoded.keySet().iterator().next();
                decoded.remove(oldest);
                decodedBytes -= decodedWeights.remove(oldest);
            }
            decoded.put(id, template);
            decodedWeights.put(id, weight);
            decodedBytes += weight;
            return Optional.of(template);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid synced structure template: " + id, failure);
        }
    }

    private static net.minecraft.nbt.CompoundTag readTemplate(byte[] bytes, NbtAccounter accounting) throws IOException {
        try (var input = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            return NbtIo.read(input, accounting);
        }
    }

    @Override public StructureTemplate getOrCreate(ResourceLocation id) {
        return get(id).orElseThrow(() -> new UnsupportedOperationException("Missing synced structure template: " + id));
    }

    static void stripEndCityEntityMarkers(ResourceLocation id, net.minecraft.nbt.CompoundTag tag) {
        if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("end_city/")) return;
        for (var value : tag.getList("blocks",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var block = (net.minecraft.nbt.CompoundTag) value;
            if (!block.contains("nbt",net.minecraft.nbt.Tag.TAG_COMPOUND)) continue;
            var data = block.getCompound("nbt");
            String marker = data.getString("metadata");
            if (marker.startsWith("Sentry") || marker.startsWith("Elytra")) data.putString("metadata","");
        }
    }
    static void stripOceanRuinEntityMarkers(ResourceLocation id, net.minecraft.nbt.CompoundTag tag) {
        if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("underwater_ruin/")) return;
        // OceanRuinPieces creates the drowned through getLevel() before it
        // checks addFreshEntity(). Prediction has no ServerLevel or entities;
        // keep the ruin and chest markers, omitting only this entity marker.
        for (var value : tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var block = (net.minecraft.nbt.CompoundTag) value;
            if (!block.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)) continue;
            var data = block.getCompound("nbt");
            if (data.getString("metadata").equals("drowned")) data.putString("metadata", "");
        }
    }
}
