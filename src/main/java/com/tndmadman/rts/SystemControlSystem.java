package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SystemControlSystem {
    private static final double CAPTURE_SECONDS = 75.0;
    private static final double MINIMUM_INFLUENCE = 3.0;

    private SystemControlSystem() { }

    static void update(World world, WorldSystemState state, double dt) {
        if (world == null || state == null || dt <= 0) return;
        if (state.lifetime == SystemLifetime.PLAYER_HOME) return;

        List<Influence> eligible = eligibleInfluence(state);
        if (eligible.isEmpty()) {
            state.control.decay(dt / CAPTURE_SECONDS * 0.35);
            return;
        }
        if (eligible.size() > 1) {
            state.control.contested();
            return;
        }

        String ownerId = eligible.get(0).ownerId;
        if (ownerId.equals(state.control.controllerId())) {
            if (state.control.status() != SystemControlStatus.CONTROLLED) {
                state.control.controlled(ownerId, state.systemTime);
            }
            return;
        }

        state.control.capture(ownerId, dt / CAPTURE_SECONDS);
        if (state.control.captureComplete()) {
            state.control.controlled(ownerId, state.systemTime);
            world.status = PlayerRegistry.name(ownerId) + " secured control of " + state.definition.name() + ".";
        }
    }

    private static List<Influence> eligibleInfluence(WorldSystemState state) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Base base : state.bases.values()) {
            if (base.hp <= 0 || invalidOwner(base.playerId)) continue;
            scores.merge(base.playerId, 4.0, Double::sum);
        }
        for (Unit unit : state.units.values()) {
            if (unit.hp <= 0 || invalidOwner(unit.playerId)) continue;
            double score = WeaponRules.armed(unit.type()) ? 1.5 : unit.type().harvestKinds.isEmpty() ? 0.75 : 0.5;
            scores.merge(unit.playerId, score, Double::sum);
        }

        List<Influence> eligible = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (entry.getValue() >= MINIMUM_INFLUENCE) eligible.add(new Influence(entry.getKey(), entry.getValue()));
        }
        eligible.sort(Comparator.comparingDouble(Influence::score).reversed().thenComparing(Influence::ownerId));
        return eligible;
    }

    private static boolean invalidOwner(String ownerId) {
        return ownerId == null || ownerId.isBlank() || "WAIT".equals(ownerId);
    }

    private record Influence(String ownerId, double score) { }
}
