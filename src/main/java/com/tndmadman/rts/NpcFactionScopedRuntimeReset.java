package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provides faction-scoped cleanup for legacy runtime maps whose state is private
 * to their subsystem. This prevents one organized faction reset from clearing
 * unrelated factions while preserving deterministic expedition settlement.
 */
final class NpcFactionScopedRuntimeReset {
    private NpcFactionScopedRuntimeReset() { }

    static void clearExpedition(World world, NpcFaction faction,
                                NpcFactionResetReason reason) {
        if (world == null || faction == null) return;
        NpcRepairEvacuationSystem.clearFaction(world, faction);
        removeExpeditionRuntime(world, faction, reason);
        NpcExpeditionReadinessSystem.clearFaction(world, faction);
    }

    /** Cancels only the unlaunched expedition plan, leaving recovery state intact. */
    static void cancelUnlaunchedExpedition(World world, NpcFaction faction) {
        if (world == null || faction == null) return;
        removeExpeditionRuntime(world, faction, NpcFactionResetReason.DEV_RESET);
        NpcExpeditionReadinessSystem.clearFaction(world, faction);
    }

    private static void removeExpeditionRuntime(World world, NpcFaction faction,
                                                NpcFactionResetReason reason) {
        try {
            Map<World, Map<String, Object>> runtimes = runtimeMap(
                    NpcExpeditionSystem.class, "RUNTIMES");
            Map<String, Object> byFaction = runtimes.get(world);
            if (byFaction == null) return;
            Object runtime = byFaction.get(faction.id());
            if (runtime != null) settleExpedition(world, faction, runtime, reason);
            byFaction.remove(faction.id());
            if (byFaction.isEmpty()) runtimes.remove(world);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Unable to clear faction expedition runtime", ex);
        }
    }

    static void clearSquads(World world, NpcFaction faction) {
        if (world == null || faction == null) return;
        try {
            Map<World, Map<String, Object>> runtimes = runtimeMap(
                    NpcSquadCombatSystem.class, "RUNTIMES");
            Map<String, Object> byKey = runtimes.get(world);
            if (byKey == null) return;
            String suffix = "|" + faction.id();
            byKey.keySet().removeIf(key -> key.endsWith(suffix));
            if (byKey.isEmpty()) runtimes.remove(world);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Unable to clear faction squad runtime", ex);
        }
    }

    private static void settleExpedition(World world, NpcFaction faction,
                                         Object runtime,
                                         NpcFactionResetReason reason)
            throws ReflectiveOperationException {
        Object plan = value(runtime, "plan");
        if (plan == null) return;

        boolean launched = booleanValue(plan, "launched");
        if (reason != null && reason.refundsUnlaunched() && !launched) {
            refundUnlaunched(world, faction, plan);
        }
        clearBuilderPackage(world, plan);
    }

    @SuppressWarnings("unchecked")
    private static void refundUnlaunched(World world, NpcFaction faction,
                                         Object plan)
            throws ReflectiveOperationException {
        String sourceSystemId = stringValue(plan, "sourceSystemId");
        String sourceBaseId = stringValue(plan, "sourceBaseId");
        Map<Material, Double> supplies =
                (Map<Material, Double>)value(plan, "supplies");
        Map<Material, Double> packageCost =
                (Map<Material, Double>)value(plan, "packageCost");

        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            if (sourceSystemId != null && !sourceSystemId.isBlank()) {
                world.activateSystem(sourceSystemId);
            }
            Base source = world.bases.get(sourceBaseId);
            if (source == null || source.hp <= 0
                    || !faction.id().equals(source.playerId)) {
                source = firstFactionBase(world, faction.id());
            }
            if (source == null) return;
            refund(source, supplies);
            refund(source, packageCost);
            world.saveActiveSystem();
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static void clearBuilderPackage(World world, Object plan)
            throws ReflectiveOperationException {
        String builderKey = stringValue(plan, "builderKey");
        if (builderKey == null || builderKey.isBlank()) return;
        List<String> route = new ArrayList<>();
        Object rawRoute = value(plan, "route");
        if (rawRoute instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) route.add(String.valueOf(entry));
            }
        }
        String sourceSystemId = stringValue(plan, "sourceSystemId");
        if (route.isEmpty() && sourceSystemId != null
                && !sourceSystemId.isBlank()) route.add(sourceSystemId);

        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            for (String systemId : route) {
                world.activateSystem(systemId);
                Unit builder = world.units.get(builderKey);
                if (builder == null || builder.hp <= 0) continue;
                builder.basePackageType = "";
                builder.clearOrder();
                builder.task = UnitTask.IDLE;
                builder.targetX = builder.x;
                builder.targetY = builder.y;
                world.saveActiveSystem();
                return;
            }
        } finally {
            if (previous != null && !previous.isBlank()) {
                world.activateSystem(previous);
            }
            world.status = previousStatus;
        }
    }

    private static Base firstFactionBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        return null;
    }

    private static void refund(Base base, Map<Material, Double> materials) {
        if (base == null || materials == null) return;
        for (Map.Entry<Material, Double> entry : materials.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null
                    && entry.getValue() > 0.001) {
                HangarStore.add(base.inventory,
                        entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<World, Map<String, Object>> runtimeMap(
            Class<?> owner, String fieldName)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<World, Map<String, Object>>)field.get(null);
    }

    private static Object value(Object owner, String fieldName)
            throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static boolean booleanValue(Object owner, String fieldName)
            throws ReflectiveOperationException {
        Object value = value(owner, fieldName);
        return value instanceof Boolean b && b;
    }

    private static String stringValue(Object owner, String fieldName)
            throws ReflectiveOperationException {
        Object value = value(owner, fieldName);
        return value == null ? "" : String.valueOf(value);
    }
}
