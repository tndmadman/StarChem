package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Regression coverage for released celestial deposits entering the active tactical resource list. */
final class CelestialResourceBridgeValidator {
    private CelestialResourceBridgeValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(918273L));
        WorldSystemState state = new WorldSystemState("celestial-live-resource-sync", definition, celestials);
        List<ResourceNode> live = new ArrayList<>();
        CelestialSystem.BodyView body = firstPlanet(state);

        ResourceSpawner.update(live, celestials, 0.016);
        require(celestial(live).isEmpty(), "unfractured celestial deposits must not enter the live tactical list");

        Base extractor = new Base("P1:BRIDGE-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                body.x() + body.radius() + 145, body.y());
        state.bases.put(extractor.id, extractor);
        celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(state, body.id(), "P1").fired(),
                "bridge fixture extractor must fire its fracture charge");
        celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        ResourceSpawner.update(live, celestials, 0.016);

        List<ResourceNode> persistentDeposits = celestial(state.resources);
        List<ResourceNode> liveDeposits = celestial(live);
        require(!persistentDeposits.isEmpty(), "released body must seed persistent deposits");
        require(liveDeposits.size() == persistentDeposits.size(),
                "every released celestial deposit must enter the active tactical resource list");

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

    private static CelestialSystem.BodyView firstPlanet(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() != CelestialVisualClass.STAR && !body.moon()) return body;
        }
        throw new IllegalStateException("fixture requires a planet");
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
