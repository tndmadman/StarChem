package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only built-in starter templates for common production-policy setups. */
final class ProductionPolicyStarterTemplates {
    static final String COMMAND_APPLY = "STARTER_APPLY";

    static final String BASIC_FUEL_COMPONENTS = "STARTER_BASIC_FUEL_COMPONENTS";
    static final String MINING_REPLACEMENT = "STARTER_MINING_REPLACEMENT";
    static final String COMBAT_REPLACEMENT = "STARTER_COMBAT_REPLACEMENT";
    static final String MANUFACTURING_INPUTS = "STARTER_MANUFACTURING_INPUTS";

    record StarterView(String id, String name, int entryCount) { }

    private record Entry(ProductionPolicySystem.PolicyType type, ProductionJobKind kind,
                         String itemId, String loadoutId, double target, int batch,
                         int priority, int maxOutstanding) { }

    private ProductionPolicyStarterTemplates() { }

    static List<StarterView> viewsFor(Base base) {
        if (base == null || base.hp <= 0 || StationControls.nonProduction(base.typeId)) return List.of();
        List<StarterView> out = new ArrayList<>();
        addView(out, BASIC_FUEL_COMPONENTS, "Basic Fuel & Components", entries(base, BASIC_FUEL_COMPONENTS));
        addView(out, MINING_REPLACEMENT, "Mining Replacement", entries(base, MINING_REPLACEMENT));
        addView(out, COMBAT_REPLACEMENT, "Combat Replacement", entries(base, COMBAT_REPLACEMENT));
        addView(out, MANUFACTURING_INPUTS, "Manufacturing Inputs", entries(base, MANUFACTURING_INPUTS));
        return List.copyOf(out);
    }

    static boolean apply(World world, String playerId, Base base, String starterId) {
        if (world == null || base == null || base.hp <= 0 || playerId == null
                || !playerId.equals(base.playerId) || StationControls.nonProduction(base.typeId)) return false;
        List<Entry> entries = entries(base, starterId);
        if (entries.isEmpty()) {
            world.status = "That starter template is not compatible with " + base.type().name + ".";
            return false;
        }

        Map<String,Object> before = new LinkedHashMap<>(ProductionPolicySystem.capture(world));
        int playerPolicies = 0;
        int stationPolicies = 0;
        for (Object item : ServerSaveStore.list(before.get("policies"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            if (!playerId.equals(ServerSaveStore.string(row, "ownerId", ""))) continue;
            playerPolicies++;
            if (world.activeSystemId().equals(ServerSaveStore.string(row, "systemId", ""))
                    && base.id.equals(ServerSaveStore.string(row, "stationId", ""))) stationPolicies++;
        }
        if (playerPolicies + entries.size() > ProductionPolicySystem.MAX_POLICIES_PER_PLAYER
                || stationPolicies + entries.size() > ProductionPolicySystem.MAX_POLICIES_PER_STATION) {
            world.status = "Applying that starter template would exceed the production policy limit.";
            return false;
        }

        for (Entry entry : entries) {
            String encoded = ProductionPolicyWire.encodeSpec("", entry.type, entry.kind, entry.itemId,
                    entry.loadoutId, entry.target, entry.batch, entry.priority, entry.maxOutstanding,
                    0, Map.of(), Map.of());
            if (!ProductionPolicySystem.applyCommand(world, playerId, base.id,
                    ProductionPolicySystem.COMMAND_CREATE, encoded)) {
                ProductionPolicySystem.restore(world, before);
                ProductionPolicySystem.refreshCurrentSystem(world);
                world.status = "Starter template could not be applied atomically.";
                return false;
            }
        }
        world.status = "Applied starter production template " + displayName(starterId) + ".";
        return true;
    }

    private static void addView(List<StarterView> out, String id, String name, List<Entry> entries) {
        if (!entries.isEmpty()) out.add(new StarterView(id, name, entries.size()));
    }

    private static List<Entry> entries(Base base, String starterId) {
        if (starterId == null || base == null) return List.of();
        List<Entry> out = new ArrayList<>();
        switch (starterId) {
            case BASIC_FUEL_COMPONENTS -> {
                addCraftable(base, out, "fuel", 200, 2, 90, 2);
                addCraftable(base, out, "steel_plate", 80, 2, 80, 2);
                addCraftable(base, out, "copper_wiring", 100, 2, 70, 2);
                addCraftable(base, out, "structural_frame", 40, 2, 60, 2);
            }
            case MANUFACTURING_INPUTS -> {
                addCraftable(base, out, "nickel_steel", 64, 2, 90, 2);
                addCraftable(base, out, "aluminum_alloy", 72, 2, 80, 2);
                addCraftable(base, out, "printed_circuit_board", 40, 2, 70, 2);
                addCraftable(base, out, "power_regulator", 32, 2, 60, 2);
            }
            case MINING_REPLACEMENT -> {
                addShip(base, out, "prospector", 3, 1, 90, 2);
                addShip(base, out, "deep_miner", 2, 1, 80, 2);
                addShip(base, out, "gas_harvester", 1, 1, 70, 1);
                addShip(base, out, "salvager", 1, 1, 60, 1);
            }
            case COMBAT_REPLACEMENT -> {
                addShip(base, out, "frigate", 4, 2, 90, 4);
                addShip(base, out, "destroyer", 2, 1, 80, 2);
                addShip(base, out, "cruiser", 1, 1, 70, 1);
            }
            default -> { return List.of(); }
        }
        return List.copyOf(out);
    }

    private static void addCraftable(Base base, List<Entry> out, String itemId, double target,
                                     int batch, int priority, int maxOutstanding) {
        CraftableItem item = CraftingRules.item(itemId);
        if (item == null || !item.canCraftAt(base.typeId)) return;
        out.add(new Entry(ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, itemId, "", target, batch, priority, maxOutstanding));
    }

    private static void addShip(Base base, List<Entry> out, String shipId, double target,
                                int batch, int priority, int maxOutstanding) {
        if (!base.type().buildableShips.contains(shipId) || Rules.findShip(shipId) == null) return;
        String loadoutId = WeaponRules.defaultLoadoutId(shipId);
        if (loadoutId == null || loadoutId.isBlank()) return;
        out.add(new Entry(ProductionPolicySystem.PolicyType.MAINTAIN_FLEET,
                ProductionJobKind.SHIP, shipId, loadoutId, target, batch, priority, maxOutstanding));
    }

    private static String displayName(String starterId) {
        return switch (starterId) {
            case BASIC_FUEL_COMPONENTS -> "Basic Fuel & Components";
            case MINING_REPLACEMENT -> "Mining Replacement";
            case COMBAT_REPLACEMENT -> "Combat Replacement";
            case MANUFACTURING_INPUTS -> "Manufacturing Inputs";
            default -> starterId == null ? "starter" : starterId;
        };
    }
}