package com.tndmadman.rts;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ViewedSystemRegistry {
    private static final Map<World, Set<String>> VIEWED = new WeakHashMap<>();

    private ViewedSystemRegistry() { }

    static synchronized void replace(World world, Collection<String> systemIds) {
        if (world == null) return;
        Set<String> clean = new LinkedHashSet<>();
        if (systemIds != null) {
            for (String systemId : systemIds) {
                if (systemId == null || systemId.isBlank() || systemId.contains("WAIT")) continue;
                clean.add(systemId);
            }
        }
        if (clean.isEmpty()) VIEWED.remove(world);
        else VIEWED.put(world, Set.copyOf(clean));
    }

    static synchronized Set<String> snapshot(World world) {
        Set<String> systems = VIEWED.get(world);
        return systems == null ? Set.of() : systems;
    }
}
