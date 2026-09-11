package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Headless acceptance checks for the player-facing gaps left in issues #380 and #382. */
public final class Issue380382UiAcceptanceValidator {
    private Issue380382UiAcceptanceValidator() { }

    public static void main(String[] args) {
        validatePersistentFleetPresentation();
        validateBranchingResearchPresentation();
        System.out.println("Issues #380/#382 strategic UI acceptance validation passed.");
    }

    private static void validatePersistentFleetPresentation() {
        PlayerRegistry.reset("SOLO", "Fleet UI acceptance", 0x50BEFF);
        World world = new World("Fleet UI acceptance", Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
        PlayerRegistry.activate(world);
        Unit first = soloUnit(world);
        Unit second = world.spawnShip(Rules.STARTING_SHIP,
                Calc.clamp(first.x + 60, 30, world.width - 30), Calc.clamp(first.y + 30, 30, world.height - 30));
        world.saveActiveSystem();
        FleetCreateResult created = FleetManager.create(world, "SOLO", "UI Vanguard", List.of(first.key(), second.key()));
        require(created.result() == FleetMutationResult.APPLIED, "fleet UI fixture should create persistent fleet");
        FleetView fleet = FleetManager.view(world, "SOLO", created.fleetId()).orElseThrow();
        require(FleetManager.setFormation(world, "SOLO", fleet.fleetId(), fleet.revision(), FleetFormation.WEDGE)
                        == FleetMutationResult.APPLIED,
                "fleet UI fixture should apply formation");

        List<StrategicCommandCenter.FleetUiRow> rows = StrategicCommandCenter.fleetRowsForTest(world, "SOLO");
        require(rows.size() == 1, "fleet command UI must expose persistent fleet entities rather than individual ship rows");
        StrategicCommandCenter.FleetUiRow row = rows.get(0);
        require(row.fleetId() == created.fleetId() && "UI Vanguard".equals(row.name()),
                "fleet command UI must retain stable identity and player name");
        require(row.ships() == 2 && row.members().size() == 2,
                "fleet command UI must expose authoritative composition");
        require("WEDGE".equals(row.formation()) && !row.stance().isBlank() && !row.priority().isBlank(),
                "fleet command UI must expose formation and combat policy");
        require(!row.system().isBlank() && row.systems() == 1,
                "fleet command UI must expose strategic system location");
        require(!row.order().isBlank() && !row.orderState().isBlank(),
                "fleet command UI must expose strategic order state");
    }

    private static void validateBranchingResearchPresentation() {
        PlayerRegistry.reset("SOLO", "Research UI acceptance", 0x50BEFF);
        World world = new World("Research UI acceptance", Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
        PlayerRegistry.activate(world);
        List<StrategicCommandCenter.ResearchUiNode> nodes = StrategicCommandCenter.researchNodesForTest(world, "SOLO");
        require(nodes.size() == ResearchRules.all().size() && !nodes.isEmpty(),
                "research tree UI must expose every configured research topic");

        Set<String> branches = new LinkedHashSet<>();
        boolean dependencyShown = false;
        boolean doctrineShown = false;
        for (StrategicCommandCenter.ResearchUiNode node : nodes) {
            branches.add(node.branch());
            if (!node.prerequisites().isEmpty()) {
                dependencyShown = true;
                require(node.depth() > 0, "dependent research must be presented below its prerequisite tier");
            }
            if (!node.doctrineGroup().isBlank()) doctrineShown = true;
            require(!node.status().isBlank(), "research node must expose queue/completion availability state");
            require(!node.unlocks().isBlank(), "research node must expose gameplay unlocks");
        }
        require(branches.size() >= 2, "research UI must expose configured branch identity");
        require(dependencyShown, "research UI must expose prerequisite dependencies");
        require(doctrineShown, "research UI must expose doctrine-exclusive choices");
    }

    private static Unit soloUnit(World world) {
        for (Unit unit : world.units.values()) if ("SOLO".equals(unit.playerId)) return unit;
        throw new IllegalStateException("Solo unit missing.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
