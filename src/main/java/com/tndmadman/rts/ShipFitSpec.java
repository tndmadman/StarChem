package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable player-authored weapon and utility-module arrangement. */
record ShipFitSpec(String hullId, List<String> weaponIds, List<String> moduleIds) {
    ShipFitSpec {
        hullId = hullId == null ? "" : hullId.trim();
        weaponIds = normalized(weaponIds);
        moduleIds = normalized(moduleIds);
    }

    ShipFitSpec(String hullId, List<String> weaponIds) {
        this(hullId, weaponIds, List.of());
    }

    String runtimeId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("StarChemShipFit/v2".getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(hullId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)1);
            for (String weaponId : weaponIds) {
                digest.update((byte)0);
                digest.update(weaponId.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte)2);
            for (String moduleId : moduleIds) {
                digest.update((byte)0);
                digest.update(moduleId.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder out = new StringBuilder("custom_");
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    Map<String,Object> toMap() {
        return Map.of("hullId", hullId, "weapons", weaponIds, "modules", moduleIds);
    }

    static ShipFitSpec from(Object value) {
        Map<String,Object> row = ServerSaveStore.object(value);
        return new ShipFitSpec(ServerSaveStore.string(row, "hullId", ""),
                strings(row.get("weapons")), strings(row.get("modules")));
    }

    private static List<String> normalized(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) for (String value : values) {
            String clean = value == null ? "" : value.trim();
            if (!clean.isBlank()) out.add(clean);
        }
        return List.copyOf(out);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : ServerSaveStore.list(value)) {
            String id = String.valueOf(item).trim();
            if (!id.isBlank()) out.add(id);
        }
        return List.copyOf(out);
    }
}

/** Server-authoritative validation and derived economics for player-authored fits. */
final class PlayerFitRules {
    private static final int MAX_FIT_NAME = 64;

    private PlayerFitRules() { }

    static Validation validate(ShipFitSpec spec) {
        if (spec == null || Rules.findShip(spec.hullId()) == null) return Validation.reject("Unknown ship hull.");
        int slots = slotCount(spec.hullId());
        if (spec.weaponIds().size() > slots) {
            return Validation.reject("Fit uses " + spec.weaponIds().size() + " weapons but the hull has " + slots + " hardpoints.");
        }
        Set<String> allowed = allowedWeaponIds(spec.hullId());
        for (String weaponId : spec.weaponIds()) {
            if (WeaponRules.WEAPONS.get(weaponId) == null) return Validation.reject("Unknown weapon: " + weaponId + ".");
            if (!allowed.contains(weaponId)) return Validation.reject("Weapon " + weaponId + " is not compatible with this hull.");
        }
        ShipModuleRules.Validation moduleValidation = ShipModuleRules.validate(spec.hullId(), spec.moduleIds());
        if (!moduleValidation.valid()) return Validation.reject(moduleValidation.reason());
        return Validation.accept();
    }

    /** Builds an authoritative candidate without registering it in global or world catalogs. */
    static ShipLoadoutDefinition previewDefinition(String requestedName, ShipFitSpec spec) {
        Validation validation = validate(spec);
        if (!validation.valid()) throw new IllegalArgumentException(validation.reason());
        String name = cleanName(requestedName);
        if (name.isBlank()) name = Rules.ship(spec.hullId()).name + " Custom Fit";
        return new ShipLoadoutDefinition(spec.runtimeId(), name, spec.hullId(), spec.weaponIds(),
                requiredResearch(spec), buildPremium(spec), installationCost(spec), refitTimeSeconds(spec), false);
    }

    static ShipLoadoutDefinition definition(String requestedName, ShipFitSpec spec) {
        ShipLoadoutDefinition definition = previewDefinition(requestedName, spec);
        ShipModuleRules.registerLoadout(definition.id(), spec.moduleIds());
        return definition;
    }

    static ShipLoadoutDefinition register(String requestedName, ShipFitSpec spec) {
        ShipLoadoutDefinition definition = definition(requestedName, spec);
        synchronized (WeaponRules.class) {
            ShipLoadoutDefinition existing = WeaponRules.SHIP_LOADOUTS.get(definition.id());
            if (existing != null) {
                if (!existing.hullId().equals(definition.hullId())
                        || !existing.weaponIds().equals(definition.weaponIds())
                        || !ShipModuleRules.moduleIds(existing).equals(spec.moduleIds())) {
                    throw new IllegalArgumentException("Runtime fit ID conflicts with a different definition.");
                }
                return existing;
            }
            WeaponRules.SHIP_LOADOUTS.put(definition.id(), definition);
            return definition;
        }
    }

    static int slotCount(String hullId) {
        int slots = 0;
        for (ShipLoadoutDefinition loadout : WeaponRules.loadoutsForHull(hullId)) {
            slots = Math.max(slots, loadout.weaponIds().size());
        }
        return slots;
    }

    static List<WeaponType> allowedWeapons(String hullId) {
        List<WeaponType> out = new ArrayList<>();
        for (String id : allowedWeaponIds(hullId)) {
            WeaponType weapon = WeaponRules.WEAPONS.get(id);
            if (weapon != null) out.add(weapon);
        }
        out.sort(java.util.Comparator.comparing(value -> value.name));
        return List.copyOf(out);
    }

    static Set<String> allowedWeaponIds(String hullId) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (WeaponType weapon : WeaponRules.WEAPONS.values()) {
            if (weapon.compatibleWith(hullId)) out.add(weapon.id);
        }
        return Set.copyOf(out);
    }

    static Set<String> requiredResearch(ShipFitSpec spec) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (spec == null) return Set.of();
        for (String weaponId : spec.weaponIds()) {
            WeaponType weapon = WeaponRules.WEAPONS.get(weaponId);
            if (weapon != null) out.addAll(weapon.requiredResearch);
        }
        out.addAll(ShipModuleRules.requiredResearch(spec.moduleIds()));
        return Set.copyOf(out);
    }

    static List<Cost> buildPremium(ShipFitSpec spec) {
        EnumMap<Material,Double> target = totals(installationCost(spec));
        ShipLoadoutDefinition defaultFit = spec == null ? null : WeaponRules.defaultLoadout(spec.hullId());
        EnumMap<Material,Double> baseline = totals(defaultFit == null ? List.of()
                : installationCost(new ShipFitSpec(defaultFit.hullId(), defaultFit.weaponIds(),
                ShipModuleRules.moduleIds(defaultFit))));
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material,Double> entry : target.entrySet()) {
            double extra = entry.getValue() - baseline.getOrDefault(entry.getKey(), 0.0);
            if (extra > 0.0001) out.add(new Cost(entry.getKey(), extra));
        }
        return List.copyOf(out);
    }

    private static EnumMap<Material,Double> totals(List<Cost> costs) {
        EnumMap<Material,Double> out = new EnumMap<>(Material.class);
        if (costs != null) for (Cost cost : costs) out.merge(cost.material(), cost.amount(), Double::sum);
        return out;
    }

    static List<Cost> installationCost(ShipFitSpec spec) {
        EnumMap<Material,Double> total = new EnumMap<>(Material.class);
        if (spec != null) {
            for (String weaponId : spec.weaponIds()) {
                WeaponType weapon = WeaponRules.WEAPONS.get(weaponId);
                if (weapon == null) continue;
                for (Cost cost : weapon.installationCost) {
                    total.merge(cost.material(), cost.amount(), Double::sum);
                }
            }
            for (Cost cost : ShipModuleRules.installationCost(spec.moduleIds())) {
                total.merge(cost.material(), cost.amount(), Double::sum);
            }
        }
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material,Double> entry : total.entrySet()) {
            if (entry.getValue() > 0) out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    static double refitTimeSeconds(ShipFitSpec spec) {
        ShipLoadoutDefinition defaultFit = spec == null ? null : WeaponRules.defaultLoadout(spec.hullId());
        if (defaultFit != null && defaultFit.refitTimeSeconds() > 0) return defaultFit.refitTimeSeconds();
        ShipType ship = spec == null ? null : Rules.findShip(spec.hullId());
        return ship == null ? 12 : Math.max(8, ship.buildTimeSeconds);
    }

    static String cleanName(String value) {
        String clean = Config.clean(value == null ? "" : value).replace('|', ' ').trim();
        return clean.length() <= MAX_FIT_NAME ? clean : clean.substring(0, MAX_FIT_NAME).trim();
    }

    record Validation(boolean valid, String reason) {
        static Validation accept() { return new Validation(true, ""); }
        static Validation reject(String reason) { return new Validation(false, reason == null ? "Invalid fit." : reason); }
    }
}
