package com.tndmadman.rts;

import java.util.Set;

public final class IntelWarfareValidator {
    private IntelWarfareValidator() { }

    public static void main(String[] args) {
        World world = new World("Intel warfare validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);
        PlayerRegistry.register("P3", "Teammate", 0x50BEFF, false);

        validateJsonAndProgression(world);
        validateModesSignaturesAndJamming(world);
        validateSurveyDispatchAndSharing(world);
        validateDecoysAndSnapshots(world);
        validateResponseAndAdaptiveAi(world);
        validateMemoryIsolation(world);
        System.out.println("Intel warfare validator passed.");
    }

    private static void validateJsonAndProgression(World world) {
        require(IntelWarfareSystem.isRadar("radar_picket"), "Radar role did not load from JSON.");
        require(IntelWarfareSystem.isJammer("signal_jammer"), "Jammer role did not load from JSON.");
        require(IntelWarfareSystem.isDecoy("radar_decoy"), "Decoy role did not load from JSON.");
        require(Rules.findShip(IntelWarfareSystem.CONTACT_SMALL) != null
                        && Rules.findShip(IntelWarfareSystem.CONTACT_MEDIUM) != null
                        && Rules.findShip(IntelWarfareSystem.CONTACT_LARGE) != null,
                "Anonymous contact hulls are missing.");
        require(Rules.findBase(IntelWarfareSystem.CONTACT_STATION) != null,
                "Anonymous contact station is missing.");
        require(Rules.base(Rules.DEFAULT_BASE).basePackages.contains("signal_jammer")
                        && Rules.base(Rules.DEFAULT_BASE).basePackages.contains("radar_decoy"),
                "Outpost does not expose counterintel packages.");
        require(!StationPackageResearchRules.unlocked(world, "P1", "signal_jammer"),
                "Signal Jammer unlocked before research.");
        world.completeResearch("P1", "advanced_industry");
        require(StationPackageResearchRules.unlocked(world, "P1", "signal_jammer")
                        && StationPackageResearchRules.unlocked(world, "P1", "radar_decoy"),
                "Advanced Industry did not unlock counterintel packages.");
    }

    private static void validateModesSignaturesAndJamming(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_picket", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.PASSIVE, "P1");
        double passive = VisibilityRules.baseSensorRange(world, radar);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        double active = VisibilityRules.baseSensorRange(world, radar);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        double focused = VisibilityRules.baseSensorRange(world, radar);
        require(passive < active && active < focused, "Radar modes do not scale range.");

        Unit target = unit(world, "P2", 1, "prospector", radar.x + active * 0.95, radar.y);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        IntelWarfareSystem.DetectionStage quiet = VisibilityRules.unitStage(world, "P1", target);
        target.weaponFlashTimer = 1;
        require(VisibilityRules.unitStage(world, "P1", target).ordinal() > quiet.ordinal(),
                "Weapon fire did not increase signature.");
        target.weaponFlashTimer = 0;

        double unjammed = VisibilityRules.baseSensorRange(world, radar);
        base(world, "P2:J1", "P2", "signal_jammer", radar.x + 80, radar.y);
        double jammed = VisibilityRules.baseSensorRange(world, radar);
        require(jammed < unjammed * 0.8, "Jammer did not reduce radar range.");
        world.bases.remove("P2:J1");

        Base nexus = base(world, "P1:R3", "P1", "radar_nexus", 6_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, nexus, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        double nexusNormal = VisibilityRules.baseSensorRange(world, nexus);
        base(world, "P2:J2", "P2", "signal_jammer", nexus.x + 80, nexus.y);
        double nexusJammed = VisibilityRules.baseSensorRange(world, nexus);
        require(nexusJammed / nexusNormal > jammed / unjammed,
                "Higher-tier counter-jamming did not improve resistance.");
    }

    private static void validateSurveyDispatchAndSharing(World world) {
        clear(world);
        Base radar = base(world, "P1:R2", "P1", "radar_array", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        ResourceNode resource = new ResourceNode(77, "Survey iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 4_500, 4_000, 500, 5, 3);
        world.resources.add(resource);
        require(VisibilityRules.resourceStage(world, "P1", resource) == IntelWarfareSystem.DetectionStage.DETAILED,
                "Radar Array did not fully survey a nearby resource.");
        for (int i = 1; i <= 8; i++) unit(world, "P1", i, "prospector", 1_000, 1_000 + i * 20);
        new ScoutSystem().update(world);
        int assigned = 0;
        for (Unit unit : world.units.values()) {
            if (unit.task == UnitTask.AUTO_HARVEST && unit.automationResourceId == resource.id) assigned++;
        }
        require(assigned == IntelWarfareSystem.dispatchLimit(radar.typeId),
                "Radar dispatch ignored its JSON limit.");

        clear(world);
        Base teammateRadar = base(world, "P3:R1", "P3", "radar_array", 5_000, 5_000);
        IntelWarfareSystem.setRadarMode(world, teammateRadar, IntelWarfareSystem.RadarMode.ACTIVE, "P3");
        Unit enemy = unit(world, "P2", 90, "frigate", 5_300, 5_000);
        require(VisibilityRules.unitStage(world, "P1", enemy) == IntelWarfareSystem.DetectionStage.NONE,
                "Unshared teammate radar leaked intel.");
        IntelWarfareSystem.setIntelAlliance(world, "P1", "P3", true);
        require(VisibilityRules.unitStage(world, "P1", enemy)
                        .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED),
                "Allied radar did not share intel.");
        IntelWarfareSystem.setIntelAlliance(world, "P1", "P3", false);
    }

    private static void validateDecoysAndSnapshots(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_picket", 2_000, 2_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.ACTIVE, "P1");
        Base decoy = base(world, "P2:D1", "P2", "radar_decoy", 6_000, 2_000);
        IntelWarfareSystem.DetectionStage distant = VisibilityRules.baseStage(world, "P1", decoy);
        require(distant == IntelWarfareSystem.DetectionStage.CLASSIFIED,
                "Distant decoy did not appear as a false classified station.");
        world.bases.put(decoy.id, new Base(decoy.id, decoy.playerId, decoy.typeId, 2_240, 2_000));
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        require(VisibilityRules.baseStage(world, "P1", world.bases.get(decoy.id))
                        == IntelWarfareSystem.DetectionStage.DETAILED,
                "Focused close scan did not expose the decoy.");

        clear(world);
        radar = base(world, "P1:R1", "P1", "radar_picket", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.PASSIVE, "P1");
        Unit enemy = unit(world, "P2", 41, "prospector", 5_150, 4_000);
        Snapshot filtered = FogSnapshotFilter.forPlayer(world, "P1", WorldNetAccess.snapshot(world, 1));
        boolean anonymous = false;
        for (UnitState state : filtered.units()) {
            anonymous |= "SENSOR_CONTACT".equals(state.playerId())
                    && state.shipTypeId().startsWith("sensor_contact_");
            require(!("P2".equals(state.playerId()) && state.unitId() == enemy.unitId),
                    "Weak contact leaked the authoritative enemy key.");
        }
        require(anonymous, "Weak return was not anonymized.");
        enemy.x = 4_180;
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        filtered = FogSnapshotFilter.forPlayer(world, "P1", WorldNetAccess.snapshot(world, 2));
        boolean exact = false;
        for (UnitState state : filtered.units()) {
            exact |= "P2".equals(state.playerId()) && state.unitId() == enemy.unitId
                    && "prospector".equals(state.shipTypeId());
        }
        require(exact, "Detailed scan did not reveal exact identity.");
    }

    private static void validateResponseAndAdaptiveAi(World world) {
        clear(world);
        Base radar = base(world, "P1:R1", "P1", "radar_array", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        Unit guard = unit(world, "P1", 1, "frigate", 3_900, 4_000);
        guard.orderType = UnitOrderType.GUARD;
        guard.orderTarget = CombatTarget.base(radar);
        Unit enemy = unit(world, "P2", 2, "frigate", 4_300, 4_000);
        IntelWarfareSystem.update(world, 0.5);
        require(CombatTarget.unit(enemy).equals(guard.attackTarget),
                "Radar did not dispatch its assigned guard.");

        clear(world);
        radar = base(world, "P1:R4", "P1", "radar_picket", 4_000, 4_000);
        ScoutSystem automation = new ScoutSystem();
        world.systemTime = 0;
        automation.update(world);
        world.systemTime = 2;
        automation.update(world);
        require(IntelWarfareSystem.radarMode(world, radar) == IntelWarfareSystem.RadarMode.PASSIVE,
                "Quiet radar did not enter passive mode.");
        unit(world, "P2", 9, "frigate", 4_180, 4_000);
        world.systemTime = 2.2;
        automation.update(world);
        require(IntelWarfareSystem.radarMode(world, radar) == IntelWarfareSystem.RadarMode.FOCUSED,
                "Radar did not focus on an identified threat.");
    }

    private static void validateMemoryIsolation(World world) {
        clear(world);
        String first = world.activeSystemId();
        Base radar = base(world, "P1:R1", "P1", "radar_array", 4_000, 4_000);
        IntelWarfareSystem.setRadarMode(world, radar, IntelWarfareSystem.RadarMode.FOCUSED, "P1");
        unit(world, "P2", 1, "frigate", 4_200, 4_000);
        IntelWarfareSystem.update(world, 0.5);
        require(!IntelWarfareSystem.memories(world, "P1").isEmpty(), "Visible contact was not remembered.");
        require(IntelWarfareSystem.uncertainty(IntelWarfareSystem.DetectionStage.IDENTIFIED, 12)
                        > IntelWarfareSystem.uncertainty(IntelWarfareSystem.DetectionStage.IDENTIFIED, 0),
                "Uncertainty does not grow with age.");
        String second = "";
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !first.equals(system.id())) { second = system.id(); break; }
        }
        require(!second.isBlank(), "No second system available for isolation test.");
        world.saveActiveSystem();
        world.activateSystem(second);
        require(IntelWarfareSystem.memories(world, "P1").isEmpty(), "Intel leaked between systems.");
        world.activateSystem(first);
    }

    private static void clear(World world) {
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
    }

    private static Base base(World world, String id, String player, String type, double x, double y) {
        Base base = new Base(id, player, type, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static Unit unit(World world, String player, int id, String type, double x, double y) {
        Unit unit = new Unit(player, id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
