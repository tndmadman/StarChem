package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * World-scoped authoritative relationship state.
 *
 * Combat, visibility, AI and command validation should query this class instead
 * of inferring hostility from different owner IDs.
 */
final class DiplomacySystem {
    enum Relationship {
        ALLIED,
        NEUTRAL,
        HOSTILE
    }

    enum MatchMode {
        FFA,
        FIXED_TEAMS,
        COOP_VS_NPC,
        LOCKED_ALLIANCES
    }

    record TeamDefinition(String id, String displayName, int rgb) {
        TeamDefinition {
            id = cleanId(id);
            displayName = Config.clean(displayName);
            if (id.isBlank()) throw new IllegalArgumentException("Team ID is required.");
            if (displayName.isBlank()) displayName = id;
            rgb &= 0xFFFFFF;
        }
    }

    private static final int MAX_TEAMS = 16;
    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private DiplomacySystem() { }

    static MatchMode mode(World world) {
        return state(world).mode;
    }

    static void configure(World world, MatchMode mode, boolean friendlyFire,
                          boolean sharedVision, boolean sharedVictory) {
        if (world == null) return;
        State state = state(world);
        MatchMode nextMode = mode == null ? MatchMode.FFA : mode;
        if (state.mode != nextMode) clearModeSpecificState(state);
        state.mode = nextMode;
        state.friendlyFire = friendlyFire;
        state.sharedVision = sharedVision;
        state.sharedVictory = sharedVictory;
        normalizeForMode(state);
    }

    static boolean friendlyFire(World world) {
        return state(world).friendlyFire;
    }

    static boolean sharedVision(World world) {
        return state(world).sharedVision;
    }

    static boolean sharedVictory(World world) {
        return state(world).sharedVictory;
    }

    static void defineTeam(World world, TeamDefinition team) {
        if (world == null || team == null) return;
        State state = state(world);
        if (!state.teams.containsKey(team.id()) && state.teams.size() >= MAX_TEAMS) {
            throw new IllegalArgumentException("Too many diplomacy teams.");
        }
        state.teams.put(team.id(), team);
    }

    static List<TeamDefinition> teams(World world) {
        return List.copyOf(state(world).teams.values());
    }

    static void assignTeam(World world, String ownerId, String teamId) {
        if (world == null || invalidOwner(ownerId)) return;
        State state = state(world);
        String cleanTeam = cleanId(teamId);
        if (cleanTeam.isBlank()) {
            state.ownerTeams.remove(ownerId);
            return;
        }
        if (!state.teams.containsKey(cleanTeam)) {
            defineTeam(world, new TeamDefinition(cleanTeam, cleanTeam, 0x888888));
        }
        state.ownerTeams.put(ownerId, cleanTeam);
    }

    static String teamId(World world, String ownerId) {
        if (invalidOwner(ownerId)) return "";
        return state(world).ownerTeams.getOrDefault(ownerId, "");
    }

    static TeamDefinition team(World world, String ownerId) {
        State state = state(world);
        return state.teams.get(state.ownerTeams.get(ownerId));
    }

    static void setRelationship(World world, String firstOwnerId, String secondOwnerId,
                                Relationship relationship) {
        if (world == null || invalidOwner(firstOwnerId) || invalidOwner(secondOwnerId)
                || firstOwnerId.equals(secondOwnerId)) return;
        State state = state(world);
        Relationship normalized = relationship == null ? Relationship.NEUTRAL : relationship;
        state.explicitRelationships.put(pair(firstOwnerId, secondOwnerId), normalized);
    }

    static Relationship relationship(World world, String firstOwnerId, String secondOwnerId) {
        if (invalidOwner(firstOwnerId) || invalidOwner(secondOwnerId)) return Relationship.NEUTRAL;
        if (firstOwnerId.equals(secondOwnerId)) return Relationship.ALLIED;
        State state = state(world);
        Relationship explicit = state.explicitRelationships.get(pair(firstOwnerId, secondOwnerId));
        if (explicit != null) return explicit;

        String firstTeam = state.ownerTeams.get(firstOwnerId);
        String secondTeam = state.ownerTeams.get(secondOwnerId);
        if (firstTeam != null && firstTeam.equals(secondTeam)) return Relationship.ALLIED;

        boolean firstNpc = NpcRules.isNpcFaction(firstOwnerId);
        boolean secondNpc = NpcRules.isNpcFaction(secondOwnerId);
        return switch (state.mode) {
            case COOP_VS_NPC -> firstNpc == secondNpc ? Relationship.ALLIED : Relationship.HOSTILE;
            case FIXED_TEAMS, LOCKED_ALLIANCES -> Relationship.HOSTILE;
            case FFA -> Relationship.HOSTILE;
        };
    }

    static boolean allied(World world, String firstOwnerId, String secondOwnerId) {
        return relationship(world, firstOwnerId, secondOwnerId) == Relationship.ALLIED;
    }

    static boolean neutral(World world, String firstOwnerId, String secondOwnerId) {
        return relationship(world, firstOwnerId, secondOwnerId) == Relationship.NEUTRAL;
    }

    static boolean hostile(World world, String firstOwnerId, String secondOwnerId) {
        return relationship(world, firstOwnerId, secondOwnerId) == Relationship.HOSTILE;
    }

    static boolean mayTarget(World world, String actorId, String targetOwnerId) {
        return hostile(world, actorId, targetOwnerId);
    }

    static boolean mayDamage(World world, String actorId, String targetOwnerId) {
        Relationship relationship = relationship(world, actorId, targetOwnerId);
        return relationship == Relationship.HOSTILE
                || (relationship == Relationship.ALLIED && friendlyFire(world));
    }

    static boolean sharesVision(World world, String viewerId, String ownerId) {
        return viewerId != null && viewerId.equals(ownerId)
                || sharedVision(world) && allied(world, viewerId, ownerId);
    }

    static boolean sharesVictory(World world, String firstOwnerId, String secondOwnerId) {
        return firstOwnerId != null && firstOwnerId.equals(secondOwnerId)
                || sharedVictory(world) && allied(world, firstOwnerId, secondOwnerId);
    }

    static String victoryGroupId(World world, String ownerId) {
        if (invalidOwner(ownerId)) return "";
        if (!sharedVictory(world)) return ownerId;
        String teamId = teamId(world, ownerId);
        return teamId.isBlank() ? ownerId : "TEAM:" + teamId;
    }

    static Map<String,Object> capture(World world) {
        State state = state(world);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("mode", state.mode.name());
        out.put("friendlyFire", state.friendlyFire);
        out.put("sharedVision", state.sharedVision);
        out.put("sharedVictory", state.sharedVictory);

        List<Object> teams = new ArrayList<>();
        for (TeamDefinition team : state.teams.values()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", team.id());
            row.put("displayName", team.displayName());
            row.put("rgb", team.rgb());
            teams.add(row);
        }
        out.put("teams", teams);
        out.put("ownerTeams", new LinkedHashMap<>(state.ownerTeams));

        List<Object> relationships = new ArrayList<>();
        for (Map.Entry<OwnerPair,Relationship> entry : state.explicitRelationships.entrySet()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("first", entry.getKey().first());
            row.put("second", entry.getKey().second());
            row.put("relationship", entry.getValue().name());
            relationships.add(row);
        }
        out.put("relationships", relationships);
        return out;
    }

    static void restore(World world, Object saved) {
        if (world == null) return;
        State replacement = new State();
        Map<String,Object> root = ServerSaveStore.object(saved);
        replacement.mode = ServerSaveStore.enumValue(MatchMode.class, root.get("mode"), MatchMode.FFA);
        replacement.friendlyFire = booleanValue(root.get("friendlyFire"), false);
        replacement.sharedVision = booleanValue(root.get("sharedVision"), true);
        replacement.sharedVictory = booleanValue(root.get("sharedVictory"), true);

        for (Object value : ServerSaveStore.list(root.get("teams"))) {
            Map<String,Object> row = ServerSaveStore.object(value);
            String id = cleanId(ServerSaveStore.string(row, "id", ""));
            if (id.isBlank() || replacement.teams.size() >= MAX_TEAMS) continue;
            String name = ServerSaveStore.string(row, "displayName", id);
            int rgb = ServerSaveStore.intValue(row, "rgb", 0x888888);
            replacement.teams.put(id, new TeamDefinition(id, name, rgb));
        }

        Map<String,Object> assignments = ServerSaveStore.object(root.get("ownerTeams"));
        for (Map.Entry<String,Object> entry : assignments.entrySet()) {
            String owner = entry.getKey();
            String team = cleanId(String.valueOf(entry.getValue()));
            if (!invalidOwner(owner) && replacement.teams.containsKey(team)) {
                replacement.ownerTeams.put(owner, team);
            }
        }

        for (Object value : ServerSaveStore.list(root.get("relationships"))) {
            Map<String,Object> row = ServerSaveStore.object(value);
            String first = ServerSaveStore.string(row, "first", "");
            String second = ServerSaveStore.string(row, "second", "");
            Relationship relationship = ServerSaveStore.enumValue(Relationship.class,
                    row.get("relationship"), Relationship.NEUTRAL);
            if (!invalidOwner(first) && !invalidOwner(second) && !first.equals(second)) {
                replacement.explicitRelationships.put(pair(first, second), relationship);
            }
        }
        normalizeForMode(replacement);
        STATES.put(world, replacement);
    }

    static void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    private static void clearModeSpecificState(State state) {
        state.teams.clear();
        state.ownerTeams.clear();
        state.explicitRelationships.clear();
    }

    private static void normalizeForMode(State state) {
        if (state.mode == MatchMode.FFA) {
            state.friendlyFire = false;
            state.sharedVision = false;
            state.sharedVictory = false;
            clearModeSpecificState(state);
        } else if (state.mode == MatchMode.COOP_VS_NPC) {
            state.sharedVision = true;
            state.sharedVictory = true;
        }
    }

    private static State state(World world) {
        if (world == null) return State.NULL;
        return STATES.computeIfAbsent(world, ignored -> new State());
    }

    private static OwnerPair pair(String first, String second) {
        return first.compareTo(second) <= 0 ? new OwnerPair(first, second) : new OwnerPair(second, first);
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return clean.length() <= 48 ? clean : clean.substring(0, 48);
    }

    private static boolean invalidOwner(String value) {
        return value == null || value.isBlank() || "WAIT".equals(value) || "SENSOR_CONTACT".equals(value);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return fallback;
    }

    private record OwnerPair(String first, String second) { }

    private static final class State {
        static final State NULL = new State();
        MatchMode mode = MatchMode.FFA;
        boolean friendlyFire;
        boolean sharedVision;
        boolean sharedVictory;
        final Map<String,TeamDefinition> teams = new LinkedHashMap<>();
        final Map<String,String> ownerTeams = new LinkedHashMap<>();
        final Map<OwnerPair,Relationship> explicitRelationships = new LinkedHashMap<>();
    }
}
