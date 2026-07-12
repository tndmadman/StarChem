package com.tndmadman.rts;

final class GalaxyRuntimeOptions {
    private static volatile int copiesPerTemplate = 1;

    private GalaxyRuntimeOptions() { }

    static void configure(Config config) {
        configureCopies(config == null ? 1 : config.galaxyCopies);
    }

    static void configureCopies(int copies) {
        copiesPerTemplate = Math.max(1, Math.min(2, copies));
    }

    static int copiesPerTemplate() { return copiesPerTemplate; }
}
