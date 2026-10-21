package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldgenTemplateCatalogTest {
    @AfterEach void clear() { WorldgenTemplateCatalog.clear(); }

    @Test void catalogContainsLargeTemplatesWithoutEmbeddingNbtAndIsSharedBetweenDimensions() throws Exception {
        byte[] data = new byte[600 * 1024]; new java.util.Random(3).nextBytes(data);
        var resources = resources(Map.of("z:house", data, "a:tree", new byte[] {2}));
        var snapshot = WorldgenTemplateCatalog.snapshot(resources);
        assertEquals(2, snapshot.entries().size());
        assertEquals(3, snapshot.manifest().size());
        assertTrue(snapshot.manifest().toString().length() < 500);
        assertArrayEquals(data, snapshot.entries().get(ResourceLocation.parse("z:house")).read());
        assertSame(snapshot, WorldgenTemplateCatalog.snapshot(resources));
        assertSame(snapshot, WorldgenTemplateCatalog.current(snapshot.identity()));
        assertNull(WorldgenTemplateCatalog.current("b".repeat(64)));
    }

    @Test void contentAndRemovedResourcesChangeCatalogIdentityOnReload() {
        var before = WorldgenTemplateCatalog.snapshot(resources(Map.of("mod:house", new byte[] {1}, "mod:tree", new byte[] {2})));
        WorldgenTemplateCatalog.clear();
        assertNull(WorldgenTemplateCatalog.current(before.identity()));
        var after = WorldgenTemplateCatalog.snapshot(resources(Map.of("mod:house", new byte[] {3})));
        assertNotEquals(before.identity(), after.identity());
        assertFalse(after.manifest().has("mod:tree"));
    }

    @Test void effectiveServerPackWinsAndResourceChangesCannotBeServedWithOldHash() throws Exception {
        byte[] data = {7, 8, 9};
        var snapshot = WorldgenTemplateCatalog.snapshot(resources(Map.of("mod:house", data)));
        assertEquals(StructureTemplateDigest.hash(data), snapshot.entries().get(ResourceLocation.parse("mod:house")).digest().sha256());
        data[0] = 1;
        assertThrows(IOException.class, () -> snapshot.entries().get(ResourceLocation.parse("mod:house")).read());
    }

    private static ResourceManager resources(Map<String, byte[]> values) {
        var entries = new java.util.HashMap<ResourceLocation, Resource>();
        values.forEach((id, data) -> {
            var key = ResourceLocation.parse(id);
            key = key.withPath("structures/" + key.getPath() + ".nbt");
            entries.put(key, new Resource(null, () -> new ByteArrayInputStream(data)));
        });
        return new ResourceManager() {
            @Override public Set<String> getNamespaces() { return Set.of("mod"); }
            @Override public Optional<Resource> getResource(ResourceLocation id) { return Optional.ofNullable(entries.get(id)); }
            @Override public java.util.List<Resource> getResourceStack(ResourceLocation id) { return getResource(id).stream().toList(); }
            @Override public Map<ResourceLocation, Resource> listResources(String directory, Predicate<ResourceLocation> predicate) {
                var selected = new java.util.HashMap<ResourceLocation, Resource>();
                entries.forEach((id, resource) -> { if (id.getPath().startsWith(directory + "/") && predicate.test(id)) selected.put(id, resource); });
                return selected;
            }
            @Override public Map<ResourceLocation, java.util.List<Resource>> listResourceStacks(String dir, Predicate<ResourceLocation> filter) {
                throw new UnsupportedOperationException();
            }
            @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }
}
