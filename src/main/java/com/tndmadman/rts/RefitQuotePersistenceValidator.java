package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RefitQuotePersistenceValidator {
    private RefitQuotePersistenceValidator() { }

    public static void main(String[] args) {
        validateQuoteSerializationAndCancellation();
        validateCompletedRoundTripEconomics();
        validateDestroyedRestoredRefund();
        validateCompatibilityVersions();
        System.out.println("StarChem conversion refit quote and persistence validation passed.");
    }

    private static void validateQuoteSerializationAndCancellation() {
        String player = "REFIT_QUOTE";
        World world = world("Refit Quote", player);
        world.completeResearch(player, "combat_doctrine");

        Base base = base(world, player, "shipyard", 900, 900);
        Unit unit = unit(world, player, 1, "destroyer", "destroyer_rail_escort", 930, 900);

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
        requireInventory(base.inventory, before, "cancellation did not refund the exact reservation");

        ProductionJob legacy = new ProductionJob("P9", ProductionJobKind.REFIT, "destroyer", 1.5, 1.5, true, "");
        legacy.loadoutId = "destroyer_missile_screen";
        legacy.subjectUnitKey = unit.key();
        RefitQuote.migrateLegacy(legacy);
        require(legacy.refitQuoteVersion == 0 && !legacy.reservedCost.isEmpty(),
                "legacy full-cost reservation migration failed");
    }

    private static void validateCompletedRoundTripEconomics() {
        String player = "REFIT_ROUND_TRIP";
        World world = world("Refit Round Trip", player);
        world.completeResearch(player, "combat_doctrine");

        Base base = base(world, player, "shipyard", 900, 900);
        Unit unit = unit(world, player, 1, "destroyer", "destroyer_rail_escort", 930, 900);
        ShipLoadoutDefinition fitA = requiredLoadout("destroyer_rail_escort");
        ShipFitSpec fitBSpec = new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_railgun", "light_missile"), List.of());
        ShipLoadoutDefinition fitB = WorldFitCatalog.registerRuntime(world, "Round Trip Mixed Fit", fitBSpec);

        EnumMap<Material,Double> starting = new EnumMap<>(base.inventory);
        RefitQuote aToB = RefitQuote.between(unit, fitB);
        require(ProductionSystem.enqueueRefit(world, base, unit, fitB, false),
                "A-to-B refit was rejected");
        completeActiveJob(world, base);
        require(fitB.id().equals(unit.loadoutId), "A-to-B refit installed the wrong fit");

        RefitQuote bToA = RefitQuote.between(unit, fitA);
        require(amount(bToA.requiredMaterials(), Material.RAILGUN_ASSEMBLY) == 1,
                "B-to-A refit did not charge exactly one replacement railgun");
        require(bToA.requiredMaterials().stream().noneMatch(cost ->
                        cost.material() == Material.MISSILE_GUIDANCE_PACKAGE
                                || cost.material() == Material.MISSILE_WARHEAD),
                "B-to-A refit charged for removed missile equipment");
        require(ProductionSystem.enqueueRefit(world, base, unit, fitA, false),
                "B-to-A refit was rejected");
        completeActiveJob(world, base);
        require(fitA.id().equals(unit.loadoutId), "B-to-A refit installed the wrong fit");

        EnumMap<Material,Double> expected = subtract(starting, aToB.requiredMaterials());
        expected = subtract(expected, bToA.requiredMaterials());
        requireInventory(base.inventory, expected,
                "completed A-to-B-to-A conversion did not match its explicit scrap economics");
        for (Material material : Material.values()) {
            require(base.inventory.getOrDefault(material, 0.0)
                            <= starting.getOrDefault(material, 0.0) + 0.000001,
                    "A-to-B-to-A conversion created " + material);
        }
    }

    private static void validateDestroyedRestoredRefund() {
        String player = "REFIT_DESTROY_RESTORE";
        World world = world("Refit Destroy Restore", player);
        world.completeResearch(player, "combat_doctrine");

        Base base = base(world, player, "shipyard", 900, 900);
        Unit unit = unit(world, player, 1, "destroyer", "destroyer_rail_escort", 930, 900);
        ShipLoadoutDefinition target = requiredLoadout("destroyer_missile_screen");
        require(ProductionSystem.enqueueRefit(world, base, unit, target, false),
                "destroyed-restored refit fixture was rejected");
        ProductionJob originalJob = base.productionQueue.get(0);
        List<Cost> reserved = originalJob.reservedCost;
        require(!reserved.isEmpty() && originalJob.refitQuoteVersion == RefitQuote.CURRENT_VERSION,
                "destroyed-restored fixture did not persist an exact quote");

        Map<String,Object> galaxy = world.captureServerSaveGalaxy();
        World restored = world("Refit Destroy Restored", player);
        restored.restoreServerSaveGalaxy(galaxy);
        Base restoredBase = restored.bases.get(base.id);
        Unit restoredUnit = restored.units.get(unit.key());
        require(restoredBase != null && restoredUnit != null,
                "save restore lost the active refit station or subject");
        require(restoredBase.productionQueue.size() == 1,
                "save restore lost the active refit job");
        ProductionJob restoredJob = restoredBase.productionQueue.get(0);
        require(restoredJob.reservedCost.equals(reserved)
                        && restoredJob.refitQuoteVersion == RefitQuote.CURRENT_VERSION,
                "save restore changed the exact reserved quote");

        EnumMap<Material,Double> beforeDestruction = new EnumMap<>(restoredBase.inventory);
        EnumMap<Material,Double> expectedRefund = add(beforeDestruction, reserved);
        restoredUnit.hp = 0;
        ProductionSystem.update(restored, 0.1);
        require(restoredBase.productionQueue.isEmpty(),
                "destroyed restored subject left its refit job queued");
        requireInventory(restoredBase.inventory, expectedRefund,
                "destroyed restored subject did not refund the persisted quote exactly once");

        EnumMap<Material,Double> afterFirstCleanup = new EnumMap<>(restoredBase.inventory);
        ProductionSystem.update(restored, 0.1);
        requireInventory(restoredBase.inventory, afterFirstCleanup,
                "destroyed restored subject was refunded more than once");
    }

    private static void validateCompatibilityVersions() {
        require(MultiplayerCompatibility.PROTOCOL_VERSION == 15, "protocol version was not bumped");
        require(ServerSaveStore.SAVE_FORMAT_VERSION == 6, "save format was not bumped");
        require(MultiplayerCompatibility.local().rulesVersion() == 27, "rules version was not bumped");
    }

    private static World world(String name, String player) {
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, name, 0x55CCFF);
        return world;
    }

    private static Base base(World world, String player, String type, double x, double y) {
        Base base = new Base(player + ":B1", player, type, x, y);
        for (Material material : Material.values()) base.inventory.put(material, 1000.0);
        world.bases.put(base.id, base);
        return base;
    }

    private static Unit unit(World world, String player, int id, String hull, String loadout,
                             double x, double y) {
        Unit unit = new Unit(player, id, hull, x, y);
        unit.loadoutId = loadout;
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static ShipLoadoutDefinition requiredLoadout(String id) {
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(id);
        if (loadout == null) throw new IllegalStateException("Missing test loadout " + id);
        return loadout;
    }

    private static void completeActiveJob(World world, Base base) {
        ProductionJob job = base.productionQueue.get(0);
        job.remaining = 0;
        ProductionSystem.update(world, 0.1);
        require(!base.productionQueue.contains(job), "completed refit remained in the production queue");
    }

    private static EnumMap<Material,Double> subtract(Map<Material,Double> source, List<Cost> costs) {
        EnumMap<Material,Double> out = new EnumMap<>(Material.class);
        out.putAll(source);
        for (Cost cost : costs) out.put(cost.material(),
                out.getOrDefault(cost.material(), 0.0) - cost.amount());
        return out;
    }

    private static EnumMap<Material,Double> add(Map<Material,Double> source, List<Cost> costs) {
        EnumMap<Material,Double> out = new EnumMap<>(Material.class);
        out.putAll(source);
        for (Cost cost : costs) out.put(cost.material(),
                out.getOrDefault(cost.material(), 0.0) + cost.amount());
        return out;
    }

    private static void requireInventory(Map<Material,Double> actual, Map<Material,Double> expected,
                                         String message) {
        for (Material material : Material.values()) {
            if (!close(actual.getOrDefault(material, 0.0), expected.getOrDefault(material, 0.0))) {
                throw new IllegalStateException(message + " for " + material + ": expected "
                        + expected.getOrDefault(material, 0.0) + " but found "
                        + actual.getOrDefault(material, 0.0));
            }
        }
    }

    private static double amount(List<Cost> costs, Material material) {
        double total = 0;
        for (Cost cost : costs) if (cost.material() == material) total += cost.amount();
        return total;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
