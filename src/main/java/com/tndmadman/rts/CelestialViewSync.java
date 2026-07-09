package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

final class CelestialViewSync {
    private CelestialViewSync() { }

    static void apply(World world, String systemId, double time) {
        if (world == null || time < 0) return;
        CelestialSystem target = activeCelestials(world);
        if (target == null) return;
        StarSystemDefinition definition = definitionFor(systemId);
        long seed = systemSeed(world.systemSeed(), systemId, definition.id());
        CelestialSystem expected = new CelestialSystem(definition, new Random(seed));
        expected.update(time);
        copyBodies(expected, target);
    }

    private static StarSystemDefinition definitionFor(String systemId) {
        if (systemId != null && systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_")) return StarSystems.get(StarSystems.PLAYER_HOME_SYSTEM_ID);
        return StarSystems.get(systemId);
    }

    private static long systemSeed(long seed, String systemId, String definitionId) {
        String id = systemId == null || systemId.isBlank() ? StarSystems.DEFAULT_SYSTEM_ID : systemId;
        return seed ^ ((long) id.hashCode() << 21) ^ definitionId.hashCode();
    }

    private static CelestialSystem activeCelestials(World world) {
        try {
            Field field = World.class.getDeclaredField("celestials");
            field.setAccessible(true);
            return (CelestialSystem) field.get(world);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void copyBodies(CelestialSystem source, CelestialSystem target) {
        try {
            Field bodiesField = CelestialSystem.class.getDeclaredField("bodies");
            bodiesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> sourceBodies = (List<Object>) bodiesField.get(source);
            @SuppressWarnings("unchecked")
            List<Object> targetBodies = (List<Object>) bodiesField.get(target);
            int count = Math.min(sourceBodies.size(), targetBodies.size());
            for (int i = 0; i < count; i++) copyBody(sourceBodies.get(i), targetBodies.get(i));
        } catch (ReflectiveOperationException ignored) { }
    }

    private static void copyBody(Object source, Object target) throws ReflectiveOperationException {
        Class<?> type = source.getClass();
        copyDouble(type, source, target, "x");
        copyDouble(type, source, target, "y");
        copyDouble(type, source, target, "angle");
    }

    private static void copyDouble(Class<?> type, Object source, Object target, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(target, field.getDouble(source));
    }
}
