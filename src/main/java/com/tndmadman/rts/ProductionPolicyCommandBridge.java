package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Command shim for edit operations that intentionally preserve server-owned reserve floors. */
final class ProductionPolicyCommandBridge {
    static final String COMMAND_UPDATE_KEEP_RESERVES = "UPDATE_KEEP_RESERVES";

    private ProductionPolicyCommandBridge() { }

    static boolean apply(World world, String playerId, String baseId, String command, String payload) {
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