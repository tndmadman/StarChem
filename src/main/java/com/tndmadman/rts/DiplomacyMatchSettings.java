package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded host-selected diplomacy policy carried by saves and WORLDINFO. */
record DiplomacyMatchSettings(DiplomacySystem.MatchMode mode,
                              boolean friendlyFire,
                              boolean sharedVision,
                              boolean sharedVictory) {
    DiplomacyMatchSettings {
        mode = mode == null ? DiplomacySystem.MatchMode.FFA : mode;
        if (mode == DiplomacySystem.MatchMode.FFA) {
            friendlyFire = false;
            sharedVision = false;
            sharedVictory = false;
        } else if (mode == DiplomacySystem.MatchMode.COOP_VS_NPC) {
            sharedVision = true;
            sharedVictory = true;
        }
    }

    static DiplomacyMatchSettings ffa() {
        return new DiplomacyMatchSettings(DiplomacySystem.MatchMode.FFA, false, false, false);
    }

    static DiplomacyMatchSettings teams() {
        return new DiplomacyMatchSettings(DiplomacySystem.MatchMode.FIXED_TEAMS, false, true, true);
    }

    static DiplomacyMatchSettings coop() {
        return new DiplomacyMatchSettings(DiplomacySystem.MatchMode.COOP_VS_NPC, false, true, true);
    }

    static DiplomacyMatchSettings lockedAlliances() {
        return new DiplomacyMatchSettings(DiplomacySystem.MatchMode.LOCKED_ALLIANCES, false, true, true);
    }

    Map<String,Object> saveMap() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("friendlyFire", friendlyFire);
        out.put("sharedVision", sharedVision);
        out.put("sharedVictory", sharedVictory);
        return out;
    }

    static DiplomacyMatchSettings fromSaved(Object value, DiplomacyMatchSettings fallback) {
        DiplomacyMatchSettings safe = fallback == null ? ffa() : fallback;
        if (!(value instanceof Map<?,?> map)) return safe;
        DiplomacySystem.MatchMode mode = parseMode(map.get("mode"), safe.mode());
        return new DiplomacyMatchSettings(mode,
                bool(map.get("friendlyFire"), safe.friendlyFire()),
                bool(map.get("sharedVision"), safe.sharedVision()),
                bool(map.get("sharedVictory"), safe.sharedVictory()));
    }

    /** Compact, bounded WORLDINFO suffix. */
    String packetField() {
        return mode.name() + "," + bit(friendlyFire) + bit(sharedVision) + bit(sharedVictory);
    }

    static DiplomacyMatchSettings fromPacketField(String value) {
        if (value == null || value.isBlank()) return ffa();
        String[] parts = value.split(",", -1);
        if (parts.length != 2 || parts[1].length() != 3) {
            throw new IllegalArgumentException("Malformed diplomacy settings.");
        }
        DiplomacySystem.MatchMode mode = parseMode(parts[0], DiplomacySystem.MatchMode.FFA);
        return new DiplomacyMatchSettings(mode,
                parts[1].charAt(0) == '1', parts[1].charAt(1) == '1', parts[1].charAt(2) == '1');
    }

    void apply(World world) {
        if (world == null) return;
        DiplomacySystem.configure(world, mode, friendlyFire, sharedVision, sharedVictory);
        if (DiplomacySystem.teams(world).isEmpty()) {
            DiplomacyBootstrap.defineDefaultTeams(world, mode);
        }
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player != null) DiplomacyBootstrap.assignRegisteredOwner(world, player.id(), player.rgb());
        }
    }

    String displayLabel() {
        return switch (mode) {
            case FFA -> "Free-for-all";
            case FIXED_TEAMS -> "Fixed teams";
            case COOP_VS_NPC -> "Co-op vs NPC";
            case LOCKED_ALLIANCES -> "Locked alliances";
        };
    }

    private static String bit(boolean value) { return value ? "1" : "0"; }

    private static DiplomacySystem.MatchMode parseMode(Object value, DiplomacySystem.MatchMode fallback) {
        if (value == null) return fallback;
        String clean = String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return DiplomacySystem.MatchMode.valueOf(clean);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) return true;
        if ("0".equals(text) || "false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)) return false;
        return fallback;
    }
}
