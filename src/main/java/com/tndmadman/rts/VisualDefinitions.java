package com.tndmadman.rts;

/** Renderer-only content contracts for non-ship world visuals. */
record StationVisualDefinition(String id, String assetPath, double assetScale) {
    StationVisualDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("station visual id is required");
        if (!Double.isFinite(assetScale) || assetScale <= 0) assetScale = 1.0;
    }

    static StationVisualDefinition conventional(String stationId) {
        String id = VisualCatalog.safeId(stationId);
        return new StationVisualDefinition(id, "/art/stations/" + id + ".png", 1.0);
    }
}

record SystemVisualDefinition(String id, String backgroundAssetPath, long visualSeed) {
    SystemVisualDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("system visual id is required");
    }

    static SystemVisualDefinition conventional(String systemId) {
        String id = VisualCatalog.safeId(systemId);
        return new SystemVisualDefinition(id, "/art/backgrounds/" + id + ".png", stableSeed(id));
    }

    private static long stableSeed(String id) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) {
            hash ^= id.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}

record CelestialVisualDefinition(String id, String surfaceAssetPath, String cloudAssetPath,
                                 String detailAssetPath) {
    CelestialVisualDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("celestial visual id is required");
    }

    static CelestialVisualDefinition conventional(String bodyId) {
        String id = VisualCatalog.safeId(bodyId);
        String root = "/art/planets/" + id;
        return new CelestialVisualDefinition(id, root + ".png", root + "-clouds.png",
                root + "-detail.png");
    }
}

record ResourceVisualDefinition(String id, String detailAssetPath, long visualSeed) {
    ResourceVisualDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("resource visual id is required");
    }

    static ResourceVisualDefinition conventional(String resourceId) {
        String id = VisualCatalog.safeId(resourceId);
        long seed = 0x9E3779B97F4A7C15L ^ id.hashCode();
        return new ResourceVisualDefinition(id, "/art/materials/resources/" + id + ".png", seed);
    }
}
