package dev.xantha.vss.common.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class StructureTemplateDigestTest {
    @Test void digestUsesExactCompressedResourceBytesAndChecksLength() throws Exception {
        byte[] data = {1, 2, 3};
        var digest = StructureTemplateDigest.inspect(new ByteArrayInputStream(data), new byte[2]);
        assertEquals(StructureTemplateDigest.hash(data), digest.sha256());
        assertArrayEquals(data, digest.read(new ByteArrayInputStream(data)));
        assertThrows(IOException.class, () -> digest.read(new ByteArrayInputStream(new byte[] {1, 2})));
        assertThrows(IOException.class, () -> digest.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
        assertThrows(IOException.class, () -> digest.read(new ByteArrayInputStream(new byte[] {1, 9, 3})));
    }
    @Test void emptyOversizedAndMalformedIdentitiesAreRejected() {
        assertThrows(IOException.class, () -> StructureTemplateDigest.inspect(new ByteArrayInputStream(new byte[0]), new byte[32]));
        assertThrows(IOException.class, () -> StructureTemplateDigest.inspect(
                new ByteArrayInputStream(new byte[StructureTemplateDigest.MAX_BYTES + 1]), new byte[32768]));
        assertFalse(StructureTemplateDigest.validHash("../template"));
        assertThrows(IllegalArgumentException.class, () -> new StructureTemplateDigest("A".repeat(64), 1));
        assertThrows(IllegalArgumentException.class, () -> new StructureTemplateDigest("a".repeat(64), 0));
    }
}
