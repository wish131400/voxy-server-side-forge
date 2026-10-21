package dev.xantha.vss.client.prediction;

/** Completes runtime state on freshly decoded pools; never touches the live registry. */
final class PredictionTemplatePoolRuntime {
    private PredictionTemplatePoolRuntime() { }

    static void initialize(Iterable<?> pools, ClassLoader loader) {
        Class<?> access;
        try {
            access = Class.forName("dev.worldgen.lithostitched.duck.StructurePoolAccess", false, loader);
        } catch (ClassNotFoundException absent) {
            return;
        }
        initialize(pools, access);
    }

    static void initialize(Iterable<?> pools, Class<?> access) {
        try {
            var templates = access.getMethod("getLithostitchedTemplates");
            var compile = access.getMethod("compileRawTemplates");
            for (Object pool : pools) {
                if (!access.isInstance(pool)) continue;
                // Newer decoders may already complete this phase. Repeating
                // compileRawTemplates would double all placement weights.
                Object current = templates.invoke(pool);
                if (!(current instanceof Iterable<?> entries))
                    throw new IllegalStateException("Unsupported Lithostitched template list");
                if (!entries.iterator().hasNext()) compile.invoke(pool);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot initialize Lithostitched snapshot template pools", failure);
        }
    }
}
