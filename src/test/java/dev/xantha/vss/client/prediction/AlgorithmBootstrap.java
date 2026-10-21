package dev.xantha.vss.client.prediction;

/** Legacy fixture name kept for existing CPU/GPU regressions; initialization has one implementation. */
final class AlgorithmBootstrap {
    private AlgorithmBootstrap() { }
    static void bootstrapMinecraft() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
}
