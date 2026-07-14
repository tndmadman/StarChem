package com.tndmadman.rts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class ItemPickupSystem {
    private static final double EPS = 0.05;
    private static final double TRACTOR_PULL_PER_TICK = 7.5;
    private static final long TRACTOR_SOUND_COOLDOWN_NANOS = 430_000_000L;

    private final Map<String, Long> lastTractorSoundNanos = new HashMap<>();

    void update(World world) {
        Set<WorldItem> assigned = new HashSet<>();
        for (Unit unit : world.units.values()) runTractors(world, unit, assigned);
        Iterator<WorldItem> it = world.items.iterator();
        while (it.hasNext()) if (it.next().empty()) it.remove();
        lastTractorSoundNanos.keySet().removeIf(key -> !world.units.containsKey(key));
    }

    private void runTractors(World world, Unit unit, Set<WorldItem> assigned) {
        ShipType type = unit.type();
        if (type.tractorBeamCount <= 0 || type.tractorRange <= 0 || unit.freeCargo() <= EPS) return;
        boolean tractorActive = false;
        for (int beam = 0; beam < type.tractorBeamCount && unit.freeCargo() > EPS; beam++) {
            WorldItem item = nearestItem(world, unit, assigned);
            if (item == null) break;
            assigned.add(item);
            tractor(item, unit);
            tractorActive = true;
            if (Calc.distance(unit.x, unit.y, item.x, item.y) <= item.pickupRange(unit)) transfer(world, item, unit);
        }
        if (tractorActive) playTractorPulse(world, unit);
    }

    private WorldItem nearestItem(World world, Unit unit, Set<WorldItem> assigned) {
        ShipType type = unit.type();
        WorldItem best = null;
        double bestDist = Double.MAX_VALUE;
        for (WorldItem item : world.items) {
            if (item.empty() || assigned.contains(item)) continue;
            double dist = Calc.distance(unit.x, unit.y, item.x, item.y);
            if (dist > type.tractorRange || dist >= bestDist) continue;
            best = item;
            bestDist = dist;
        }
        return best;
    }

    private void tractor(WorldItem item, Unit unit) {
        double dx = unit.x - item.x;
        double dy = unit.y - item.y;
        double dist = Math.hypot(dx, dy);
        if (dist <= EPS) return;
        double step = Math.min(dist, TRACTOR_PULL_PER_TICK);
        item.x += dx / dist * step;
        item.y += dy / dist * step;
        item.vx = dx / dist * TRACTOR_PULL_PER_TICK;
        item.vy = dy / dist * TRACTOR_PULL_PER_TICK;
    }

    private void transfer(World world, WorldItem item, Unit unit) {
        double take = item.take(unit.freeCargo());
        if (take <= EPS) return;
        unit.addCargo(item.material, take);
        if (PlayerRegistry.isLocal(unit.playerId)) unit.unloadingThisFrame = true;
        SystemAudio.playForPlayerInSystem(world, unit.playerId, SoundCue.ITEM_PICKUP);
    }

    private void playTractorPulse(World world, Unit unit) {
        long now = System.nanoTime();
        String key = unit.key();
        long last = lastTractorSoundNanos.getOrDefault(key, 0L);
        if (now - last < TRACTOR_SOUND_COOLDOWN_NANOS) return;
        lastTractorSoundNanos.put(key, now);
        SystemAudio.playForPlayerInSystem(world, unit.playerId, SoundCue.TRACTOR_BEAM);
    }
}
