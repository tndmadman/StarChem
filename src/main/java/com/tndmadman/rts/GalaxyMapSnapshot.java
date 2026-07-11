package com.tndmadman.rts;

import java.util.List;

record GalaxyMapSnapshot(String activeSystemId, List<GalaxyMapSystem> systems, List<GalaxyMapLink> links) {
    boolean empty() { return systems == null || systems.isEmpty(); }
}

record GalaxyMapSystem(
        String id,
        String name,
        String templateId,
        SystemLifetime lifetime,
        int ships,
        int bases,
        int resources,
        int localShips,
        int localBases,
        boolean active,
        boolean home,
        boolean special,
        String controllerId,
        String controllerName,
        SystemControlStatus controlStatus,
        double captureProgress,
        int controlColorRgb) {

    GalaxyMapSystem(String id, String name, int ships, int bases, int resources, int localShips, int localBases,
                    boolean active, boolean home, boolean special) {
        this(id, name, id, home ? SystemLifetime.PLAYER_HOME : SystemLifetime.STATIC,
                ships, bases, resources, localShips, localBases, active, home, special,
                "", "Neutral", home ? SystemControlStatus.PROTECTED : SystemControlStatus.NEUTRAL, 0, 0x8A96A3);
    }

    boolean hasLocalAssets() { return localShips > 0 || localBases > 0; }
    boolean staticSystem() { return lifetime == SystemLifetime.STATIC; }

    String controlLabel() {
        return switch (controlStatus) {
            case NEUTRAL -> "Neutral";
            case CONTESTED -> "Contested";
            case CAPTURING -> "Capturing " + Math.max(0, Math.min(100, (int)Math.round(captureProgress * 100))) + "%";
            case CONTROLLED -> "Controlled by " + safeControllerName();
            case PROTECTED -> "Protected home";
        };
    }

    private String safeControllerName() {
        return controllerName == null || controllerName.isBlank() ? controllerId == null || controllerId.isBlank() ? "Unknown" : controllerId : controllerName;
    }
}

record GalaxyMapLink(String fromSystemId, String toSystemId) { }
