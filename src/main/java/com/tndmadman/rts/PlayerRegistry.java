package com.tndmadman.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class PlayerRegistry {
    private static final RegistryState DEFAULT = new RegistryState();
    private static final Map<World, RegistryState> BY_WORLD = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<RegistryState> ACTIVE = ThreadLocal.withInitial(() -> DEFAULT);
    private static final ThreadLocal<World> ACTIVE_WORLD = new ThreadLocal<>();

    private PlayerRegistry() { }

    static void activate(World world) {
        ACTIVE_WORLD.set(world);
        SkirmishRuntime.activate(world);
        ACTIVE.set(world == null ? DEFAULT : BY_WORLD.computeIfAbsent(world, ignored -> new RegistryState()));
    }

    static World activeWorld() { return ACTIVE_WORLD.get(); }

    static void reset(String id, String name, int rgb) {
        RegistryState state = state();
        state.players.clear();
        state.localId = id;
        register(id, name, rgb, true);
    }

    static void register(String id, String name, int rgb, boolean local) {
        if (id == null || id.isBlank()) return;
        if (!local && "WAIT".equals(id)) return;
        RegistryState state = state();
        if (local) {
            if (!id.equals(state.localId)) state.players.remove(state.localId);
            state.localId = id;
            if (!"WAIT".equals(id)) state.players.remove("WAIT");
        }
        state.players.put(id, new PlayerInfo(id, Config.clean(name), rgb, local));
    }

    static void remove(String id) { state().players.remove(id); }
    static boolean isLocal(String id) { return id != null && id.equals(state().localId); }
    static String localId() { return state().localId; }
    static Color color(String id) {
        PlayerInfo p = state().players.get(id);
        return new Color(p == null ? 0x888888 : p.rgb());
    }
    static String name(String id) {
        PlayerInfo p = state().players.get(id);
        return p == null ? id : p.name();
    }
    static List<PlayerInfo> snapshotPlayers() { return new ArrayList<>(state().players.values()); }

    private static RegistryState state() { return ACTIVE.get(); }

    private static final class RegistryState {
        final Map<String, PlayerInfo> players = new LinkedHashMap<>();
        String localId = "SOLO";
    }
}
