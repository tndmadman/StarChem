package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class AlertCenter {
    private static final Map<World, ArrayList<GameNotification>> DATA = new WeakHashMap<>();

    private AlertCenter() { }

    static synchronized void push(World world, String text) {
        if (world == null || text == null || text.isBlank()) return;
        world.status = text;
        ArrayList<GameNotification> list = DATA.computeIfAbsent(world, ignored -> new ArrayList<>());
        list.add(new GameNotification(text, 2.6));
        while (list.size() > 6) list.remove(0);
    }

    static synchronized List<GameNotification> list(World world) {
        prune(world);
        ArrayList<GameNotification> list = DATA.get(world);
        return list == null ? List.of() : List.copyOf(list);
    }

    static synchronized void update(World world, double dt) { prune(world); }

    static synchronized void clear(World world) {
        if (world != null) DATA.remove(world);
    }

    static synchronized boolean containsWorldForTest(World world) {
        return world != null && DATA.containsKey(world);
    }

    static synchronized boolean usesWeakKeysForTest() {
        return DATA instanceof WeakHashMap;
    }

    private static void prune(World world) {
        ArrayList<GameNotification> list = DATA.get(world);
        if (list == null) return;
        list.removeIf(GameNotification::expired);
        if (list.isEmpty()) DATA.remove(world);
    }
}
