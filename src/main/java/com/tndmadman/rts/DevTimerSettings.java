package com.tndmadman.rts;

import java.util.Map;
import java.util.WeakHashMap;

final class DevTimerSettings {
    private static final Map<World, Boolean> DISABLED = new WeakHashMap<>();

    private DevTimerSettings() { }

    static synchronized void configure(World world, boolean disabled) {
        if (world == null) return;
        if (disabled) DISABLED.put(world, true);
        else DISABLED.remove(world);
    }

    static synchronized boolean disabled(World world) {
        return world != null && Boolean.TRUE.equals(DISABLED.get(world));
    }
}
