package com.tndmadman.rts;

final class GalaxyRuntimeOptions {
    private static volatile int copiesPerTemplate = 1;
    private static volatile GalaxyGenerationSettings generationSettings;
    private static volatile Long generationSeedOverride;
    private static volatile boolean explicitGenerationOverride;

    private GalaxyRuntimeOptions() { }

    static void configure(Config config) {
        copiesPerTemplate = Math.max(1, Math.min(2, config == null ? 1 : config.galaxyCopies));
        if (!explicitGenerationOverride) {
            generationSettings = GalaxyGenerationSettings.load(copiesPerTemplate);
            generationSeedOverride = null;
        }
    }

    static void configureCopies(int copies) {
        copiesPerTemplate = Math.max(1, Math.min(2, copies));
        explicitGenerationOverride = false;
        generationSeedOverride = null;
        generationSettings = GalaxyGenerationSettings.load(copiesPerTemplate);
    }

    static void configureGeneration(GalaxyGenerationSettings settings, long seed) {
        generationSettings = settings == null ? GalaxyGenerationSettings.legacy(copiesPerTemplate) : settings;
        generationSeedOverride = seed;
        explicitGenerationOverride = true;
    }

    static void clearGenerationOverride() {
        explicitGenerationOverride = false;
        generationSeedOverride = null;
        generationSettings = GalaxyGenerationSettings.load(copiesPerTemplate);
    }

    static int copiesPerTemplate() { return copiesPerTemplate; }

    static GalaxyGenerationSettings generationSettings() {
        GalaxyGenerationSettings current = generationSettings;
        if (current != null) return current;
        current = GalaxyGenerationSettings.load(copiesPerTemplate);
        generationSettings = current;
        return current;
    }

    static long generationSeed(long fallback) {
        Long override = generationSeedOverride;
        return override == null ? GalaxyGenerationSettings.configuredSeed(fallback) : override;
    }
}
