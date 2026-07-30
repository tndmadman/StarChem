package com.tndmadman.rts;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class SkirmishRuntime {
    private static final Map<World, State> BY_WORLD = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<World> ACTIVE_WORLD = new ThreadLocal<>();

    private SkirmishRuntime() { }

    static void bind(World world, SkirmishSettings settings) {
        if (world == null) return;
        SkirmishSettings normalized = settings == null ? SkirmishSettings.standard() : settings;
        BY_WORLD.put(world, new State(normalized, normalized.resolve(NpcRules.baseFactions())));
        normalized.diplomacy().apply(world);
        ObjectiveSystem.reconfigure(world, normalized);
        ACTIVE_WORLD.set(world);
    }

    static void activate(World world) {
        ACTIVE_WORLD.set(world);
        if (world != null) {
            State state = BY_WORLD.computeIfAbsent(world,
                    ignored -> new State(SkirmishSettings.standard(), NpcRules.baseFactions()));
            state.settings().diplomacy().apply(world);
            ObjectiveSystem.state(world);
        }
    }

    static SkirmishSettings settings(World world) {
        State state = world == null ? null : BY_WORLD.get(world);
        return state == null ? SkirmishSettings.standard() : state.settings();
    }

    static SkirmishSettings settings() { return settings(ACTIVE_WORLD.get()); }

    static List<NpcFaction> factions() {
        State state = BY_WORLD.get(ACTIVE_WORLD.get());
        return state == null ? NpcRules.baseFactions() : state.factions();
    }

    private record State(SkirmishSettings settings, List<NpcFaction> factions) { }
}
