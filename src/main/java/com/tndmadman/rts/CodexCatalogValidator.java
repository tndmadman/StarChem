package com.tndmadman.rts;

import java.util.List;

public final class CodexCatalogValidator {
    private CodexCatalogValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem codex validation passed.");
    }

    static void validateOrThrow() {
        List<CodexEntry> entries = CodexCatalog.entries();
        require(!entries.isEmpty(), "codex contains no entries");

        for (ShipType ship : Rules.SHIPS.values()) {
            CodexEntry entry = find(entries, CodexCategory.SHIPS, ship.id);
            require(entry != null, "codex missing ship: " + ship.id);
            require(entry.body().contains("Build cost:"), "ship entry missing build cost: " + ship.id);
        }

        for (BaseType base : Rules.BASES.values()) {
            CodexEntry entry = find(entries, CodexCategory.STATIONS, base.id);
            require(entry != null, "codex missing station: " + base.id);
            require(entry.body().contains("Builds ships:"), "station entry missing production data: " + base.id);
        }

        for (Material material : Material.values()) {
            CodexEntry entry = find(entries, CodexCategory.RESOURCES, material.name());
            require(entry != null, "codex missing material: " + material.name());
            require(entry.body().contains("Source:"), "material entry missing source: " + material.name());
        }

        for (ResearchTopic topic : ResearchRules.all()) {
            CodexEntry entry = find(entries, CodexCategory.RESEARCH, topic.id);
            require(entry != null, "codex missing research: " + topic.id);
            require(entry.body().contains("Prerequisites:"), "research entry missing prerequisites: " + topic.id);
            require(entry.body().contains("Unlocks:"), "research entry missing unlocks: " + topic.id);
        }

        for (CraftableItem item : CraftingRules.all()) {
            require(find(entries, CodexCategory.CRAFTING, item.id) != null, "codex missing craftable: " + item.id);
        }

        for (NpcFaction faction : NpcRules.factions()) {
            require(find(entries, CodexCategory.NPC_FACTIONS, faction.id()) != null, "codex missing NPC faction: " + faction.id());
        }

        require(find(entries, CodexCategory.EVENTS, "dynamic_events") != null, "codex missing dynamic event overview");
        require(find(entries, CodexCategory.EVENTS, "unstable_wormhole") != null, "codex missing unstable wormhole event rules");
        require(CodexCatalog.filter(CodexCategory.EVENTS, "discovery").size() >= 1,
                "event codex entries are not searchable by discovery rules");

        require(!CodexCatalog.filter(CodexCategory.SHIPS, "cargo").isEmpty(), "codex role search returned no cargo ships");
        require(CodexCatalog.filter(CodexCategory.RESEARCH, "prerequisites").size() == ResearchRules.all().size(),
                "research search did not cover prerequisite text");
        require(!CodexCatalog.filter(CodexCategory.ALL, "galaxy map").isEmpty(), "codex controls are not searchable");
        require(CodexCatalog.filter(CodexCategory.CONTROLS, "").size() >= 4, "codex control guide is incomplete");
    }

    private static CodexEntry find(List<CodexEntry> entries, CodexCategory category, String id) {
        for (CodexEntry entry : entries) {
            if (entry.category() == category && entry.id().equals(id)) return entry;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
