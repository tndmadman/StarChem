package com.tndmadman.rts;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Regression coverage for Production Manager discovery and research eligibility parity. */
public final class ProductionCatalogRulesValidator {
    private ProductionCatalogRulesValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem production catalog validation passed.");
    }

    static void validateOrThrow() {
        validateGlobalProductionDiscovery();
        validateResearchPolicyParity();
    }

    private static void validateGlobalProductionDiscovery() {
        Set<String> expectedHulls = new LinkedHashSet<>();
        Set<String> expectedPackages = new LinkedHashSet<>();
        for (BaseType station : Rules.BASES.values()) {
            expectedHulls.addAll(station.buildableShips);
            expectedPackages.addAll(station.basePackages);
        }
        require(ProductionCatalogRules.buildableHullIds().equals(expectedHulls),
                "catalog hull discovery is not sourced from all registered station definitions");
        require(ProductionCatalogRules.buildableStationPackageIds().equals(expectedPackages),
                "catalog station-package discovery is not sourced from all registered station definitions");
    }

    private static void validateResearchPolicyParity() {
        PlayerRegistry.reset("SOLO", "Catalog Validator", 0x50BEFF);
        World world = new World("Catalog Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "CATALOG_TEST";
        Base lab = new Base(playerId + ":B1", playerId, "laboratory", 100, 100);
        world.bases.put(lab.id, lab);

        ResearchTopic industrial = requiredTopic("industrial_specialization");
        ResearchTopic military = requiredTopic("military_mobilization");
        ResearchTopic automation = requiredTopic("industrial_automation");

        Set<String> completed = world.completedResearch.computeIfAbsent(playerId, ignored -> new HashSet<>());
        completed.addAll(industrial.requires);
        completed.addAll(military.requires);

        lab.productionQueue.add(new ProductionJob("P1", ProductionJobKind.RESEARCH, industrial.id,
                industrial.timeSeconds, industrial.timeSeconds, false, ""));
        String queueBlock = ResearchPolicy.blockedResearchReason(world, lab, military);
        String displayBlock = ProductionCatalogRules.researchLockedReason(world, playerId, military);
        require(!queueBlock.isBlank(), "queued doctrine sibling did not block authoritative research validation");
        require(displayBlock.equals(sentenceCase(queueBlock)),
                "Production Manager research state diverged from authoritative doctrine validation");

        lab.productionQueue.clear();
        completed.remove(automation.id);
        completed.add("extraction_specialization");
        lab.productionQueue.add(new ProductionJob("P2", ProductionJobKind.RESEARCH, automation.id,
                automation.timeSeconds, automation.timeSeconds, false, ""));
        String dependentQueueBlock = ResearchPolicy.blockedResearchReason(world, lab, industrial);
        String dependentDisplayBlock = ProductionCatalogRules.researchLockedReason(world, playerId, industrial);
        require(dependentQueueBlock.isBlank(),
                "authoritative research policy rejected a prerequisite already queued at the same station");
        require(dependentDisplayBlock.isBlank(),
                "Production Manager did not honor the queue-aware prerequisite rule");
    }

    private static ResearchTopic requiredTopic(String id) {
        ResearchTopic topic = ResearchRules.topic(id);
        if (topic == null) throw new IllegalStateException("Production catalog validation missing research topic: " + id);
        return topic;
    }

    private static String sentenceCase(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() == 1) return value.toUpperCase();
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Production catalog validation failed: " + message);
    }
}
