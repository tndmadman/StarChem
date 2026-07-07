package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.List;

final class CelestialSnapshotSync {
    private CelestialSnapshotSync() { }

    static String write(World world) {
        CelestialSystem system = activeCelestials(world);
        if (system == null) return "";
        StringBuilder out = new StringBuilder();
        for (Object body : bodies(system)) {
            if (!out.isEmpty()) out.append(';');
            out.append(Calc.round(get(body, "x"))).append(',')
                    .append(Calc.round(get(body, "y"))).append(',')
                    .append(Calc.round(get(body, "angle")));
        }
        return out.toString();
    }

    static void apply(World world, String data) {
        if (data == null || data.isBlank()) return;
        CelestialSystem system = activeCelestials(world);
        if (system == null) return;
        List<Object> bodies = bodies(system);
        String[] rows = data.split(";", -1);
        int count = Math.min(bodies.size(), rows.length);
        for (int i = 0; i < count; i++) {
            String[] c = rows[i].split(",", -1);
            if (c.length < 3) continue;
            Object body = bodies.get(i);
            set(body, "x", parse(c[0]));
            set(body, "y", parse(c[1]));
            set(body, "angle", parse(c[2]));
        }
    }

    private static CelestialSystem activeCelestials(World world) {
        try {
            Field field = World.class.getDeclaredField("celestials");
            field.setAccessible(true);
            return (CelestialSystem) field.get(world);
        } catch (ReflectiveOperationException ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bodies(CelestialSystem system) {
        try {
            Field field = CelestialSystem.class.getDeclaredField("bodies");
            field.setAccessible(true);
            return (List<Object>) field.get(system);
        } catch (ReflectiveOperationException ignored) { return List.of(); }
    }

    private static double get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getDouble(target);
        } catch (ReflectiveOperationException ignored) { return 0; }
    }

    private static void set(Object target, String name, double value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(target, value);
        } catch (ReflectiveOperationException ignored) { }
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
