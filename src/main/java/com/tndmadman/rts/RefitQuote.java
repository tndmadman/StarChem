package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable, versioned source-to-destination refit economics. Removed equipment is scrapped. */
record RefitQuote(String sourceLoadoutId, String destinationLoadoutId, List<Cost> requiredMaterials,
                  List<String> removedComponents, double durationSeconds, int version) {
    static final int CURRENT_VERSION = 1;

    RefitQuote {
        sourceLoadoutId = sourceLoadoutId == null ? "" : sourceLoadoutId.trim();
        destinationLoadoutId = destinationLoadoutId == null ? "" : destinationLoadoutId.trim();
        requiredMaterials = requiredMaterials == null ? List.of() : List.copyOf(requiredMaterials);
        removedComponents = removedComponents == null ? List.of() : List.copyOf(removedComponents);
        durationSeconds = Math.max(0, durationSeconds);
        version = Math.max(0, version);
    }

    static RefitQuote between(Unit unit, ShipLoadoutDefinition destination) {
        return between(unit, destination, ShipModuleRules.moduleIds(destination));
    }

    static RefitQuote between(Unit unit, ShipLoadoutDefinition destination, List<String> destinationModules) {
        if (unit == null || destination == null || !unit.shipTypeId.equals(destination.hullId())) {
            throw new IllegalArgumentException("A matching source ship and destination fit are required.");
        }
        ShipLoadoutDefinition source = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);
        if (source == null) throw new IllegalArgumentException("The source ship fit is unavailable.");

        List<String> sourceWeapons = source.weaponIds();
        List<String> targetWeapons = destination.weaponIds();
        List<String> sourceModules = ShipModuleRules.moduleIds(source);
        List<String> targetModules = destinationModules == null ? List.of() : List.copyOf(destinationModules);
        EnumMap<Material,Double> required = new EnumMap<>(Material.class);
        List<String> removed = new ArrayList<>();
        addWeaponDelta(sourceWeapons, targetWeapons, required, removed);
        addModuleDelta(sourceModules, targetModules, required, removed);
        return new RefitQuote(source.id(), destination.id(), costs(required), removed,
                destination.refitTimeSeconds(), CURRENT_VERSION);
    }

    static List<Cost> fullInstallationCost(ShipLoadoutDefinition loadout) {
        if (loadout == null) return List.of();
        return PlayerFitRules.installationCost(new ShipFitSpec(loadout.hullId(), loadout.weaponIds(),
                ShipModuleRules.moduleIds(loadout)));
    }

    static List<Cost> legacyReservedCost(ProductionJob job) {
        if (job == null || job.kind != ProductionJobKind.REFIT || !job.resourcesReserved) return List.of();
        ShipLoadoutDefinition destination = WeaponRules.findLoadout(job.loadoutId);
        return destination == null ? List.of() : WeaponRules.refitCost(destination);
    }

    static void migrateLegacy(ProductionJob job) {
        if (job == null || job.kind != ProductionJobKind.REFIT || job.refitQuoteVersion > 0) return;
        job.reservedCost = legacyReservedCost(job);
    }

    static String encodeCosts(List<Cost> costs) {
        if (costs == null || costs.isEmpty()) return "-";
        StringBuilder out = new StringBuilder();
        for (Cost cost : costs) {
            if (cost == null || cost.material() == null || !Double.isFinite(cost.amount()) || cost.amount() <= 0) continue;
            if (!out.isEmpty()) out.append('+');
            out.append(cost.material().name()).append(':')
                    .append(String.format(Locale.ROOT, "%.9f", cost.amount()).replaceAll("0+$", "").replaceAll("\\.$", ""));
        }
        return out.isEmpty() ? "-" : out.toString();
    }

    static List<Cost> decodeCosts(String encoded) {
        if (encoded == null || encoded.isBlank() || "-".equals(encoded)) return List.of();
        EnumMap<Material,Double> totals = new EnumMap<>(Material.class);
        String[] rows = encoded.split("\\+", -1);
        if (rows.length > Material.values().length) throw new IllegalArgumentException("Too many reserved-cost rows.");
        for (String row : rows) {
            int colon = row.indexOf(':');
            if (colon <= 0 || colon == row.length() - 1) throw new IllegalArgumentException("Malformed reserved cost.");
            Material material;
            double amount;
            try {
                material = Material.valueOf(row.substring(0, colon));
                amount = Double.parseDouble(row.substring(colon + 1));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Malformed reserved cost.", ex);
            }
            if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000) {
                throw new IllegalArgumentException("Reserved cost amount is outside the allowed range.");
            }
            totals.merge(material, amount, Double::sum);
        }
        return costs(totals);
    }

    static Map<String,Object> costMap(List<Cost> costs) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (costs != null) for (Cost cost : costs) {
            if (cost != null && cost.material() != null && Double.isFinite(cost.amount()) && cost.amount() > 0) {
                out.merge(cost.material().name(), cost.amount(),
                        (left, right) -> ((Number)left).doubleValue() + ((Number)right).doubleValue());
            }
        }
        return out;
    }

    static List<Cost> costsFromMap(Object value) {
        Map<String,Object> row = ServerSaveStore.object(value);
        EnumMap<Material,Double> totals = new EnumMap<>(Material.class);
        for (Map.Entry<String,Object> entry : row.entrySet()) {
            Material material;
            try { material = Material.valueOf(entry.getKey()); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Unknown reserved-cost material " + entry.getKey() + "."); }
            if (!(entry.getValue() instanceof Number number)) {
                throw new IllegalArgumentException("Reserved-cost amount is not numeric.");
            }
            double amount = number.doubleValue();
            if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000) {
                throw new IllegalArgumentException("Reserved-cost amount is outside the allowed range.");
            }
            totals.merge(material, amount, Double::sum);
        }
        return costs(totals);
    }

    private static void addWeaponDelta(List<String> source, List<String> target,
                                       EnumMap<Material,Double> required, List<String> removed) {
        Map<String,Integer> delta = count(target);
        subtract(delta, source);
        for (Map.Entry<String,Integer> entry : delta.entrySet()) {
            WeaponType weapon = WeaponRules.WEAPONS.get(entry.getKey());
            if (weapon == null) throw new IllegalArgumentException("Unknown weapon " + entry.getKey() + ".");
            if (entry.getValue() > 0) addRepeated(required, weapon.installationCost, entry.getValue());
            else if (entry.getValue() < 0) removed.add((-entry.getValue()) + "× " + weapon.name);
        }
    }

    private static void addModuleDelta(List<String> source, List<String> target,
                                       EnumMap<Material,Double> required, List<String> removed) {
        Map<String,Integer> delta = count(target);
        subtract(delta, source);
        for (Map.Entry<String,Integer> entry : delta.entrySet()) {
            ShipModuleDefinition module = ShipModuleRules.find(entry.getKey());
            if (module == null) throw new IllegalArgumentException("Unknown utility module " + entry.getKey() + ".");
            if (entry.getValue() > 0) addRepeated(required, module.installationCost(), entry.getValue());
            else if (entry.getValue() < 0) removed.add((-entry.getValue()) + "× " + module.displayName());
        }
    }

    private static Map<String,Integer> count(List<String> values) {
        Map<String,Integer> out = new LinkedHashMap<>();
        if (values != null) for (String value : values) out.merge(value, 1, Integer::sum);
        return out;
    }

    private static void subtract(Map<String,Integer> delta, List<String> source) {
        if (source != null) for (String value : source) delta.merge(value, -1, Integer::sum);
        delta.entrySet().removeIf(entry -> entry.getValue() == 0);
    }

    private static void addRepeated(EnumMap<Material,Double> total, List<Cost> costs, int count) {
        for (Cost cost : costs) total.merge(cost.material(), cost.amount() * count, Double::sum);
    }

    private static List<Cost> costs(EnumMap<Material,Double> totals) {
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material,Double> entry : totals.entrySet()) {
            if (entry.getValue() > 0) out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }
}
