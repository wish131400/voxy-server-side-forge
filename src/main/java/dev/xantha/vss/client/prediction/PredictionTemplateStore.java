package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.StructureTemplateDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;

/** Content-addressed cache. Every read is checked against the current server manifest. */
final class PredictionTemplateStore {
    private static final long DISK_BYTES = 512L * 1024 * 1024;
    private final Path directory;
    private int writes;

    PredictionTemplateStore(Path directory) { this.directory = directory; }

    byte[] read(StructureTemplateDigest digest) {
        Path file = directory.resolve(digest.sha256() + ".nbt");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != digest.bytes()) return null;
            byte[] data;
            try (var input = Files.newInputStream(file)) { data = digest.read(input); }
            try { Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); }
            catch (IOException readOnlyCache) { /* Verified bytes remain usable. */ }
            return data;
        } catch (IOException failure) { return null; }
    }

    void write(StructureTemplateDigest digest, byte[] data) throws IOException {
        if (!digest.matches(data)) throw new IOException("Unverified template cache write");
        Files.createDirectories(directory);
        Path file = directory.resolve(digest.sha256() + ".nbt");
        Path temporary = Files.createTempFile(directory, digest.sha256(), ".tmp");
        try {
            Files.write(temporary, data);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
        if (writes++ % 32 == 0) trim();
    }

    private void trim() throws IOException {
        record Cached(Path path, long bytes, long modified) { }
        var files = new java.util.ArrayList<Cached>();
        long total = 0;
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.nbt")).toList()) {
                long size = Files.size(file);
                total += size;
                files.add(new Cached(file, size, Files.getLastModifiedTime(file).toMillis()));
            }
        }
        files.sort(Comparator.comparingLong(Cached::modified));
        for (Cached file : files) {
            if (total <= DISK_BYTES) break;
            Files.deleteIfExists(file.path());
            total -= file.bytes();
        }
    }
}
