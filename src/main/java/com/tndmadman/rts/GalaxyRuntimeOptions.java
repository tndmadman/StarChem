package com.tndmadman.rts;

final class GalaxyRuntimeOptions {
    private static volatile int copiesPerTemplate = 1;

    private GalaxyRuntimeOptions() { }

    static void configure(Config config) {
        copiesPerTemplate = config == null ? 1 : Math.max(1, Math.min(2, config.galaxyCopies));
    }

    static int copiesPerTemplate() { return copiesPerTemplate; }
}
