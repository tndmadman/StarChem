package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recovery operations for policies whose assigned production station was destroyed or removed. */
final class ProductionPolicyRecoveryBridge {
    record OrphanView(String id, String stationId, ProductionPolicySystem.PolicyType type,
                      ProductionJobKind kind, String itemId, double targetAmount) { }

    private ProductionPolicyRecoveryBridge() { }

    static List<OrphanView> orphanViews(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        String systemId = world.activeSystemId();
        List<OrphanView> out = new ArrayList<>();
        Map<String,Object> snapshot = ProductionPolicySystem.capture(world);
        for (Object item : ServerSaveStore.list(snapshot.get("policies"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            if (!playerId.equals(ServerSaveStore.string(row, "ownerId", ""))) continue;
            if (!systemId.equals(ServerSaveStore.string(row, "systemId", ""))) continue;
            String stationId = ServerSaveStore.string(row, "stationId", "");
            Base assigned = world.bases.get(stationId);
            if (assigned != null && assigned.hp > 0 && playerId.equals(assigned.playerId)) continue;
            ProductionPolicySystem.PolicyType type = ServerSaveStore.enumValue(
                    ProductionPolicySystem.PolicyType.class, row.get("type"), null);
            ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, row.get("kind"), null);
            String itemId = ServerSaveStore.string(row, "itemId", "");
            if (type == null || kind == null || itemId.isBlank()) continue;
            out.add(new OrphanView(ServerSaveStore.string(row, "id", ""), stationId, type, kind,
                    itemId, ServerSaveStore.doubleValue(row, "targetAmount", 0)));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    static boolean reassign(World world, String playerId, String policyId, Base target) {
        if (world == null || target == null || target.hp <= 0 || !playerId.equals(target.playerId)
                || StationControls.nonProduction(target.typeId)) return false;
        Map<String,Object> snapshot = mutableSnapshot(world);
        List<Object> policies = new ArrayList<>(ServerSaveStore.list(snapshot.get("policies")));
        boolean changed = false;
        for (int i = 0; i < policies.size(); i++) {
            Map<String,Object> row = new LinkedHashMap<>(ServerSaveStore.object(policies.get(i)));
            if (!policyId.equals(ServerSaveStore.string(row, "id", ""))
                    || !playerId.equals(ServerSaveStore.string(row, "ownerId", ""))
                    || !world.activeSystemId().equals(ServerSaveStore.string(row, "systemId", ""))) continue;
            String oldStationId = ServerSaveStore.string(row, "stationId", "");
            Base old = world.bases.get(oldStationId);
            if (old != null && old.hp > 0 && playerId.equals(old.playerId)) return false;
            ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, row.get("kind"), null);
            String itemId = ServerSaveStore.string(row, "itemId", "");
            if (!compatible(target, kind, itemId)) return false;
            row.put("stationId", target.id);
            row.put("enabled", true);
            row.put("status", ProductionPolicySystem.PolicyStatus.WAITING_FOR_RESOURCES.name());
            row.put("reason", "reassigned after station loss");
            policies.set(i, row);
            changed = true;
            break;
        }
        if (!changed) return false;
        snapshot.put("policies", policies);
        snapshot.put("jobLinks", withoutPolicyLinks(snapshot.get("jobLinks"), policyId));
        ProductionPolicySystem.restore(world, snapshot);
        ProductionPolicySystem.refreshCurrentSystem(world);
        world.status = "Reassigned production policy " + policyId + " to " + target.type().name + ".";
        return true;
    }

    static boolean delete(World world, String playerId, String policyId) {
        if (world == null || playerId == null || policyId == null) return false;
        Map<String,Object> snapshot = mutableSnapshot(world);
        List<Object> policies = new ArrayList<>();
        boolean removed = false;
        for (Object item : ServerSaveStore.list(snapshot.get("policies"))) {
            Map<String,Object> row = new LinkedHashMap<>(ServerSaveStore.object(item));
            boolean match = policyId.equals(ServerSaveStore.string(row, "id", ""))
                    && playerId.equals(ServerSaveStore.string(row, "ownerId", ""))
                    && world.activeSystemId().equals(ServerSaveStore.string(row, "systemId", ""));
            if (match) {
                String stationId = ServerSaveStore.string(row, "stationId", "");
                Base assigned = world.bases.get(stationId);
                if (assigned != null && assigned.hp > 0 && playerId.equals(assigned.playerId)) return false;
                removed = true;
                continue;
            }
            policies.add(row);
        }
        if (!removed) return false;
        snapshot.put("policies", policies);
        snapshot.put("jobLinks", withoutPolicyLinks(snapshot.get("jobLinks"), policyId));
        ProductionPolicySystem.restore(world, snapshot);
        ProductionPolicySystem.refreshCurrentSystem(world);
        world.status = "Deleted orphaned production policy " + policyId + ".";
        return true;
    }

    private static Map<String,Object> mutableSnapshot(World world) {
        return new LinkedHashMap<>(ProductionPolicySystem.capture(world));
    }

    private static List<Object> withoutPolicyLinks(Object saved, String policyId) {
        List<Object> out = new ArrayList<>();
        for (Object item : ServerSaveStore.list(saved)) {
            Map<String,Object> row = new LinkedHashMap<>(ServerSaveStore.object(item));
            if (!policyId.equals(ServerSaveStore.string(row, "policyId", ""))) out.add(row);
        }
        return out;
    }

    private static boolean compatible(Base base, ProductionJobKind kind, String itemId) {
        if (base == null || kind == null || itemId == null || itemId.isBlank()) return false;
        if (kind == ProductionJobKind.SHIP) return base.type().buildableShips.contains(itemId);
        if (kind == ProductionJobKind.CRAFTABLE) {
            CraftableItem item = CraftingRules.item(itemId);
            return item != null && item.canCraftAt(base.typeId);
        }
        return false;
    }
}