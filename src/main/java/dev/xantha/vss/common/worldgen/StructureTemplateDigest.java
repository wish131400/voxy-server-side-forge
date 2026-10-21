package dev.xantha.vss.common.worldgen;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Identity of the exact compressed NBT resource selected by the server. */
public record StructureTemplateDigest(String sha256, int bytes) {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int FRAGMENT_BYTES = 128 * 1024;

    public StructureTemplateDigest {
        if (!validHash(sha256) || bytes <= 0 || bytes > MAX_BYTES)
            throw new IllegalArgumentException("Invalid structure template identity");
    }

    public static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }

    public boolean matches(byte[] data) {
        return data != null && data.length == bytes && sha256.equals(hash(data));
    }

    public static StructureTemplateDigest inspect(InputStream input, byte[] scratch) throws IOException {
        MessageDigest hash = digest();
        int total = 0, count;
        while ((count = input.read(scratch)) != -1) {
            total += count;
            if (total > MAX_BYTES) throw new IOException("Structure template exceeds " + MAX_BYTES + " bytes");
            hash.update(scratch, 0, count);
        }
        if (total == 0) throw new IOException("Empty structure template");
        return new StructureTemplateDigest(HexFormat.of().formatHex(hash.digest()), total);
    }

    public byte[] read(InputStream input) throws IOException {
        byte[] data = input.readNBytes(bytes + 1);
        if (!matches(data)) throw new IOException("Structure template differs from server identity");
        return data;
    }
}
