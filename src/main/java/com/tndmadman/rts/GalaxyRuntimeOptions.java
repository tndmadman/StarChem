package com.tndmadman.rts;

final class GalaxyRuntimeOptions {
    private static volatile int copiesPerTemplate = 1;
    private static volatile GalaxyGenerationSettings generationSettings;
    private static volatile Long generationSeedOverride;
    private static volatile boolean explicitGenerationOverride;

    private GalaxyRuntimeOptions() { }

    static void configure(Config config) {
        int configured = config == null ? 1 : config.galaxyCopies;
        copiesPerTemplate = Math.max(1, Math.min(2, ScenarioLaunch.galaxyCopies(configured)));
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

    static boolean configureGeneration(GalaxyGenerationSettings settings, long seed) {
        GalaxyGenerationSettings normalized = settings == null
                ? GalaxyGenerationSettings.legacy(copiesPerTemplate) : settings;
        boolean changed = !explicitGenerationOverride || generationSeedOverride == null
                || generationSeedOverride.longValue() != seed || !normalized.equals(generationSettings);
        generationSettings = normalized;
        generationSeedOverride = seed;
        explicitGenerationOverride = true;
        return changed;
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
