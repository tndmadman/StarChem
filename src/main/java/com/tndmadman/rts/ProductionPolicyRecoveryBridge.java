package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recovery operations for policies whose assigned production station was destroyed or removed. */
final class ProductionPolicyRecoveryBridge {
    static final String COMMAND_RECOVER_HERE = "RECOVER_HERE";
    static final String COMMAND_DELETE_ORPHAN = "DELETE_ORPHAN";

    private static final String ORPHAN_MARKER = "Production orphans: ";

    record OrphanView(String id, String stationId, ProductionPolicySystem.PolicyType type,
                      ProductionJobKind kind, String itemId, double targetAmount) { }

    private ProductionPolicyRecoveryBridge() { }

    static List<OrphanView> orphanViews(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        Map<String,Object> snapshot = ProductionPolicySystem.capture(world);
        if (snapshot.containsKey("policies")) return authoritativeOrphans(world, playerId, snapshot);
        return compactOrphans(world, playerId);
    }

    static void refreshStatus(World world) {
        if (world == null) return;
        Map<String,Object> snapshot = ProductionPolicySystem.capture(world);
        Map<String,List<OrphanView>> byOwner = new LinkedHashMap<>();
        if (snapshot.containsKey("policies")) {
            Set<String> owners = new LinkedHashSet<>();
            for (Object item : ServerSaveStore.list(snapshot.get("policies"))) {
                String owner = ServerSaveStore.string(ServerSaveStore.object(item), "ownerId", "");
                if (!owner.isBlank()) owners.add(owner);
            }
            for (String owner : owners) {
                List<OrphanView> orphans = authoritativeOrphans(world, owner, snapshot);
                if (!orphans.isEmpty()) byOwner.put(owner, orphans);
            }
        }

        for (Base base : world.bases.values()) {
            String existing = removeSection(base.logisticsStatus, ORPHAN_MARKER);
            List<OrphanView> orphans = byOwner.getOrDefault(base.playerId, List.of());
            if (base.hp <= 0 || StationControls.nonProduction(base.typeId) || orphans.isEmpty()) {
                base.logisticsStatus = existing;
                continue;
            }
            StringBuilder encoded = new StringBuilder(ORPHAN_MARKER);
            for (int i = 0; i < orphans.size(); i++) {
                if (i > 0) encoded.append(';');
                OrphanView orphan = orphans.get(i);
                encoded.append(token(orphan.id())).append('~')
                        .append(token(orphan.stationId())).append('~')
                        .append(orphan.type().name()).append('~')
                        .append(orphan.kind().name()).append('~')
                        .append(token(orphan.itemId())).append('~')
                        .append(orphan.targetAmount());
            }
            base.logisticsStatus = existing.isBlank() ? encoded.toString() : existing + " | " + encoded;
        }
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
        refreshStatus(world);
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
        refreshStatus(world);
        world.status = "Deleted orphaned production policy " + policyId + ".";
        return true;
    }

    private static List<OrphanView> authoritativeOrphans(World world, String playerId, Map<String,Object> snapshot) {
        String systemId = world.activeSystemId();
        List<OrphanView> out = new ArrayList<>();
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
            String id = ServerSaveStore.string(row, "id", "");
            if (id.isBlank() || type == null || kind == null || itemId.isBlank()) continue;
            out.add(new OrphanView(id, stationId, type, kind, itemId,
                    ServerSaveStore.doubleValue(row, "targetAmount", 0)));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    private static List<OrphanView> compactOrphans(World world, String playerId) {
        Map<String,OrphanView> unique = new LinkedHashMap<>();
        for (Base base : world.bases.values()) {
            if (base.hp <= 0 || !playerId.equals(base.playerId) || StationControls.nonProduction(base.typeId)) continue;
            String section = section(base.logisticsStatus, ORPHAN_MARKER);
            if (section.isBlank()) continue;
            for (String row : section.split(";")) {
                String[] c = row.split("~", -1);
                if (c.length != 6) continue;
                try {
                    String id = untoken(c[0]);
                    String stationId = untoken(c[1]);
                    ProductionPolicySystem.PolicyType type = ProductionPolicySystem.PolicyType.valueOf(c[2]);
                    ProductionJobKind kind = ProductionJobKind.valueOf(c[3]);
                    String itemId = untoken(c[4]);
                    double target = Double.parseDouble(c[5]);
                    if (id.isBlank() || itemId.isBlank() || !Double.isFinite(target)) continue;
                    unique.putIfAbsent(id, new OrphanView(id, stationId, type, kind, itemId, target));
                } catch (RuntimeException ignored) { }
            }
        }
        List<OrphanView> out = new ArrayList<>(unique.values());
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
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

    private static String section(String status, String marker) {
        if (status == null || status.isBlank()) return "";
        int start = status.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = status.indexOf(" | ", start);
        return (end < 0 ? status.substring(start) : status.substring(start, end)).trim();
    }

    private static String removeSection(String status, String marker) {
        String value = status == null ? "" : status.trim();
        int start = value.indexOf(marker);
        if (start < 0) return value;
        int end = value.indexOf(" | ", start + marker.length());
        String left = value.substring(0, start).trim();
        String right = end < 0 ? "" : value.substring(end + 3).trim();
        while (left.endsWith("|") || left.endsWith(";")) left = left.substring(0, left.length() - 1).trim();
        if (left.isBlank()) return right;
        if (right.isBlank()) return left;
        return left + " | " + right;
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) return "-";
        StringBuilder out = new StringBuilder(Math.min(value.length(), 256));
        for (int i = 0; i < value.length() && out.length() < 256; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '~' || c == ';' || c == '|' || c == ',' || c == '^') out.append('_');
            else out.append(c);
        }
        return out.toString();
    }

    private static String untoken(String value) {
        return value == null || "-".equals(value) ? "" : value;
    }
}