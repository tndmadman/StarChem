package com.tndmadman.rts;

import java.util.Locale;

/** Initializes bounded match diplomacy and assigns newly registered owners to teams. */
final class DiplomacyBootstrap {
    private static final String HUMAN_COOP_TEAM = "HUMANS";
    private static final String TEAM_ALPHA = "ALPHA";
    private static final String TEAM_BETA = "BETA";

    private DiplomacyBootstrap() { }

    static void initialize(World world) {
        if (world == null || DiplomacySystem.mode(world) != DiplomacySystem.MatchMode.FFA) return;
        String raw = firstNonBlank(System.getProperty("starchem.diplomacyMode"),
                System.getenv("STARCHEM_DIPLOMACY_MODE"));
        DiplomacySystem.MatchMode mode = parseMode(raw);
        if (mode == DiplomacySystem.MatchMode.FFA) return;
        boolean friendlyFire = booleanOption("starchem.friendlyFire", "STARCHEM_FRIENDLY_FIRE", false);
        boolean sharedVision = booleanOption("starchem.sharedVision", "STARCHEM_SHARED_VISION", true);
        boolean sharedVictory = booleanOption("starchem.sharedVictory", "STARCHEM_SHARED_VICTORY", true);
        DiplomacySystem.configure(world, mode, friendlyFire, sharedVision, sharedVictory);
        defineDefaultTeams(world, mode);
    }

    static void assignRegisteredOwner(World world, String ownerId, int rgb) {
        if (world == null || ownerId == null || ownerId.isBlank() || "WAIT".equals(ownerId)) return;
        initialize(world);
        if (DiplomacySystem.teamId(world, ownerId).isBlank()) {
            DiplomacySystem.MatchMode mode = DiplomacySystem.mode(world);
            if (NpcRules.isNpcFaction(ownerId)) {
                String teamId = "NPC_" + sanitize(ownerId);
                DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition(teamId,
                        rawPlayerName(ownerId), rgb));
                DiplomacySystem.assignTeam(world, ownerId, teamId);
            } else {
                switch (mode) {
                    case COOP_VS_NPC -> DiplomacySystem.assignTeam(world, ownerId, HUMAN_COOP_TEAM);
                    case FIXED_TEAMS, LOCKED_ALLIANCES -> DiplomacySystem.assignTeam(world, ownerId,
                            playerOrdinal(ownerId) % 2 == 0 ? TEAM_BETA : TEAM_ALPHA);
                    case FFA -> { }
                }
            }
        }
        syncIntelAlliances(world, ownerId);
    }

    private static void syncIntelAlliances(World world, String ownerId) {
        for (PlayerInfo other : PlayerRegistry.snapshotPlayers()) {
            if (other == null || other.id() == null || other.id().equals(ownerId)) continue;
            boolean shared = DiplomacySystem.sharesVision(world, ownerId, other.id());
            IntelWarfareSystem.setIntelAlliance(world, ownerId, other.id(), shared);
        }
    }

    private static String rawPlayerName(String ownerId) {
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player != null && ownerId.equals(player.id())) return player.name();
        }
        return ownerId;
    }

    private static void defineDefaultTeams(World world, DiplomacySystem.MatchMode mode) {
        if (mode == DiplomacySystem.MatchMode.COOP_VS_NPC) {
            DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition(
                    HUMAN_COOP_TEAM, "Human Coalition", 0x50BEFF));
        } else if (mode == DiplomacySystem.MatchMode.FIXED_TEAMS
                || mode == DiplomacySystem.MatchMode.LOCKED_ALLIANCES) {
            DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition(
                    TEAM_ALPHA, "Team Alpha", 0x50BEFF));
            DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition(
                    TEAM_BETA, "Team Beta", 0xFF7A70));
        }
    }

    private static DiplomacySystem.MatchMode parseMode(String value) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (clean) {
            case "TEAM", "TEAMS", "FIXED_TEAM", "FIXED_TEAMS" -> DiplomacySystem.MatchMode.FIXED_TEAMS;
            case "COOP", "CO_OP", "COOP_VS_NPC" -> DiplomacySystem.MatchMode.COOP_VS_NPC;
            case "ALLIANCE", "ALLIANCES", "LOCKED_ALLIANCES" -> DiplomacySystem.MatchMode.LOCKED_ALLIANCES;
            default -> DiplomacySystem.MatchMode.FFA;
        };
    }

    private static boolean booleanOption(String property, String environment, boolean fallback) {
        String value = firstNonBlank(System.getProperty(property), System.getenv(environment));
        if (value == null || value.isBlank()) return fallback;
        if ("1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)) return true;
        if ("0".equals(value) || "false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private static int playerOrdinal(String ownerId) {
        int value = 0;
        for (int i = 0; i < ownerId.length(); i++) {
            char c = ownerId.charAt(i);
            if (Character.isDigit(c)) value = Math.min(10_000, value * 10 + (c - '0'));
        }
        return value <= 0 ? Math.abs(ownerId.hashCode()) : value;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
