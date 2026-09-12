package com.tndmadman.rts;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Regression coverage for selectable/scannable/claimable celestial gameplay. */
final class CelestialGameplayValidator {
    private CelestialGameplayValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        selectableBodiesExposeStableViews();
        planetsAndMoonsAreClassifiedCorrectly();
        depositsAreBodyAnchoredAndDeterministic();
        restoredDepositsReattachWithoutDuplication();
        stationsOrbitClaimAndContestBodies();
        scansAndObjectivesProgress();
        progressPersistsAcrossSavePayload();
        System.out.println("Celestial gameplay validation passed.");
    }

    private static void selectableBodiesExposeStableViews() {
        WorldSystemState state = state("celestial-select", 101L);
        CelestialSystem.BodyView body = firstPlayableBody(state);
        CelestialSystem.BodyView hit = state.celestials.bodyAt(body.x(), body.y(), 0);
        require(hit != null && body.id().equals(hit.id()), "click hit-test must resolve the selected body by id");
        require(state.celestials.bodyView(body.id()) != null, "selected body must remain addressable by stable id");
    }

    private static void planetsAndMoonsAreClassifiedCorrectly() {
        WorldSystemState state = state("celestial-kinds", 151L);
        boolean foundPlanet = false;
        boolean foundMoon = false;
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() == CelestialVisualClass.STAR) continue;
            if (body.moon()) foundMoon = true;
            else foundPlanet = true;
        }
        require(foundPlanet, "fixture must expose at least one non-star planet");
        require(foundMoon, "fixture must expose at least one moon orbiting a non-star body");
    }

    private static void depositsAreBodyAnchoredAndDeterministic() {
        WorldSystemState a = state("celestial-a", 202L);
        WorldSystemState b = state("celestial-b", 202L);
        Set<Integer> idsA = resourceIds(a);
        Set<Integer> idsB = resourceIds(b);
        require(!idsA.isEmpty() && !idsB.isEmpty(), "celestial systems must seed body resource deposits");
        Set<Integer> overlap = new HashSet<>(idsA);
        overlap.retainAll(idsB);
        require(overlap.isEmpty(), "different systems must not reuse generated celestial resource ids");

        for (ResourceNode node : a.resources) {
            require(node.id >= (1 << 30), "generated celestial resource ids must stay in the reserved namespace");
            require(node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank(),
                    "celestial resource must record its parent body");
            CelestialSystem.BodyView body = a.celestials.bodyView(node.celestialAnchorBodyId);
            require(body != null, "resource anchor must resolve to an existing body");
            node.deplete();
            ResourceSpawner.relocate(node, a.resources, a.bases.values(), a.celestials, new Random(33));
            require(Math.hypot(node.orbitCenterX - body.x(), node.orbitCenterY - body.y()) < 0.001,
                    "respawned celestial deposit must remain centered on its parent body");
        }
    }

    private static void restoredDepositsReattachWithoutDuplication() {
        StarSystemDefinition definition = StarSystems.defaultSystem();
        long seed = 303L;
        WorldSystemState original = state("celestial-restore", seed);
        int expectedCount = original.resources.size();

        CelestialSystem restoredCelestials = new CelestialSystem(definition, new Random(seed));
        WorldSystemState restored = new WorldSystemState("celestial-restore", definition, restoredCelestials);
        for (ResourceNode source : original.resources) {
            ResourceNode copy = new ResourceNode(source.id, source.name, source.kind, source.material,
                    source.x, source.y, source.maxAmount, source.harvestRate, source.radius);
            copy.amount = source.amount;
            copy.active = source.active;
            copy.respawnTimer = source.respawnTimer;
            copy.orbiting = source.orbiting;
            copy.orbitCenterX = source.orbitCenterX;
            copy.orbitCenterY = source.orbitCenterY;
            copy.orbitRadius = source.orbitRadius;
            copy.orbitAngle = source.orbitAngle;
            copy.orbitSpeed = source.orbitSpeed;
            copy.celestialAnchorBodyId = ""; // Mirrors the pre-anchor save payload.
            restored.resources.add(copy);
        }

        restoredCelestials.update(0);
        require(restored.resources.size() == expectedCount,
                "restore must reattach saved celestial deposits instead of generating duplicates");
        for (ResourceNode node : restored.resources) {
            require(node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank(),
                    "restored celestial deposit must recover its body anchor");
        }
    }

    private static void stationsOrbitClaimAndContestBodies() {
        WorldSystemState state = state("celestial-claim", 404L);
        CelestialSystem.BodyView body = bodyWithMiningBonus(state);
        Base local = new Base("p1:B1", "P1", Rules.DEFAULT_BASE,
                body.x() + body.radius() + 160, body.y());
        state.bases.put(local.id, local);
        state.celestials.update(0);

        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(body.id().equals(local.celestialAnchorBodyId), "nearby station must attach to the body orbit");
        require(bodyState != null && "P1".equals(bodyState.claimantId) && !bodyState.contested,
                "single-owner orbital installation must claim its body");
        require(CelestialGameplaySystem.bonusMultiplier(state, "P1", CelestialBonusKind.MINING) > 1.0,
                "claimed extractor body must provide its mining bonus");

        double beforeX = local.x;
        double beforeY = local.y;
        state.celestials.update(2.0);
        CelestialSystem.BodyView movedBody = state.celestials.bodyView(body.id());
        require(Math.hypot(local.x - beforeX, local.y - beforeY) > 0.01,
                "orbital station must move as the body/orbit advances");
        require(Math.abs(Math.hypot(local.x - movedBody.x(), local.y - movedBody.y()) - local.celestialOrbitRadius) < 0.01,
                "orbital station must preserve its body-relative orbital radius");

        Base enemy = new Base("p2:B1", "P2", Rules.DEFAULT_BASE,
                movedBody.x() - movedBody.radius() - 170, movedBody.y());
        state.bases.put(enemy.id, enemy);
        state.celestials.update(0);
        require(bodyState.contested && bodyState.claimantId.isBlank(),
                "multiple owners with orbital installations must contest the body");
        require(Math.abs(CelestialGameplaySystem.bonusMultiplier(state, "P1", CelestialBonusKind.MINING) - 1.0) < 0.0001,
                "contested bodies must not grant strategic bonuses");
    }

    private static void scansAndObjectivesProgress() {
        WorldSystemState state = state("celestial-objectives", 505L);
        CelestialSystem.BodyView body = firstPlayableBody(state);
        Base base = new Base("p1:SCAN", "P1", Rules.DEFAULT_BASE,
                body.x() + body.radius() + 150, body.y());
        state.bases.put(base.id, base);
        // Anchor first, then advance scan time so the test does not depend on body displacement
        // during a deliberately large validation timestep.
        state.celestials.update(0);
        state.celestials.update(CelestialGameplaySystem.ANALYZE_SECONDS + 0.5);

        require(CelestialGameplaySystem.intel(state, body.id(), "P1") == CelestialIntelLevel.ANALYZED,
                "remaining near a body must progress scan intel through ANALYZED");
        require(CelestialGameplaySystem.objectiveStatus(state, body.id(), "P1", CelestialObjectiveType.SCAN).complete(),
                "scan objective must complete after scanning");
        require(CelestialGameplaySystem.objectiveStatus(state, body.id(), "P1", CelestialObjectiveType.CLAIM).complete(),
                "claim objective must complete for the uncontested installation owner");

        state.celestials.update(CelestialGameplaySystem.HOLD_OBJECTIVE_SECONDS + 1.0);
        require(CelestialGameplaySystem.objectiveStatus(state, body.id(), "P1", CelestialObjectiveType.HOLD).complete(),
                "hold objective must complete after the configured hold duration");

        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(bodyState != null && !bodyState.resourceNodeIds.isEmpty(), "objective body must expose a deposit");
        ResourceNode deposit = resource(state, bodyState.resourceNodeIds.get(0));
        require(deposit != null, "body deposit id must resolve to a resource node");
        CelestialGameplaySystem.recordExtraction(deposit, "P1", CelestialGameplaySystem.EXTRACT_OBJECTIVE_AMOUNT);
        require(CelestialGameplaySystem.objectiveStatus(state, body.id(), "P1", CelestialObjectiveType.EXTRACT).complete(),
                "extract objective must credit the player that actually mined the body deposit");
    }

    private static void progressPersistsAcrossSavePayload() {
        WorldSystemState original = state("celestial-persist", 606L);
        CelestialSystem.BodyView body = firstPlayableBody(original);
        CelestialBodyState progress = CelestialGameplaySystem.bodyState(original, body.id());
        require(progress != null, "persistence fixture body state missing");
        progress.intelByPlayer.put("P1", CelestialIntelLevel.ANALYZED);
        progress.scanSecondsByPlayer.put("P1", 14.0);
        progress.holdSecondsByPlayer.put("P1", 63.0);
        progress.extractedByPlayer.put("P1", 321.0);
        Map<String,Object> saved = CelestialGameplayPersistence.captureState(original);

        WorldSystemState restored = state("celestial-persist", 606L);
        CelestialGameplayPersistence.restoreState(restored, saved);
        CelestialBodyState loaded = CelestialGameplaySystem.bodyState(restored, body.id());
        require(loaded != null && loaded.intelByPlayer.get("P1") == CelestialIntelLevel.ANALYZED,
                "save payload must preserve analyzed celestial intel");
        require(Math.abs(loaded.scanSecondsByPlayer.getOrDefault("P1", 0.0) - 14.0) < 0.001,
                "save payload must preserve scan progress");
        require(Math.abs(loaded.holdSecondsByPlayer.getOrDefault("P1", 0.0) - 63.0) < 0.001,
                "save payload must preserve hold objective progress");
        require(Math.abs(loaded.extractedByPlayer.getOrDefault("P1", 0.0) - 321.0) < 0.001,
                "save payload must preserve extraction objective progress");
    }

    private static WorldSystemState state(String id, long seed) {
        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(seed));
        WorldSystemState state = new WorldSystemState(id, definition, celestials);
        celestials.update(0);
        return state;
    }

    private static CelestialSystem.BodyView firstPlayableBody(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() != CelestialVisualClass.STAR) return body;
        }
        throw new IllegalStateException("fixture requires at least one planet or moon");
    }

    private static CelestialSystem.BodyView bodyWithMiningBonus(WorldSystemState state) {
        for (CelestialBodyState bodyState : CelestialGameplaySystem.bodyStates(state)) {
            if (bodyState.profile.bonuses().getOrDefault(CelestialBonusKind.MINING, 0.0) <= 0) continue;
            CelestialSystem.BodyView body = state.celestials.bodyView(bodyState.profile.bodyId());
            if (body != null) return body;
        }
        throw new IllegalStateException("fixture requires a body with a mining bonus");
    }

    private static Set<Integer> resourceIds(WorldSystemState state) {
        Set<Integer> out = new HashSet<>();
        for (ResourceNode node : state.resources) require(out.add(node.id), "duplicate celestial resource id in one system: " + node.id);
        return out;
    }

    private static ResourceNode resource(WorldSystemState state, int id) {
        for (ResourceNode node : state.resources) if (node.id == id) return node;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
