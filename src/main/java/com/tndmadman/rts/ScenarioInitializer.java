package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Applies authored starting conditions exactly once for a new scenario. */
final class ScenarioInitializer {
    private ScenarioInitializer() { }

    static void apply(World world, ScenarioStartingState startingState) {
        if (world == null || startingState == null) return;
        String previousSystem = world.activeSystemId();
        if (!startingState.startingSystem().isBlank()) world.activateSystem(startingState.startingSystem());

        for (Map.Entry<String,List<String>> entry : startingState.completedResearch().entrySet()) {
            for (String playerId : resolveOwners(entry.getKey())) {
                world.completedResearch.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
            }
        }
        for (ScenarioStartingShip ship : startingState.ships()) {
            for (String playerId : resolveOwners(ship.owner())) {
                for (int i = 0; i < ship.count(); i++) {
                    int unitId = nextUnitId(world, playerId);
                    world.units.put(Unit.key(playerId, unitId), new Unit(playerId, unitId, ship.typeId(),
                            ship.x() + i * ship.spacing(), ship.y()));
                }
            }
        }
        for (ScenarioStartingStation station : startingState.stations()) {
            for (String playerId : resolveOwners(station.owner())) {
                String baseId = nextBaseId(world, playerId);
                Base base = new Base(baseId, playerId, station.typeId(), station.x(), station.y());
                for (Map.Entry<String,Double> item : station.inventory().entrySet()) {
                    double amount = item.getValue() == null ? 0 : item.getValue();
                    if (amount > 0) base.inventory.put(Material.valueOf(item.getKey()), amount);
                }
                world.bases.put(baseId, base);
            }
        }
        world.saveActiveSystem();
        if (startingState.startingSystem().isBlank() && previousSystem != null && !previousSystem.isBlank()) {
            world.activateSystem(previousSystem);
        }
    }

    private static List<String> resolveOwners(String owner) {
        List<String> humans = humanPlayers();
        if ("ALL_HUMANS".equals(owner)) return humans;
        if ("PLAYER".equals(owner)) {
            String local = PlayerRegistry.localId();
            if (human(local)) return List.of(local);
            return humans.isEmpty() ? List.of() : List.of(humans.get(0));
        }
        return owner == null || owner.isBlank() ? List.of() : List.of(owner);
    }

    private static List<String> humanPlayers() {
        List<String> out = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) if (human(player.id())) out.add(player.id());
        Collections.sort(out);
        return List.copyOf(out);
    }

    private static boolean human(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }

    private static int nextUnitId(World world, String playerId) {
        int id = 1;
        while (world.units.containsKey(Unit.key(playerId, id))) id++;
        return id;
    }

    private static String nextBaseId(World world, String playerId) {
        int id = 1;
        while (world.bases.containsKey(playerId + ":B" + id)) id++;
        return playerId + ":B" + id;
    }
}
