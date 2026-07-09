package com.tndmadman.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlayerRegistry {
    private static final Map<String, PlayerInfo> PLAYERS = new LinkedHashMap<>();
    private static String localId = "SOLO";

    private PlayerRegistry() { }

    static void reset(String id, String name, int rgb) {
        PLAYERS.clear();
        localId = id;
        register(id, name, rgb, true);
    }

    static void register(String id, String name, int rgb, boolean local) {
        if (id == null || id.isBlank()) return;
        if (!local && "WAIT".equals(id)) return;
        if (local) {
            if (!id.equals(localId)) PLAYERS.remove(localId);
            localId = id;
            if (!"WAIT".equals(id)) PLAYERS.remove("WAIT");
        }
        PLAYERS.put(id, new PlayerInfo(id, Config.clean(name), rgb, local));
    }

    static void remove(String id) { PLAYERS.remove(id); }
    static boolean isLocal(String id) { return id != null && id.equals(localId); }
    static String localId() { return localId; }
    static Color color(String id) {
        PlayerInfo p = PLAYERS.get(id);
        return new Color(p == null ? 0x888888 : p.rgb());
    }
    static String name(String id) {
        PlayerInfo p = PLAYERS.get(id);
        return p == null ? id : p.name();
    }
    static List<PlayerInfo> snapshotPlayers() { return new ArrayList<>(PLAYERS.values()); }
}
