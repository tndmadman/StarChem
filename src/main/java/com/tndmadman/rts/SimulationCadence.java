package com.tndmadman.rts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Accumulates elapsed simulation time for systems that do not require frame-rate cadence.
 * Returning the complete accumulated delta preserves timers/rates while distributing expensive
 * decision work across frames.
 */
final class SimulationCadence {
    private static final Map<World, Map<String, State>> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SimulationCadence() { }

    static double consume(World world, String key, double dt, double intervalSeconds) {
        if (world == null || key == null || key.isBlank() || !(dt > 0) || !(intervalSeconds > 0)) return dt;
        String system = world.activeSystemId() == null ? "" : world.activeSystemId();
        String scoped = system + '|' + key;
        State state;
        synchronized (STATES) {
            Map<String, State> byKey = STATES.computeIfAbsent(world, ignored -> new HashMap<>());
            state = byKey.computeIfAbsent(scoped, ignored -> {
                double phase = intervalSeconds * Math.floorMod(scoped.hashCode(), 997) / 997.0;
                return new State(phase);
            });
        }
        state.accumulated += dt;
        if (state.accumulated + 1e-9 < intervalSeconds) return 0;
        double elapsed = state.accumulated;
        state.accumulated = 0;
        return elapsed;
    }

    static void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    private static final class State {
        double accumulated;
        State(double accumulated) { this.accumulated = accumulated; }
    }
}
