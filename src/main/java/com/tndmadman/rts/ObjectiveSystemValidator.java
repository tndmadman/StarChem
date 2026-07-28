package com.tndmadman.rts;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

final class ObjectiveSystemValidator {
    private ObjectiveSystemValidator() { }

    static void validate() {
        validateCatalog();
        validateSelectionTransport();
        validateAuthoritativeCompletion();
        validateSnapshotRoundTrip();
        validateSandbox();
    }

    private static void validateCatalog() {
        expectEquals("configured victory count", 10, VictoryConditionRules.all().size());
        EnumSet<VictoryConditionType> types = EnumSet.noneOf(VictoryConditionType.class);
        for (VictoryConditionDefinition definition : VictoryConditionRules.all()) {
            types.add(definition.type());
            expectEquals("victory lookup " + definition.id(), definition,
                    VictoryConditionRules.require(definition.id()));
            if (definition.type() == VictoryConditionType.COMPLETE_RESEARCH
                    && ResearchRules.topic(definition.value()) == null) {
                throw new IllegalStateException("Unknown research topic in victory condition: " + definition.id());
            }
            if (definition.type() == VictoryConditionType.OWN_SHIP_TYPE
                    && Rules.findShip(definition.value()) == null) {
                throw new IllegalStateException("Unknown ship type in victory condition: " + definition.id());
            }
            if (definition.type() == VictoryConditionType.OWN_STATION_TYPE
                    && Rules.findBase(definition.value()) == null) {
                throw new IllegalStateException("Unknown station type in victory condition: " + definition.id());
            }
        }
        expectEquals("all victory types represented", EnumSet.allOf(VictoryConditionType.class), types);
    }

    private static void validateSelectionTransport() {
        Config server = Config.parse(new String[]{"--server", "50123", "--name", "Victory Test",
                "--victory-condition", "fleet_muster", "--new-world"});
        expectEquals("headless victory argument", "fleet_muster",
                server.skirmishSettings.victoryConditionId());

        SkirmishSettings selected = new SkirmishSettings(SkirmishPreset.HOSTILE, NpcDifficulty.HARD,
                Set.of(), "system_dominance");
        SkirmishSettings packet = SkirmishSettings.fromPacket(selected.packet());
        expectEquals("world-info victory", "system_dominance", packet.victoryConditionId());
        expectEquals("world-info preset", SkirmishPreset.HOSTILE, packet.preset());

        Map<String,Object> saved = selected.saveMap();
        SkirmishSettings restored = SkirmishSettings.fromSaved(saved, SkirmishSettings.standard());
        expectEquals("saved victory", "system_dominance", restored.victoryConditionId());

        expectInvalidVictoryArgument();
    }

    private static void validateAuthoritativeCompletion() {
        PlayerRegistry.reset("SOLO", "Objective Tester", 0x50BEFF);
        SkirmishSettings settings = new SkirmishSettings(SkirmishPreset.STANDARD, NpcDifficulty.NORMAL,
                Set.of(), "industrial_breakthrough");
        World world = new World("Objective Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(world, settings);
        ObjectiveSystem.evaluateAuthoritative(world, 0);

        ObjectiveView active = ObjectiveSystem.view(world);
        expectEquals("research objective ID", "industrial_breakthrough", active.id());
        expectEquals("research objective active", ObjectiveStatus.ACTIVE, active.status());
        expectEquals("research objective progress", 0, active.current());
        expectEquals("research objective target", 1, active.target());

        world.completeResearch("SOLO", "advanced_industry");
        ObjectiveSystem.evaluateAuthoritative(world, 0);
        ObjectiveView completed = ObjectiveSystem.view(world);
        expectEquals("completed objective status", ObjectiveStatus.COMPLETED, completed.status());
        expectEquals("completed objective progress", 1, completed.current());
        expectEquals("completed objective commander", "Objective Tester", completed.completedBy());

        world.completedResearch.clear();
        ObjectiveSystem.evaluateAuthoritative(world, 0);
        expectEquals("completed objective remains locked", ObjectiveStatus.COMPLETED,
                ObjectiveSystem.view(world).status());
    }

    private static void validateSnapshotRoundTrip() {
        PlayerRegistry.reset("SOLO", "Snapshot Tester", 0x50BEFF);
        SkirmishSettings settings = new SkirmishSettings(SkirmishPreset.STANDARD, NpcDifficulty.NORMAL,
                Set.of(), "fleet_muster");
        World world = new World("Snapshot Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(world, settings);
        ObjectiveSystem.evaluateAuthoritative(world, 0);

        Snapshot source = WorldNetAccess.snapshot(world, 77);
        Snapshot decoded = SnapshotReader.read(SnapshotWriter.write(source));
        expectEquals("snapshot objective ID", "fleet_muster", decoded.objective().conditionId());
        expectEquals("snapshot objective status", ObjectiveStatus.ACTIVE, decoded.objective().status());
        expectEquals("snapshot objective target", 12, decoded.objective().target());
    }

    private static void validateSandbox() {
        World sandbox = new World("Sandbox Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(sandbox, new SkirmishSettings(SkirmishPreset.SANDBOX,
                NpcDifficulty.NORMAL, Set.of(), "fleet_muster"));
        ObjectiveSystem.evaluateAuthoritative(sandbox, 10);
        expectEquals("sandbox objective disabled", ObjectiveStatus.DISABLED,
                ObjectiveSystem.view(sandbox).status());
    }

    private static void expectInvalidVictoryArgument() {
        try {
            Config.parse(new String[]{"--server", "50123", "--victory-condition", "not-a-preset"});
            throw new IllegalStateException("Expected unknown victory condition to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectEquals(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual);
        }
    }
}
