package com.tndmadman.rts;

final class GalaxyRuntimeOptions {
    private static volatile int copiesPerTemplate = 1;
    private static volatile GalaxyGenerationSettings generationSettings;

    private GalaxyRuntimeOptions() { }

    static void configure(Config config) {
        configureCopies(config == null ? 1 : config.galaxyCopies);
    }

    static void configureCopies(int copies) {
        copiesPerTemplate = Math.max(1, Math.min(2, copies));
        generationSettings = GalaxyGenerationSettings.load(copiesPerTemplate);
    }

    static void configureGeneration(GalaxyGenerationSettings settings) {
        generationSettings = settings == null ? GalaxyGenerationSettings.legacy(copiesPerTemplate) : settings;
    }

    static int copiesPerTemplate() { return copiesPerTemplate; }

    static GalaxyGenerationSettings generationSettings() {
        GalaxyGenerationSettings current = generationSettings;
        if (current != null) return current;
        current = GalaxyGenerationSettings.load(copiesPerTemplate);
        generationSettings = current;
        return current;
    }
}
