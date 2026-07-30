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
        DiplomacyBootstrap.initialize(world);
    }

    static World activeWorld() { return ACTIVE_WORLD.get(); }

    static void reset(String id, String name, int rgb) {
        RegistryState state = state();
        state.players.clear();
        state.localId = id;
        System.out.println("[CONNECTION][CLIENT] Resetting player registry for local owner id=" + safe(id));
        register(id, name, rgb, true);
    }

    static void register(String id, String name, int rgb, boolean local) {
        if (id == null || id.isBlank()) {
            System.err.println("[CONNECTION][REGISTRY] Rejected player registration: blank player id.");
            return;
        }
        if (!local && "WAIT".equals(id)) {
            System.err.println("[CONNECTION][SERVER] Ignored invalid remote WAIT registration attempt.");
            return;
        }
        String side = local ? "CLIENT" : "SERVER";
        System.out.println("[CONNECTION][" + side + "] Registering player id=" + safe(id)
                + " name=" + safe(Config.clean(name)) + ".");
        RegistryState state = state();
        try {
            if (local) {
                if (!id.equals(state.localId)) state.players.remove(state.localId);
                state.localId = id;
                if (!"WAIT".equals(id)) state.players.remove("WAIT");
            }
            state.players.put(id, new PlayerInfo(id, Config.clean(name), rgb, local));
            System.out.println("[CONNECTION][" + side + "] Player record stored; assigning diplomacy owner state.");
            DiplomacyBootstrap.assignRegisteredOwner(activeWorld(), id, rgb);
            System.out.println("[CONNECTION][" + side + "] Player registration completed id=" + safe(id) + ".");
        } catch (RuntimeException ex) {
            System.err.println("[CONNECTION][" + side + "][FAILURE] Player registration failed id="
                    + safe(id) + " at " + ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
            ex.printStackTrace(System.err);
            throw ex;
        }
    }

    static void remove(String id) {
        if (state().players.remove(id) != null) {
            System.out.println("[CONNECTION][REGISTRY] Removed player id=" + safe(id) + ".");
        }
    }
    static boolean isLocal(String id) { return id != null && id.equals(state().localId); }
    static String localId() { return state().localId; }
    static Color color(String id) {
        World world = activeWorld();
        DiplomacySystem.TeamDefinition team = DiplomacySystem.team(world, id);
        if (team != null && DiplomacySystem.mode(world) != DiplomacySystem.MatchMode.FFA) {
            return new Color(team.rgb());
        }
        PlayerInfo p = state().players.get(id);
        return new Color(p == null ? 0x888888 : p.rgb());
    }
    static String name(String id) {
        PlayerInfo p = state().players.get(id);
        String base = p == null ? id : p.name();
        World world = activeWorld();
        DiplomacySystem.TeamDefinition team = DiplomacySystem.team(world, id);
        if (team == null || DiplomacySystem.mode(world) == DiplomacySystem.MatchMode.FFA) return base;
        return base + " [" + team.displayName() + "]";
    }
    static List<PlayerInfo> snapshotPlayers() { return new ArrayList<>(state().players.values()); }

    private static RegistryState state() { return ACTIVE.get(); }

    private static String safe(String value) {
        if (value == null) return "<null>";
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').trim();
        return clean.isBlank() ? "<blank>" : clean;
    }

    private static final class RegistryState {
        final Map<String, PlayerInfo> players = new LinkedHashMap<>();
        String localId = "SOLO";
    }
}
