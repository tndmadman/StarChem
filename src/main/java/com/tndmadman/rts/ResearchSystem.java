package com.tndmadman.rts;

final class ResearchSystem {
    private ResearchSystem() { }

    static void start(World world, Base base, ResearchTopic topic) {
        ProductionSystem.enqueuePrepaidResearch(world, base, topic);
    }

    static boolean active(World world, String playerId, String topicId) {
        return ProductionSystem.researchQueued(world, playerId, topicId);
    }

    static ResearchJob job(World world, String playerId, String topicId) {
        if (world == null) return null;
        for (Base base : world.bases.values()) {
            if (!base.playerId.equals(playerId)) continue;
            for (ProductionJob production : base.productionQueue) {
                if (production.kind != ProductionJobKind.RESEARCH || !production.itemId.equals(topicId)) continue;
                ResearchJob job = new ResearchJob(playerId, base.id, topicId, production.duration);
                job.remaining = production.remaining;
                return job;
            }
        }
        return null;
    }

    static String timeLabel(ResearchJob job) {
        if (job == null) return "";
        return Math.max(0, (int)Math.ceil(job.remaining)) + "s left";
    }

    static void update(World world, double dt) {
        ProductionSystem.update(world, DevTimerSettings.disabled(world) ? Double.MAX_VALUE : dt);
    }
}
