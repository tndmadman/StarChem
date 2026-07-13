package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class ProductionQueueScheduler {
    private static final String TEMPORARY_ACTIVE = "temporary active";
    private static final String TEMPORARY_BLOCKED = TEMPORARY_ACTIVE + ": ";

    private ProductionQueueScheduler() { }

    static void update(World world, double dt) {
        if (world == null || dt < 0) return;
        List<Promotion> promotions = new ArrayList<>();
        for (Base base : world.bases.values()) {
            clearTemporaryMarkers(base);
            Promotion promotion = promoteRunnableJob(base);
            if (promotion != null) promotions.add(promotion);
        }

        ProductionSystem.update(world, dt);

        for (Promotion promotion : promotions) restore(promotion);
    }

    static ProductionJob active(Base base) {
        if (base == null || base.productionQueue.isEmpty()) return null;
        ProductionJob head = base.productionQueue.get(0);
        if (!ProductionSystem.waitingForResources(head)) return head;
        for (int i = 1; i < base.productionQueue.size(); i++) {
            ProductionJob candidate = base.productionQueue.get(i);
            if (temporaryActive(candidate)) return candidate;
        }
        return head;
    }

    static String detail(Base base, ProductionJob job) {
        if (base == null || job == null) return "";
        if (temporaryActive(job)) {
            String reason = temporaryReason(job);
            if (!reason.isBlank()) return "temporarily selected | " + reason;
            return "temporarily running | " + Math.max(0, (int)Math.ceil(job.remaining)) + "s left";
        }
        return ProductionSystem.detail(base, job);
    }

    private static void clearTemporaryMarkers(Base base) {
        if (base == null) return;
        for (ProductionJob job : base.productionQueue) {
            if (temporaryActive(job)) job.blockedReason = "";
        }
    }

    private static Promotion promoteRunnableJob(Base base) {
        if (base == null || base.productionQueue.size() < 2) return null;
        ProductionJob head = base.productionQueue.get(0);
        if (!ProductionSystem.waitingForResources(head)) return null;
        for (int i = 1; i < base.productionQueue.size(); i++) {
            ProductionJob candidate = base.productionQueue.get(i);
            if (ProductionSystem.waitingForResources(candidate) || !candidate.resourcesReserved) continue;
            base.productionQueue.remove(i);
            base.productionQueue.add(0, candidate);
            return new Promotion(base, candidate, i);
        }
        return null;
    }

    private static void restore(Promotion promotion) {
        Base base = promotion.base;
        ProductionJob job = promotion.job;
        int current = base.productionQueue.indexOf(job);
        if (current < 0) return;
        String blocked = job.blockedReason == null ? "" : job.blockedReason.trim();
        base.productionQueue.remove(current);
        int target = Math.max(1, Math.min(promotion.originalIndex, base.productionQueue.size()));
        base.productionQueue.add(target, job);
        job.blockedReason = blocked.isBlank() ? TEMPORARY_ACTIVE : TEMPORARY_BLOCKED + blocked;
    }

    private static boolean temporaryActive(ProductionJob job) {
        if (job == null || job.blockedReason == null) return false;
        return job.blockedReason.equals(TEMPORARY_ACTIVE) || job.blockedReason.startsWith(TEMPORARY_BLOCKED);
    }

    private static String temporaryReason(ProductionJob job) {
        if (!temporaryActive(job) || !job.blockedReason.startsWith(TEMPORARY_BLOCKED)) return "";
        return job.blockedReason.substring(TEMPORARY_BLOCKED.length()).trim();
    }

    private record Promotion(Base base, ProductionJob job, int originalIndex) { }
}
