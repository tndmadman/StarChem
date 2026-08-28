package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CodexCatalog {
    private static final List<CodexEntry> ENTRIES = build();

    private CodexCatalog() { }

    static List<CodexEntry> entries() {
        return ENTRIES;
    }

    static List<CodexEntry> filter(CodexCategory category, String query) {
        CodexCategory selected = category == null ? CodexCategory.ALL : category;
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<CodexEntry> out = new ArrayList<>();
        for (CodexEntry entry : ENTRIES) {
            if (selected != CodexCategory.ALL && entry.category() != selected) continue;
            if (!needle.isBlank() && !entry.searchText().contains(needle)) continue;
            out.add(entry);
        }
        return List.copyOf(out);
    }

    private static List<CodexEntry> build() {
        List<CodexEntry> out = new ArrayList<>();
        addShips(out);
        addStations(out);
        addResources(out);
        addResearch(out);
        addCrafting(out);
        addNpcFactions(out);
        addEvents(out);
        addControls(out);
        return List.copyOf(out);
    }

    private static void addShips(List<CodexEntry> out) {
        for (ShipType ship : Rules.SHIPS.values()) {
            List<String> builtAt = new ArrayList<>();
            for (BaseType base : Rules.BASES.values()) if (base.buildableShips.contains(ship.id)) builtAt.add(base.name);
            List<String> unlockedBy = new ArrayList<>();
            for (ResearchTopic topic : ResearchRules.all()) if (topic.unlocks.ships.contains(ship.id)) unlockedBy.add(topic.name);

            StringBuilder body = new StringBuilder();
            line(body, "Role", shipRole(ship));
            line(body, "Size", title(ship.size.name()));
            line(body, "Hull / shields", number(ship.maxHp) + " / " + number(ship.maxShield));
            if (ship.maxShield > 0) line(body, "Shield regeneration", number(ship.shieldRegen) + "/sec after " + number(ship.shieldRegenDelay) + " sec");
            line(body, "Speed", number(ship.speed));
            line(body, "Cargo", number(ship.cargoCapacity));
            if (ship.harvestRange > 0) line(body, "Harvest range", number(ship.harvestRange));
            if (!ship.harvestKinds.isEmpty()) line(body, "Harvests", joinTitles(ship.harvestKinds));
            if (ship.scoutRange > 0) line(body, "Scout range", number(ship.scoutRange));
            if (ship.scoutDispatchLimit > 0) line(body, "Scout dispatch limit", Integer.toString(ship.scoutDispatchLimit));
            if (ship.tractorBeamCount > 0) line(body, "Tractor beams", ship.tractorBeamCount + " at range " + number(ship.tractorRange));
            if (ship.baseBuilder) line(body, "Special", "Carries and deploys station packages");
            line(body, "Built at", builtAt.isEmpty() ? "Not directly buildable" : String.join(", ", builtAt));
            line(body, "Research", unlockedBy.isEmpty() ? "No research gate" : String.join(", ", unlockedBy));
            line(body, "Build time", seconds(ship.buildTimeSeconds));
            line(body, "Build cost", Rules.formatCost(ship.buildCost));
            List<String> loadouts = new ArrayList<>();
            for (ShipLoadoutDefinition loadout : WeaponRules.loadoutsForHull(ship.id)) {
                List<String> weapons = new ArrayList<>();
                for (WeaponType weapon : WeaponRules.loadout(loadout)) weapons.add(weapon.name);
                String detail = loadout.displayName() + (loadout.defaultForHull() ? " [default]" : "")
                        + ": " + (weapons.isEmpty() ? "unarmed" : String.join(", ", weapons))
                        + " | utility " + ShipModuleRules.summary(ShipModuleRules.moduleIds(loadout));
                if (!loadout.buildCost().isEmpty()) detail += " | build premium " + Rules.formatCost(loadout.buildCost());
                if (!loadout.refitCost().isEmpty()) detail += " | refit " + Rules.formatCost(loadout.refitCost())
                        + " in " + seconds(loadout.refitTimeSeconds());
                loadouts.add(detail);
            }
            line(body, "Loadouts", loadouts.isEmpty() ? "None configured" : String.join("; ", loadouts));
            out.add(new CodexEntry(CodexCategory.SHIPS, ship.id, ship.name, shipRole(ship), body.toString()));
        }
    }

    private static void addStations(List<CodexEntry> out) {
        for (BaseType base : Rules.BASES.values()) {
            List<String> ships = new ArrayList<>();
            for (String id : base.buildableShips) ships.add(shipName(id));
            List<String> packages = new ArrayList<>();
            for (String id : base.basePackages) packages.add(baseName(id));
            List<String> research = new ArrayList<>();
            for (ResearchTopic topic : ResearchRules.forStation(base.id)) research.add(topic.name);
            List<String> crafting = new ArrayList<>();
            for (CraftableItem item : CraftingRules.forStation(base.id)) crafting.add(item.name);
            StationFuelRequirement fuel = StationFuelRules.requirement(base.id);

            StringBuilder body = new StringBuilder();
            line(body, "Hull / shields", number(base.maxHp) + " / " + number(base.maxShield));
            if (base.maxShield > 0) line(body, "Shield regeneration", number(base.shieldRegen) + "/sec after " + number(base.shieldRegenDelay) + " sec");
            line(body, "Unload range / rate", number(base.unloadRange) + " / " + number(base.unloadRate) + "/sec");
            line(body, "Build radius", number(base.buildRadius));
            line(body, "Builds ships", ships.isEmpty() ? "None" : String.join(", ", ships));
            line(body, "Builds station packages", packages.isEmpty() ? "None" : String.join(", ", packages));
            line(body, "Research topics", research.isEmpty() ? "None" : String.join(", ", research));
            line(body, "Manufactures", crafting.isEmpty() ? "None" : String.join(", ", crafting));
            line(body, "Fuel", fuel == null ? "No operating fuel" : number(fuel.perSecond()) + " " + fuel.material().label + "/sec while working");
            if (IntelWarfareSystem.isRadar(base.id)) {
                IntelWarfareSystem.StructureIntelRule radar = IntelWarfareSystem.rule(base.id);
                line(body, "Radar tier", Integer.toString(radar.tier()));
                line(body, "Sensor range", number(radar.sensorRange()));
                line(body, "Resource dispatch", radar.resourceDispatchLimit() + " miners / workers");
                line(body, "Combat response", radar.responseShipLimit() + " armed ships within " + number(radar.responseRadius()));
                line(body, "Combat policy", "Guarding ships respond first; then idle owned combat ships. Passive and Hold Fire opt out; Defensive responds to threats; Aggressive intercepts valid contacts.");
            }
            line(body, "Build time", seconds(base.buildTimeSeconds));
            line(body, "Build cost", Rules.formatCost(base.buildCost));
            out.add(new CodexEntry(CodexCategory.STATIONS, base.id, base.name, stationRole(base, research, crafting), body.toString()));
        }
    }

    private static void addResources(List<CodexEntry> out) {
        for (Material material : Material.values()) {
            ResourceSystemCatalog.Entry availability = ResourceSystemCatalog.entry(material);
            List<String> systems = new ArrayList<>();
            for (ResourceSystemCatalog.SystemAvailability system : availability.systems()) {
                systems.add(system.systemName() + " [" + title(system.role()) + "]");
            }
            List<String> producedBy = new ArrayList<>();
            List<String> usedBy = new ArrayList<>();
            for (CraftableItem item : CraftingRules.all()) {
                if (item.outputMaterial == material) producedBy.add(item.name);
                if (contains(item.requiredResources, material)) usedBy.add(item.name + " recipe");
            }
            for (ShipType ship : Rules.SHIPS.values()) if (contains(ship.buildCost, material)) usedBy.add(ship.name + " ship");
            for (BaseType base : Rules.BASES.values()) if (contains(base.buildCost, material)) usedBy.add(base.name + " station");
            for (ResearchTopic topic : ResearchRules.all()) if (contains(topic.requiredResources, material)) usedBy.add(topic.name + " research");

            StringBuilder body = new StringBuilder();
            line(body, "Family", title(material.family.name()));
            line(body, "Rarity", title(material.tier.name()));
            line(body, "Source", availability.sourceLabel());
            line(body, "Natural systems", systems.isEmpty() ? "None" : String.join(", ", systems));
            line(body, "Produced by", producedBy.isEmpty() ? "Not manufactured" : String.join(", ", producedBy));
            line(body, "Used by", usedBy.isEmpty() ? "No loaded recipe, build, or research cost" : String.join(", ", usedBy));
            out.add(new CodexEntry(CodexCategory.RESOURCES, material.name(), material.label,
                    title(material.family.name()) + " - " + availability.sourceLabel(), body.toString()));
        }
    }

    private static void addResearch(List<CodexEntry> out) {
        for (ResearchTopic topic : ResearchRules.all()) {
            List<String> stations = new ArrayList<>();
            for (String id : topic.stationTypes) stations.add(baseName(id));
            List<String> prerequisites = new ArrayList<>();
            for (String id : topic.requires) prerequisites.add(researchName(id));
            List<String> unlocks = new ArrayList<>();
            for (String id : topic.unlocks.ships) unlocks.add(shipName(id));
            List<String> requiredBy = new ArrayList<>();
            for (ResearchTopic candidate : ResearchRules.all()) if (candidate.requires.contains(topic.id)) requiredBy.add(candidate.name);

            StringBuilder body = new StringBuilder();
            if (!topic.description.isBlank()) paragraph(body, topic.description);
            line(body, "Researched at", stations.isEmpty() ? "No loaded station" : String.join(", ", stations));
            line(body, "Prerequisites", prerequisites.isEmpty() ? "None" : String.join(", ", prerequisites));
            line(body, "Required by", requiredBy.isEmpty() ? "No later loaded topic" : String.join(", ", requiredBy));
            line(body, "Unlocks", unlocks.isEmpty() ? "No ships" : String.join(", ", unlocks));
            line(body, "Time", seconds(topic.timeSeconds));
            line(body, "Cost", Rules.formatCost(topic.requiredResources));
            out.add(new CodexEntry(CodexCategory.RESEARCH, topic.id, topic.name,
                    prerequisites.isEmpty() ? "Foundation research" : "Requires " + String.join(", ", prerequisites), body.toString()));
        }
    }

    private static void addCrafting(List<CodexEntry> out) {
        for (CraftableItem item : CraftingRules.all()) {
            List<String> stations = new ArrayList<>();
            for (String id : item.stationTypes) stations.add(baseName(id));
            List<String> research = new ArrayList<>();
            for (String id : item.requiresResearch) research.add(researchName(id));

            StringBuilder body = new StringBuilder();
            if (!item.description.isBlank()) paragraph(body, item.description);
            line(body, "Category", item.category.label);
            line(body, "Manufactured at", stations.isEmpty() ? "No loaded station" : String.join(", ", stations));
            line(body, "Research", research.isEmpty() ? "No research gate" : String.join(", ", research));
            line(body, "Inputs", Rules.formatCost(item.requiredResources));
            line(body, "Output", item.outputLabel());
            line(body, "Time", seconds(item.timeSeconds));
            out.add(new CodexEntry(CodexCategory.CRAFTING, item.id, item.name, item.category.label, body.toString()));
        }
    }

    private static void addNpcFactions(List<CodexEntry> out) {
        for (NpcFaction faction : NpcRules.factions()) {
            List<String> units = uniqueNames(faction.startingUnits(), faction.workerUnitTypes(), faction.fleetUnitTypes(),
                    faction.supportUnitTypes(), faction.industryUnitTypes());
            List<String> stations = new ArrayList<>();
            stations.add(baseName(faction.baseType()));
            for (String id : faction.stationPackageTypes()) stations.add(baseName(id));
            List<String> research = new ArrayList<>();
            for (String id : faction.researchTopicIds()) research.add(researchName(id));

            StringBuilder body = new StringBuilder();
            paragraph(body, faction.spawnMessage());
            line(body, "Behavior", title(faction.behavior().name()));
            line(body, "First spawn / respawn", seconds(faction.firstSpawnSeconds()) + " / " + seconds(faction.respawnSeconds()));
            line(body, "Units", units.isEmpty() ? "None configured" : String.join(", ", units));
            line(body, "Stations", String.join(", ", new LinkedHashSet<>(stations)));
            line(body, "Research plan", research.isEmpty() ? "None" : String.join(", ", research));
            line(body, "Fleet targets", "fleet " + faction.targetFleetSize() + ", raid " + faction.raidFleetSize() + ", harass " + faction.harassFleetSize());
            line(body, "Combat rules", combatRules(faction));
            line(body, "Raid cooldown", seconds(faction.raidCooldownSeconds()));
            out.add(new CodexEntry(CodexCategory.NPC_FACTIONS, faction.id(), faction.name(),
                    title(faction.behavior().name()) + " faction", body.toString()));
        }
    }

    private static void addEvents(List<CodexEntry> out) {
        out.add(new CodexEntry(CodexCategory.EVENTS, "dynamic_events", "Dynamic Galaxy Events",
                "Temporary server-authoritative opportunities and hazards",
                "Events appear over time according to the server event policy. Hidden events are revealed only by their configured sensor or proximity discovery rule. Discovered events show a marker and countdown, and temporary state is removed when the event completes, fails, or expires.\n\nServer operators can disable events, change event frequency, or restrict event categories when creating a session."));
        out.add(new CodexEntry(CodexCategory.EVENTS, "rich_resource", "Rich Deposits",
                "Temporary high-yield resource fields",
                "Rich deposits create event-owned resource nodes for a limited time. Mining the field can complete the event. Undiscovered deposits do not appear on maps or network event projections."));
        out.add(new CodexEntry(CodexCategory.EVENTS, "derelict_salvage", "Derelict Convoys",
                "Temporary salvage caches",
                "Derelict events create event-owned salvage items. Recovering all event salvage completes the encounter. Event cleanup never removes unrelated world items."));
        out.add(new CodexEntry(CodexCategory.EVENTS, "distress", "Distress Beacons",
                "Timed civilian defense encounters",
                "A discovered distress beacon can materialize a protected civilian and hostile attackers. Saving the civilian by defeating the attackers completes the event; losing the civilian fails it. Completion rewards are generated deterministically and only once."));
        out.add(new CodexEntry(CodexCategory.EVENTS, "pirate_ambush", "Pirate Ambushes",
                "Concealed hostile encounters",
                "Pirate ambushes use proximity discovery and spawn event-owned raiders. Their ships use normal StarChem combat while remaining isolated from ordinary NPC faction population and economy accounting."));
        out.add(new CodexEntry(CodexCategory.EVENTS, "environmental", "Environmental Anomalies",
                "Temporary system-wide modifiers",
                "Ion storms and similar anomalies temporarily modify sensors, shields, movement, weapons, resource behavior, or environmental damage according to their data-driven definition. Their effects end with the event."));
        out.add(new CodexEntry(CodexCategory.EVENTS, "unstable_wormhole", "Unstable Wormholes",
                "Temporary discovered galaxy shortcuts",
                "An unstable wormhole is visible and routable only to players allowed to know it. When collapse begins, new entrants are rejected while ships already committed to the gate are given a bounded drain window. The event never modifies permanent galaxy topology."));
    }

    private static void addControls(List<CodexEntry> out) {
        out.add(control("camera", "Camera and maps", "Navigate the battlefield",
                "WASD or Arrow Keys: pan camera\nMouse Wheel: zoom\nM: galaxy map\nClick tactical minimap: pan camera\nI: resource catalog\nF1: codex"));
        out.add(control("selection", "Selection and movement", "Control friendly fleets",
                "Left-click: select ship, station, or resource\nDrag left mouse: box-select ships\nDouble-click ship: select same visible type\nRight-click empty space: move selected ships\nRight-click resource: auto-harvest with compatible ships\nRight-click enemy: attack"));
        out.add(control("orders", "Fleet orders", "Assign persistent tactical behavior",
                "F: cycle formation\nX: attack-move\nP: patrol\nG: guard\nE: escort\nH: hold position\nEscape: cancel command mode or close an overlay"));
        out.add(control("combat-policy", "Combat policy and radar response", "Choose how selected ships fight automatically",
                "Selected ships show STANCE and TARGET controls below the main HUD.\nLeft-click cycles forward; Shift+Left-click cycles backward.\nPassive: no automatic attacks or radar response.\nDefensive: react to immediate threats and radar-reported attacks on friendly assets.\nAggressive: automatically acquire and accept radar intercepts inside bounded leashes.\nHold Fire: suppress offensive fire and automatic radar response; point defense remains active.\nTarget policy ranks legal targets for local acquisition and radar dispatch.\nShips Guarding an owned radar respond before idle owned combat ships; shared allied intel never grants control of allied ships."));
        out.add(control("audio", "Audio and accessibility", "Sound and narration controls",
                "Ctrl+M: mute or enable audio\nF8: narration settings\nEscape: close the codex"));
    }

    private static CodexEntry control(String id, String title, String summary, String body) {
        return new CodexEntry(CodexCategory.CONTROLS, id, title, summary, body);
    }

    private static String shipRole(ShipType ship) {
        List<String> roles = new ArrayList<>();
        if (!ship.harvestKinds.isEmpty()) roles.add("resource harvesting");
        if (ship.cargoCapacity >= 300) roles.add("cargo logistics");
        if (ship.scoutRange > 0) roles.add("scouting");
        if (ship.tractorBeamCount > 0) roles.add("salvage recovery");
        if (ship.baseBuilder) roles.add("station deployment");
        if (WeaponRules.armed(ship)) roles.add("combat");
        if (roles.isEmpty()) roles.add("fleet support");
        return String.join(" / ", roles);
    }

    private static String stationRole(BaseType base, List<String> research, List<String> crafting) {
        List<String> roles = new ArrayList<>();
        if (IntelWarfareSystem.isRadar(base.id)) roles.add("sensor and fleet coordination");
        if (IntelWarfareSystem.isJammer(base.id)) roles.add("electronic warfare");
        if (IntelWarfareSystem.isDecoy(base.id)) roles.add("strategic deception");
        if (!base.buildableShips.isEmpty()) roles.add("ship production");
        if (!base.basePackages.isEmpty()) roles.add("station deployment");
        if (!research.isEmpty()) roles.add("research");
        if (!crafting.isEmpty()) roles.add("manufacturing");
        if (roles.isEmpty()) roles.add("storage and logistics");
        return String.join(" / ", roles);
    }

    @SafeVarargs
    private static List<String> uniqueNames(List<String>... groups) {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> group : groups) for (String id : group) names.add(shipName(id));
        return List.copyOf(names);
    }

    private static String combatRules(NpcFaction faction) {
        List<String> rules = new ArrayList<>();
        if (faction.attackUnits()) rules.add("attacks ships");
        if (faction.attackBases()) rules.add("attacks stations");
        if (faction.attackNpcFactions()) rules.add("attacks other NPC factions");
        if (faction.preferWorkerTargets()) rules.add("prefers workers");
        if (rules.isEmpty()) return "Non-hostile";
        return String.join(", ", rules);
    }

    private static boolean contains(List<Cost> costs, Material material) {
        for (Cost cost : costs) if (cost.material() == material) return true;
        return false;
    }

    private static String shipName(String id) {
        ShipType ship = Rules.findShip(id);
        return ship == null ? id : ship.name;
    }

    private static String baseName(String id) {
        BaseType base = Rules.findBase(id);
        return base == null ? id : base.name;
    }

    private static String researchName(String id) {
        ResearchTopic topic = ResearchRules.topic(id);
        return topic == null ? id : topic.name;
    }

    private static String joinTitles(Iterable<?> values) {
        List<String> labels = new ArrayList<>();
        for (Object value : values) labels.add(title(String.valueOf(value)));
        return String.join(", ", labels);
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "";
        String[] words = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String seconds(double value) {
        return number(value) + " sec";
    }

    private static String number(double value) {
        return String.valueOf(Calc.round(value));
    }

    private static void line(StringBuilder body, String label, String value) {
        if (!body.isEmpty()) body.append('\n');
        body.append(label).append(": ").append(value == null || value.isBlank() ? "None" : value);
    }

    private static void paragraph(StringBuilder body, String value) {
        if (value == null || value.isBlank()) return;
        if (!body.isEmpty()) body.append("\n\n");
        body.append(value.trim());
    }
}

enum CodexCategory {
    ALL("All"),
    SHIPS("Ships"),
    STATIONS("Stations"),
    RESOURCES("Resources"),
    RESEARCH("Research"),
    CRAFTING("Crafting"),
    NPC_FACTIONS("NPC Factions"),
    EVENTS("Events"),
    CONTROLS("Controls");

    final String label;

    CodexCategory(String label) {
        this.label = label;
    }

    @Override public String toString() {
        return label;
    }
}

record CodexEntry(CodexCategory category, String id, String title, String summary, String body) {
    CodexEntry {
        category = category == null ? CodexCategory.CONTROLS : category;
        id = id == null ? "" : id;
        title = title == null || title.isBlank() ? id : title;
        summary = summary == null ? "" : summary;
        body = body == null ? "" : body;
    }

    String searchText() {
        return (category.label + " " + id + " " + title + " " + summary + " " + body).toLowerCase(Locale.ROOT);
    }

    String displayText() {
        StringBuilder out = new StringBuilder();
        out.append(title).append('\n');
        if (!summary.isBlank()) out.append(summary).append("\n\n");
        out.append(body);
        return out.toString();
    }

    @Override public String toString() {
        return title;
    }
}
