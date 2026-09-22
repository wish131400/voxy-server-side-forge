# Forge LZ4 dependency

Minecraft 1.20.1 does not provide the LZ4 dependency used by the mesh compressor.
VSS embeds the unmodified Maven artifact `org.lz4:lz4-java:1.8.0` from
`src/main/jarjar/lz4-java-1.8.0.jar` using Forge Jar-in-Jar metadata. The same
file is an `implementation` dependency for compilation and tests. The explicit
`.gitignore` exception keeps the local file dependency available in checkouts.

- Upstream: https://github.com/lz4/lz4-java/tree/1.8.0
- Maven artifact: https://repo.maven.apache.org/maven2/org/lz4/lz4-java/1.8.0/lz4-java-1.8.0.jar
- SHA-256: `d74a3334fb35195009b338a951f918203d6bbca3d1d359033dc33edd1cadc9ef`
- License: Apache-2.0, included as `META-INF/jarjar/lz4-java-LICENSE.txt`.

The compressor calls `LZ4Factory.fastestJavaInstance()`, so it does not require
the artifact's native LZ4 library. An available and self-tested zstd-jni bridge
still takes priority. LZ4 is the fallback when that bridge is unavailable.
