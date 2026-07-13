package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class ProductionQueueScheduler {
    private static final Map<Base, ProductionJob> LAST_ACTIVE = new IdentityHashMap<>();

    private ProductionQueueScheduler() { }

    static void update(World world, double dt) {
        if (world == null || dt < 0) return;
        List<Promotion> promotions = new ArrayList<>();
        for (Base base : world.bases.values()) {
            Promotion promotion = promoteRunnableJob(base);
            if (promotion != null) promotions.add(promotion);
        }

        ProductionSystem.update(world, dt);

        for (Promotion promotion : promotions) restore(promotion);
        LAST_ACTIVE.entrySet().removeIf(entry -> !world.bases.containsValue(entry.getKey())
                || !entry.getKey().productionQueue.contains(entry.getValue()));
    }

    static ProductionJob active(Base base) {
        if (base == null || base.productionQueue.isEmpty()) return null;
        ProductionJob head = base.productionQueue.get(0);
        if (!ProductionSystem.waitingForResources(head)) return head;
        ProductionJob skippedTo = LAST_ACTIVE.get(base);
        if (skippedTo != null && base.productionQueue.contains(skippedTo)
                && !ProductionSystem.waitingForResources(skippedTo)) return skippedTo;
        return head;
    }

    static String detail(Base base, ProductionJob job) {
        if (base == null || job == null) return "";
        int position = base.productionQueue.indexOf(job);
        if (position > 0 && job == LAST_ACTIVE.get(base) && !ProductionSystem.waitingForResources(job)) {
            if (job.blockedReason != null && !job.blockedReason.isBlank()) {
                return "temporarily selected | " + job.blockedReason;
            }
            return "temporarily running | " + Math.max(0, (int)Math.ceil(job.remaining)) + "s left";
        }
        return ProductionSystem.detail(base, job);
    }

    private static Promotion promoteRunnableJob(Base base) {
        if (base == null || base.productionQueue.size() < 2) {
            if (base != null) LAST_ACTIVE.remove(base);
            return null;
        }
        ProductionJob head = base.productionQueue.get(0);
        if (!ProductionSystem.waitingForResources(head)) {
            LAST_ACTIVE.remove(base);
            return null;
        }
        for (int i = 1; i < base.productionQueue.size(); i++) {
            ProductionJob candidate = base.productionQueue.get(i);
            if (ProductionSystem.waitingForResources(candidate) || !candidate.resourcesReserved) continue;
            base.productionQueue.remove(i);
            base.productionQueue.add(0, candidate);
            LAST_ACTIVE.put(base, candidate);
            return new Promotion(base, candidate, i);
        }
        LAST_ACTIVE.remove(base);
        return null;
    }

    private static void restore(Promotion promotion) {
        Base base = promotion.base;
        ProductionJob job = promotion.job;
        int current = base.productionQueue.indexOf(job);
        if (current < 0) {
            LAST_ACTIVE.remove(base);
            return;
        }
        base.productionQueue.remove(current);
        int target = Math.max(1, Math.min(promotion.originalIndex, base.productionQueue.size()));
        base.productionQueue.add(target, job);
    }

    private record Promotion(Base base, ProductionJob job, int originalIndex) { }
}
