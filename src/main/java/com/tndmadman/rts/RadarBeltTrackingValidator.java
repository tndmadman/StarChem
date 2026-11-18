package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Targeted, no-network-port regression gate for owner-only moving orbital-belt intelligence. */
public final class RadarBeltTrackingValidator {
    private RadarBeltTrackingValidator() { }

    public static void main(String[] args) {
        validateConfigAndTierCommands();
        validateGeometryAndDepletion();
        validateFogAndAuthorization();
        validateSystemSwitching();
        validateSaveAndLegacyDefaults();
        validateFilteredWireAndRendering();
        System.out.println("Radar belt tracking validator passed.");
    }

    private static void validateConfigAndTierCommands() {
        require(RadarTowerRules.trackedBeltLimit("radar_picket") == 1
                && RadarTowerRules.trackedBeltLimit("radar_array") == 2
                && RadarTowerRules.trackedBeltLimit("radar_nexus") == 3, "Tier caps are not 1/2/3.");
        require(RadarTowerRules.resourceDispatchLimit("radar_picket") == 20
                && RadarTowerRules.resourceDispatchLimit("radar_array") == 50
                && RadarTowerRules.resourceDispatchLimit("radar_nexus") == 100, "Worker caps changed.");
        for (StarSystemDefinition system : StarSystems.options()) {
            Set<String> ids = new HashSet<>();
            for (ResourceBelt belt : system.resourceBelts()) {
                require(!belt.id.isBlank() && ids.add(belt.id), "Invalid belt catalog in " + system.id());
            }
        }
        World world = createWorld();
        try {
            clear(world);
            List<ResourceBelt> belts = RadarBeltTrackingSystem.catalog(world);
            require(belts.size() >= 4, "Expected four independently selectable authored belts.");
            Base t1 = radar(world, "P1:B1", "P1", "radar_picket");
            Base t2 = radar(world, "P1:B2", "P1", "radar_array");
            Base t3 = radar(world, "P1:B3", "P1", "radar_nexus");
            require(RadarBeltTrackingSystem.selected(world, t1).equals(List.of(belts.get(0).id)), "T1 default incorrect");
            require(RadarBeltTrackingSystem.selected(world, t2).size() == 2, "T2 default incorrect");
            require(RadarBeltTrackingSystem.selected(world, t3).size() == 3, "T3 default incorrect");
            require(StationControlCommands.apply(world, "P1", t1.id, "RADAR_BELT_SELECT", belts.get(3).id),
                    "T1 failed to replace belt");
            require(RadarBeltTrackingSystem.selected(world, t1).equals(List.of(belts.get(3).id)),
                    "T1 retained old selected belt");
            require(!StationControlCommands.apply(world, "P1", t2.id, "RADAR_BELT_ADD", belts.get(3).id),
                    "T2 exceeded two selections");
            require(StationControlCommands.apply(world, "P1", t2.id, "RADAR_BELT_REMOVE", belts.get(1).id),
                    "T2 could not remove");
            require(StationControlCommands.apply(world, "P1", t2.id, "RADAR_BELT_ADD", belts.get(3).id),
                    "T2 could not add after freeing slot");
            require(RadarBeltTrackingSystem.selected(world, t2).size() == 2, "T2 selection count incorrect");
            require(RadarBeltTrackingSystem.view(world, "P1").tracks().size() == 4,
                    "Selected belts from distinct radars were not unioned/deduplicated");
            require(!StationControlCommands.apply(world, "P1", t3.id, "RADAR_BELT_ADD", belts.get(3).id),
                    "T3 exceeded three selections");
            require(!StationControlCommands.apply(world, "P1", t1.id, "RADAR_BELT_SELECT", "other-system-only"),
                    "Accepted nonexistent/cross-system belt");
            require(!StationControlCommands.apply(world, "P2", t1.id, "RADAR_BELT_CLEAR", ""),
                    "Accepted spoofed radar owner");
            require(!StationControlCommands.apply(world, "P1", t1.id, "RADAR_BELT_ADD", "bad_belt"),
                    "Accepted malformed belt selection");
            Base nonRadar = new Base("P1:BN", "P1", Rules.DEFAULT_BASE, 6000, 6000);
            world.bases.put(nonRadar.id, nonRadar);
            require(!StationControlCommands.apply(world, "P1", nonRadar.id, "RADAR_BELT_CLEAR", ""),
                    "Accepted belt control on non-radar base");
            require(StationControlCommands.apply(world, "P1", t3.id, "RADAR_BELT_CLEAR", ""),
                    "T3 clear rejected");
            require(RadarBeltTrackingSystem.selected(world, t3).isEmpty(), "Clear repopulated T3 slots");
            t3.hp = 0;
            require(!StationControlCommands.apply(world, "P1", t3.id, "RADAR_BELT_SELECT", belts.get(0).id),
                    "Accepted command on destroyed radar");
            require(!RadarBeltTrackingSystem.selected(world, t3).contains(belts.get(0).id),
                    "Destroyed radar revealed previously cleared belt");
        } finally { world.dispose(); }
    }

    private static void validateGeometryAndDepletion() {
        World world = createWorld();
        try {
            clear(world);
            Base radar = radar(world, "P1:B10", "P1", "radar_picket");
            ResourceBelt belt = RadarBeltTrackingSystem.catalog(world).get(0);
            double sunX = world.width * 0.5, sunY = world.height * 0.5;
            double radius = belt.orbit;
            // Two real nodes straddling the ±pi wrap must point to the left of the star.
            ResourceNode first = node(70001, belt, sunX, sunY, radius, Math.PI - 0.06);
            ResourceNode second = node(70002, belt, sunX, sunY, radius, -Math.PI + 0.07);
            world.resources.add(first); world.resources.add(second);
            RadarBeltTrackingSystem.Track atStart = RadarBeltTrackingSystem.view(world, "P1")
                    .tracks().get(belt.id);
            require(atStart != null && atStart.activeCount() == 2 && atStart.totalCount() == 2,
                    "Aggregate did not include both active belt members");
            require(atStart.x() < sunX - radius * 0.85
                    && Math.abs(atStart.y() - sunY) < radius * 0.2,
                    "Angular wrap generated a phantom position");
            require(atStart.halfArc() < 0.15, "Aggregated a full ring instead of partial arc");

            // Advance the actual nodes, not a template-defined radius/angle proxy.
            double delta = 20.0;
            first.updateOrbit(delta); second.updateOrbit(delta);
            world.systemTime += delta;
            RadarBeltTrackingSystem.Track later = RadarBeltTrackingSystem.view(world, "P1")
                    .tracks().get(belt.id);
            require(later != null && later.active() && Math.hypot(later.x() - atStart.x(), later.y() - atStart.y()) > 1,
                    "Live centroid failed to follow node movement");
            require(later.activeCount() == 2 && later.halfArc() < 0.15,
                    "Orbiting cluster dispersed unexpectedly");
            double difference = Math.atan2(Math.sin(first.orbitAngle - second.orbitAngle),
                    Math.cos(first.orbitAngle - second.orbitAngle));
            for (int i = 0; i < 120; i++) {
                first.updateOrbit(60); second.updateOrbit(60);
            }
            require(Math.abs(difference - Math.atan2(Math.sin(first.orbitAngle - second.orbitAngle),
                    Math.cos(first.orbitAngle - second.orbitAngle))) < 1e-7,
                    "Long-run belt shear separated member offsets");
            // Deliberately simulate a legacy split cluster and ensure the primary
            // bearing selects a populated segment instead of drawing a full ring.
            ResourceNode outlier = node(70003, belt, sunX, sunY, radius, 0.0);
            world.resources.add(outlier);
            world.systemTime += 0.5;
            RadarBeltTrackingSystem.Track split = RadarBeltTrackingSystem.view(world, "P1")
                    .tracks().get(belt.id);
            require(split != null && split.active() && split.halfArc() < belt.arc,
                    "Sheared orbital resource belt produced a fictitious full-ring marker");
            world.resources.remove(outlier);
            first.deplete(); second.deplete();
            world.systemTime += 0.5;
            RadarBeltTrackingSystem.Track depleted = RadarBeltTrackingSystem.view(world, "P1")
                    .tracks().get(belt.id);
            require(depleted != null && depleted.activeCount() == 0 && !depleted.active()
                    && !Double.isFinite(depleted.x()), "Depleted belt fabricated an active coordinate");
            require(RadarBeltTrackingSystem.selected(world, radar).size() == 1,
                    "Depleted belt incorrectly removed radar selection");
        } finally { world.dispose(); }
    }

    private static void validateFogAndAuthorization() {
        World world = createWorld();
        try {
            clear(world);
            Base radar = radar(world, "P1:B20", "P1", "radar_picket");
            ResourceBelt first = RadarBeltTrackingSystem.catalog(world).get(0);
            ResourceBelt second = RadarBeltTrackingSystem.catalog(world).get(1);
            ResourceNode selected = node(70011, first, radar.x, radar.y, 250, 0.3);
            ResourceNode hidden = node(70012, second, radar.x, radar.y, 280, 0.3);
            world.resources.add(selected); world.resources.add(hidden);
            require(VisibilityRules.resourceStage(world, "P1", selected)
                    .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED),
                    "AREA survey failed to identify a selected resource");
            require(VisibilityRules.resourceStage(world, "P1", hidden)
                    == IntelWarfareSystem.DetectionStage.NONE,
                    "Radar point sensor leaked unselected belt resource");
            require(StationControlCommands.apply(world, "P1", radar.id, "RADAR_SEARCH_TARGET", "WORMHOLES"),
                    "Could not switch to WORMHOLES");
            require(VisibilityRules.resourceStage(world, "P1", selected)
                    == IntelWarfareSystem.DetectionStage.NONE,
                    "WORMHOLES mode continues resource survey");
            require(RadarBeltTrackingSystem.view(world, "P1").tracks().isEmpty(),
                    "WORMHOLES mode transmitted a live cluster");
            require(StationControlCommands.apply(world, "P1", radar.id, "RADAR_SEARCH_TARGET", "AREA"),
                    "Could not resume AREA survey");
            require(RadarBeltTrackingSystem.selected(world, radar).equals(List.of(first.id)),
                    "WORMHOLES destroyed tracking choice");
            require(VisibilityRules.resourceStage(world, "P1", selected)
                    .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED),
                    "Resumed AREA survey failed");
            Base enemy = radar(world, "P2:B1", "P2", "radar_picket");
            RadarBeltTrackingSystem.View owner = RadarBeltTrackingSystem.view(world, "P1");
            RadarBeltTrackingSystem.View intruder = RadarBeltTrackingSystem.view(world, "P2");
            require(owner.radars().stream().noneMatch(r -> r.radarId().equals(enemy.id)),
                    "Owner view leaked enemy radar selection");
            require(intruder.radars().stream().noneMatch(r -> r.radarId().equals(radar.id)),
                    "Enemy view leaked owner's radar selection");
            require(owner.tracks().size() == 1 && intruder.tracks().size() == 1,
                    "Incorrect per-owner selected belt union");
        } finally { world.dispose(); }
    }

    private static void validateSystemSwitching() {
        World world = createWorld();
        try {
            String home = world.activeSystemId();
            clear(world);
            List<ResourceBelt> homeBelts = RadarBeltTrackingSystem.catalog(world);
            if (homeBelts.size() < 2) throw new IllegalStateException("No alternate home belt");
            Base homeRadar = radar(world, "P1:B45", "P1", "radar_picket");
            require(StationControlCommands.apply(world, "P1", homeRadar.id,
                    "RADAR_BELT_SELECT", homeBelts.get(1).id), "Home selection rejected");
            world.saveActiveSystem();
            String away = "";
            for (WorldSystemState state : world.policySystemStates()) {
                if (!state.id.equals(home) && !state.definition.resourceBelts().isEmpty()) {
                    away = state.id;
                    break;
                }
            }
            require(!away.isBlank(), "No second star system for independent selection regression");
            world.activateSystem(away);
            Base awayRadar = radar(world, homeRadar.id, "P1", "radar_picket");
            List<String> awayDefault = RadarBeltTrackingSystem.selected(world, awayRadar);
            require(awayDefault.size() == 1
                    && awayDefault.get(0).equals(RadarBeltTrackingSystem.catalog(world).get(0).id),
                    "Away radar reused another system's saved selection");
            require(StationControlCommands.apply(world, "P1", awayRadar.id,
                    "RADAR_BELT_CLEAR", ""), "Away system clear rejected");
            require(RadarBeltTrackingSystem.view(world, "P1").tracks().isEmpty(),
                    "Away system retained a radar track after clear");
            world.saveActiveSystem();
            world.activateSystem(home);
            Base recovered = world.bases.get(homeRadar.id);
            require(recovered != null && RadarBeltTrackingSystem.selected(world, recovered)
                    .equals(List.of(homeBelts.get(1).id)), "System switch leaked or discarded home selection");
            world.activateSystem(away);
            require(RadarBeltTrackingSystem.selected(world, world.bases.get(awayRadar.id)).isEmpty(),
                    "Empty selection from away system was replaced when switched back");
        } finally { world.dispose(); }
    }

    @SuppressWarnings("unchecked")
    private static void validateSaveAndLegacyDefaults() {
        World world = createWorld();
        World restored = null;
        World legacy = null;
        try {
            clear(world);
            Base radar = radar(world, "P1:B31", "P1", "radar_array");
            List<ResourceBelt> belts = RadarBeltTrackingSystem.catalog(world);
            require(StationControlCommands.apply(world, "P1", radar.id, "RADAR_BELT_CLEAR", ""),
                    "Could not clear saved selection");
            require(StationControlCommands.apply(world, "P1", radar.id, "RADAR_SEARCH_TARGET", "WORMHOLES"),
                    "Could not change saved search mode");
            Map<String,Object> save = world.captureServerSaveGalaxy();
            restored = createWorld();
            restored.restoreServerSaveGalaxy(save);
            restored.activateSystem(world.activeSystemId());
            Base read = restored.bases.get(radar.id);
            require(read != null && RadarBeltTrackingSystem.selected(restored, read).isEmpty(),
                    "Empty selection was overwritten with legacy defaults");
            require(StationControls.radarSearchTarget(restored, read)
                    == StationControls.RadarSearchTarget.WORMHOLES, "Search mode failed save/reload");
            require(StationControlCommands.apply(restored, "P1", read.id, "RADAR_BELT_SELECT", belts.get(2).id),
                    "Failed to edit selections after restore");
            Map<String,Object> after = restored.captureServerSaveGalaxy();
            restored.dispose(); restored = null;

            for (Object row : (List<Object>)after.get("systems")) {
                Map<String,Object> sys = (Map<String,Object>)row;
                if (world.activeSystemId().equals(sys.get("systemId"))) {
                    sys.remove("radarBeltSelections");
                    sys.remove("radarSearchTargets");
                }
            }
            legacy = createWorld();
            legacy.restoreServerSaveGalaxy(after);
            legacy.activateSystem(world.activeSystemId());
            Base prior = legacy.bases.get(radar.id);
            require(prior != null && RadarBeltTrackingSystem.selected(legacy, prior).size() == 2,
                    "Legacy save missing selection did not initialize T2 defaults");
            require(StationControls.radarSearchTarget(legacy, prior)
                    == StationControls.RadarSearchTarget.AREA, "Legacy search mode failed default");
            require(!RadarBeltTrackingSystem.selected(legacy, prior).contains(belts.get(2).id),
                    "Legacy save improperly reused an unpersisted selection");
        } finally {
            if (restored != null) restored.dispose();
            if (legacy != null) legacy.dispose();
            world.dispose();
        }
    }

    private static void validateFilteredWireAndRendering() {
        World server = createWorld();
        World client = null;
        try {
            clear(server);
            Base radar = radar(server, "P1:B90", "P1", "radar_picket");
            Base enemy = radar(server, "P2:B90", "P2", "radar_array");
            List<ResourceBelt> belts = RadarBeltTrackingSystem.catalog(server);
            ResourceNode resource = node(70022, belts.get(0), server.width / 2., server.height / 2., 300, 0.2);
            server.resources.add(resource);
            String packet = RadarBeltTrackingSystem.packet(server, "P1", server.activeSystemId());
            require(packet.startsWith("RADAR_BELT_VIEW|"), "Missing typed filtered radar packet");
            String encoded = packet.substring("RADAR_BELT_VIEW|".length());
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            require(!decoded.contains(enemy.id) && decoded.contains(radar.id),
                    "Radar wire exposed another player's radar");
            require(!decoded.contains(belts.get(2).id) && !decoded.contains(belts.get(3).id),
                    "Radar wire exposed untracked belt");
            client = createWorld();
            RadarBeltTrackingSystem.markClient(client);
            RadarBeltTrackingSystem.applyClient(client, encoded);
            require(RadarBeltTrackingSystem.view(client, "P1").radars().size() == 1
                    && RadarBeltTrackingSystem.view(client, "P1").tracks().size() == 1,
                    "Client did not apply filtered authoritative radar track");
            BufferedImage canvas = new BufferedImage(800, 700, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            try {
                RadarBeltTrackingOverlay.drawWorld(g, server, new Rectangle2D.Double(0, 0, server.width, server.height));
                RadarBeltTrackingOverlay.drawMinimap(g, server, new Rectangle(0, 0, 240, 160));
            } finally { g.dispose(); }
        } finally {
            if (client != null) client.dispose();
            server.dispose();
        }
    }

    private static World createWorld() {
        return new World("Radar orbital belt regression", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
    }

    private static void clear(World world) {
        world.units.clear(); world.bases.clear(); world.resources.clear();
        world.shots.clear(); world.items.clear();
    }

    private static Base radar(World world, String id, String player, String type) {
        Base value = new Base(id, player, type, 4000, 4000);
        world.bases.put(id, value);
        return value;
    }

    private static ResourceNode node(int id, ResourceBelt belt, double cx, double cy, double radius, double angle) {
        ResourceNode result = new ResourceNode(id, belt.name + " " + belt.materials.get(0) + " test",
                belt.kind, belt.materials.get(0), 0, 0, 500, belt.harvestRate, 12);
        result.resourceBeltId = belt.id;
        result.resourceBeltCenterAngle = angle;
        result.orbit(cx, cy, radius, angle, ResourceSpawner.speedFor(belt.orbit));
        return result;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
