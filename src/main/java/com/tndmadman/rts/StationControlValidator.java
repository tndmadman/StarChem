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
        require(!StationControlMenu.handles("outpost") && StationControlMenu.handles("radar_array")
                        && StationControlMenu.handles("signal_jammer") && StationControlMenu.handles("radar_decoy"),
                "Station click routing does not follow the JSON non-production flag.");

        World world = new World("Station control validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Operator", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);

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

        System.out.println("Station control validator passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
