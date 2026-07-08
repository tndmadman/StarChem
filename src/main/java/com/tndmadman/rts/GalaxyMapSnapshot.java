package com.tndmadman.rts;

import java.util.List;

record GalaxyMapSnapshot(String activeSystemId, List<GalaxyMapSystem> systems, List<GalaxyMapLink> links) { }
record GalaxyMapSystem(String id, String name, int ships, int bases, int resources, boolean active, boolean home, boolean special) { }
record GalaxyMapLink(String fromSystemId, String toSystemId) { }
