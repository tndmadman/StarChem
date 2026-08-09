package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Command shim for policy edits, starter templates, and destroyed-station recovery operations. */
final class ProductionPolicyCommandBridge {
    static final String COMMAND_UPDATE_KEEP_RESERVES = "UPDATE_KEEP_RESERVES";

    private ProductionPolicyCommandBridge() { }

    static boolean apply(World world, String playerId, String baseId, String command, String payload) {
        if (world == null || playerId == null || baseId == null || command == null) return false;
        if (ProductionPolicyRecoveryBridge.COMMAND_RECOVER_HERE.equalsIgnoreCase(command)) {
            Base target = world.bases.get(baseId);
            return ProductionPolicyRecoveryBridge.reassign(world, playerId, payload, target);
        }
        if (ProductionPolicyRecoveryBridge.COMMAND_DELETE_ORPHAN.equalsIgnoreCase(command)) {
            return ProductionPolicyRecoveryBridge.delete(world, playerId, payload);
        }
        if (ProductionPolicyStarterTemplates.COMMAND_APPLY.equalsIgnoreCase(command)) {
            Base target = world.bases.get(baseId);
            return ProductionPolicyStarterTemplates.apply(world, playerId, target, payload);
        }
        if (!COMMAND_UPDATE_KEEP_RESERVES.equalsIgnoreCase(command)) {
            return ProductionPolicySystem.applyCommand(world, playerId, baseId, command, payload);
        }
        String merged = mergeExistingReserves(world, playerId, baseId, payload);
        if (merged.isBlank()) return false;
        return ProductionPolicySystem.applyCommand(world, playerId, baseId,
                ProductionPolicySystem.COMMAND_UPDATE, merged);
    }

    private static String mergeExistingReserves(World world, String playerId, String baseId, String payload) {
        if (world == null || payload == null || payload.length() > ProductionPolicySystem.MAX_COMMAND_CHARS) return "";
        String[] fields = payload.split("~", -1);
        if (fields.length != 13 || !"v1".equals(fields[0])) return "";
        String policyId = fields[1].trim();
        if (policyId.isBlank() || "-".equals(policyId)) return "";

        Map<String,Object> policies = ServerSaveStore.object(
                ProductionPolicySystem.capture(world));
        Map<String,Object> found = Map.of();
        for (Object item : ServerSaveStore.list(policies.get("policies"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            if (!policyId.equals(ServerSaveStore.string(row, "id", ""))) continue;
            if (!playerId.equals(ServerSaveStore.string(row, "ownerId", ""))) return "";
            if (!baseId.equals(ServerSaveStore.string(row, "stationId", ""))) return "";
            if (!world.activeSystemId().equals(ServerSaveStore.string(row, "systemId", ""))) return "";
            found = row;
            break;
        }
        if (found.isEmpty()) return "";

        fields[11] = reserveText(ServerSaveStore.restoreMaterialMap(found.get("stationReserve")));
        fields[12] = reserveText(ServerSaveStore.restoreMaterialMap(found.get("networkReserve")));
        return String.join("~", fields);
    }

    private static String reserveText(Map<Material,Double> reserves) {
        if (reserves == null || reserves.isEmpty()) return "-";
        List<String> out = new ArrayList<>();
        for (Material material : Material.values()) {
            double amount = reserves.getOrDefault(material, 0.0);
            if (!Double.isFinite(amount) || amount <= 0.05) continue;
            out.add(material.name() + ':' + String.format(Locale.ROOT, "%.6f", amount)
                    .replaceAll("0+$", "").replaceAll("\\.$", ""));
        }
        return out.isEmpty() ? "-" : String.join(",", out);
    }
}