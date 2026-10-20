package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Regression coverage for extractor-gated planet/moon deposits and fracture charges. */
final class CelestialExtractionValidator {
    private CelestialExtractionValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        depositsStayAbsentUntilChargeImpact();
        oneMasterExtractorCanFractureSlaveMoons();
        exhaustedFieldRequiresCooldownAndRefiresWithoutNodeChurn();
        missedDepletionCallbackSelfHealsBeforeRefire();
        extractorCooldownPreventsPlanetMoonSpam();
        celestialOwnershipSurvivesSnapshotRoundTrip();
        extractionNetworkStateConvergesAcrossReconnect();
        authoritativeAudioEmitsOncePerLifecycleEvent();
        releasedBodiesPersist();
        System.out.println("Celestial extraction validation passed.");
    }

    private static void depositsStayAbsentUntilChargeImpact() {
        WorldSystemState state = state("extract-gate", 9101L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, planet.id());
        require(body != null, "planet body state missing");

        state.celestials.update(0);
        int expectedRocks = body.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT;
        require(body.resourceNodeIds.size() == expectedRocks,
                "fracture field must preallocate ten mineables per surveyed material");
        require(countActiveAnchored(state, planet.id()) == 0,
                "unfractured body must not expose active physical resource nodes");
        require(CelestialExtractionSystem.locatableFragment(state, planet.id(), 0) == null,
                "LOCATE must never return a sealed/inactive celestial fragment");
        require(Math.abs(totalMaxVolume(state, planet.id()) - expectedVolume(body)) < 0.001,
                "ten-times denser fracture field must preserve the original total resource volume");

        Base extractor = new Base("P1:EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(planet.id().equals(extractor.celestialAnchorBodyId), "extractor must anchor to the master planet");
        require(CelestialExtractionSystem.extractorReady(state, planet.id(), "P1"),
                "anchored claimant extractor must be ready to fire");
        require(countActiveAnchored(state, planet.id()) == 0,
                "deploying extractor alone must not create visible/minable rocks");

        CelestialExtractionSystem.FireResult fired = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(fired.fired(), "extractor must fire a fracture charge at its claimed planet");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS * 0.5);
        require(!CelestialExtractionSystem.released(state, planet.id()), "body must remain sealed while charge is in flight");
        require(countActiveAnchored(state, planet.id()) == 0, "rocks must remain inactive before charge impact");

        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS * 0.51);
        require(CelestialExtractionSystem.released(state, planet.id()), "charge impact must release the target body");
        require(countActiveAnchored(state, planet.id()) == expectedRocks,
                "fracture impact must expose the complete debris field");
        require(CelestialExtractionSystem.locatableFragment(state, planet.id(), 0) != null,
                "LOCATE must return an active fragment after authoritative impact");
        require(Math.abs(totalActiveVolume(state, planet.id()) - expectedVolume(body)) < 0.001,
                "released debris field must still contain the original aggregate volume");
    }

    private static void oneMasterExtractorCanFractureSlaveMoons() {
        WorldSystemState state = state("extract-moon", 9102L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        CelestialSystem.BodyView moon = firstMoonOf(state, planet.id());
        require(moon != null, "fixture requires a slave moon");

        Base extractor = new Base("P1:MOON-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(planet.id().equals(extractor.celestialAnchorBodyId), "extractor must remain on the master planet");
        require(CelestialExtractionSystem.extractorReady(state, moon.id(), "P1"),
                "master-planet extractor must be able to service a slave moon");

        CelestialExtractionSystem.FireResult fired = CelestialExtractionSystem.fireCharge(state, moon.id(), "P1");
        require(fired.fired(), "master extractor must fire a charge at a slave moon");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);

        CelestialBodyState moonState = CelestialGameplaySystem.bodyState(state, moon.id());
        require(CelestialExtractionSystem.released(state, moon.id()), "moon charge must release only the targeted moon");
        require(moonState != null && countActiveAnchored(state, moon.id()) == moonState.resourceNodeIds.size(),
                "fractured moon must expose active debris");
        require(!CelestialExtractionSystem.released(state, planet.id()),
                "fracturing a moon must not automatically fracture its master planet");
    }

    private static void exhaustedFieldRequiresCooldownAndRefiresWithoutNodeChurn() {
        WorldSystemState state = state("extract-refire", 9104L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        Base extractor = new Base("P1:REFIRE-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(), "first charge must fire");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);

        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, planet.id());
        require(body != null && !body.resourceNodeIds.isEmpty(), "first fracture must create a field");
        int storedBefore = countStoredAnchored(state, planet.id());
        int resourceListBefore = state.resources.size();
        List<Integer> idsBefore = new ArrayList<>(body.resourceNodeIds);
        require(idsBefore.size() == body.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT,
                "fracture cycle must use ten mineables per material");
        require(countActiveAnchored(state, planet.id()) == idsBefore.size(), "first field must be active");

        boolean exhausted = false;
        for (int id : idsBefore) {
            ResourceNode node = resourceById(state, id);
            require(node != null, "fracture node missing during depletion");
            node.deplete();
            exhausted |= CelestialExtractionSystem.onDepositDepleted(node);
        }
        require(exhausted, "last depleted rock must close the fracture cycle");
        require(!CelestialExtractionSystem.released(state, planet.id()),
                "fully depleted field must return the body to sealed state");
        require(countActiveAnchored(state, planet.id()) == 0, "depleted field must have no active rocks");
        require(CelestialExtractionSystem.locatableFragment(state, planet.id(), 0) == null,
                "LOCATE must not return recycled inactive fragments after depletion");
        require(countStoredAnchored(state, planet.id()) == storedBefore,
                "depletion must recycle deterministic nodes instead of deleting/reallocating them");
        require(state.resources.size() == resourceListBefore,
                "field depletion must not resize the persistent resource list");
        require(CelestialExtractionSystem.cooldownRemaining(state, planet.id()) > 0,
                "field exhaustion must start the extractor recycle delay");

        Map<String,Object> savedCooldown = CelestialExtractionSystem.captureState(state);
        WorldSystemState reloaded = state("extract-refire", 9104L);
        CelestialSystem.BodyView reloadedPlanet = planetWithMoon(reloaded);
        Base reloadedExtractor = new Base(extractor.id, "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                reloadedPlanet.x() + reloadedPlanet.radius() + 145, reloadedPlanet.y());
        reloaded.bases.put(reloadedExtractor.id, reloadedExtractor);
        reloaded.celestials.update(0);
        CelestialExtractionSystem.restoreState(reloaded, savedCooldown);
        require(CelestialExtractionSystem.cooldownRemaining(reloaded, reloadedPlanet.id()) > 0,
                "body recycle cooldown must survive save/reload");
        require(CelestialExtractionSystem.extractorCooldownRemaining(reloaded, reloadedPlanet.id(), "P1") > 0,
                "extractor firing cooldown must survive save/reload");

        CelestialExtractionSystem.FireResult immediate = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(!immediate.fired(), "refire must be blocked during the recycle delay");
        state.celestials.update(CelestialExtractionSystem.REFIRE_COOLDOWN_SECONDS * 0.5);
        require(!CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(),
                "refire must remain blocked halfway through cooldown");
        state.celestials.update(CelestialExtractionSystem.REFIRE_COOLDOWN_SECONDS * 0.51);
        require(CelestialExtractionSystem.cooldownRemaining(state, planet.id()) <= 0.001,
                "cooldown must expire after the configured recycle period");

        CelestialExtractionSystem.FireResult refired = CelestialExtractionSystem.fireCharge(state, planet.id(), "P1");
        require(refired.fired(), "cooled-down field must allow another fracture charge");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        require(CelestialExtractionSystem.released(state, planet.id()), "second charge must reopen the field");
        require(countActiveAnchored(state, planet.id()) == idsBefore.size(),
                "second charge must reactivate the same number of rocks");
        require(state.resources.size() == resourceListBefore,
                "refire must reactivate existing nodes without allocating another field");
        require(body.resourceNodeIds.equals(idsBefore), "refire must preserve deterministic resource ids");
        for (int id : idsBefore) {
            ResourceNode node = resourceById(state, id);
            require(node != null && node.active && Math.abs(node.amount - node.maxAmount) < 0.001,
                    "refired rock must be active and restored to full amount");
        }
    }

    private static void missedDepletionCallbackSelfHealsBeforeRefire() {
        WorldSystemState state = state("extract-missed-depletion", 9110L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        Base extractor = new Base("P1:MISSED-DEPLETION-EXTRACTOR", "P1",
                CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);

        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(),
                "missed-depletion fixture first charge must fire");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        require(CelestialExtractionSystem.released(state, planet.id()),
                "missed-depletion fixture field must be released before depletion");

        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, planet.id());
        require(body != null && !body.resourceNodeIds.isEmpty(),
                "missed-depletion fixture requires a fracture field");
        for (int id : body.resourceNodeIds) {
            ResourceNode node = resourceById(state, id);
            require(node != null, "missed-depletion fracture node missing");
            node.deplete();
        }

        // Deliberately skip onDepositDepleted(...) to model a delayed/lost final mining callback.
        require(CelestialExtractionSystem.released(state, planet.id()),
                "fixture must still be stale/released before readiness reconciliation");
        require(!CelestialExtractionSystem.fireReady(state, planet.id(), "P1"),
                "self-healed exhausted field must enter recycle cooldown before refiring");
        require(!CelestialExtractionSystem.released(state, planet.id()),
                "readiness must self-heal a fully exhausted stale released field");
        require(CelestialExtractionSystem.cooldownRemaining(state, planet.id()) > 0,
                "self-healing must start the body recycle cooldown");

        state.celestials.update(CelestialExtractionSystem.REFIRE_COOLDOWN_SECONDS + 0.05);
        require(CelestialExtractionSystem.fireReady(state, planet.id(), "P1"),
                "existing anchored extractor must become fire-ready when recycle cooldown expires");
        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(),
                "existing anchored extractor must refire after a self-healed depletion cycle");
    }

    private static void extractorCooldownPreventsPlanetMoonSpam() {
        WorldSystemState state = state("extract-cooldown", 9105L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        CelestialSystem.BodyView moon = firstMoonOf(state, planet.id());
        require(moon != null, "cooldown fixture requires a moon");
        Base extractor = new Base("P1:COOLDOWN-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);

        CelestialSystem.BodyView star = null;
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() == CelestialVisualClass.STAR) {
                star = body;
                break;
            }
        }
        require(star != null, "fixture must contain a star");
        require(!CelestialExtractionSystem.fireCharge(state, star.id(), "P1").fired(),
                "invalid star fire must be rejected");
        require(CelestialExtractionSystem.extractorCooldownRemaining(state, moon.id(), "P1") <= 0.001,
                "rejected fire must not start extractor cooldown");

        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(),
                "valid planet shot must fire");
        require(CelestialExtractionSystem.extractorCooldownRemaining(state, moon.id(), "P1") > 0,
                "successful fire must start extractor cooldown");
        require(!CelestialExtractionSystem.fireCharge(state, moon.id(), "P1").fired(),
                "same extractor must not spam a slave moon during cooldown");

        state.celestials.update(CelestialExtractionSystem.EXTRACTOR_FIRE_COOLDOWN_SECONDS + 0.05);
        require(CelestialExtractionSystem.extractorCooldownRemaining(state, moon.id(), "P1") <= 0.001,
                "extractor cooldown must expire at the configured duration");
        require(CelestialExtractionSystem.fireCharge(state, moon.id(), "P1").fired(),
                "master extractor must fire at its moon after extractor cooldown expires");
    }

    private static void celestialOwnershipSurvivesSnapshotRoundTrip() {
        PlayerRegistry.reset("P1", "Extraction Snapshot Host", 0x50BEFF);
        World host = new World("Extraction Snapshot Host", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(host);

        WorldSystemState hostState = activeState(host);
        require(hostState != null, "snapshot host active system missing");
        CelestialSystem.BodyView body = planetWithMoon(hostState);

        int id = 1_700_000_123;
        ResourceNode source = new ResourceNode(id, "Celestial snapshot shard", NodeKind.SILICATE_ROCK,
                Material.IRON, 1200, 1300, 500, 8, 18);
        source.celestialAnchorBodyId = body.id();
        source.orbit(body.x(), body.y(), body.radius() + 180, 0.35, 0.02);
        host.resources.add(source);

        Base sourceExtractor = new Base("P1:SNAPSHOT-EXTRACTOR", "P1",
                CelestialExtractionSystem.EXTRACTOR_STATION_ID, body.x() + body.radius() + 145, body.y());
        sourceExtractor.celestialAnchorBodyId = body.id();
        sourceExtractor.celestialOrbitRadius = body.radius() + 145;
        sourceExtractor.celestialOrbitAngle = 0.25;
        sourceExtractor.celestialOrbitSpeed = 0.004;
        host.bases.put(sourceExtractor.id, sourceExtractor);

        ResourceSyncMode.fullForNextSnapshot();
        Snapshot encodedSource = WorldNetAccess.snapshot(host, 101);
        Snapshot parsed = SnapshotReader.read(SnapshotWriter.write(encodedSource));
        ResourceState parsedState = null;
        for (ResourceState candidate : parsed.resources()) {
            if (candidate.id() == id) {
                parsedState = candidate;
                break;
            }
        }
        require(parsedState != null, "celestial resource must be present in serialized snapshot");
        require(source.celestialAnchorBodyId.equals(parsedState.celestialAnchorBodyId()),
                "serialized ResourceState must preserve celestialAnchorBodyId");

        World client = new World("Extraction Snapshot Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(client);
        NetResourceSync.apply(client, parsed.resources());
        ResourceNode replicated = client.findResource(id);
        require(replicated != null, "client must reconstruct celestial resource node");
        require(source.celestialAnchorBodyId.equals(replicated.celestialAnchorBodyId),
                "client ResourceNode must retain celestial ownership after network reconstruction");

        ResourceSyncMode.fullForNextSnapshot();
        Snapshot replacement = SnapshotReader.read(SnapshotWriter.write(WorldNetAccess.snapshot(host, 102)));
        WorldNetAccess.applyFullView(client, replacement);
        ResourceNode afterReplacement = client.findResource(id);
        require(afterReplacement != null
                        && source.celestialAnchorBodyId.equals(afterReplacement.celestialAnchorBodyId),
                "full resource replacement must preserve celestial identity");
        Base replicatedExtractor = client.bases.get(sourceExtractor.id);
        require(replicatedExtractor != null
                        && body.id().equals(replicatedExtractor.celestialAnchorBodyId)
                        && Math.abs(replicatedExtractor.celestialOrbitRadius - sourceExtractor.celestialOrbitRadius) < 0.001
                        && Math.abs(replicatedExtractor.celestialOrbitAngle - sourceExtractor.celestialOrbitAngle) < 0.001
                        && Math.abs(replicatedExtractor.celestialOrbitSpeed - sourceExtractor.celestialOrbitSpeed) < 0.001,
                "base snapshot round-trip must preserve extractor celestial anchor/orbit metadata");
    }

    private static void extractionNetworkStateConvergesAcrossReconnect() {
        WorldSystemState server = state("extract-wire", 9106L);
        CelestialSystem.BodyView planet = planetWithMoon(server);
        Base extractor = new Base("P1:WIRE-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        server.bases.put(extractor.id, extractor);
        server.celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(server, planet.id(), "P1").fired(),
                "wire fixture charge must fire");

        WorldSystemState client = state("extract-wire", 9106L);
        Base clientExtractor = new Base(extractor.id, "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                extractor.x, extractor.y);
        client.bases.put(clientExtractor.id, clientExtractor);
        client.celestials.update(0);
        String inFlight = CelestialExtractionSystem.networkState(server.celestials);
        CelestialExtractionSystem.applyNetworkState(client.celestials, inFlight);
        require(CelestialExtractionSystem.chargeInFlight(client, planet.id()),
                "client must converge on authoritative in-flight charge");
        require(CelestialExtractionSystem.extractorCooldownRemaining(client, planet.id(), "P1") > 0,
                "client must receive authoritative extractor cooldown");

        server.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        String released = CelestialExtractionSystem.networkState(server.celestials);
        CelestialExtractionSystem.applyNetworkState(client.celestials, released);
        require(CelestialExtractionSystem.released(client, planet.id()),
                "client must converge on released body state after impact");
        require(!CelestialExtractionSystem.chargeInFlight(client, planet.id()),
                "impact snapshot must clear stale in-flight state");

        CelestialBodyState serverBody = CelestialGameplaySystem.bodyState(server, planet.id());
        require(serverBody != null, "wire server body missing");
        for (int id : serverBody.resourceNodeIds) {
            ResourceNode node = resourceById(server, id);
            require(node != null, "wire server fracture node missing");
            node.deplete();
            CelestialExtractionSystem.onDepositDepleted(node);
        }
        String recycled = CelestialExtractionSystem.networkState(server.celestials);
        CelestialExtractionSystem.applyNetworkState(client.celestials, recycled);
        require(!CelestialExtractionSystem.released(client, planet.id()),
                "client must converge on sealed state after final depletion");
        require(CelestialExtractionSystem.cooldownRemaining(client, planet.id()) > 0,
                "client must receive body recycle cooldown");

        WorldSystemState reconnect = state("extract-wire", 9106L);
        Base reconnectExtractor = new Base(extractor.id, "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                extractor.x, extractor.y);
        reconnect.bases.put(reconnectExtractor.id, reconnectExtractor);
        reconnect.celestials.update(0);
        CelestialExtractionSystem.applyNetworkState(reconnect.celestials, recycled);
        require(!CelestialExtractionSystem.released(reconnect, planet.id())
                        && CelestialExtractionSystem.cooldownRemaining(reconnect, planet.id()) > 0,
                "fresh reconnect must converge directly to authoritative recycle state");
    }

    private static void authoritativeAudioEmitsOncePerLifecycleEvent() {
        PlayerRegistry.reset("P1", "Extraction Audio Host", 0x50BEFF);
        World world = new World("Extraction Audio Host", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        SystemAudio.markNonRendered(world);

        WorldSystemState system = activeState(world);
        require(system != null, "audio fixture active system missing");
        CelestialSystem.BodyView planet = planetWithMoon(system);
        CelestialSystem.BodyView moon = firstMoonOf(system, planet.id());
        require(moon != null, "audio command fixture requires a moon");
        // This is a real World fixture, so install the extractor through the live authoritative
        // world collection. policySystemStates() refreshes the active WorldSystemState from these
        // live collections before CelestialExtractionCommand resolves the command target.
        world.bases.clear();
        Base extractor = new Base("P1:AUDIO-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        world.bases.put(extractor.id, extractor);
        world.saveActiveSystem();
        system.celestials.update(0);
        require(planet.id().equals(extractor.celestialAnchorBodyId),
                "audio command fixture must anchor the extractor through normal planetary anchoring");

        String otherSystem = system.id + "-not-viewed";
        AudioEventCenter.drain(world, "P1", system.id);
        AudioEventCenter.drain(world, "P2", otherSystem);
        CelestialExtractionSystem.FireResult result =
                CelestialExtractionCommand.apply(world, "P1", system.id, moon.id());
        require(result.fired(), "authoritative command path must fire at a slave moon from the master extractor: "
                + result.message());

        List<AudioEvent> launch = AudioEventCenter.drain(world, "P1", system.id);
        require(countCue(launch, SoundCue.EXTRACTION_CHARGE_LAUNCH) == 1,
                "successful authoritative fire must distribute launch audio exactly once");
        require(AudioEventCenter.drain(world, "P2", otherSystem).isEmpty(),
                "system-scoped launch audio must not leak to a viewer in another system");
        require(AudioEventCenter.drain(world, "P1", system.id).isEmpty(),
                "launch audio must not replay without a new event");

        system.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        require(CelestialExtractionSystem.released(system, moon.id()),
                "authoritative command path must release the targeted moon");
        require(!CelestialExtractionSystem.released(system, planet.id()),
                "authoritative moon command must not release the master planet");
        List<AudioEvent> impact = AudioEventCenter.drain(world, "P1", system.id);
        require(countCue(impact, SoundCue.EXTRACTION_FRACTURE_IMPACT) == 1,
                "authoritative impact must distribute fracture audio exactly once");
        require(AudioEventCenter.drain(world, "P2", otherSystem).isEmpty(),
                "system-scoped impact audio must not leak to a viewer in another system");
        require(AudioEventCenter.drain(world, "P1", system.id).isEmpty(),
                "impact audio must not replay without a new event");
    }

    private static int countCue(List<AudioEvent> events, SoundCue cue) {
        int count = 0;
        for (AudioEvent event : events) {
            if (event != null && event.kind() == AudioEventKind.CUE && cue.name().equals(event.argument())) count++;
        }
        return count;
    }

    private static WorldSystemState activeState(World world) {
        if (world == null) return null;
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id.equals(world.activeSystemId())) return state;
        }
        return null;
    }

    private static void releasedBodiesPersist() {
        WorldSystemState state = state("extract-persist", 9103L);
        CelestialSystem.BodyView planet = planetWithMoon(state);
        Base extractor = new Base("P1:PERSIST-EXTRACTOR", "P1", CelestialExtractionSystem.EXTRACTOR_STATION_ID,
                planet.x() + planet.radius() + 145, planet.y());
        state.bases.put(extractor.id, extractor);
        state.celestials.update(0);
        require(CelestialExtractionSystem.fireCharge(state, planet.id(), "P1").fired(), "persistence charge must fire");
        state.celestials.update(CelestialExtractionSystem.CHARGE_SECONDS + 0.05);
        Map<String,Object> saved = CelestialExtractionSystem.captureState(state);

        WorldSystemState restored = state("extract-persist", 9103L);
        CelestialExtractionSystem.restoreState(restored, saved);
        restored.celestials.update(0);
        require(CelestialExtractionSystem.released(restored, planet.id()), "released body flag must survive save/restore");
        CelestialBodyState restoredBody = CelestialGameplaySystem.bodyState(restored, planet.id());
        require(restoredBody != null && restoredBody.resourceNodeIds.size()
                        == restoredBody.profile.deposits().size() * CelestialExtractionSystem.FRAGMENTS_PER_DEPOSIT,
                "restored released body must regenerate the full deterministic fracture field");
        require(countActiveAnchored(restored, planet.id()) == restoredBody.resourceNodeIds.size(),
                "restored released field must be active");
    }

    private static double expectedVolume(CelestialBodyState body) {
        double total = 0;
        for (int slot = 0; slot < body.profile.deposits().size(); slot++) total += 1800.0 + slot * 450.0;
        return total;
    }

    private static double totalMaxVolume(WorldSystemState state, String bodyId) {
        double total = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && bodyId.equals(node.celestialAnchorBodyId)) total += node.maxAmount;
        }
        return total;
    }

    private static double totalActiveVolume(WorldSystemState state, String bodyId) {
        double total = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && node.active && bodyId.equals(node.celestialAnchorBodyId)) total += node.amount;
        }
        return total;
    }

    private static WorldSystemState state(String id, long seed) {
        StarSystemDefinition definition = StarSystems.defaultSystem();
        CelestialSystem celestials = new CelestialSystem(definition, new Random(seed));
        return new WorldSystemState(id, definition, celestials);
    }

    private static CelestialSystem.BodyView planetWithMoon(WorldSystemState state) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.visualClass() == CelestialVisualClass.STAR || body.moon()) continue;
            if (firstMoonOf(state, body.id()) != null) return body;
        }
        throw new IllegalStateException("fixture requires a planet with at least one moon");
    }

    private static CelestialSystem.BodyView firstMoonOf(WorldSystemState state, String planetId) {
        for (CelestialSystem.BodyView body : state.celestials.bodyViews()) {
            if (body.moon() && planetId.equals(body.parentId())) return body;
        }
        return null;
    }

    private static ResourceNode resourceById(WorldSystemState state, int id) {
        for (ResourceNode node : state.resources) if (node != null && node.id == id) return node;
        return null;
    }

    private static int countActiveAnchored(WorldSystemState state, String bodyId) {
        int count = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && node.active && bodyId.equals(node.celestialAnchorBodyId)) count++;
        }
        return count;
    }

    private static int countStoredAnchored(WorldSystemState state, String bodyId) {
        int count = 0;
        for (ResourceNode node : state.resources) {
            if (node != null && bodyId.equals(node.celestialAnchorBodyId)) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
