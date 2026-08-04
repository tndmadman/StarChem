package com.tndmadman.rts;

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
