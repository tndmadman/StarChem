package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class StationControlValidator {
    private StationControlValidator() { }

    public static void main(String[] args) {
        require(!StationControls.nonProduction("outpost") && "production".equals(StationControls.role("outpost")),
                "Outpost production interaction was not loaded from JSON.");
        require(StationControls.nonProduction("radar_picket") && "radar".equals(StationControls.role("radar_picket")),
                "Radar non-production role was not loaded from JSON.");
        require(StationControls.nonProduction("signal_jammer") && "jammer".equals(StationControls.role("signal_jammer")),
                "Jammer non-production role was not loaded from JSON.");
        require(StationControls.nonProduction("radar_decoy") && "decoy".equals(StationControls.role("radar_decoy")),
                "Decoy non-production role was not loaded from JSON.");
        require(StationControlMenu.handles("outpost") && StationControlMenu.handles("radar_array")
                        && StationControlMenu.handles("signal_jammer") && StationControlMenu.handles("radar_decoy"),
                "Owned stations are not routed through the unified control/logistics menu.");

        World world = new World("Station control validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Operator", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();

        Base radar = new Base("P1:R1", "P1", "radar_picket", 4_000, 4_000);
        world.bases.put(radar.id, radar);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        ResourceNode iron = new ResourceNode(1, "Iron field", NodeKind.SILICATE_ROCK,
                Material.IRON, 4_450, 4_000, 500, 5, 3);
        ResourceNode copper = new ResourceNode(2, "Copper field", NodeKind.SILICATE_ROCK,
                Material.COPPER, 4_850, 4_000, 500, 5, 3);
        world.resources.add(iron);
        world.resources.add(copper);

        List<Material> candidates = StationControls.radarCandidates(world, radar);
        require(candidates.size() == 2 && candidates.contains(Material.IRON) && candidates.contains(Material.COPPER),
                "Radar menu did not restrict itself to materials present in the current system.");
        require(!candidates.contains(Material.HYDROGEN),
                "Radar menu included a material that is not present in the current system.");
        require(ProductionCommands.apply(world, "P1", "CONTROL", radar.id,
                        "RADAR_PRIORITY_TOP", Material.COPPER.name()),
                "Authoritative production command path rejected a radar control command.");
        require(!ProductionCommands.apply(world, "P2", "CONTROL", radar.id,
                        "RADAR_PRIORITY_TOP", Material.IRON.name()),
                "A non-owner changed radar priorities.");

        Unit miner = new Unit("P1", 1, "prospector", 1_000, 1_000);
        world.units.put(miner.key(), miner);
        new ScoutSystem().update(world);
        require(miner.task == UnitTask.AUTO_HARVEST && miner.automationResourceId == copper.id,
                "Radar miner dispatch ignored the configured resource priority.");

        Base decoy = new Base("P2:D1", "P2", "radar_decoy", 6_000, 2_000);
        world.bases.put(decoy.id, decoy);
        require(StationControls.decoyProfiles(decoy.typeId).contains("shipyard"),
                "Decoy spoof profiles were not loaded from JSON.");
        require(StationControlCommands.apply(world, "P2", decoy.id, "DECOY_PROFILE", "shipyard"),
                "Decoy spoof profile command was rejected.");
        require("shipyard".equals(StationControls.decoySpoofType(world, decoy)),
                "Decoy did not retain its selected spoof profile.");

        world.bases.remove(radar.id);
        Base observer = new Base("P1:R2", "P1", "radar_picket", 2_000, 2_000);
        world.bases.put(observer.id, observer);
        IntelWarfareSystem.setRadarMode(world, observer, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        Snapshot filtered = FogSnapshotFilter.forPlayer(world, "P1", WorldNetAccess.snapshot(world, 1));
        boolean spoofed = false;
        for (BaseState state : filtered.bases()) {
            if ("shipyard".equals(state.typeId()) && !decoy.id.equals(state.id())) spoofed = true;
        }
        require(spoofed, "Filtered intel snapshot did not present the selected decoy spoof signal.");

        validateSharedMinerAssignments();
        validateRadarWormholeSearch();

        System.out.println("Station control validator passed.");
    }

    private static void validateSharedMinerAssignments() {
        World world = new World("Shared miner assignment validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Miner Operator", 0x50BEFF);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();

        Unit first = new Unit("P1", 1, "prospector", 1_000, 1_000);
        Unit second = new Unit("P1", 2, "prospector", 1_000, 1_000);
        world.units.put(first.key(), first);
        world.units.put(second.key(), second);
        ResourceNode near = new ResourceNode(101, "Near iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 1_020, 1_000, 500, 5, 3);
        ResourceNode alternate = new ResourceNode(102, "Alternate iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 1_040, 1_000, 500, 5, 3);
        world.resources.add(near);
        world.resources.add(alternate);

        new ScoutSystem().update(world);
        require(first.task == UnitTask.AUTO_HARVEST && second.task == UnitTask.AUTO_HARVEST,
                "Shared miner assignment optimization left an eligible miner idle.");
        require(first.automationResourceId != second.automationResourceId,
                "Shared miner assignment counts were not updated after the first assignment.");
    }

    private static void validateRadarWormholeSearch() {
        World world = new World("Radar wormhole search validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Searcher", 0x50BEFF);
        PlayerRegistry.register("P2", "Intruder", 0xFF5F55, false);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();

        Base radar = new Base("P1:SEARCH-RADAR", "P1", RadarTowerRules.TIER_ONE,
                world.width * 0.38, world.height * 0.50);
        world.bases.put(radar.id, radar);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        require(StationControls.radarSearchTarget(world, radar) == StationControls.RadarSearchTarget.AREA,
                "Radar search target did not default to AREA.");

        double areaRange = VisibilityRules.baseSensorRange(world, radar);
        double wormholeRange = StationControls.wormholeSearchRange(world, radar);
        require(wormholeRange > areaRange * 1.5,
                "Wormhole-focused radar did not receive a meaningfully larger search radius.");

        double probeX = radar.x;
        double probeY = radar.y + areaRange * 0.72;
        double gateDistance = Math.min(wormholeRange * 0.86, areaRange * 1.45);
        double gateX = radar.x + gateDistance;
        double gateY = radar.y;
        WormholeGate gate = new WormholeGate("focused-search-gate", world.activeSystemId(),
                "focused-search-target", gateX, gateY, gateX + 180, gateY);
        world.wormholes.add(gate);

        VisibilityRules.Frame area = VisibilityRules.frame(world, "P1");
        require(area.pointVisible(probeX, probeY),
                "AREA radar did not reveal its normal tactical scan region.");
        require(!area.pointVisible(gate.x, gate.y),
                "AREA radar unexpectedly revealed a wormhole outside its normal scan range.");

        require(!ProductionCommands.apply(world, "P2", "CONTROL", radar.id,
                        "RADAR_SEARCH_TARGET", StationControls.RadarSearchTarget.WORMHOLES.name()),
                "A non-owner changed the radar wormhole search target.");
        require(!ProductionCommands.apply(world, "P1", "CONTROL", radar.id,
                        "RADAR_SEARCH_TARGET", "INVALID"),
                "Radar accepted an invalid search target.");
        require(ProductionCommands.apply(world, "P1", "CONTROL", radar.id,
                        "RADAR_SEARCH_TARGET", StationControls.RadarSearchTarget.WORMHOLES.name()),
                "Authoritative command path rejected wormhole search mode.");
        require(StationControls.radarSearchTarget(world, radar) == StationControls.RadarSearchTarget.WORMHOLES,
                "Radar did not retain its wormhole search target.");

        VisibilityRules.Frame focused = VisibilityRules.frame(world, "P1");
        require(!focused.pointVisible(probeX, probeY),
                "Wormhole search still revealed the radar's general area scan region.");
        require(focused.pointVisible(gate.x, gate.y),
                "Wormhole search did not create a discovery aperture at an in-range gate.");

        ResourceNode resource = new ResourceNode(77, "Focused hidden resource", NodeKind.SILICATE_ROCK,
                Material.IRON, probeX, probeY, 100, 5, 3);
        world.resources.add(resource);
        require(focused.resourceStage(resource) == IntelWarfareSystem.DetectionStage.NONE,
                "Wormhole search continued surveying general-area resources.");

        ServerFogOfWarState.configureForTest(world, ServerFogOfWarStore.disabled());
        ServerFogOfWarState.observeSystem(world, "P1", world.activeSystemId());
        String packet = ServerFogOfWarState.packet(world, "P1", world.activeSystemId());
        require(packet.startsWith("FOG_STATE|"),
                "Authoritative fog state was not produced for focused wormhole discovery.");
        ServerFogOfWarState.Stored stored = ServerFogOfWarState.decode(packet.substring("FOG_STATE|".length()));
        require(stored != null && stored.wormholes().stream()
                        .anyMatch(known -> gate.id.equals(known.id()) && gate.toSystemId.equals(known.toSystemId())),
                "Server-authoritative fog state did not remember the focused wormhole discovery.");

        require(ProductionCommands.apply(world, "P1", "CONTROL", radar.id,
                        "RADAR_SEARCH_TARGET", StationControls.RadarSearchTarget.AREA.name()),
                "Radar could not return to AREA scanning.");
        require(VisibilityRules.frame(world, "P1").pointVisible(probeX, probeY),
                "AREA scanning did not resume after leaving wormhole search.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
