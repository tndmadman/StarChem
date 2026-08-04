package com.tndmadman.rts;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Verifies that authoritative combat, client prediction, and presentation share one attack-range model. */
public final class AttackRangeParityValidator {
    private static final Method UPDATE_ATTACK = updateAttackMethod();

    private AttackRangeParityValidator() { }

    public static void main(String[] args) {
        validateSystemModifier("warzone", 1.08);
        validateSystemModifier("nebula_expanse", 0.90);
        validateModulePreferredRange();
        validateHoldPositionDoesNotChase();
        validateRefittedRangeAppliesImmediately();
        System.out.println("StarChem authoritative/client attack-range parity validation passed.");
    }

    private static void validateSystemModifier(String systemId, double expectedModifier) {
        String player = "RANGE_" + systemId.toUpperCase(java.util.Locale.ROOT);
        World world = world("Range Parity", player, systemId);

        Unit attacker = unit(world, player, 1, "destroyer", "destroyer_rail_escort", 500, 900);
        Unit target = unit(world, Config.CORSAIRS_ID, 2, "destroyer", "destroyer", 3200, 900);
        addSpotter(world, player, target.x, target.y);
        attack(attacker, target);

        double fitted = WeaponRules.maxRange(world, attacker);
        double effective = AttackRangeRules.effectiveWeaponRange(world, attacker);
        require(close(AttackRangeRules.systemRangeMultiplier(world), expectedModifier),
                systemId + " fixture has an unexpected weapon-range modifier");
        require(close(effective, fitted * expectedModifier),
                systemId + " effective range ignored its system modifier");
        require(close(UnitRenderer.displayedWeaponRange(world, attacker), effective),
                systemId + " range indicator disagrees with authoritative range");

        double orbit = AttackRangeRules.orbitRange(world, attacker);
        double expectedTargetX = target.x - orbit;
        resetMovement(attacker);
        invokeAuthoritativeAttack(world, attacker);
        require(close(attacker.targetX, expectedTargetX) && close(attacker.targetY, target.y),
                "authoritative attack movement disagrees with the shared orbit in " + systemId);

        resetMovement(attacker);
        attack(attacker, target);
        ClientAttackPrediction.apply(world, attacker);
        require(close(attacker.targetX, expectedTargetX) && close(attacker.targetY, target.y),
                "dedicated client attack prediction disagrees with authoritative orbit in " + systemId);

        resetMovement(attacker);
        attack(attacker, target);
        ClientPrediction.update(world, 0);
        require(close(attacker.targetX, expectedTargetX) && close(attacker.targetY, target.y),
                "normal client prediction disagrees with authoritative orbit in " + systemId);
    }

    private static void validateModulePreferredRange() {
        String player = "RANGE_TACKLE";
        World world = world("Tackle Range", player, StarSystems.DEFAULT_SYSTEM_ID);

        ShipFitSpec spec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("warp_scrambler"));
        ShipLoadoutDefinition fit = WorldFitCatalog.registerRuntime(world, "Tackle Range Fit", spec);
        Unit attacker = unit(world, player, 1, "destroyer", fit.id(), 500, 900);
        Unit target = unit(world, Config.CORSAIRS_ID, 2, "destroyer", "destroyer", 2600, 900);
        addSpotter(world, player, target.x, target.y);
        attack(attacker, target);

        ShipModuleDefinition scrambler = ShipModuleRules.find("warp_scrambler");
        double expectedPreferred = Math.min(AttackRangeRules.effectiveWeaponRange(world, attacker),
                Math.max(80, scrambler.range() * 0.82));
        require(close(AttackRangeRules.preferredAttackRange(world, attacker), expectedPreferred),
                "utility-module preferred range was not applied");

        resetMovement(attacker);
        attack(attacker, target);
        invokeAuthoritativeAttack(world, attacker);
        require(close(Calc.distance(attacker.targetX, attacker.targetY, target.x, target.y),
                        AttackRangeRules.orbitRange(world, attacker)),
                "authoritative movement ignored the fitted tackle approach range");

        resetMovement(attacker);
        attack(attacker, target);
        ClientAttackPrediction.apply(world, attacker);
        require(close(Calc.distance(attacker.targetX, attacker.targetY, target.x, target.y),
                        AttackRangeRules.orbitRange(world, attacker)),
                "client prediction ignored the fitted tackle approach range");
    }

    private static void validateHoldPositionDoesNotChase() {
        String player = "RANGE_HOLD";
        World world = world("Hold Range", player, StarSystems.DEFAULT_SYSTEM_ID);
        ShipFitSpec spec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("warp_scrambler"));
        ShipLoadoutDefinition fit = WorldFitCatalog.registerRuntime(world, "Hold Tackle Fit", spec);
        Unit attacker = unit(world, player, 1, "destroyer", fit.id(), 500, 900);
        Unit target = unit(world, Config.CORSAIRS_ID, 2, "destroyer", "destroyer", 1000, 900);
        addSpotter(world, player, target.x, target.y);
        attacker.orderType = UnitOrderType.HOLD;
        attacker.orderX1 = attacker.x;
        attacker.orderY1 = attacker.y;
        attack(attacker, target);
        double startX = attacker.targetX;
        double startY = attacker.targetY;

        invokeAuthoritativeAttack(world, attacker);
        require(attacker.task == UnitTask.IDLE && attacker.attackTarget.isBlank(),
                "authoritative hold-position ship chased beyond its preferred fitted distance");
        require(close(attacker.targetX, startX) && close(attacker.targetY, startY),
                "hold-position rejection changed the movement target");
    }

    private static void validateRefittedRangeAppliesImmediately() {
        String player = "RANGE_REFIT";
        World world = world("Refitted Range", player, "warzone");
        Unit attacker = unit(world, player, 1, "destroyer", "destroyer_rail_escort", 500, 900);
        double railRange = AttackRangeRules.effectiveWeaponRange(world, attacker);
        attacker.loadoutId = "destroyer_missile_screen";
        double missileRange = AttackRangeRules.effectiveWeaponRange(world, attacker);
        require(!close(railRange, missileRange), "refitted range fixture did not change weapon range");
        require(close(UnitRenderer.displayedWeaponRange(world, attacker), missileRange),
                "range indicator did not immediately use the refitted loadout");
    }

    private static World world(String name, String player, String systemId) {
        World world = new World(name, Set.of(), systemId, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, name, 0x55CCFF);
        return world;
    }

    private static Unit unit(World world, String player, int id, String hull, String loadout,
                             double x, double y) {
        Unit unit = new Unit(player, id, hull, x, y);
        unit.loadoutId = loadout;
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static void addSpotter(World world, String player, double x, double y) {
        Unit spotter = new Unit(player, 9000 + world.units.size(), "prospector", x - 20, y);
        world.units.put(spotter.key(), spotter);
    }

    private static void attack(Unit attacker, Unit target) {
        attacker.task = UnitTask.ATTACK;
        attacker.attackTarget = CombatTarget.unit(target);
    }

    private static void resetMovement(Unit unit) {
        unit.targetX = unit.x;
        unit.targetY = unit.y;
    }

    private static Method updateAttackMethod() {
        try {
            Method method = WeaponSystem.class.getDeclaredMethod("updateAttack", World.class, Unit.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void invokeAuthoritativeAttack(World world, Unit unit) {
        try {
            UPDATE_ATTACK.invoke(new WeaponSystem(), world, unit);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke authoritative attack movement.", ex);
        }
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
