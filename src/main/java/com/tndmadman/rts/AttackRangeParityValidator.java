package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

/** Verifies that client movement prediction uses the same fitted ranges as authoritative combat. */
public final class AttackRangeParityValidator {
    private AttackRangeParityValidator() { }

    public static void main(String[] args) {
        validateSystemModifier("warzone", 1.08);
        validateSystemModifier("nebula_expanse", 0.90);
        validateModulePreferredRange();
        System.out.println("StarChem authoritative/client attack-range parity validation passed.");
    }

    private static void validateSystemModifier(String systemId, double expectedModifier) {
        String player = "RANGE_" + systemId.toUpperCase(java.util.Locale.ROOT);
        World world = new World("Range Parity", Set.of(), systemId, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Range Parity", 0x55CCFF);

        Unit attacker = new Unit(player, 1, "destroyer", 500, 900);
        attacker.loadoutId = "destroyer_rail_escort";
        Unit target = new Unit(Config.CORSAIRS_ID, 2, "destroyer", 3200, 900);
        world.units.put(attacker.key(), attacker);
        world.units.put(target.key(), target);
        attacker.task = UnitTask.ATTACK;
        attacker.attackTarget = CombatTarget.unit(target);

        double fitted = WeaponRules.maxRange(attacker);
        double effective = AttackRangeRules.effectiveWeaponRange(world, attacker);
        require(close(SystemModifierRules.weaponRange(world), expectedModifier),
                systemId + " fixture has an unexpected weapon-range modifier");
        require(close(effective, fitted * expectedModifier),
                systemId + " effective range ignored its system modifier");
        require(close(UnitRenderer.displayedWeaponRange(world, attacker), effective),
                systemId + " range indicator disagrees with authoritative range");

        double orbit = AttackRangeRules.orbitRange(world, attacker);
        double expectedTargetX = target.x - orbit;
        ClientAttackPrediction.apply(world, attacker);
        require(close(attacker.targetX, expectedTargetX) && close(attacker.targetY, target.y),
                "dedicated client attack prediction disagrees with authoritative orbit in " + systemId);

        attacker.targetX = attacker.x;
        attacker.targetY = attacker.y;
        ClientPrediction.update(world, 0);
        require(close(attacker.targetX, expectedTargetX) && close(attacker.targetY, target.y),
                "normal client prediction disagrees with authoritative orbit in " + systemId);
    }

    private static void validateModulePreferredRange() {
        String player = "RANGE_TACKLE";
        World world = new World("Tackle Range", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Tackle Range", 0x55CCFF);

        ShipFitSpec spec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("warp_scrambler"));
        ShipLoadoutDefinition fit = PlayerFitRules.register("Tackle Range Fit", spec);
        Unit attacker = new Unit(player, 1, "destroyer", 500, 900);
        attacker.loadoutId = fit.id();
        Unit target = new Unit(Config.CORSAIRS_ID, 2, "destroyer", 2600, 900);
        world.units.put(attacker.key(), attacker);
        world.units.put(target.key(), target);
        attacker.task = UnitTask.ATTACK;
        attacker.attackTarget = CombatTarget.unit(target);

        ShipModuleDefinition scrambler = ShipModuleRules.find("warp_scrambler");
        double expectedPreferred = Math.min(AttackRangeRules.effectiveWeaponRange(world, attacker),
                Math.max(80, scrambler.range() * 0.82));
        require(close(AttackRangeRules.preferredAttackRange(world, attacker), expectedPreferred),
                "utility-module preferred range was not applied");

        ClientAttackPrediction.apply(world, attacker);
        require(close(Calc.distance(attacker.targetX, attacker.targetY, target.x, target.y),
                        AttackRangeRules.orbitRange(world, attacker)),
                "client prediction ignored the fitted tackle approach range");
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
