package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class ConfiguredFitRuleValidator {
    private ConfiguredFitRuleValidator() { }

    public static void main(String[] args) {
        for (WeaponType weapon : WeaponRules.WEAPONS.values()) {
            require(!weapon.compatibleHulls.isEmpty(), weapon.id + " lacks compatible hulls");
            require(!weapon.installationCost.isEmpty(), weapon.id + " lacks configured installation cost");
            for (String hullId : weapon.compatibleHulls) {
                require(Rules.findShip(hullId) != null, weapon.id + " references unknown hull " + hullId);
            }
            for (String topicId : weapon.requiredResearch) {
                require(ResearchRules.topic(topicId) != null, weapon.id + " references unknown research " + topicId);
            }
        }
        for (ShipModuleDefinition module : ShipModuleRules.MODULES.values()) {
            require(!module.compatibleHulls().isEmpty(), module.id() + " lacks compatible hulls");
            require(!module.installationCost().isEmpty(), module.id() + " lacks installation cost");
        }

        ShipFitSpec mixed = new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_missile", "point_defense_laser"),
                List.of("micro_jump_drive"));
        PlayerFitRules.Validation mixedValidation = PlayerFitRules.validate(mixed);
        require(mixedValidation.valid(), "mixed configured fit rejected: " + mixedValidation.reason());
        require(PlayerFitRules.requiredResearch(mixed).equals(Set.of("combat_doctrine", "battlefleet_engineering")),
                "mixed component research aggregation is not exact");

        ShipFitSpec repeatedPresetWeapon = new ShipFitSpec("cruiser",
                List.of("light_missile", "light_missile", "torpedo"), List.of());
        require(PlayerFitRules.requiredResearch(repeatedPresetWeapon).equals(Set.of("combat_doctrine")),
                "weapon research still depends on authored preset membership");

        PlayerFitRules.Validation incompatible = PlayerFitRules.validate(
                new ShipFitSpec("frigate", List.of("capital_lance"), List.of()));
        require(!incompatible.valid() && incompatible.reason().contains("not compatible"),
                "incompatible hull/weapon pair was not rejected precisely");

        require(WeaponRules.findLoadout("frigate").requiredResearch().isEmpty(),
                "legacy frigate authored unlock changed");
        require(WeaponRules.findLoadout("destroyer").requiredResearch().contains("combat_doctrine"),
                "legacy destroyer authored unlock changed");
        require(WeaponRules.findLoadout("dreadnought").requiredResearch().contains("battlefleet_engineering"),
                "legacy dreadnought authored unlock changed");

        List<Cost> missileCost = WeaponRules.WEAPONS.get("light_missile").installationCost;
        require(missileCost.stream().anyMatch(cost -> cost.material() == Material.MISSILE_GUIDANCE_PACKAGE),
                "configured missile cost missing guidance package");
        require(missileCost.stream().anyMatch(cost -> cost.material() == Material.MISSILE_WARHEAD),
                "configured missile cost missing warheads");

        System.out.println("StarChem configured ship-fit compatibility, research, and cost validation passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
