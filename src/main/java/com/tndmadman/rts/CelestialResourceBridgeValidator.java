package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Regression coverage for released celestial deposits entering and recycling in the tactical list. */
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
        require(activeCelestial(live).isEmpty(),
                "unfractured celestial deposits must not enter the live tactical layer as active rocks");

        Base extractor = new Base("P1:BRIDGE-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                body.x() + body.radius() + 145, body.y());
        state.bases.put(extractor.id, extractor);
        celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(state, body.id(), "P1").fired(),
                "bridge fixture extractor must fire its fracture charge");
        celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        ResourceSpawner.update(live, celestials, 0.016);

        List<ResourceNode> persistentDeposits = activeForBody(state.resources, body.id());
        List<ResourceNode> liveDeposits = activeForBody(live, body.id());
        require(!persistentDeposits.isEmpty(), "released body must expose persistent deposits");
        require(liveDeposits.size() == persistentDeposits.size(),
                "every active deposit for the released body must enter the tactical resource list");

        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(bodyState != null, "body state must exist");
        int expectedRocks = bodyState.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT;
        require(liveDeposits.size() == expectedRocks,
                "live bridge must expose ten fracture mineables per surveyed material");

        Set<Integer> liveIds = ids(liveDeposits);
        require(liveIds.size() == liveDeposits.size(), "live celestial deposit ids must be unique");
        for (ResourceNode persistent : persistentDeposits) {
            ResourceNode liveNode = find(liveDeposits, persistent.id);
            require(liveNode == persistent,
                    "live resource bridge must preserve the authoritative ResourceNode object for id " + persistent.id);
        }

        int bridgedCount = live.size();
        ResourceSpawner.update(live, celestials, 0.016);
        require(live.size() == bridgedCount, "repeated live-resource sync must not duplicate celestial deposits");

        // Exhaust the released field exactly as WorkSystem would. The bridge must keep the same
        // objects/list shape, but make them inactive so renderer, targeting and mining see no rocks.
        boolean exhausted = false;
        for (ResourceNode node : new ArrayList<>(liveDeposits)) {
            node.deplete();
            exhausted |= CelestialExtractionSystem.onDepositDepleted(node);
        }
        require(exhausted, "last live rock must close the current extraction cycle");
        require(!CelestialExtractionSystem.released(state, body.id()),
                "exhausted live field must return the body to sealed state");
        require(CelestialExtractionSystem.cooldownRemaining(state, body.id()) > 0,
                "exhausted live field must enter extractor recycle cooldown");
        ResourceSpawner.update(live, celestials, 0.016);
        require(live.size() == bridgedCount,
                "exhausting a celestial field must not resize the tactical resource list");
        require(activeForBody(live, body.id()).isEmpty(),
                "exhausted celestial field must expose no active tactical rocks");

        require(!CelestialExtractionSystem.fireCharge(state, body.id(), "P1").fired(),
                "exhausted body must reject immediate refire during recycle cooldown");
        celestials.update(CelestialExtractionSystem.REFIRE_COOLDOWN_SECONDS + 0.05);
        require(CelestialExtractionSystem.cooldownRemaining(state, body.id()) <= 0.001,
                "extractor recycle cooldown must expire before refire");
        require(CelestialExtractionSystem.fireCharge(state, body.id(), "P1").fired(),
                "cooled-down exhausted body must accept another fracture charge");
        celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        ResourceSpawner.update(live, celestials, 0.016);
        List<ResourceNode> refired = activeForBody(live, body.id());
        require(refired.size() == liveDeposits.size(), "refired field must restore the full tactical rock set");
        require(live.size() == bridgedCount, "refiring must reuse existing tactical resource objects");
        for (ResourceNode previous : liveDeposits) {
            ResourceNode current = find(refired, previous.id);
            require(current == previous,
                    "refire must reactivate the same live ResourceNode object for id " + previous.id);
        }

        System.out.println("Celestial live resource bridge validation passed.");
    }

    private static CelestialSystem.BodyView firstPlanet(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() != CelestialVisualClass.STAR && !body.moon()) return body;
        }
        throw new IllegalStateException("fixture requires a planet");
    }

    private static List<ResourceNode> activeCelestial(List<ResourceNode> resources) {
        List<ResourceNode> out = new ArrayList<>();
        for (ResourceNode node : resources) {
            if (node != null && node.active && node.celestialAnchorBodyId != null
                    && !node.celestialAnchorBodyId.isBlank()) out.add(node);
        }
        return out;
    }

    private static List<ResourceNode> activeForBody(List<ResourceNode> resources, String bodyId) {
        List<ResourceNode> out = new ArrayList<>();
        for (ResourceNode node : resources) {
            if (node != null && node.active && bodyId.equals(node.celestialAnchorBodyId)) out.add(node);
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
