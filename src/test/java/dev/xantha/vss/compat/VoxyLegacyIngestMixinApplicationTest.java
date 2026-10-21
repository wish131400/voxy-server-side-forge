package dev.xantha.vss.compat;

class VoxyLegacyIngestMixinApplicationTest extends VoxyIngestMixinApplicationTest {
    @Override String jarProperty() { return "vss.legacyVoxyJar"; }
}
