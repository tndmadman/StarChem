package com.tndmadman.rts;

final class NetResourceSync {
    private static final double POSITION_CORRECTION_THRESHOLD = 12.0;

    private NetResourceSync() { }

    static void apply(World world, Iterable<ResourceState> states) {
        int seen = 0, missingCount = 0, corrected = 0, synced = 0;
        for (ResourceState s : states) {
            seen++;
            ResourceNode node = world.findResource(s.id());
            boolean missing = node == null;
            boolean wasActive = node != null && node.active;
            String before = ResourceNetDebug.nodeShort(node);
            double drift = missing ? 0 : Calc.distance(node.x, node.y, s.x(), s.y());
            if (missing) {
                missingCount++;
                node = new ResourceNode(s.id(), s.name(), NodeKind.valueOf(s.kind()), Material.valueOf(s.material()), s.x(), s.y(), s.maxAmount(), s.harvestRate(), s.radius());
                world.resources.add(node);
            }
            boolean reactivated = !wasActive && s.active();
            boolean drifted = drift > POSITION_CORRECTION_THRESHOLD;
            boolean correctPosition = missing || reactivated || drifted;
            if (correctPosition || !s.active()) {
                corrected++;
                ResourceOrbitSync.apply(world, node, s);
                ResourceNetDebug.netResourceCorrection(world, reason(missing, reactivated, drifted, s.active()), s, before, node, drift);
            } else {
                synced++;
                ResourceOrbitSync.applyAmounts(node, s);
            }
        }
        ResourceNetDebug.netResourceSummary(world, seen, missingCount, corrected, synced);
    }

    private static String reason(boolean missing, boolean reactivated, boolean drifted, boolean active) {
        if (missing) return "missing";
        if (!active) return "inactive";
        if (reactivated) return "reactivated";
        if (drifted) return "drift";
        return "forced";
    }
}
