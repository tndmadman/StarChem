package com.tndmadman.rts;

import java.lang.reflect.Field;

final class ResourceViewSync {
    private ResourceViewSync() { }

    static void apply(World world, Iterable<ResourceState> states) {
        CelestialSystem celestials = activeCelestials(world);
        double cx = celestials == null ? world.width / 2.0 : celestials.sunX();
        double cy = celestials == null ? world.height / 2.0 : celestials.sunY();
        for (ResourceState s : states) {
            ResourceNode node = world.findResource(s.id());
            if (node == null) {
                node = new ResourceNode(s.id(), s.name(), NodeKind.valueOf(s.kind()), Material.valueOf(s.material()), s.x(), s.y(), s.maxAmount(), s.harvestRate(), s.radius());
                world.resources.add(node);
            }
            node.x = s.x();
            node.y = s.y();
            node.amount = s.amount();
            node.active = s.active();
            node.respawnTimer = s.respawnTimer();
            reanchor(node, cx, cy);
        }
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

    private static void reanchor(ResourceNode node, double cx, double cy) {
        double r = Math.hypot(node.x - cx, node.y - cy);
        double a = Math.atan2(node.y - cy, node.x - cx);
        double speed = node.orbitSpeed == 0 ? 0.01 : node.orbitSpeed;
        node.orbit(cx, cy, r, a, speed);
    }
}
