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
        Unit ship = new Unit(playerId, 1, "destroyer", 1050, 1000);
        world.units.put(ship.key(), ship);

        ShipFitSpec mobilitySpec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("afterburner", "micro_jump_drive"));
        ShipLoadoutDefinition mobility = refit(world, playerId, yard, ship, "Mobility Refit", mobilitySpec);
        require(mobility.id().equals(ship.loadoutId), "completed refit did not install the runtime fit ID");
        require(ShipModuleRules.moduleIds(ship).equals(mobilitySpec.moduleIds()),
                "completed refit did not install the authored module layout");
        require(ShipModuleRules.has(ship, ShipModuleKind.AFTERBURNER),
                "completed refit did not expose the afterburner effect");
        require(ShipModuleRules.has(ship, ShipModuleKind.MICRO_JUMP_DRIVE),
                "completed refit did not expose the micro jump effect");

        ship.moveTo(ship.x + 1800, ship.y);
        double startX = ship.x;
        ShipModuleRules.update(world, ship, 0.1);
        require(ship.afterburnerActive, "refitted afterburner did not activate on a distant move");
        require(ShipModuleRules.speedMultiplier(ship) > 1.0,
                "refitted afterburner did not increase movement speed");
        ShipModuleRules.update(world, ship, 0.1);
        require(ship.x > startX + 400, "refitted micro jump drive did not move the ship");
        require(ship.microJumpCooldown > 0 && ship.microJumpFlashTimer > 0,
                "micro jump did not enter cooldown and visual feedback state");

        validateAuthoritativeJumpCorrection(world, ship, mobility);

        ship.x = yard.x + 40;
        ship.y = yard.y;
        ship.targetX = ship.x;
        ship.targetY = ship.y;
        ship.task = UnitTask.IDLE;
        ship.microJumpCooldown = 0;
        ship.microJumpFlashTimer = 0;
        ship.afterburnerActive = false;

        ShipFitSpec tackleSpec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("warp_scrambler"));
        ShipLoadoutDefinition tackle = refit(world, playerId, yard, ship, "Tackle Refit", tackleSpec);
        require(tackle.id().equals(ship.loadoutId), "second refit did not install the tackle fit");
        require(ShipModuleRules.moduleIds(ship).equals(tackleSpec.moduleIds()),
                "second refit retained stale modules from the previous fit");

        Unit enemy = new Unit("MODULE_TARGET", 1, "destroyer", ship.x + 120, ship.y);
        world.units.put(enemy.key(), enemy);
        ship.attackTarget = CombatTarget.unit(enemy);
        ship.task = UnitTask.ATTACK;
        require(ShipModuleRules.tackled(world, enemy),
                "refitted jump scrambler did not suppress its targeted enemy");

        System.out.println("StarChem module refit and runtime-effect validation passed.");
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

    private static void validateAuthoritativeJumpCorrection(World world, Unit source,
                                                            ShipLoadoutDefinition mobility) {
        Unit replica = new Unit(source.playerId, 99, source.shipTypeId, 100, 100);
        replica.loadoutId = mobility.id();
        replica.moveTo(1900, 100);
        UnitState state = new UnitState(replica.playerId, replica.unitId, replica.shipTypeId,
                780, 100, 1900, 100, 0, UnitTask.MOVE.name(), -1, "", "",
                replica.hp, replica.shield, "", 0, UnitOrderType.NONE.name(),
                0, 0, 0, 0, 0, "", 0, mobility.id());
        SnapshotSmoother.apply(replica, state);
        require(Math.abs(replica.x - state.x()) < 0.001,
                "authoritative micro jump was blended instead of snapped on the client");
        require(replica.microJumpFlashTimer > 0 && replica.microJumpCooldown > 0,
                "authoritative micro jump did not synchronize client feedback and cooldown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
