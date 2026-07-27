package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

enum ObjectiveStatus {
    DISABLED,
    ACTIVE,
    COMPLETED
}

record ObjectiveView(String id, String title, String description, ObjectiveStatus status,
                     int current, int target, String completedBy) {
    ObjectiveView {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        status = status == null ? ObjectiveStatus.DISABLED : status;
        current = Math.max(0, current);
        target = Math.max(0, target);
        completedBy = completedBy == null ? "" : completedBy;
    }

    static ObjectiveView disabled() {
        return new ObjectiveView("", "", "", ObjectiveStatus.DISABLED, 0, 0, "");
    }

    boolean enabled() { return status != ObjectiveStatus.DISABLED; }
    boolean completed() { return status == ObjectiveStatus.COMPLETED; }

    String progressLabel() {
        return completed() ? "COMPLETE" : current + " / " + target;
    }
}

final class ObjectiveSystem {
    static final String ADVANCED_INDUSTRY_OBJECTIVE_ID = "complete_advanced_industry";
    static final String ADVANCED_INDUSTRY_TOPIC_ID = "advanced_industry";

    private ObjectiveSystem() { }

    /**
     * Projects objective progress from authoritative world state. In multiplayer, completed research is
     * mutated by the server and distributed in validated snapshots; clients only render that synchronized
     * state and never advance objective progress independently.
     */
    static ObjectiveView view(World world) {
        if (world == null || SkirmishRuntime.settings(world).preset() == SkirmishPreset.SANDBOX) {
            return ObjectiveView.disabled();
        }

        ResearchTopic topic = ResearchRules.topic(ADVANCED_INDUSTRY_TOPIC_ID);
        String title = topic == null || topic.name == null || topic.name.isBlank()
                ? "Advanced Industry" : topic.name;
        String description = "Complete " + title + " research.";
        String completedBy = completedBy(world, ADVANCED_INDUSTRY_TOPIC_ID);
        boolean completed = !completedBy.isBlank();
        return new ObjectiveView(
                ADVANCED_INDUSTRY_OBJECTIVE_ID,
                title,
                description,
                completed ? ObjectiveStatus.COMPLETED : ObjectiveStatus.ACTIVE,
                completed ? 1 : 0,
                1,
                completedBy);
    }

    private static String completedBy(World world, String topicId) {
        List<String> playerIds = new ArrayList<>(world.completedResearch.keySet());
        Collections.sort(playerIds);
        for (String playerId : playerIds) {
            if (!humanPlayer(playerId)) continue;
            Set<String> completed = world.completedResearch.get(playerId);
            if (completed != null && completed.contains(topicId)) return PlayerRegistry.name(playerId);
        }
        return "";
    }

    private static boolean humanPlayer(String playerId) {
        return playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId)
                && !NpcRules.isNpcFaction(playerId);
    }
}
