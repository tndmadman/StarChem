package com.tndmadman.rts;

import java.util.Iterator;
import java.util.Map;

final class ResearchSystem {
    void update(World world, double dt) {
        if (dt <= 0) return;
        Iterator<Map.Entry<String, ResearchJob>> it = world.activeResearch.entrySet().iterator();
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
            world.notifyEvent("Research completed: " + topic.name + ".");
            if (PlayerRegistry.isLocal(job.playerId)) ProceduralAudio.play(SoundCue.CRAFT_ITEM);
        }
    }
}
