package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class AiDevLog {
    private static final int LIMIT = 80;
    private static final List<String> LINES = new ArrayList<>();

    private AiDevLog() { }

    static void add(String source, String message) {
        String line = String.format("%6.1fs %-12s %s", System.nanoTime() / 1_000_000_000.0, source, message);
        LINES.add(line);
        while (LINES.size() > LIMIT) LINES.remove(0);
        AiBrainLog.event(null, source, "ai_event", message);
    }

    static void add(World world, NpcFaction faction, String message) {
        String time = world == null ? "  0.0" : String.format("%5.1f", world.systemTime());
        String name = faction == null ? "AI" : faction.name();
        LINES.add(time + "s " + name + ": " + message);
        while (LINES.size() > LIMIT) LINES.remove(0);
        AiBrainLog.event(world, faction, "ai_event", message);
    }

    static void clear() { LINES.clear(); }

    static List<String> lines(int max) {
        int from = Math.max(0, LINES.size() - max);
        return List.copyOf(LINES.subList(from, LINES.size()));
    }
}
