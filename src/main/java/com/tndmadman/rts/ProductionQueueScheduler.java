package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ProductionQueueScheduler {
    private static final String TEMPORARY_ACTIVE = "temporary active";
    private static final String TEMPORARY_BLOCKED = TEMPORARY_ACTIVE + ": ";
    private static final long DIAGNOSTIC_CACHE_NANOS = 2_000_000_000L;
    private static final Map<World, Map<String, CachedDetail>> DIAGNOSTIC_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ProductionQueueScheduler() { }

    static void update(World world, double dt) {
        if (world == null || dt < 0) return;
        ProductionPolicySystem.update(world, dt);
        ProductionPolicyRecoveryBridge.refreshStatus(world);
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
        String ordinary = ProductionSystem.detail(base, job);
        String blocked = job.blockedReason == null ? "" : job.blockedReason.trim();
        if (blocked.isBlank() || !PlayerRegistry.isLocal(base.playerId)) return ordinary;
        World world = PlayerRegistry.activeWorld();
        if (world == null) return ordinary;
        String causal = cachedCausalDetail(world, base, job, blocked);
        return causal.isBlank() ? ordinary : causal;
    }

    private static String cachedCausalDetail(World world, Base base, ProductionJob job, String blockedReason) {
        long now = System.nanoTime();
        String key = world.activeSystemId() + '|' + base.id + '|' + job.id + '|' + blockedReason;
        Map<String, CachedDetail> worldCache = DIAGNOSTIC_CACHE.computeIfAbsent(world,
                ignored -> new LinkedHashMap<>());
        CachedDetail cached = worldCache.get(key);
        if (cached != null && now - cached.createdNanos < DIAGNOSTIC_CACHE_NANOS) return cached.text;

        ProductionCausalAnalyzer.Analysis analysis = ProductionCausalAnalyzer.analyze(world, base, job);
        String text = ordinaryCause(analysis, blockedReason);
        worldCache.clear();
        worldCache.put(key, new CachedDetail(now, text));
        return text;
    }

    private static String ordinaryCause(ProductionCausalAnalyzer.Analysis analysis, String fallback) {
        if (analysis == null || analysis.causes().isEmpty()) return fallback;
        ProductionCausalAnalyzer.Cause first = analysis.causes().get(0);
        if (first == null || first.summary().isBlank()) return fallback;
        String text = first.summary();
        if (!first.children().isEmpty()) {
            ProductionCausalAnalyzer.Cause child = first.children().get(0);
            if (child != null && !child.summary().isBlank()) text += " | " + child.summary();
        }
        return text;
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
    private record CachedDetail(long createdNanos, String text) { }
}
