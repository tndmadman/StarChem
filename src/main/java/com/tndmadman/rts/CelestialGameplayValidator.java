package com.tndmadman.rts;

import java.util.Map;
import java.util.Random;

/** Regression coverage for selectable/scannable/claimable celestial gameplay. */
final class CelestialGameplayValidator {
    private CelestialGameplayValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        selectableBodiesExposeStableViews();
        planetsAndMoonsAreClassifiedCorrectly();
        buildableInstallationRolesMapCorrectly();
        planetOnlyStationAnchoring();
        stationsOrbitClaimAndRejectHostiles();
        sameOwnerAnchoringRespectsInstallationCapacity();
        stationsUseSharedNonOverlappingOrbit();
        scansAndObjectivesProgress();
        progressAndAnchorsPersistAcrossSavePayload();
        orbitLayoutPersistsAcrossSavePayload();
        legacyMoonAnchorIsSanitized();
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

    private static void buildableInstallationRolesMapCorrectly() {
        require(Rules.findBase("extractor") != null, "Planetary Extractor must be buildable");
        require(Rules.findBase("manufacturing") != null, "manufacturing station must remain buildable");
        require(Rules.findBase("laboratory") != null, "laboratory station must remain buildable");
        require(Rules.findBase("radar_picket") != null, "radar station must remain buildable");
        require(Rules.findBase("outpost") != null, "outpost must remain available for logistics gameplay");
        require(installationRole("extractor", 701L) == CelestialInstallationType.EXTRACTOR,
                "dedicated Planetary Extractor must own the celestial extractor role");
        require(installationRole("manufacturing", 702L) != CelestialInstallationType.EXTRACTOR,
                "manufacturing station must no longer act as a celestial extractor");
        require(installationRole("laboratory", 703L) == CelestialInstallationType.RESEARCH_SITE,
                "laboratory must act as a celestial research site");
        require(installationRole("radar_picket", 704L) == CelestialInstallationType.SENSOR_ARRAY,
                "radar station must act as a celestial sensor array");
        require(installationRole("outpost", 705L) == CelestialInstallationType.LOGISTICS_HUB,
                "outpost must act as a celestial logistics hub");
    }

    private static CelestialInstallationType installationRole(String typeId, long seed) {
        WorldSystemState state = state("role-" + typeId, seed);
        CelestialSystem.BodyView body = firstPlanet(state);
        Base base = new Base("P1:" + typeId, "P1", typeId, body.x() + body.radius() + 140, body.y());
        state.bases.put(base.id, base);
        state.celestials.update(0);
        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(bodyState != null && !bodyState.installations.isEmpty(), "station must register as a body installation: " + typeId);
        return bodyState.installations.get(0);
    }

    private static void planetOnlyStationAnchoring() {
        WorldSystemState moonState = state("celestial-moon-anchor", 303L);
        CelestialSystem.BodyView moon = firstMoon(moonState);
        Base nearMoon = new Base("P1:MOON", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                moon.x(), moon.y());
        moonState.bases.put(nearMoon.id, nearMoon);
        moonState.celestials.update(0);
        require(!moon.id().equals(nearMoon.celestialAnchorBodyId),
                "a station inside moon capture distance must never anchor to the moon");

        WorldSystemState planetState = state("celestial-planet-anchor", 304L);
        CelestialSystem.BodyView planet = firstPlanet(planetState);
        Base nearPlanet = new Base("P1:PLANET", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 150, planet.y());
        planetState.bases.put(nearPlanet.id, nearPlanet);
        planetState.celestials.update(0);
        require(planet.id().equals(nearPlanet.celestialAnchorBodyId),
                "a station inside planet capture distance must anchor to the planet");
    }

    private static void stationsOrbitClaimAndRejectHostiles() {
        WorldSystemState state = state("celestial-claim", 404L);
        CelestialSystem.BodyView body = firstPlanet(state);
        Base local = new Base("p1:B1", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                body.x() + body.radius() + 160, body.y());
        state.bases.put(local.id, local);
        state.celestials.update(0);

        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(body.id().equals(local.celestialAnchorBodyId), "nearby station must attach to the body orbit");
        require(bodyState != null && "P1".equals(bodyState.claimantId) && !bodyState.contested,
                "single-owner orbital installation must claim its body");

        double beforeX = local.x;
        double beforeY = local.y;
        state.celestials.update(2.0);
        CelestialSystem.BodyView movedBody = state.celestials.bodyView(body.id());
        require(Math.hypot(local.x - beforeX, local.y - beforeY) > 0.01,
                "orbital station must move as the body/orbit advances");
        require(Math.abs(Math.hypot(local.x - movedBody.x(), local.y - movedBody.y()) - local.celestialOrbitRadius) < 0.01,
                "orbital station must preserve its body-relative orbital radius");

        Base enemy = new Base("p2:B1", "P2", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                movedBody.x() - movedBody.radius() - 170, movedBody.y());
        state.bases.put(enemy.id, enemy);
        state.celestials.update(0);
        require(!bodyState.contested && "P1".equals(bodyState.claimantId),
                "hostile station must not contest or steal an active planetary claim");
        require(enemy.celestialAnchorBodyId == null || enemy.celestialAnchorBodyId.isBlank(),
                "hostile station must be rejected from the claimed planetary anchor");
    }

    private static void sameOwnerAnchoringRespectsInstallationCapacity() {
        WorldSystemState state = state("celestial-capacity", 454L);
        CelestialSystem.BodyView body = firstPlanet(state);
        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(bodyState != null, "capacity fixture body state missing");
        int slots = bodyState.profile.installationSlots();
        require(slots >= 2, "capacity fixture requires at least two installation slots");

        Base[] bases = new Base[slots + 1];
        double deploymentRadius = body.radius() + 150;
        for (int i = 0; i < bases.length; i++) {
            double angle = i * 0.01;
            Base base = new Base(String.format("P1:CAP:%02d", i), "P1",
                    CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                    body.x() + Math.cos(angle) * deploymentRadius,
                    body.y() + Math.sin(angle) * deploymentRadius);
            bases[i] = base;
            state.bases.put(base.id, base);
        }
        state.celestials.update(0);

        for (int i = 0; i < slots; i++) {
            require(body.id().equals(bases[i].celestialAnchorBodyId),
                    "same-owner station must anchor while installation capacity remains");
        }
        require(bases[slots].celestialAnchorBodyId == null || bases[slots].celestialAnchorBodyId.isBlank(),
                "same-owner station beyond installation capacity must remain unanchored");
        require(bodyState.orbitalBaseIds.size() == slots,
                "planet must not contain more anchored stations than configured installation slots");
        require("P1".equals(bodyState.claimantId) && !bodyState.contested,
                "capacity rejection must not disturb the existing same-owner claim");
    }

    private static void stationsUseSharedNonOverlappingOrbit() {
        WorldSystemState state = state("celestial-spacing", 455L);
        CelestialSystem.BodyView body = firstPlanet(state);
        double x = body.x() + body.radius() + 150;
        double y = body.y();
        Base extractor = new Base("P1:SPACE:A", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID, x, y);
        Base shipyard = new Base("P1:SPACE:B", "P1", "shipyard", x, y);
        state.bases.put(extractor.id, extractor);
        state.bases.put(shipyard.id, shipyard);
        state.celestials.update(0);

        require(body.id().equals(extractor.celestialAnchorBodyId) && body.id().equals(shipyard.celestialAnchorBodyId),
                "same-owner spacing fixture stations must both anchor");
        require(Math.abs(extractor.celestialOrbitRadius - shipyard.celestialOrbitRadius) < 0.000001,
                "stations sharing a planet must use one canonical orbit radius");
        require(Math.abs(extractor.celestialOrbitSpeed - shipyard.celestialOrbitSpeed) < 0.000000001,
                "stations sharing a planet must use one angular speed");
        requireNonOverlapping(extractor, shipyard, "newly anchored stations must not visually overlap");
        double separationBefore = Math.hypot(extractor.x - shipyard.x, extractor.y - shipyard.y);

        state.celestials.update(10_000.0);
        requireNonOverlapping(extractor, shipyard, "station orbits must remain non-overlapping after long simulation");
        double separationAfter = Math.hypot(extractor.x - shipyard.x, extractor.y - shipyard.y);
        require(Math.abs(separationBefore - separationAfter) < 0.01,
                "shared-radius/shared-speed stations must preserve their relative spacing over time");
    }

    private static void scansAndObjectivesProgress() {
        WorldSystemState state = state("celestial-objectives", 505L);
        CelestialSystem.BodyView body = firstPlanet(state);
        Base extractor = new Base("p1:SCAN", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                body.x() + body.radius() + 150, body.y());
        state.bases.put(extractor.id, extractor);
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

        require(CelestialExtractionSystem.fireCharge(state, body.id(), "P1").fired(),
                "extract objective fixture must fire a fracture charge");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, body.id());
        require(bodyState != null && !bodyState.resourceNodeIds.isEmpty(), "fractured objective body must expose a deposit");
        ResourceNode deposit = resource(state, bodyState.resourceNodeIds.get(0));
        require(deposit != null, "body deposit id must resolve to a resource node");
        CelestialGameplaySystem.recordExtraction(deposit, "P1", CelestialGameplaySystem.EXTRACT_OBJECTIVE_AMOUNT);
        require(CelestialGameplaySystem.objectiveStatus(state, body.id(), "P1", CelestialObjectiveType.EXTRACT).complete(),
                "extract objective must credit the player that actually mined the exposed body deposit");
    }

    private static void progressAndAnchorsPersistAcrossSavePayload() {
        WorldSystemState original = state("celestial-persist", 606L);
        CelestialSystem.BodyView body = firstPlanet(original);
        CelestialBodyState progress = CelestialGameplaySystem.bodyState(original, body.id());
        require(progress != null, "persistence fixture body state missing");
        progress.intelByPlayer.put("P1", CelestialIntelLevel.ANALYZED);
        progress.scanSecondsByPlayer.put("P1", 14.0);
        progress.holdSecondsByPlayer.put("P1", 63.0);
        progress.extractedByPlayer.put("P1", 321.0);
        Base originalBase = new Base("P1:PERSIST", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                body.x() + body.radius() + 145, body.y());
        original.bases.put(originalBase.id, originalBase);
        original.celestials.update(0);
        Map<String,Object> saved = CelestialGameplayPersistence.captureState(original);

        WorldSystemState restored = state("celestial-persist", 606L);
        Base restoredBase = new Base(originalBase.id, originalBase.playerId, originalBase.typeId, 10, 10);
        restored.bases.put(restoredBase.id, restoredBase);
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
        require(originalBase.celestialAnchorBodyId.equals(restoredBase.celestialAnchorBodyId),
                "save payload must preserve orbital-station body anchors");
        require(Math.abs(originalBase.celestialOrbitRadius - restoredBase.celestialOrbitRadius) < 0.001,
                "save payload must preserve station orbital radius");
    }

    private static void orbitLayoutPersistsAcrossSavePayload() {
        WorldSystemState original = state("celestial-orbit-persist", 607L);
        CelestialSystem.BodyView body = firstPlanet(original);
        double x = body.x() + body.radius() + 150;
        double y = body.y();
        Base a = new Base("P1:SAVE:A", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID, x, y);
        Base b = new Base("P1:SAVE:B", "P1", "shipyard", x, y);
        original.bases.put(a.id, a);
        original.bases.put(b.id, b);
        original.celestials.update(0);
        requireNonOverlapping(a, b, "save fixture must begin with a non-overlapping orbit");
        Map<String,Object> saved = CelestialGameplayPersistence.captureState(original);

        WorldSystemState restored = state("celestial-orbit-persist", 607L);
        Base restoredA = new Base(a.id, a.playerId, a.typeId, 10, 10);
        Base restoredB = new Base(b.id, b.playerId, b.typeId, 20, 20);
        restored.bases.put(restoredA.id, restoredA);
        restored.bases.put(restoredB.id, restoredB);
        CelestialGameplayPersistence.restoreState(restored, saved);
        restored.celestials.update(0);

        require(body.id().equals(restoredA.celestialAnchorBodyId)
                        && body.id().equals(restoredB.celestialAnchorBodyId),
                "save restore must preserve valid planet anchors");
        require(Math.abs(restoredA.celestialOrbitRadius - restoredB.celestialOrbitRadius) < 0.000001,
                "restored stations must normalize onto the common planetary orbit lane");
        require(Math.abs(restoredA.celestialOrbitSpeed - restoredB.celestialOrbitSpeed) < 0.000000001,
                "restored stations must normalize to a common planetary angular speed");
        requireNonOverlapping(restoredA, restoredB,
                "save restore must preserve or reconstruct a non-overlapping station layout");
    }

    private static void legacyMoonAnchorIsSanitized() {
        WorldSystemState state = state("celestial-legacy-moon", 608L);
        CelestialSystem.BodyView moon = firstMoon(state);
        Base base = new Base("P1:LEGACY-MOON", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                moon.x(), moon.y());
        state.bases.put(base.id, base);

        Map<String,Object> saved = Map.of(
                "$baseAnchors", Map.of(
                        base.id, Map.of(
                                "bodyId", moon.id(),
                                "radius", moon.radius() + 180,
                                "angle", 0.5,
                                "speed", 0.01)));
        CelestialGameplayPersistence.restoreState(state, saved);
        require(base.celestialAnchorBodyId == null || base.celestialAnchorBodyId.isBlank(),
                "legacy persisted moon anchors must be rejected during restore");

        state.celestials.update(0);
        require(!moon.id().equals(base.celestialAnchorBodyId),
                "sanitized legacy station must not re-anchor to the moon on update");
    }

    private static WorldSystemState state(String id, long seed) {
        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(seed));
        return new WorldSystemState(id, definition, celestials);
    }

    private static CelestialSystem.BodyView firstPlayableBody(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() != CelestialVisualClass.STAR) return body;
        }
        throw new IllegalStateException("fixture requires at least one planet or moon");
    }

    private static CelestialSystem.BodyView firstMoon(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.moon()) return body;
        }
        throw new IllegalStateException("fixture requires a moon");
    }

    private static CelestialSystem.BodyView firstPlanet(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() != CelestialVisualClass.STAR && !body.moon()) return body;
        }
        throw new IllegalStateException("fixture requires a planet");
    }

    private static void requireNonOverlapping(Base a, Base b, String message) {
        double minimum = StationRenderer.visualExtent(a) + StationRenderer.visualExtent(b)
                + CelestialGameplayConfig.INSTANCE.anchoring.stationSeparationPadding();
        double actual = Math.hypot(a.x - b.x, a.y - b.y);
        require(actual + 0.001 >= minimum,
                message + " (actual=" + actual + ", required=" + minimum + ")");
    }

    private static ResourceNode resource(WorldSystemState state, int id) {
        for (ResourceNode node : state.resources) if (node.id == id) return node;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
