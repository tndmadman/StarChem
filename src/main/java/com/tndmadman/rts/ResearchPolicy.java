package com.tndmadman.rts;

import java.util.List;

/**
 * Single authorization point for research prerequisites, doctrine exclusions, and
 * data-driven unlock checks. Gameplay/network entry points should use this class
 * instead of open-coding World.hasResearch checks.
 */
final class ResearchPolicy {
    private ResearchPolicy() { }

    static String blockedResearchReason(World world, String playerId, ResearchTopic topic) {
        if (world == null || playerId == null || playerId.isBlank()) return "missing player";
        if (topic == null) return "unknown research";
        if (world.hasResearch(playerId, topic.id)) return "already researched";

        String excludedBy = doctrineExclusion(world, playerId, topic, false);
        if (!excludedBy.isBlank()) return "excluded by doctrine " + excludedBy;

        String missing = ResearchRules.missingPrerequisite(world, playerId, topic);
        if (!missing.isBlank()) return "requires " + missing;
        return "";
    }

    static String blockedResearchReason(World world, Base base, ResearchTopic topic) {
        if (world == null || base == null) return "missing player";
        if (topic == null) return "unknown research";
        if (!topic.canResearchAt(base.typeId)) return "requires a compatible research station";
        if (world.hasResearch(base.playerId, topic.id)) return "already researched";
        if (ProductionSystem.researchQueued(world, base.playerId, topic.id)) return "already queued";

        String excludedBy = doctrineExclusion(world, base.playerId, topic, true);
        if (!excludedBy.isBlank()) return "excluded by doctrine " + excludedBy;

        // The production queue deliberately permits a dependent topic after its
        // prerequisite is already queued at an owned station.
        String missing = ProductionSystem.missingResearchPrerequisite(world, base, topic);
        if (!missing.isBlank()) return "requires " + missing;
        return "";
    }

    static boolean canStartResearch(World world, String playerId, ResearchTopic topic) {
        return blockedResearchReason(world, playerId, topic).isBlank();
    }

    static boolean canStartResearch(World world, Base base, ResearchTopic topic) {
        return blockedResearchReason(world, base, topic).isBlank();
    }

    static String doctrineExclusion(World world, String playerId, ResearchTopic topic) {
        return doctrineExclusion(world, playerId, topic, true);
    }

    private static String doctrineExclusion(World world, String playerId, ResearchTopic topic, boolean includeQueued) {
        if (world == null || playerId == null || playerId.isBlank() || topic == null || topic.doctrineGroup.isBlank()) {
            return "";
        }
        for (ResearchTopic candidate : ResearchRules.all()) {
            if (candidate.id.equals(topic.id) || !candidate.doctrineGroup.equals(topic.doctrineGroup)) continue;
            if (world.hasResearch(playerId, candidate.id)
                    || includeQueued && ProductionSystem.researchQueued(world, playerId, candidate.id)) {
                return candidate.name;
            }
        }
        return "";
    }

    static boolean unlocked(World world, String playerId, ResearchUnlockKind kind, String targetId) {
        if (kind == null || targetId == null || targetId.isBlank()) return false;

        List<ResearchTopic> granting = ResearchRules.topicsUnlocking(kind, targetId);
        if (granting.isEmpty() && kind == ResearchUnlockKind.STATION_PACKAGE) {
            String legacyRequired = StationPackageResearchRules.requiredResearchId(targetId);
            if (legacyRequired.isBlank()) return true;
            return world != null && playerId != null && !playerId.isBlank()
                    && world.hasResearch(playerId, legacyRequired);
        }
        if (granting.isEmpty()) return true;
        if (world == null || playerId == null || playerId.isBlank()) return false;

        // Multiple topics may intentionally provide the same capability on different
        // branches. Completion of any granting topic unlocks the target.
        for (ResearchTopic topic : granting) {
            if (world.hasResearch(playerId, topic.id)) return true;
        }
        return false;
    }

    static String missingUnlockLabel(World world, String playerId, ResearchUnlockKind kind, String targetId) {
        if (unlocked(world, playerId, kind, targetId)) return "";
        List<ResearchTopic> granting = ResearchRules.topicsUnlocking(kind, targetId);
        if (granting.isEmpty() && kind == ResearchUnlockKind.STATION_PACKAGE) {
            return StationPackageResearchRules.requiredResearchName(targetId);
        }
        return granting.stream().map(topic -> topic.name).distinct().reduce((a, b) -> a + " or " + b).orElse("");
    }
}
