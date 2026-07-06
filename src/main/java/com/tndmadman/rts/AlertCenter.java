package com.tndmadman.rts;

import java.util.*;

final class AlertCenter {
    private static final Map<World, ArrayList<GameNotification>> DATA = new IdentityHashMap<>();

    private AlertCenter() { }

    static void push(World world, String text) {
        if (world == null || text == null || text.isBlank()) return;
        world.status = text;
        ArrayList<GameNotification> list = DATA.computeIfAbsent(world, ignored -> new ArrayList<>());
        list.add(new GameNotification(text, 2.6));
        while (list.size() > 6) list.remove(0);
    }

    static java.util.List<GameNotification> list(World world) {
        prune(world);
        ArrayList<GameNotification> list = DATA.get(world);
        return list == null ? java.util.List.of() : list;
    }

    static void update(World world, double dt) { prune(world); }

    private static void prune(World world) {
        ArrayList<GameNotification> list = DATA.get(world);
        if (list == null) return;
        list.removeIf(GameNotification::expired);
        if (list.isEmpty()) DATA.remove(world);
    }
}
