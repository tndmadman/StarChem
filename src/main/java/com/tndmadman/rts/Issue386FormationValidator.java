package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class Issue386FormationValidator {
    private Issue386FormationValidator() { }

    public static void main(String[] args) {
        allFourFormationsRemainAvailable();
        rotationFollowsMoveDirection();
        deterministicAssignmentIgnoresSelectionOrder();
        rolesPreferFrontFlankAndRear();
        mixedSpeedsUseAnchorCohesion();
        destroyedOrDetachedMembersReform();
        arrivalToleranceMatchesQueueCompletion();
        settledCompletionRetainsSlots();
        crossSystemFormationIntentSurvivesWormholeCommand();
        malformedFormationIntentIsRejected();
        largeSelectionsStayCompleteAndFinite();
        System.out.println("Issue 386 formation validation passed.");
    }

    private static FleetFormationPlanner.Member member(String key, double x, double y,
                                                        double speed, double durability,
                                                        boolean armed, boolean support) {
        return new FleetFormationPlanner.Member(key, x, y, speed, durability, armed, support);
    }

    private static void allFourFormationsRemainAvailable() {
        List<FleetFormationPlanner.Member> members = List.of(
                member("p:1", 0, 0, 80, 100, true, false),
                member("p:2", 10, 0, 90, 120, true, false),
                member("p:3", 20, 0, 100, 90, false, true));
        for (FleetFormation formation : FleetFormation.values()) {
            FleetFormationPlanner.Plan plan = FleetFormationPlanner.plan(members, formation, 300, 300, 0, -1);
            require(plan.targets().size() == members.size(), formation + " must assign every member");
        }
    }

    private static void rotationFollowsMoveDirection() {
        List<FleetFormationPlanner.Member> members = List.of(
                member("p:1", 0, -20, 100, 100, true, false),
                member("p:2", 0, 0, 100, 100, true, false),
                member("p:3", 0, 20, 100, 100, true, false));
        FleetFormationPlanner.Plan plan = FleetFormationPlanner.plan(members, FleetFormation.LINE, 500, 100, 1, 0);
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (FleetFormationPlanner.Target target : plan.targets().values()) {
            require(Math.abs(target.x() - 500) < 0.001, "eastbound line must rotate perpendicular to travel");
            minY = Math.min(minY, target.y());
            maxY = Math.max(maxY, target.y());
        }
        require(maxY - minY > 80, "rotated line must retain formation width");
    }

    private static void deterministicAssignmentIgnoresSelectionOrder() {
        List<FleetFormationPlanner.Member> members = new ArrayList<>(List.of(
                member("p:3", 30, 10, 140, 90, true, false),
                member("p:1", 0, 0, 80, 210, true, false),
                member("p:4", 20, -10, 70, 70, false, true),
                member("p:2", 10, 5, 100, 120, true, false)));
        FleetFormationPlanner.Plan first = FleetFormationPlanner.plan(members, FleetFormation.WEDGE, 400, 400, 0, -1);
        Collections.reverse(members);
        FleetFormationPlanner.Plan second = FleetFormationPlanner.plan(members, FleetFormation.WEDGE, 400, 400, 0, -1);
        require(first.targets().equals(second.targets()), "slot assignment must be deterministic by unit key/role");
        require(first.anchorKey().equals(second.anchorKey()), "formation anchor must be deterministic");
    }

    private static void rolesPreferFrontFlankAndRear() {
        List<FleetFormationPlanner.Member> members = List.of(
                member("heavy", 0, 0, 70, 400, true, false),
                member("screen", 0, 0, 180, 80, true, false),
                member("line", 0, 0, 100, 130, true, false),
                member("support", 0, 0, 80, 110, false, true),
                member("line2", 0, 0, 105, 125, true, false));
        FleetFormationPlanner.Plan plan = FleetFormationPlanner.plan(members, FleetFormation.WEDGE, 500, 500, 1, 0);
        FleetFormationPlanner.Target heavy = plan.target("heavy");
        FleetFormationPlanner.Target support = plan.target("support");
        FleetFormationPlanner.Target screen = plan.target("screen");
        require(heavy.x() > support.x(), "frontline should be ahead of support along formation forward");
        require(Math.abs(screen.y() - 500) > Math.abs(heavy.y() - 500) * 0.5,
                "screen should prefer a flank slot");
    }

    private static void mixedSpeedsUseAnchorCohesion() {
        List<FleetFormationPlanner.Member> members = List.of(
                member("slow", 0, 0, 80, 100, true, false),
                member("fast", 0, 0, 200, 100, true, false));
        FleetFormationPlanner.Plan plan = FleetFormationPlanner.plan(members, FleetFormation.COLUMN, 100, 100, 1, 0);
        require(Math.abs(plan.pace() - 80.0) < 0.001, "fleet pace should use the slowest active member");
        require("slow".equals(plan.anchorKey()), "slow member should become the deterministic cohesion anchor");
        require(FormationController.cohesionCap(plan.pace(), 320, 100) > plan.pace(),
                "lagging member should receive bounded catch-up allowance");
        require(FormationController.cohesionCap(plan.pace(), 20, 220) < plan.pace(),
                "member ahead of the anchor should be throttled");
        FleetFormationPlanner.Plan solo = FleetFormationPlanner.plan(List.of(members.get(0)), FleetFormation.COLUMN, 100, 100, 1, 0);
        require(solo.pace() == 0, "single ship should not receive a formation speed cap");
    }

    private static void destroyedOrDetachedMembersReform() {
        List<FleetFormationPlanner.Member> members = new ArrayList<>(List.of(
                member("p:1", 0, 0, 80, 150, true, false),
                member("p:2", 10, 0, 95, 120, true, false),
                member("p:3", 20, 0, 130, 80, true, false),
                member("p:4", 30, 0, 90, 90, false, true),
                member("p:5", 40, 0, 110, 110, true, false)));
        FleetFormationPlanner.Plan before = FleetFormationPlanner.plan(members, FleetFormation.GRID, 500, 500, 1, 0);
        members.removeIf(member -> "p:2".equals(member.key()));
        FleetFormationPlanner.Plan after = FleetFormationPlanner.plan(members, FleetFormation.GRID, 500, 500, 1, 0);
        require(before.targets().size() == 5 && after.targets().size() == 4,
                "removing a destroyed/detached member must trigger a compact re-plan");
        require(after.target("p:2") == null, "removed member must not retain a stale slot");
        for (FleetFormationPlanner.Member member : members) require(after.target(member.key()) != null,
                "survivor lost its slot after re-form: " + member.key());
    }

    private static void arrivalToleranceMatchesQueueCompletion() {
        require(Math.abs(FormationController.arrivalDistance() - 7.0) < 0.001,
                "formation readiness must use the same seven-unit radius as queued move completion");
    }

    private static void settledCompletionRetainsSlots() {
        List<FleetFormationPlanner.Member> members = List.of(
                member("p:1", 0, 0, 100, 100, true, false),
                member("p:2", 0, 0, 100, 100, true, false),
                member("p:3", 0, 0, 100, 100, true, false),
                member("p:4", 0, 0, 100, 100, true, false));
        FleetFormationPlanner.Plan settled = FleetFormationPlanner.plan(
                members, FleetFormation.LINE, 500, 500, 0, -1);

        List<FleetFormationPlanner.Member> remainingMembers = members.subList(1, members.size());
        List<String> remainingKeys = remainingMembers.stream()
                .map(FleetFormationPlanner.Member::key)
                .toList();
        FleetFormationPlanner.Plan replanned = FleetFormationPlanner.plan(
                remainingMembers, FleetFormation.LINE, 500, 500, 0, -1);
        Map<String, FleetFormationPlanner.Target> retained =
                FormationController.retainedSettledTargets(settled.targets(), remainingKeys);

        require(retained.size() == remainingKeys.size(),
                "arrival handoff must retain a target for every still-active member");
        boolean ordinaryReplanWouldShift = false;
        for (String key : remainingKeys) {
            require(settled.target(key).equals(retained.get(key)),
                    "settled slot changed while another member completed: " + key);
            if (!settled.target(key).equals(replanned.target(key))) ordinaryReplanWouldShift = true;
        }
        require(ordinaryReplanWouldShift,
                "regression fixture must demonstrate that an N-1 line re-plan would move slots");
    }

    private static void crossSystemFormationIntentSurvivesWormholeCommand() {
        String encoded = FormationIntent.encode("transit-1", FleetFormation.WEDGE, 1, 0);
        QueuedUnitCommand command = QueuedUnitCommand.formationWormhole(
                "alpha", "gate-a", "beta", 420, 360, 400, 360, 80, encoded).withStepId(9);
        FormationIntent parsed = FormationIntent.parse(command);
        require(parsed != null && parsed.formation() == FleetFormation.WEDGE,
                "wormhole command must preserve formation type");
        require("transit-1".equals(parsed.token()) && "beta".equals(command.destinationSystemId()),
                "wormhole command must preserve group token and destination");
        require(command.x2() == 400 && command.y2() == 360,
                "wormhole command must preserve destination regroup anchor");
        require(UnitCommandQueueSystem.validateStructural(command),
                "well-formed cross-system formation command must pass structural validation");
    }

    private static void malformedFormationIntentIsRejected() {
        QueuedUnitCommand malformed = new QueuedUnitCommand(0, QueuedCommandKind.MOVE, "alpha",
                100, 100, 120, 100, 80, "@F2|bad token|NOPE|NaN|0", -1, "", "", UnitOrderType.NONE);
        require(FormationIntent.claims(malformed), "malformed command should still be recognized as claiming formation metadata");
        require(!UnitCommandQueueSystem.validateStructural(malformed),
                "server structural validation must reject malformed formation metadata");
    }

    private static void largeSelectionsStayCompleteAndFinite() {
        List<FleetFormationPlanner.Member> members = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            members.add(member("p:" + i, i % 64, i / 64, 80 + i % 70,
                    100 + i % 150, i % 7 != 0, i % 11 == 0));
        }
        FleetFormationPlanner.Plan plan = FleetFormationPlanner.plan(members, FleetFormation.GRID,
                Double.NaN, Double.POSITIVE_INFINITY, 0, 0);
        require(plan.targets().size() == members.size(), "large selection must assign every member exactly once");
        for (Map.Entry<String, FleetFormationPlanner.Target> entry : plan.targets().entrySet()) {
            require(Double.isFinite(entry.getValue().x()) && Double.isFinite(entry.getValue().y()),
                    "planner emitted non-finite target for " + entry.getKey());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
