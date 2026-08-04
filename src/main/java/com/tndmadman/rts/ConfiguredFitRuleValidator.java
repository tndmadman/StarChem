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
        validateExplicitHardpoints();

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

        require(MultiplayerCompatibility.local().rulesVersion() == 27,
                "world-scoped fit and explicit-hardpoint rules version was not bumped");
        System.out.println("StarChem configured ship-fit compatibility, research, cost, and hardpoint validation passed.");
    }

    private static void validateExplicitHardpoints() {
        for (ShipType ship : Rules.SHIPS.values()) {
            require(ship.weaponHardpoints >= 0 && ship.weaponHardpoints <= 64,
                    ship.id + " has invalid configured weapon hardpoints");
            require(PlayerFitRules.slotCount(ship.id) == ship.weaponHardpoints,
                    ship.id + " custom-fit capacity is not sourced from ship configuration");
            for (ShipLoadoutDefinition loadout : WeaponRules.loadoutsForHull(ship.id)) {
                require(loadout.weaponIds().size() <= ship.weaponHardpoints,
                        loadout.id() + " exceeds configured weapon hardpoints for " + ship.id);
            }
        }

        ShipType destroyer = Rules.ship("destroyer");
        require(destroyer.weaponHardpoints == 3,
                "destroyer hardpoint fixture changed unexpectedly");
        PlayerFitRules.Validation exact = PlayerFitRules.validate(new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_missile", "point_defense_laser"), List.of()));
        require(exact.valid(), "fit at exact configured hardpoint capacity was rejected: " + exact.reason());
        PlayerFitRules.Validation excessive = PlayerFitRules.validate(new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_missile", "point_defense_laser", "light_railgun"), List.of()));
        require(!excessive.valid() && excessive.reason().contains("hardpoints"),
                "fit above configured hardpoint capacity was not rejected precisely");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
