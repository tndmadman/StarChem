package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Recipient-scoped diplomacy roster and pending-request state supplied by the authoritative server. */
final class DiplomacyClientState {
    record PlayerView(String id, String name, int rgb, boolean online,
                      DiplomacySystem.Relationship relationship,
                      boolean incomingOffer, boolean outgoingOffer) {
        PlayerView {
            id = cleanId(id);
            name = Config.clean(name);
            if (name.isBlank()) name = id;
            rgb &= 0xFFFFFF;
            relationship = relationship == null ? DiplomacySystem.Relationship.HOSTILE : relationship;
        }
    }

    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private DiplomacyClientState() { }

    static synchronized void apply(World world, Object encodedState) {
        if (world == null) return;
        Map<String,Object> root = ServerSaveStore.object(encodedState);
        State replacement = new State();
        replacement.revision = Math.max(0, ServerSaveStore.longValue(root, "revision", 0));
        replacement.mode = ServerSaveStore.enumValue(DiplomacySystem.MatchMode.class,
                root.get("mode"), DiplomacySystem.MatchMode.FFA);
        replacement.negotiationAllowed = booleanValue(root.get("negotiationAllowed"),
                replacement.mode == DiplomacySystem.MatchMode.FFA);
        replacement.selfId = cleanId(ServerSaveStore.string(root, "selfId", ""));

        for (Object value : ServerSaveStore.list(root.get("players"))) {
            Map<String,Object> row = ServerSaveStore.object(value);
            String id = cleanId(ServerSaveStore.string(row, "id", ""));
            if (id.isBlank() || id.equals(replacement.selfId) || NpcRules.isNpcFaction(id)) continue;
            String name = ServerSaveStore.string(row, "name", id);
            int rgb = ServerSaveStore.intValue(row, "rgb", 0x888888);
            boolean online = booleanValue(row.get("online"), false);
            DiplomacySystem.Relationship relationship = ServerSaveStore.enumValue(
                    DiplomacySystem.Relationship.class, row.get("relationship"),
                    DiplomacySystem.Relationship.HOSTILE);
            boolean incoming = booleanValue(row.get("incomingOffer"), false);
            boolean outgoing = booleanValue(row.get("outgoingOffer"), false);
            PlayerView player = new PlayerView(id, name, rgb, online, relationship, incoming, outgoing);
            replacement.players.put(id, player);
        }
        STATES.put(world, replacement);
    }

    static synchronized List<PlayerView> players(World world) {
        State state = STATES.get(world);
        if (state == null || state.players.isEmpty()) return List.of();
        List<PlayerView> out = new ArrayList<>(state.players.values());
        out.sort(Comparator.comparing(PlayerView::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerView::id));
        return List.copyOf(out);
    }

    static synchronized PlayerView player(World world, String playerId) {
        State state = STATES.get(world);
        return state == null || playerId == null ? null : state.players.get(playerId);
    }

    static synchronized long revision(World world) {
        State state = STATES.get(world);
        return state == null ? 0 : state.revision;
    }

    static synchronized boolean negotiationAllowed(World world) {
        State state = STATES.get(world);
        return state != null ? state.negotiationAllowed : DiplomacySystem.liveNegotiationAllowed(world);
    }

    static synchronized DiplomacySystem.MatchMode mode(World world) {
        State state = STATES.get(world);
        return state == null ? DiplomacySystem.mode(world) : state.mode;
    }

    static synchronized void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    static synchronized boolean containsWorldForTest(World world) {
        return world != null && STATES.containsKey(world);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return fallback;
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        String clean = value.replace("|", "").trim();
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    private static final class State {
        long revision;
        String selfId = "";
        DiplomacySystem.MatchMode mode = DiplomacySystem.MatchMode.FFA;
        boolean negotiationAllowed = true;
        final Map<String,PlayerView> players = new LinkedHashMap<>();
    }
}
