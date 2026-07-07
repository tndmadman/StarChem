package com.tndmadman.rts;

final class ResourceViewSync {
    private ResourceViewSync() { }

    static void apply(World world, Iterable<ResourceState> states) {
        for (ResourceState s : states) {
            ResourceNode node = world.findResource(s.id());
            if (node == null) continue;
            node.x = s.x();
            node.y = s.y();
            node.amount = s.amount();
            node.active = s.active();
            node.respawnTimer = s.respawnTimer();
            reanchor(world, node);
        }
    }

    private static void reanchor(World world, ResourceNode node) {
        double cx = world.width / 2.0;
        double cy = world.height / 2.0;
        double r = Math.hypot(node.x - cx, node.y - cy);
        double a = Math.atan2(node.y - cy, node.x - cx);
        double speed = node.orbitSpeed == 0 ? 0.01 : node.orbitSpeed;
        node.orbit(cx, cy, r, a, speed);
    }
}
