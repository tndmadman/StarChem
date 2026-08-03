package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** End-to-end validation that authored module fits survive refitting and drive simulation effects. */
public final class ShipModuleRefitValidator {
    private ShipModuleRefitValidator() { }

    public static void main(String[] args) {
        String playerId = "MODULE_REFIT";
        World world = new World("Module Refit Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.setDevFreeBuild(playerId, true);

        Base yard = new Base(playerId + ":B1", playerId, "shipyard", 1000, 1000);
        world.bases.put(yard.id, yard);
        Unit ship = new Unit(playerId, 1, "prospector", 1050, 1000);
        world.units.put(ship.key(), ship);

        validateAfterburnerRefit(world, playerId, yard, ship);
        ShipLoadoutDefinition jumpFit = validateMicroJumpRefit(world, playerId, yard, ship);
        validateAuthoritativeJumpCorrection(ship, jumpFit);
        validateScramblerRefit(world, playerId, yard, ship);

        System.out.println("StarChem module refit and runtime-effect validation passed.");
    }

    private static void validateAfterburnerRefit(World world, String playerId, Base yard, Unit ship) {
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("afterburner"));
        ShipLoadoutDefinition fit = refit(world, playerId, yard, ship, "Prospector Afterburner", spec);
        require(fit.id().equals(ship.loadoutId), "completed refit did not install the afterburner fit ID");
        require(ShipModuleRules.moduleIds(ship).equals(spec.moduleIds()),
                "completed refit did not install the authored afterburner layout");

        ship.moveTo(ship.x + 1800, ship.y);
        ShipModuleRules.update(world, ship, 0.1);
        require(ship.afterburnerActive, "refitted afterburner did not activate on a distant move");
        require(ShipModuleRules.speedMultiplier(ship) > 1.0,
                "refitted afterburner did not increase movement speed");
        require(ShipModuleRules.agilityMultiplier(ship) < 1.0,
                "refitted afterburner did not reduce turning agility");
    }

    private static ShipLoadoutDefinition validateMicroJumpRefit(World world, String playerId,
                                                                 Base yard, Unit ship) {
        resetAtYard(yard, ship);
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("micro_jump_drive"));
        ShipLoadoutDefinition fit = refit(world, playerId, yard, ship, "Prospector Jump Drive", spec);
        require(fit.id().equals(ship.loadoutId), "completed refit did not install the jump fit ID");
        require(ShipModuleRules.moduleIds(ship).equals(spec.moduleIds()),
                "completed refit retained stale modules from the afterburner fit");
        require(ShipModuleRules.has(ship, ShipModuleKind.MICRO_JUMP_DRIVE),
                "completed refit did not expose the micro jump effect");

        ship.moveTo(ship.x + 4000, ship.y);
        double startX = ship.x;
        double initialDistance = ship.targetX - ship.x;
        ShipModuleRules.update(world, ship, 0.1);
        require(ship.microJumpCooldown < 0,
                "micro jump drive did not enter its visible charge state");
        require(Math.abs(ship.x - startX) < 0.001,
                "micro jump moved before the charge completed");

        ShipModuleRules.update(world, ship, 0.8);
        require(ShipModuleRules.microJumpChargeProgress(ship) > 0.35,
                "micro jump charge did not advance over time");
        require(Math.abs(ship.x - startX) < 0.001,
                "micro jump moved during the charge-up period");

        int guard = 0;
        while (ship.microJumpCooldown < 0 && guard++ < 30) ShipModuleRules.update(world, ship, 0.1);
        require(ship.microJumpCooldown > 0, "micro jump charge completed without entering cooldown");
        double remaining = ship.targetX - ship.x;
        require(Math.abs(remaining - initialDistance * 0.05) < 2.0,
                "micro jump did not cover 95 percent of the remaining route");
        require(ship.microJumpFlashTimer > 0,
                "micro jump did not enter visual feedback state");
        require(ShipModuleRules.jumpVisualActiveForTest(ship),
                "micro jump did not create the source-to-destination tunnel visual");
        require(ShipModuleRules.effectSummary(ShipModuleRules.find("micro_jump_drive")).contains("95%"),
                "micro jump UI summary does not advertise the percentage jump distance");
        return fit;
    }

    private static void validateScramblerRefit(World world, String playerId, Base yard, Unit ship) {
        resetAtYard(yard, ship);
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("warp_scrambler"));
        ShipLoadoutDefinition fit = refit(world, playerId, yard, ship, "Prospector Scrambler", spec);
        require(fit.id().equals(ship.loadoutId), "third refit did not install the tackle fit");
        require(ShipModuleRules.moduleIds(ship).equals(spec.moduleIds()),
                "third refit retained stale modules from the jump fit");

        Unit enemy = new Unit("MODULE_TARGET", 1, "prospector", ship.x + 120, ship.y);
        world.units.put(enemy.key(), enemy);
        ship.attackTarget = CombatTarget.unit(enemy);
        ship.task = UnitTask.ATTACK;
        require(ShipModuleRules.tackled(world, enemy),
                "refitted jump scrambler did not suppress its targeted enemy");
    }

    private static ShipLoadoutDefinition refit(World world, String playerId, Base yard, Unit ship,
                                               String name, ShipFitSpec spec) {
        FitCommand.Result result = FitCommand.applyLocal(world, playerId, "REFIT", Map.of(
                "name", name,
                "spec", spec.toMap(),
                "baseId", yard.id,
                "unitKey", ship.key()));
        require(result.success(), "fit command was rejected: " + result.message());
        require(ProductionSystem.refitReserved(world, ship.key()),
                "accepted fit command did not create a refit reservation");
        ProductionSystem.update(world, 1000);
        require(!ProductionSystem.refitReserved(world, ship.key()),
                "refit remained queued after its configured duration");
        ShipLoadoutDefinition installed = WeaponRules.findLoadout(ship.loadoutId);
        require(installed != null, "installed runtime fit could not be resolved after refit");
        return installed;
    }

    private static void resetAtYard(Base yard, Unit ship) {
        ship.x = yard.x + 40;
        ship.y = yard.y;
        ship.targetX = ship.x;
        ship.targetY = ship.y;
        ship.task = UnitTask.IDLE;
        ship.attackTarget = "";
        ship.microJumpCooldown = 0;
        ship.microJumpFlashTimer = 0;
        ship.afterburnerActive = false;
    }

    private static void validateAuthoritativeJumpCorrection(Unit source,
                                                            ShipLoadoutDefinition jumpFit) {
        Unit replica = new Unit(source.playerId, 99, source.shipTypeId, 100, 100);
        replica.loadoutId = jumpFit.id();
        replica.moveTo(1900, 100);
        UnitState state = new UnitState(replica.playerId, replica.unitId, replica.shipTypeId,
                1810, 100, 1900, 100, 0, UnitTask.MOVE.name(), -1, "", "",
                replica.hp, replica.shield, "", 0, UnitOrderType.NONE.name(),
                0, 0, 0, 0, 0, "", 0, jumpFit.id());
        SnapshotSmoother.apply(replica, state);
        require(Math.abs(replica.x - state.x()) < 0.001,
                "authoritative micro jump was blended instead of snapped on the client");
        require(replica.microJumpFlashTimer > 0 && replica.microJumpCooldown > 0,
                "authoritative micro jump did not synchronize client feedback and cooldown");
        require(ShipModuleRules.jumpVisualActiveForTest(replica),
                "authoritative micro jump did not recreate the A-to-B tunnel on the client");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
