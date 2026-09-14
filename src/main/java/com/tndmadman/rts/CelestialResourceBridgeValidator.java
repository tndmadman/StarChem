package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Regression coverage for persistent celestial deposits entering the active tactical resource list. */
final class CelestialResourceBridgeValidator {
    private CelestialResourceBridgeValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(918273L));
        WorldSystemState state = new WorldSystemState("celestial-live-resource-sync", definition, celestials);
        List<ResourceNode> live = new ArrayList<>();

        // This is the same boundary used by World.advanceEnvironment: CelestialSystem owns the
        // persistent state update and ResourceSpawner owns the active tactical list update.
        ResourceSpawner.update(live, celestials, 0.016);

        List<ResourceNode> persistentDeposits = celestial(state.resources);
        List<ResourceNode> liveDeposits = celestial(live);
        require(!persistentDeposits.isEmpty(), "celestial update must seed persistent body deposits");
        require(liveDeposits.size() == persistentDeposits.size(),
                "every persistent celestial deposit must enter the active tactical resource list");

        Set<Integer> liveIds = ids(liveDeposits);
        require(liveIds.size() == liveDeposits.size(), "live celestial deposit ids must be unique");
        for (ResourceNode persistent : persistentDeposits) {
            ResourceNode liveNode = find(liveDeposits, persistent.id);
            require(liveNode == persistent,
                    "live resource bridge must preserve the authoritative ResourceNode object for id " + persistent.id);
        }

        int count = live.size();
        ResourceSpawner.update(live, celestials, 0.016);
        require(live.size() == count, "repeated live-resource sync must not duplicate celestial deposits");

        System.out.println("Celestial live resource bridge validation passed.");
    }

    private static List<ResourceNode> celestial(List<ResourceNode> resources) {
        List<ResourceNode> out = new ArrayList<>();
        for (ResourceNode node : resources) {
            if (node != null && node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank()) out.add(node);
        }
        return out;
    }

    private static Set<Integer> ids(List<ResourceNode> resources) {
        Set<Integer> out = new HashSet<>();
        for (ResourceNode node : resources) out.add(node.id);
        return out;
    }

    private static ResourceNode find(List<ResourceNode> resources, int id) {
        for (ResourceNode node : resources) if (node.id == id) return node;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
