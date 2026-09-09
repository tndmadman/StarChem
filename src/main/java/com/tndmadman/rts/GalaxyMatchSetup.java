package com.tndmadman.rts;

import java.util.Map;

final class GalaxyMatchSetup {
    private GalaxyMatchSetup() { }

    static GalaxyGenerationSettings procedural(GalaxySizePreset size, GalaxyTopologyStyle topology) {
        GalaxySizePreset normalizedSize = size == null ? GalaxySizePreset.MEDIUM : size;
        GalaxyTopologyStyle normalizedTopology = topology == null ? GalaxyTopologyStyle.MIXED : topology;
        return new GalaxyGenerationSettings(true, normalizedSize.systemCount(), normalizedTopology,
                0.35, 0.20, 1.0, 0.15, 0.15, 0.5, 3, Map.of());
    }

    static long seed(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || text.equalsIgnoreCase("random") || text.equalsIgnoreCase("auto")) {
            return System.nanoTime() ^ System.currentTimeMillis();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < text.length(); i++) {
                hash ^= text.charAt(i);
                hash *= 0x100000001b3L;
            }
            return hash;
        }
    }

    static boolean randomSeedRequested(String value) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() || text.equalsIgnoreCase("random") || text.equalsIgnoreCase("auto");
    }
}
