package com.tndmadman.rts;

import java.util.*;

final class ResearchSystem {
    private static final Map<World, Map<String, ResearchJob>> JOBS = new IdentityHashMap<>();

    private ResearchSystem() { }

    static void start(World world, Base base, ResearchTopic topic) {
        jobs(world).put(key(base.playerId, topic.id), new ResearchJob(base.playerId, base.id, topic.id, topic.timeSeconds));
        AlertCenter.push(world, "Research started: " + topic.name + ".");
    }

    static boolean active(World world, String playerId, String topicId) {
        return jobs(world).containsKey(key(playerId, topicId));
    }

    static ResearchJob job(World world, String playerId, String topicId) {
        return jobs(world).get(key(playerId, topicId));
    }

    static String timeLabel(ResearchJob job) {
        if (job == null) return "";
        return Math.max(1, (int)Math.ceil(job.remaining)) + "s left";
    }

    static void update(World world, double dt) {
        if (dt <= 0) return;
        Map<String, ResearchJob> jobs = JOBS.get(world);
        if (jobs == null) return;
        Iterator<Map.Entry<String, ResearchJob>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            ResearchJob job = it.next().getValue();
            Base base = world.bases.get(job.baseId);
            ResearchTopic topic = ResearchRules.topic(job.topicId);
            if (base == null || topic == null) {
                it.remove();
                continue;
            }
            if (!StationFuelRules.isOperational(base)) continue;
            job.remaining -= dt;
            if (job.remaining > 0) continue;
            it.remove();
            world.completeResearch(job.playerId, job.topicId);
            AlertCenter.push(world, "Research completed: " + topic.name + ".");
            if (PlayerRegistry.isLocal(job.playerId)) ProceduralAudio.play(SoundCue.CRAFT_ITEM);
        }
        if (jobs.isEmpty()) JOBS.remove(world);
    }

    private static Map<String, ResearchJob> jobs(World world) {
        return JOBS.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
    }

    private static String key(String playerId, String topicId) { return playerId + "|" + topicId; }
}
