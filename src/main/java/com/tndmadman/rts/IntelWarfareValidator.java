package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class IntelWarfareValidator {
    private IntelWarfareValidator() { }

    public static void main(String[] args) {
        World world = new World("Intel warfare validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);
        PlayerRegistry.register("P3", "Teammate", 0x50BEFF, false);

        validateConfiguration(world);
        validateModesSignaturesAndJamming(world);
        validateSurveyingAndDispatch(world);
        validateDecoys(world);
        validateSharedIntel(world);
        validateStagedSnapshots(world);
        validateRadarResponse(world);
        validateAdaptiveModes(world);
        validateSystemIsolation(world);
        validateUncertainty();
        System.out.println("Intel warfare validator passed.");
    }

    private static void validateConfiguration(World world) {
        require(IntelWarfareSystem.isRadar("radar_picket"), "Radar role was not loaded from station JSON.");
        require(IntelWarfareSystem.isJammer("signal_jammer"), "Jammer role was not loaded from station JSON.");
        require(IntelWarfareSystem.isDecoy("radar_decoy"), "Decoy role was not loaded from station JSON.");
        require(Rules.findShip(IntelWarfareSystem.CONTACT_SMALL) != null
                        && Rules.findShip(IntelWarfareSystem.CONTACT_MEDIUM) != null
                        && Rules.findShip(IntelWarfareSystem.CONTACT_LARGE) != null,
                "Anonymous sensor-contact hulls are missing.");
        require(Rules.findBase(IntelWarfareSystem.CONTACT_STATION) != null,
                "Anonymous sensor-contact station is missing.");
        require("advanced_industry".equals(StationPackageResearchRules.requiredResearchId("signal_jammer")),
                "Signal Jammer research gate was not loaded from JSON.");
        require("advanced_industry".equals(StationPackageResearchRules.requiredResearchId("radar_decoy")),
                "Strategic Decoy research gate was not loaded from JSON.");
        require(!StationPackageResearchRules.unlocked(world, "P1", "signal_jammer"),
                "Signal Jammer unlocked before Advanced Industry.");
        world.completeResearch("P1", "advanced_industry");
        require(StationPackageResearchRules.unlocked(world, "P1", "signal_jammer")
                        && StationPackageResearchRules.unlocked(world, "P1", "radar_decoy"),
                "Advanced Industry did not unlock counterintel packages.");
        require(Rules.base(Rules.DEFAULT_BASE).basePackages.contains("signal_jammer")
                        && Rules.base(Rules.DEFAULT_BASE).basePackages.contains("radar_decoy"),
                "Outpost does not expose jammer and decoy packages.");
        require(Rules.ship("station_builder").stationPackageTypes.contains("signal_jammer")
                        && Rules.ship("station_builder").stationPackageTypes.contains("radar_decoy"),
                "Deployer cannot carry jammer and decoy packages.");
    }

    private static void validateModesSignaturesAndJamming(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_picket", 4_000, 4_000);
        require(IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.PASSIVE, "P1"),
                "Could not set passive radar mode.");
        double passive = VisibilityRules.baseSensorRange(world, radar);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        double active = VisibilityRules.baseSensorRange(world, radar);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        double focused = VisibilityRules.baseSensorRange(world, radar);
        require(passive < active && active < focused, "Radar scan modes do not scale sensor range.");

        Unit target = unit(world, "P2", 1, "prospector", radar.x + active * 0.95, radar.y);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        IntelWarfareSystem.DetectionStage quiet = VisibilityRules.unitStage(world, "P1", target);
        target.weaponFlashTimer = 1;
        IntelWarfareSystem.DetectionStage firing = VisibilityRules.unitStage(world, "P1", target);
        require(firing.ordinal() > quiet.ordinal(), "Weapon fire did not increase ship signature.");
        target.weaponFlashTimer = 0;
        target.x = radar.x + 220;
        target.y = radar.y;
        require(VisibilityRules.unitStage(world, "P1", target)
                        .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED),
                "Close radar contact was not identified.");

        double unjammed = VisibilityRules.baseSensorRange(world, radar);
        base(world, "P2:J1", "P2", "signal_jammer", radar.x + 80, radar.y);
        double jammed = VisibilityRules.baseSensorRange(world, radar);
        require(jammed < unjammed * 0.8, "Signal Jammer did not materially reduce enemy radar range.");
        world.bases.remove("P2:J1");

        Base nexus = base(world, "P1:R3", "P1", "radar_nexus", 6_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, nexus, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        double nexusUnjammed = VisibilityRules.baseSensorRange(world, nexus);
        base(world, "P2:J2", "P2", "signal_jammer", nexus.x + 80, nexus.y);
        double nexusJammed = VisibilityRules.baseSensorRange(world, nexus);
        require(nexusJammed / nexusUnjammed > jammed / unjammed,
                "Higher-tier radar counter-jamming did not improve resistance.");
    }

    private static void validateSurveyingAndDispatch(World world) {
        clear(world);
        Base picket = base(world, "P1:R1", "P1", "radar_picket", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, picket, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        ResourceNode node = new ResourceNode(77, "Survey iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 4_500, 4_000, 500, 5, 3);
        world.resources.add(node);
        require(VisibilityRules.resourceStage(world, "P1", node)
                        .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED),
                "Tier-one radar did not identify a nearby resource for dispatch.");
        world.bases.remove(picket.id);
        Base array = base(world, "P1:R2", "P1", "radar_array", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, array, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        require(VisibilityRules.resourceStage(world, "P1", node) == IntelWarfareSystem.DetectionStage.DETAILED,
                "Tier-two radar did not provide a detailed resource survey.");

        for (int i = 1; i <= 8; i++) unit(world, "P1", i, "prospector", 1_000, 1_000 + i * 20);
        new ScoutSystem().update(world);
        int assigned = 0;
        for (Unit miner : world.units.values()) {
            if (miner.task == UnitTask.AUTO_HARVEST && miner.automationResourceId == node.id) assigned++;
        }
        require(assigned == IntelWarfareSystem.dispatchLimit(array.typeId),
                "Radar worker dispatch did not honor the JSON limit.");
    }

    private static void validateDecoys(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_picket", 2_000, 2_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        Base decoy = base(world, "P2:D1", "P2", "radar_decoy", 6_000, 2_000);
        IntelWarfareSystem.DetectionStage distant = VisibilityRules.baseStage(world, "P1", decoy);
        require(distant == IntelWarfareSystem.DetectionStage.CLASSIFIED,
                "Strategic Decoy did not appear as a classified false station contact.");
        decoy = replaceBase(world, decoy, 2_240, 2_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        require(VisibilityRules.baseStage(world, "P1", decoy) == IntelWarfareSystem.DetectionStage.DETAILED,
                "Close focused scan could not expose the strategic decoy.");
    }

    private static void validateSharedIntel(World world) {
        clear(world);
        Base teammateRadar = base(world, "P3:R1", "P3", "radar_array", 5_000, 5_000);
        IntelWarfareSystem.setRadarMode(world, teammateRadar, IntelWarfareSystem.RadarMode.ACTIVE, "P3");
        Unit enemy = unit(world, "P2", 1, "frigate", 5_300, 5_000);
        require(VisibilityRules.unitStage(world, "P1", enemy) == IntelWarfareSystem.DetectionStage.NONE,
                "Player received teammate radar before intel sharing was enabled.");
        IntelWarfareSystem.setIntelAlliance(world, "P1", "P3", true);
        require(VisibilityRules.unitStage(world, "P1", enemy)
                        .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED),
                "Shared allied radar did not reveal the teammate's contact.");
        IntelWarfareSystem.setIntelAlliance(world, "P1", "P3", false);
        require(VisibilityRules.unitStage(world, "P1", enemy) == IntelWarfareSystem.DetectionStage.NONE,
                "Disabling shared intel did not remove teammate sensors.");
    }

    private static void validateStagedSnapshots(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_picket", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.PASSIVE, "P1");
        Unit enemy = unit(world, "P2", 41, "prospector", 5_150, 4_000);
        Snapshot filtered = FogSnapshotFilter.forPlayer(world, "P1", WorldNetAccess.snapshot(world, 1));
        UnitState anonymous = null;
        for (UnitState state : filtered.units()) {
            if ("SENSOR_CONTACT".equals(state.playerId())) anonymous = state;
            require(!("P2".equals(state.playerId()) && state.unitId() == enemy.unitId),
                    "Weak sensor return leaked the exact enemy entity key.");
        }
        require(anonymous != null && anonymous.shipTypeId().startsWith("sensor_contact_"),
                "Weak sensor return was not represented as an anonymous contact.");

        enemy.x = 4_180;
        enemy.y = 4_000;
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        filtered = FogSnapshotFilter.forPlayer(world, "P1", WorldNetAccess.snapshot(world, 2));
        UnitState identified = null;
        for (UnitState state : filtered.units()) {
            if ("P2".equals(state.playerId()) && state.unitId() == enemy.unitId) identified = state;
        }
        require(identified != null && "prospector".equals(identified.shipTypeId()),
                "Detailed scan did not reveal the exact enemy hull.");
    }

    private static void validateRadarResponse(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_array", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        Unit guard = unit(world, "P1", 1, "frigate", 3_900, 4_000);
        guard.orderType = UnitOrderType.GUARD;
        guard.orderTarget = CombatTarget.base(radar);
        Unit enemy = unit(world, "P2", 2, "frigate", 4_300, 4_000);
        IntelWarfareSystem.update(world, 0.5);
        require(CombatTarget.unit(enemy).equals(guard.attackTarget) && guard.task == UnitTask.ATTACK,
                "Radar did not dispatch its assigned guard ship to an identified threat.");
    }

    private static void validateAdaptiveModes(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_picket", 4_000, 4_000);
        ScoutSystem automation = new ScoutSystem();
        world.systemTime = 0;
        automation.update(world);
        world.systemTime = 2.0;
        automation.update(world);
        require(IntelWarfareSystem.radarMode(world, radar) == IntelWarfareSystem.RadarMode.PASSIVE,
                "Quiet radar did not settle into passive mode.");
        Unit enemy = unit(world, "P2", 9, "frigate", 4_180, 4_000);
        world.systemTime = 2.2;
        automation.update(world);
        require(IntelWarfareSystem.radarMode(world, radar) == IntelWarfareSystem.RadarMode.FOCUSED,
                "Radar did not focus on an identified threat.");
        require(enemy.hp > 0, "Adaptive mode validation target was destroyed unexpectedly.");
    }

    private static void validateSystemIsolation(World world) {
        clear(world);
        String firstSystem = world.activeSystemId();
        Base radar = base(world, "P1:R1", "P1", "radar_array", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        unit(world, "P2", 1, "frigate", 4_200, 4_000);
        IntelWarfareSystem.update(world, 0.5);
        require(!IntelWarfareSystem.memories(world, "P1").isEmpty(),
                "Intel memory did not record a visible contact.");

        String secondSystem = "";
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && !firstSystem.equals(system.id())) { secondSystem = system.id(); break; }
        }
        require(!secondSystem.isBlank(), "Could not select a second system for intel isolation.");
        world.saveActiveSystem();
        world.activateSystem(secondSystem);
        require(IntelWarfareSystem.memories(world, "P1").isEmpty(),
                "Intel memory leaked into another star system.");
        world.activateSystem(firstSystem);
    }

    private static void validateUncertainty() {
        double fresh = IntelWarfareSystem.uncertainty(IntelWarfareSystem.DetectionStage.IDENTIFIED, 0);
        double stale = IntelWarfareSystem.uncertainty(IntelWarfareSystem.DetectionStage.IDENTIFIED, 12);
        require(stale > fresh, "Last-known contact uncertainty does not grow with age.");
    }

    private static void clear(World world) {
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
    }

    private static Base base(World world, String id, String playerId, String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static Base replaceBase(World world, Base old, double x, double y) {
        Base replacement = new Base(old.id, old.playerId, old.typeId, x, y);
        world.bases.put(old.id, replacement);
        return replacement;
    }

    private static Unit unit(World world, String playerId, int id, String typeId, double x, double y) {
        Unit unit = new Unit(playerId, id, typeId, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
