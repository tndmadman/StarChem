package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

record LeaderboardEntry(String playerId, int units, int bases, int score) { }

final class GlobalLeaderboard {
    private static final Map<World, List<LeaderboardEntry>> BY_WORLD = new WeakHashMap<>();

    private GlobalLeaderboard() { }

    static synchronized void set(World world, List<LeaderboardEntry> entries) {
        BY_WORLD.put(world, entries == null ? List.of() : List.copyOf(entries));
    }

    static synchronized List<LeaderboardEntry> get(World world) {
        return BY_WORLD.getOrDefault(world, List.of());
    }

    static String encode(List<LeaderboardEntry> entries) {
        StringBuilder out = new StringBuilder("LEADER|");
        boolean first = true;
        for (LeaderboardEntry entry : entries) {
            if (entry == null || entry.playerId() == null || entry.playerId().isBlank()) continue;
            if (!first) out.append(';');
            first = false;
            out.append(clean(entry.playerId())).append(',')
                    .append(Math.max(0, entry.units())).append(',')
                    .append(Math.max(0, entry.bases())).append(',')
                    .append(Math.max(0, entry.score()));
        }
        return out.toString();
    }

    static List<LeaderboardEntry> decode(String message) {
        List<LeaderboardEntry> out = new ArrayList<>();
        if (message == null || !message.startsWith("LEADER|")) return out;
        String body = message.substring(7);
        if (body.isBlank()) return out;
        for (String row : body.split(";")) {
            String[] fields = row.split(",", -1);
            if (fields.length < 4 || fields[0].isBlank()) continue;
            try {
                out.add(new LeaderboardEntry(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static List<LeaderboardEntry> aggregate(World world, String[] systemIds) {
        Map<String, MutableEntry> totals = new LinkedHashMap<>();
        String previous = world.activeSystemId();
        try {
            for (String systemId : systemIds) {
                if (systemId == null || systemId.isBlank()) continue;
                world.activateSystem(systemId);
                for (Unit unit : world.units.values()) {
                    if (unit.hp <= 0 || NpcRules.isNpcFaction(unit.playerId)) continue;
                    MutableEntry entry = totals.computeIfAbsent(unit.playerId, ignored -> new MutableEntry());
                    entry.units++;
                    entry.hp += unit.hp;
                }
                for (Base base : world.bases.values()) {
                    if (base.hp <= 0 || NpcRules.isNpcFaction(base.playerId)) continue;
                    MutableEntry entry = totals.computeIfAbsent(base.playerId, ignored -> new MutableEntry());
                    entry.bases++;
                    entry.hp += base.hp;
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
        List<LeaderboardEntry> out = new ArrayList<>();
        for (Map.Entry<String, MutableEntry> row : totals.entrySet()) {
            MutableEntry entry = row.getValue();
            int score = (int)Math.round(entry.hp + entry.bases * 1000.0 + entry.units * 100.0);
            out.add(new LeaderboardEntry(row.getKey(), entry.units, entry.bases, score));
        }
        return out;
    }

    private static String clean(String value) { return value.replace("|", "").replace(";", "").replace(",", "").trim(); }

    private static final class MutableEntry {
        int units;
        int bases;
        double hp;
    }
}
