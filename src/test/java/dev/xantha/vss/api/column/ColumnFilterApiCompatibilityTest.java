package dev.xantha.vss.api.column;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColumnFilterApiCompatibilityTest {
    @TempDir Path directory;

    @Test void oldCompiledCallerLinksToCurrentConstructorAndAccessors() throws Exception {
        Path sources = Files.createDirectories(directory.resolve("old-api/dev/xantha/vss/api/column"));
        Path oldApi = sources.resolve("ColumnInput.java");
        Files.writeString(oldApi, """
                package dev.xantha.vss.api.column;
                public final class ColumnInput {
                    public record Section(int sectionY, boolean onlyAir, byte[] blockLight, byte[] skyLight) {
                        public boolean hasBlockLight() { return blockLight != null; }
                        public boolean hasSkyLight() { return skyLight != null; }
                    }
                }
                """);
        Path oldCaller = directory.resolve("old-api/LegacyColumnFilter.java");
        Files.writeString(oldCaller, """
                import dev.xantha.vss.api.column.ColumnInput;
                public final class LegacyColumnFilter {
                    public static ColumnInput.Section create(byte[] light) {
                        return new ColumnInput.Section(-2, false, light, null);
                    }
                    public static boolean read(ColumnInput.Section section) {
                        return section.sectionY() == -2 && !section.onlyAir()
                                && section.blockLight()[0] == 7 && section.skyLight() == null
                                && section.hasBlockLight() && !section.hasSkyLight();
                    }
                }
                """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The build's JDK must provide javac for the binary compatibility fixture");
        var diagnostics = new StringWriter();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            assertTrue(compiler.getTask(diagnostics, files, null,
                    List.of("--release", "17", "-proc:none", "-classpath", directory.resolve("old-api").toString(),
                            "-d", classes.toString()), null,
                    files.getJavaFileObjects(oldApi.toFile(), oldCaller.toFile())).call(), diagnostics.toString());
        }
        // Parent-first loading resolves the old caller's descriptors to today's API,
        // rather than loading the old API classes used only to compile the fixture.
        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> caller = loader.loadClass("LegacyColumnFilter");
            byte[] light = new byte[2048]; light[0] = 7;
            var section = (ColumnInput.Section) caller.getMethod("create", byte[].class).invoke(null, (Object) light);
            light[0] = 42;
            assertSame(ColumnInput.Section.class, section.getClass());
            assertEquals(Integer.MIN_VALUE, section.maxBlockY());
            assertEquals(Boolean.TRUE, caller.getMethod("read", ColumnInput.Section.class).invoke(null, section));
        }
    }

    @Test void newHeightMetadataPreservesLightOwnershipAndLegacyUnknownState() {
        byte[] blockLight = new byte[2048]; blockLight[0] = 7;
        byte[] skyLight = new byte[2048]; skyLight[0] = 12;
        var section = new ColumnInput.Section(-2, false, -17, blockLight, skyLight);
        blockLight[0] = 42; skyLight[0] = 42;
        section.blockLight()[0] = 99; section.skyLight()[0] = 99;
        assertEquals(-17, section.maxBlockY());
        assertEquals(7, section.blockLight()[0]); assertEquals(12, section.skyLight()[0]);
        assertFalse(section.onlyAir()); assertTrue(section.hasBlockLight()); assertTrue(section.hasSkyLight());
        var unknown = new ColumnInput.Section(-2, false, null, null);
        var air = new ColumnInput.Section(-2, true, null, null);
        assertEquals(Integer.MIN_VALUE, unknown.maxBlockY()); assertFalse(unknown.onlyAir());
        assertEquals(Integer.MIN_VALUE, air.maxBlockY()); assertTrue(air.onlyAir());
        assertThrows(IllegalArgumentException.class, () -> new ColumnInput.Section(0, false, 15, new byte[1], null));
    }
}
