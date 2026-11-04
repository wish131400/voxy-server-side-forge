package dev.xantha.vss.testutil;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagFile;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Block;

/** Real vanilla resource reload for standalone worldgen tests, including nested tags. */
public final class VanillaBlockTagFixtures {
    private static boolean loaded;
    private VanillaBlockTagFixtures() { }

    public static synchronized void bootstrap() {
        if (loaded) return;
        for (var block : BuiltInRegistries.BLOCK) for (var state : block.getStateDefinition().getPossibleStates())
            if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) Block.BLOCK_STATE_REGISTRY.add(state);
        String anchor = "data/minecraft/tags/blocks/dirt.json";
        var source = VanillaBlockTagFixtures.class.getResource("/" + anchor);
        if (source == null) throw new IllegalStateException("Missing vanilla block tag resources");
        var files = new LinkedHashMap<ResourceLocation, List<TagLoader.EntryWithSource>>();
        try {
            if (source.getProtocol().equals("jar")) {
                var connection = (JarURLConnection) source.openConnection();
                // Own the handle: closing the class loader's cached JarFile would
                // prevent subsequent registry / feature resources from loading.
                try (var jar = new JarFile(Path.of(connection.getJarFileURL().toURI()).toFile())) {
                    var entries = jar.stream().filter(entry -> isBlockTag(entry.getName())).toList();
                    for (var entry : entries) try (var input = jar.getInputStream(entry)) {
                        read(files, entry.getName(), input);
                    }
                }
            } else if (source.getProtocol().equals("file")) {
                Path root = Path.of(source.toURI());
                for (int i = 0; i < anchor.split("/").length; i++) root = root.getParent();
                final Path resources = root;
                try (var paths = Files.walk(resources.resolve("data"))) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
                        String name = resources.relativize(path).toString().replace('\\', '/');
                        if (isBlockTag(name)) try (var input = Files.newInputStream(path)) {
                            read(files, name, input);
                        }
                    }
                }
            } else throw new IllegalStateException("Unsupported vanilla resource source " + source);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalStateException("Invalid vanilla resource source " + source, failure);
        }
        var loader = new TagLoader<Holder<Block>>(
                id -> BuiltInRegistries.BLOCK.getHolder(net.minecraft.resources.ResourceKey.create(Registries.BLOCK, id)).map(holder -> holder), "tags/blocks");
        var resolved = loader.build(files);
        if (resolved.size() != files.size()) {
            var missing = new java.util.LinkedHashSet<>(files.keySet());
            missing.removeAll(resolved.keySet());
            throw new IllegalStateException("Unresolved vanilla block tags " + missing);
        }
        Map<TagKey<Block>, List<Holder<Block>>> tags = new LinkedHashMap<>();
        resolved.forEach((id, values) -> tags.put(TagKey.create(Registries.BLOCK, id), List.copyOf(values)));
        BuiltInRegistries.BLOCK.bindTags(tags);
        loaded = true;
    }

    private static boolean isBlockTag(String name) {
        return name.matches("data/[^/]+/tags/blocks/.+\\.json");
    }

    private static void read(Map<ResourceLocation, List<TagLoader.EntryWithSource>> files,
            String name, InputStream input) {
        int namespaceEnd = name.indexOf('/', "data/".length());
        String namespace = name.substring("data/".length(), namespaceEnd);
        String path = name.substring(namespaceEnd + "/tags/blocks/".length(), name.length() - ".json".length());
        var file = TagFile.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))).getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
        files.put(new ResourceLocation(namespace, path), file.entries().stream()
                .map(entry -> new TagLoader.EntryWithSource(entry, "vanilla")).toList());
    }
}
