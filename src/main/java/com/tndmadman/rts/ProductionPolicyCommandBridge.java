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

        // Ship REPEAT policies are especially dangerous from the compact manufacturing UI: REPEAT has
        // no target semantics in ProductionPolicySystem (repeatLimit == 0 means unbounded), while the
        // same panel also shows a Target spinner. Retire any legacy ship-repeat policy we encounter and
        // never admit a new one. Ship automation should use MAINTAIN_FLEET, which counts living + queued
        // hulls and stops at the requested target.
        disableLegacyShipRepeats(world, playerId);
        if (definesUnboundedShipRepeat(command, payload)) {
            world.status = "Ship repeat production is disabled. Use Maintain Fleet and set a target instead.";
            return false;
        }

        boolean changed;
        if (ProductionPolicyRecoveryBridge.COMMAND_RECOVER_HERE.equalsIgnoreCase(command)) {
            Base target = world.bases.get(baseId);
            changed = ProductionPolicyRecoveryBridge.reassign(world, playerId, payload, target);
        } else if (ProductionPolicyRecoveryBridge.COMMAND_DELETE_ORPHAN.equalsIgnoreCase(command)) {
            changed = ProductionPolicyRecoveryBridge.delete(world, playerId, payload);
        } else if (ProductionPolicyStarterTemplates.COMMAND_APPLY.equalsIgnoreCase(command)) {
            Base target = world.bases.get(baseId);
            changed = ProductionPolicyStarterTemplates.apply(world, playerId, target, payload);
        } else if (!COMMAND_UPDATE_KEEP_RESERVES.equalsIgnoreCase(command)) {
            changed = ProductionPolicySystem.applyCommand(world, playerId, baseId, command, payload);
        } else {
            String merged = mergeExistingReserves(world, playerId, baseId, payload);
            if (merged.isBlank()) return false;
            changed = ProductionPolicySystem.applyCommand(world, playerId, baseId,
                    ProductionPolicySystem.COMMAND_UPDATE, merged);
        }

        // Templates and recovery paths can restore older policy definitions. Apply the same safety
        // migration after those commands so an old saved ship-repeat policy cannot restart forever.
        disableLegacyShipRepeats(world, playerId);
        return changed;
    }

    private static boolean definesUnboundedShipRepeat(String command, String payload) {
        if (command == null || payload == null) return false;
        boolean definitionCommand = ProductionPolicySystem.COMMAND_CREATE.equalsIgnoreCase(command)
                || ProductionPolicySystem.COMMAND_UPDATE.equalsIgnoreCase(command)
                || COMMAND_UPDATE_KEEP_RESERVES.equalsIgnoreCase(command);
        if (!definitionCommand) return false;
        String[] fields = payload.split("~", -1);
        return fields.length == 13
                && "v1".equals(fields[0])
                && "REPEAT".equalsIgnoreCase(fields[2])
                && "SHIP".equalsIgnoreCase(fields[3]);
    }

    private static void disableLegacyShipRepeats(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return;
        Map<String,Object> snapshot = ProductionPolicySystem.capture(world);
        if (snapshot.isEmpty()) return;
        String systemId = world.activeSystemId();
        for (Object item : ServerSaveStore.list(snapshot.get("policies"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            if (!playerId.equals(ServerSaveStore.string(row, "ownerId", ""))) continue;
            if (!systemId.equals(ServerSaveStore.string(row, "systemId", ""))) continue;
            if (!"SHIP".equalsIgnoreCase(ServerSaveStore.string(row, "kind", ""))) continue;
            if (!"REPEAT".equalsIgnoreCase(ServerSaveStore.string(row, "type", ""))) continue;
            if (!ServerSaveStore.boolValue(row, "enabled", true)) continue;
            String stationId = ServerSaveStore.string(row, "stationId", "");
            String policyId = ServerSaveStore.string(row, "id", "");
            if (stationId.isBlank() || policyId.isBlank()) continue;
            ProductionPolicySystem.applyCommand(world, playerId, stationId,
                    ProductionPolicySystem.COMMAND_TOGGLE, policyId + "~0");
        }
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
