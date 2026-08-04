#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path): return (ROOT / path).read_text(encoding='utf-8')
def write(path, text): (ROOT / path).write_text(text, encoding='utf-8')
def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1: raise SystemExit(f'{label}: expected 1 exact match, found {count}')
    return text.replace(old, new, 1)
def regex_once(text, pattern, replacement, label):
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1: raise SystemExit(f'{label}: expected 1 regex match, found {count}')
    return updated

write('src/main/java/com/tndmadman/rts/RefitQuote.java', r'''package com.tndmadman.rts;

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
        if (unit == null || destination == null || !unit.shipTypeId.equals(destination.hullId())) {
            throw new IllegalArgumentException("A matching source ship and destination fit are required.");
        }
        ShipLoadoutDefinition source = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);
        if (source == null) throw new IllegalArgumentException("The source ship fit is unavailable.");

        List<String> sourceWeapons = source.weaponIds();
        List<String> targetWeapons = destination.weaponIds();
        List<String> sourceModules = ShipModuleRules.moduleIds(source);
        List<String> targetModules = ShipModuleRules.moduleIds(destination);
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
''')

# Production jobs now own the exact reservation and source fit.
p = read('src/main/java/com/tndmadman/rts/ProductionSystem.java')
p = regex_once(p,
    r'''    static boolean enqueueRefit\(World world, Base base, Unit unit, ShipLoadoutDefinition loadout, boolean free\) \{.*?\n    \}\n\n    static boolean enqueuePackage''',
'''    static boolean enqueueRefit(World world, Base base, Unit unit, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || base == null || unit == null || loadout == null) return false;
        if (base.hp <= 0 || !base.type().canRefitShips || !unit.playerId.equals(base.playerId)) {
            world.status = "An owned refit-capable station is required.";
            return false;
        }
        if (unit.hp <= 0 || !unit.shipTypeId.equals(loadout.hullId())) {
            world.status = "That loadout is not valid for the selected ship.";
            return false;
        }
        if (loadout.id().equals(unit.loadoutId)) {
            world.status = unit.type().name + " already uses " + loadout.displayName() + ".";
            return false;
        }
        if (!free && !WeaponRules.unlocked(world, base.playerId, loadout)) {
            world.status = loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, base.playerId, loadout) + ".";
            return false;
        }
        if (refitReserved(world, unit.key())) {
            world.status = "That ship is already reserved for refitting.";
            return false;
        }
        RefitQuote quote = RefitQuote.between(unit, loadout);
        List<Cost> cost = free ? List.of() : quote.requiredMaterials();
        if (!free && !HangarStore.canAfford(base.inventory, cost)) {
            world.status = "Need " + Rules.formatCost(cost) + " in " + base.type().name + " hangar.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, cost);
        ProductionJob job = enqueueRefitPrepaid(base, unit, loadout, quote, !free);
        beginRefit(world, base, unit, job);
        world.status = "Recalling " + unit.type().name + " to " + base.type().name
                + " for refit: " + loadout.displayName() + ".";
        AlertCenter.push(world, world.status);
        processBase(world, base, 0);
        return true;
    }

    static ProductionJob enqueueRefitPrepaid(Base base, Unit unit, ShipLoadoutDefinition loadout,
                                             RefitQuote quote, boolean resourcesReserved) {
        if (base == null || unit == null || loadout == null || quote == null) return null;
        ProductionJob job = newJob(base, ProductionJobKind.REFIT, unit.shipTypeId,
                quote.durationSeconds(), resourcesReserved, "");
        job.loadoutId = loadout.id();
        job.subjectUnitKey = unit.key();
        job.sourceLoadoutId = quote.sourceLoadoutId();
        job.reservedCost = resourcesReserved ? quote.requiredMaterials() : List.of();
        job.refitQuoteVersion = quote.version();
        job.blockedReason = "recalling ship to refit";
        base.productionQueue.add(job);
        return job;
    }

    static void beginRefit(World world, Base base, Unit unit, ProductionJob job) {
        if (world == null || base == null || unit == null || job == null) return;
        recall(base, unit, job);
    }

    static void processBaseAfterTransaction(World world, Base base) {
        processBase(world, base, 0);
    }

    static boolean enqueuePackage''', 'ProductionSystem.enqueueRefit')

p = replace_once(p,
'''            case REFIT -> WeaponRules.refitCost(WeaponRules.findLoadout(job.loadoutId));
''',
'''            case REFIT -> job.refitQuoteVersion > 0
                    ? job.reservedCost
                    : RefitQuote.legacyReservedCost(job);
''', 'ProductionSystem.costFor refit')

p = replace_once(p,
'''    String loadoutId = "";
    String subjectUnitKey = "";
    String blockedReason = "";
''',
'''    String loadoutId = "";
    String subjectUnitKey = "";
    String sourceLoadoutId = "";
    List<Cost> reservedCost = List.of();
    int refitQuoteVersion;
    String blockedReason = "";
''', 'ProductionJob fields')

p = replace_once(p,
'''                    .append(clean(job.blockedReason)).append('^').append(clean(job.loadoutId)).append('^')
                    .append(clean(job.subjectUnitKey));
''',
'''                    .append(clean(job.blockedReason)).append('^').append(clean(job.loadoutId)).append('^')
                    .append(clean(job.subjectUnitKey)).append('^').append(clean(job.sourceLoadoutId)).append('^')
                    .append(job.refitQuoteVersion).append('^').append(RefitQuote.encodeCosts(job.reservedCost));
''', 'ProductionQueueCodec write')

p = replace_once(p,
'''                if (c.length >= 10) job.subjectUnitKey = unclean(c[9]);
                base.productionQueue.add(job);
''',
'''                if (c.length >= 10) job.subjectUnitKey = unclean(c[9]);
                if (c.length >= 13) {
                    job.sourceLoadoutId = unclean(c[10]);
                    job.refitQuoteVersion = Integer.parseInt(c[11]);
                    job.reservedCost = RefitQuote.decodeCosts(c[12]);
                } else RefitQuote.migrateLegacy(job);
                base.productionQueue.add(job);
''', 'ProductionQueueCodec read')
write('src/main/java/com/tndmadman/rts/ProductionSystem.java', p)

# Strict snapshot queue codec accepts and validates the new row shape.
s = read('src/main/java/com/tndmadman/rts/StrictProductionQueueCodec.java')
s = replace_once(s,
'''            if (columns.length != 7 && columns.length != 8 && columns.length != 10) {
                throw error(systemId, baseId, rowIndex,
                        "expected 7 or 8 columns for legacy rows, or 10 columns for loadout rows, but found " + columns.length);
            }
''',
'''            if (columns.length != 7 && columns.length != 8 && columns.length != 10 && columns.length != 13) {
                throw error(systemId, baseId, rowIndex,
                        "expected 7 or 8 legacy columns, 10 loadout columns, or 13 quote columns, but found " + columns.length);
            }
''', 'Strict codec column count')
s = replace_once(s,
'''            validateLoadoutFields(kind, itemId, loadoutId, subjectUnitKey, systemId, baseId, rowIndex);

            ProductionJob job = new ProductionJob(id, kind, itemId, duration, remaining,
                    resourcesReserved, reservedUnitKey);
            job.blockedReason = blockedReason;
            job.loadoutId = kind == ProductionJobKind.SHIP && loadoutId.isBlank()
                    ? WeaponRules.defaultLoadoutId(itemId) : loadoutId;
            job.subjectUnitKey = subjectUnitKey;
            jobs.add(job);
''',
'''            validateLoadoutFields(kind, itemId, loadoutId, subjectUnitKey, systemId, baseId, rowIndex);
            String sourceLoadoutId = columns.length >= 13 ? unclean(columns[10]) : "";
            int quoteVersion = columns.length >= 13
                    ? nonNegativeInteger(columns[11], "refit quote version", systemId, baseId, rowIndex) : 0;
            List<Cost> reservedCost;
            try { reservedCost = columns.length >= 13 ? RefitQuote.decodeCosts(columns[12]) : List.of(); }
            catch (IllegalArgumentException ex) { throw error(systemId, baseId, rowIndex, ex.getMessage()); }
            validateQuoteFields(kind, itemId, sourceLoadoutId, quoteVersion, reservedCost,
                    systemId, baseId, rowIndex);

            ProductionJob job = new ProductionJob(id, kind, itemId, duration, remaining,
                    resourcesReserved, reservedUnitKey);
            job.blockedReason = blockedReason;
            job.loadoutId = kind == ProductionJobKind.SHIP && loadoutId.isBlank()
                    ? WeaponRules.defaultLoadoutId(itemId) : loadoutId;
            job.subjectUnitKey = subjectUnitKey;
            job.sourceLoadoutId = sourceLoadoutId;
            job.refitQuoteVersion = quoteVersion;
            job.reservedCost = reservedCost;
            if (columns.length < 13) RefitQuote.migrateLegacy(job);
            jobs.add(job);
''', 'Strict codec quote parse')

anchor = '''    private static void validateReservedUnitKey(ProductionJobKind kind, String value, String systemId,
'''
helpers = '''    private static void validateQuoteFields(ProductionJobKind kind, String itemId, String sourceLoadoutId,
                                            int quoteVersion, List<Cost> reservedCost,
                                            String systemId, String baseId, int rowIndex) {
        validateText(sourceLoadoutId, MAX_ITEM_ID_LENGTH, "source loadout ID", systemId, baseId, rowIndex);
        if (kind != ProductionJobKind.REFIT) {
            if (!sourceLoadoutId.isBlank() || quoteVersion != 0 || !reservedCost.isEmpty()) {
                throw error(systemId, baseId, rowIndex, "refit quote fields are only valid for refit jobs");
            }
            return;
        }
        if (quoteVersion == 0) {
            if (!sourceLoadoutId.isBlank() || !reservedCost.isEmpty()) {
                throw error(systemId, baseId, rowIndex, "legacy refit rows cannot contain quote fields");
            }
            return;
        }
        if (quoteVersion != RefitQuote.CURRENT_VERSION) {
            throw error(systemId, baseId, rowIndex, "unsupported refit quote version " + quoteVersion);
        }
        ShipLoadoutDefinition source = WeaponRules.findLoadout(sourceLoadoutId);
        if (source == null || !itemId.equals(source.hullId())) {
            throw error(systemId, baseId, rowIndex, "unknown or mismatched source loadout ID " + printable(sourceLoadoutId));
        }
    }

    private static int nonNegativeInteger(String value, String field, String systemId, String baseId, int rowIndex) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException ex) {
            throw error(systemId, baseId, rowIndex, field + " must be a non-negative integer");
        }
    }

    private static void validateReservedUnitKey(ProductionJobKind kind, String value, String systemId,
'''
s = replace_once(s, anchor, helpers, 'Strict codec helpers')
write('src/main/java/com/tndmadman/rts/StrictProductionQueueCodec.java', s)

# Structured server saves persist the exact quote.
g = read('src/main/java/com/tndmadman/rts/GalaxyCoordinator.java')
g = replace_once(g,
'''            row.put("reservedUnitKey", job.reservedUnitKey); row.put("loadoutId", job.loadoutId);
            row.put("subjectUnitKey", job.subjectUnitKey); row.put("blockedReason", job.blockedReason);
''',
'''            row.put("reservedUnitKey", job.reservedUnitKey); row.put("loadoutId", job.loadoutId);
            row.put("subjectUnitKey", job.subjectUnitKey); row.put("sourceLoadoutId", job.sourceLoadoutId);
            row.put("refitQuoteVersion", job.refitQuoteVersion);
            row.put("reservedCost", RefitQuote.costMap(job.reservedCost));
            row.put("blockedReason", job.blockedReason);
''', 'Galaxy capture quote')
g = replace_once(g,
'''            job.subjectUnitKey = ServerSaveStore.string(row, "subjectUnitKey", "");
            if ((kind == ProductionJobKind.SHIP || kind == ProductionJobKind.REFIT)) {
''',
'''            job.subjectUnitKey = ServerSaveStore.string(row, "subjectUnitKey", "");
            job.sourceLoadoutId = ServerSaveStore.string(row, "sourceLoadoutId", "");
            job.refitQuoteVersion = Math.max(0, ServerSaveStore.intValue(row, "refitQuoteVersion", 0));
            job.reservedCost = RefitQuote.costsFromMap(row.get("reservedCost"));
            if (job.refitQuoteVersion == 0) RefitQuote.migrateLegacy(job);
            if ((kind == ProductionJobKind.SHIP || kind == ProductionJobKind.REFIT)) {
''', 'Galaxy restore quote')
write('src/main/java/com/tndmadman/rts/GalaxyCoordinator.java', g)

# Compatibility versions.
config_path = ROOT / 'config/starchem.json'
config = json.loads(config_path.read_text(encoding='utf-8'))
config['rulesVersion'] = 25
config_path.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')

m = read('src/main/java/com/tndmadman/rts/MultiplayerCompatibility.java')
m = replace_once(m, 'static final int PROTOCOL_VERSION = 12;', 'static final int PROTOCOL_VERSION = 13;', 'protocol version')
write('src/main/java/com/tndmadman/rts/MultiplayerCompatibility.java', m)

ss = read('src/main/java/com/tndmadman/rts/ServerSaveStore.java')
ss = replace_once(ss, 'static final int SAVE_FORMAT_VERSION = 4;', 'static final int SAVE_FORMAT_VERSION = 5;', 'save version')
write('src/main/java/com/tndmadman/rts/ServerSaveStore.java', ss)

sm = read('src/main/java/com/tndmadman/rts/ServerSaveMigration.java')
sm = replace_once(sm,
'''        if (version != ServerSaveStore.SAVE_FORMAT_VERSION) {
''',
'''        if (version == 4) {
            version = 5;
            notes.add("v4->v5 persists exact source-to-destination refit reservations");
        }
        if (version != ServerSaveStore.SAVE_FORMAT_VERSION) {
''', 'save migration v5')
write('src/main/java/com/tndmadman/rts/ServerSaveMigration.java', sm)

# Focused economics, cancellation, and wire round-trip validator.
write('src/main/java/com/tndmadman/rts/RefitQuotePersistenceValidator.java', r'''package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;

public final class RefitQuotePersistenceValidator {
    private RefitQuotePersistenceValidator() { }

    public static void main(String[] args) {
        String player = "REFIT_QUOTE";
        PlayerRegistry.reset(player, "Refit Quote", 0x55CCFF);
        World world = new World("Refit Quote", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.completeResearch(player, "combat_doctrine");

        Base base = new Base(player + ":B1", player, "shipyard", 900, 900);
        world.bases.put(base.id, base);
        for (Material material : Material.values()) base.inventory.put(material, 1000.0);
        Unit unit = new Unit(player, 1, "destroyer", 930, 900);
        unit.loadoutId = "destroyer_rail_escort";
        world.units.put(unit.key(), unit);

        ShipFitSpec spec = new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_railgun", "light_missile"), List.of());
        ShipLoadoutDefinition target = WorldFitCatalog.registerRuntime(world, "Conversion Target", spec);
        RefitQuote quote = RefitQuote.between(unit, target);
        require(quote.requiredMaterials().stream().noneMatch(cost -> cost.material() == Material.RAILGUN_ASSEMBLY),
                "retained railguns were charged again");
        require(amount(quote.requiredMaterials(), Material.MISSILE_GUIDANCE_PACKAGE) == 1,
                "conversion quote did not charge one missile guidance package");
        require(amount(quote.requiredMaterials(), Material.MISSILE_WARHEAD) == 2,
                "conversion quote did not charge two missile warheads");
        require(quote.removedComponents().stream().anyMatch(value -> value.contains("Light Railgun")),
                "removed component was not described as scrapped");

        EnumMap<Material,Double> before = new EnumMap<>(base.inventory);
        require(ProductionSystem.enqueueRefit(world, base, unit, target, false), "conversion refit was rejected");
        ProductionJob job = base.productionQueue.get(0);
        require(job.refitQuoteVersion == RefitQuote.CURRENT_VERSION, "quote version was not stored");
        require("destroyer_rail_escort".equals(job.sourceLoadoutId), "source fit was not stored");
        require(job.reservedCost.equals(quote.requiredMaterials()), "exact reserved quote was not stored");

        String encoded = ProductionQueueCodec.write(base.productionQueue);
        Base decoded = new Base(player + ":B2", player, "shipyard", 1200, 900);
        ProductionQueueCodec.readInto(encoded, decoded);
        ProductionJob roundTrip = decoded.productionQueue.get(0);
        require(roundTrip.refitQuoteVersion == RefitQuote.CURRENT_VERSION, "queue quote version did not round-trip");
        require(roundTrip.sourceLoadoutId.equals(job.sourceLoadoutId), "queue source fit did not round-trip");
        require(roundTrip.reservedCost.equals(job.reservedCost), "queue exact reservation did not round-trip");
        StrictProductionQueueCodec.decode(encoded, world.activeSystemId(), decoded.id);

        require(ProductionSystem.cancel(world, player, base.id, job.id), "conversion refit cancellation failed");
        for (Material material : Material.values()) {
            require(close(base.inventory.getOrDefault(material, 0.0), before.getOrDefault(material, 0.0)),
                    "cancellation did not refund exact reservation for " + material);
        }

        ProductionJob legacy = new ProductionJob("P9", ProductionJobKind.REFIT, "destroyer", 1.5, 1.5, true, "");
        legacy.loadoutId = "destroyer_missile_screen";
        legacy.subjectUnitKey = unit.key();
        RefitQuote.migrateLegacy(legacy);
        require(legacy.refitQuoteVersion == 0 && !legacy.reservedCost.isEmpty(),
                "legacy full-cost reservation migration failed");

        require(MultiplayerCompatibility.PROTOCOL_VERSION == 13, "protocol version was not bumped");
        require(ServerSaveStore.SAVE_FORMAT_VERSION == 5, "save format was not bumped");
        require(MultiplayerCompatibility.local().rulesVersion() == 25, "rules version was not bumped");
        System.out.println("StarChem conversion refit quote and persistence validation passed.");
    }

    private static double amount(List<Cost> costs, Material material) {
        double total = 0;
        for (Cost cost : costs) if (cost.material() == material) total += cost.amount();
        return total;
    }

    private static boolean close(double left, double right) { return Math.abs(left - right) < 0.000001; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
''')

print('Applied issue #289 phase 2A conversion refit persistence.')
