package com.tndmadman.rts;

import java.util.Set;

final class ObjectiveSystemValidator {
    private ObjectiveSystemValidator() { }

    static void validate() {
        PlayerRegistry.reset("SOLO", "Objective Tester", 0x50BEFF);
        World standard = new World("Objective Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(standard, SkirmishSettings.standard());

        ObjectiveView active = ObjectiveSystem.view(standard);
        expectEquals("standard objective ID", ObjectiveSystem.ADVANCED_INDUSTRY_OBJECTIVE_ID, active.id());
        expectEquals("standard objective active", ObjectiveStatus.ACTIVE, active.status());
        expectEquals("standard objective progress", 0, active.current());
        expectEquals("standard objective target", 1, active.target());

        standard.completeResearch("SOLO", ObjectiveSystem.ADVANCED_INDUSTRY_TOPIC_ID);
        ObjectiveView completed = ObjectiveSystem.view(standard);
        expectEquals("completed objective status", ObjectiveStatus.COMPLETED, completed.status());
        expectEquals("completed objective progress", 1, completed.current());
        expectEquals("completed objective commander", "Objective Tester", completed.completedBy());

        World sandbox = new World("Sandbox Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(sandbox, SkirmishSettings.create(SkirmishPreset.SANDBOX, NpcDifficulty.NORMAL));
        expectEquals("sandbox objective disabled", ObjectiveStatus.DISABLED, ObjectiveSystem.view(sandbox).status());
    }

    private static void expectEquals(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual);
        }
    }
}
