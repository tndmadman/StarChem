package com.tndmadman.rts;

import java.util.*;

final class WorldSystemState {
    private static final Set<WorldSystemState> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    final String id;
    final StarSystemDefinition definition;
    final CelestialSystem celestials;
    final List<ResourceNode> resources = new ArrayList<>();
    final Map<String, Unit> units = new LinkedHashMap<>();
    final Map<String, Base> bases = new LinkedHashMap<>();
    final List<ProjectileShot> shots = new ArrayList<>();
    final List<WorldItem> items = new ArrayList<>();
    final List<WormholeGate> wormholes = new ArrayList<>();

    WorldSystemState(String id, StarSystemDefinition definition, CelestialSystem celestials) {
        this.id = id;
        this.definition = definition;
        this.celestials = celestials;
        LIVE.add(this);
    }

    static void updateInactive(String activeId, double dt) {
        if (dt == 0) return;
        for (WorldSystemState state : new ArrayList<>(LIVE)) {
            if (state == null || state.id.equals(activeId)) continue;
            state.celestials.update(dt);
            ResourceSpawner.update(state.resources, state.celestials, dt);
        }
    }

    int width() { return definition.width(); }
    int height() { return definition.height(); }
}
