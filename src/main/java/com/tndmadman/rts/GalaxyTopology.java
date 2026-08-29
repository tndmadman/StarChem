package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the effective galaxy topology visible/usable by one player.
 * Permanent coordinator links are always present. Dynamic event links are
 * added only when the authoritative event director has revealed them to the
 * requested owner. The permanent coordinator graph is never mutated.
 */
final class GalaxyTopology {
    private GalaxyTopology() { }

    static List<GalaxyMapLink> effectiveLinks(World world, String playerId,
                                               List<GalaxyMapLink> permanentLinks) {
        Map<String,GalaxyMapLink> links = new LinkedHashMap<>();
        if (permanentLinks != null) {
            for (GalaxyMapLink link : permanentLinks) add(links, link);
        }
        if (world != null && playerId != null && !playerId.isBlank()) {
            for (GalaxyMapLink link : GalaxyEventDirector.temporaryLinksFor(world, playerId)) add(links, link);
        }
        return List.copyOf(links.values());
    }

    static GalaxyMapSnapshot effectiveSnapshot(World world, String playerId) {
        if (world == null) return new GalaxyMapSnapshot("", List.of(), List.of());
        GalaxyMapSnapshot permanent = world.authoritativeGalaxyMapSnapshot();
        if (permanent == null) return new GalaxyMapSnapshot("", List.of(), List.of());
        return new GalaxyMapSnapshot(permanent.activeSystemId(), permanent.systems(),
                effectiveLinks(world, playerId, permanent.links()));
    }

    static boolean containsLink(List<GalaxyMapLink> links, String fromSystemId, String toSystemId) {
        if (links == null) return false;
        String key = key(fromSystemId, toSystemId);
        if (key.isBlank()) return false;
        for (GalaxyMapLink link : links) {
            if (link != null && key.equals(key(link.fromSystemId(), link.toSystemId()))) return true;
        }
        return false;
    }

    private static void add(Map<String,GalaxyMapLink> links, GalaxyMapLink link) {
        if (link == null) return;
        String from = clean(link.fromSystemId());
        String to = clean(link.toSystemId());
        String key = key(from, to);
        if (key.isBlank()) return;
        links.putIfAbsent(key, new GalaxyMapLink(from, to));
    }

    private static String key(String from, String to) {
        String a = clean(from);
        String b = clean(to);
        if (a.isBlank() || b.isBlank() || a.equals(b)) return "";
        return a.compareTo(b) <= 0 ? a + '\u0000' + b : b + '\u0000' + a;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
