package com.tndmadman.rts;

import java.util.List;

record GalaxyMapSnapshot(String activeSystemId, List<GalaxyMapSystem> systems, List<GalaxyMapLink> links) {
    boolean empty() { return systems == null || systems.isEmpty(); }
}

record GalaxyMapSystem(
        String id,
        String name,
        int ships,
        int bases,
        int resources,
        int localShips,
        int localBases,
        boolean active,
        boolean home,
        boolean special) {
    boolean hasLocalAssets() { return localShips > 0 || localBases > 0; }
}

record GalaxyMapLink(String fromSystemId, String toSystemId) { }
