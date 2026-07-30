package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

record SkirmishSettings(SkirmishPreset preset, NpcDifficulty difficulty,
                        Set<String> disabledNpcFactionIds, String victoryConditionId,
                        DiplomacyMatchSettings diplomacy) {
    SkirmishSettings {
        preset = preset == null ? SkirmishPreset.STANDARD : preset;
        difficulty = difficulty == null ? NpcDifficulty.NORMAL : difficulty;
        disabledNpcFactionIds = sanitizeDisabled(disabledNpcFactionIds);
        victoryConditionId = VictoryConditionRules.normalizeId(victoryConditionId);
        diplomacy = diplomacy == null ? DiplomacyMatchSettings.ffa() : diplomacy;
    }

    SkirmishSettings(SkirmishPreset preset, NpcDifficulty difficulty,
                     Set<String> disabledNpcFactionIds, String victoryConditionId) {
        this(preset, difficulty, disabledNpcFactionIds, victoryConditionId, DiplomacyMatchSettings.ffa());
    }

    SkirmishSettings(SkirmishPreset preset, NpcDifficulty difficulty,
                     Set<String> disabledNpcFactionIds) {
        this(preset, difficulty, disabledNpcFactionIds, VictoryConditionRules.defaultId(),
                DiplomacyMatchSettings.ffa());
    }

    static SkirmishSettings standard() {
        return new SkirmishSettings(SkirmishPreset.STANDARD, NpcDifficulty.NORMAL,
                SkirmishPreset.STANDARD.defaultDisabledFactionIds(), VictoryConditionRules.defaultId(),
                DiplomacyMatchSettings.ffa());
    }

    static SkirmishSettings standard(Set<String> disabledNpcFactionIds) {
        return new SkirmishSettings(SkirmishPreset.STANDARD, NpcDifficulty.NORMAL,
                disabledNpcFactionIds, VictoryConditionRules.defaultId(), DiplomacyMatchSettings.ffa());
    }

    static SkirmishSettings create(SkirmishPreset preset, NpcDifficulty difficulty) {
        return create(preset, difficulty, VictoryConditionRules.defaultId());
    }

    static SkirmishSettings create(SkirmishPreset preset, NpcDifficulty difficulty,
                                   String victoryConditionId) {
        SkirmishPreset normalized = preset == null ? SkirmishPreset.STANDARD : preset;
        return new SkirmishSettings(normalized, difficulty,
                normalized.defaultDisabledFactionIds(), victoryConditionId, DiplomacyMatchSettings.ffa());
    }

    SkirmishSettings withDiplomacy(DiplomacyMatchSettings settings) {
        return new SkirmishSettings(preset, difficulty, disabledNpcFactionIds, victoryConditionId, settings);
    }

    String presetId() { return preset.id(); }
    String difficultyId() { return difficulty.id(); }
    VictoryConditionDefinition victoryCondition() { return VictoryConditionRules.require(victoryConditionId); }

    String displayLabel() {
        String custom = disabledNpcFactionIds.equals(preset.defaultDisabledFactionIds())
                ? "" : " / Custom factions";
        return preset.label() + " / " + difficulty.label() + " / "
                + victoryCondition().displayName() + " / " + diplomacy.displayLabel() + custom;
    }

    String statusLabel() {
        return "preset " + preset.label() + " | difficulty " + difficulty.label()
                + " | victory " + victoryCondition().displayName()
                + " | diplomacy " + diplomacy.displayLabel();
    }

    Map<String,Object> saveMap() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("presetId", preset.id());
        out.put("difficultyId", difficulty.id());
        out.put("disabledNpcFactionIds", new ArrayList<>(disabledNpcFactionIds));
        out.put("victoryConditionId", victoryConditionId);
        out.put("diplomacy", diplomacy.saveMap());
        return out;
    }

    static SkirmishSettings fromSaved(Object value, SkirmishSettings fallback) {
        SkirmishSettings safeFallback = fallback == null ? standard() : fallback;
        if (!(value instanceof Map<?,?> map)) return safeFallback;
        Object savedPreset = map.containsKey("presetId") ? map.get("presetId") : safeFallback.presetId();
        Object savedDifficulty = map.containsKey("difficultyId") ? map.get("difficultyId") : safeFallback.difficultyId();
        Object savedVictory = map.containsKey("victoryConditionId")
                ? map.get("victoryConditionId") : safeFallback.victoryConditionId();
        SkirmishPreset preset = SkirmishPreset.parse(String.valueOf(savedPreset));
        NpcDifficulty difficulty = NpcDifficulty.parse(String.valueOf(savedDifficulty));
        Set<String> disabled = new LinkedHashSet<>();
        Object rawDisabled = map.get("disabledNpcFactionIds");
        if (rawDisabled instanceof List<?> list) {
            for (Object item : list) disabled.add(String.valueOf(item));
        } else {
            disabled.addAll(preset.defaultDisabledFactionIds());
        }
        DiplomacyMatchSettings diplomacy = DiplomacyMatchSettings.fromSaved(
                map.get("diplomacy"), safeFallback.diplomacy());
        return new SkirmishSettings(preset, difficulty, disabled, String.valueOf(savedVictory), diplomacy);
    }

    String packet() {
        return "WORLDINFO|" + preset.id() + "|" + difficulty.id() + "|"
                + String.join(",", disabledNpcFactionIds) + "|" + victoryConditionId
                + "|" + diplomacy.packetField();
    }

    static SkirmishSettings fromPacket(String message) {
        if (message == null || !message.startsWith("WORLDINFO|")) {
            throw new IllegalArgumentException("Invalid world-info packet.");
        }
        String[] parts = message.split("\\|", -1);
        if (parts.length < 4 || parts.length > 6) {
            throw new IllegalArgumentException("Malformed world-info packet.");
        }
        Set<String> disabled = new LinkedHashSet<>();
        if (!parts[3].isBlank()) {
            for (String id : parts[3].split(",")) disabled.add(id);
        }
        String victoryConditionId = parts.length >= 5 && !parts[4].isBlank()
                ? parts[4] : VictoryConditionRules.defaultId();
        DiplomacyMatchSettings diplomacy = parts.length == 6
                ? DiplomacyMatchSettings.fromPacketField(parts[5]) : DiplomacyMatchSettings.ffa();
        return new SkirmishSettings(SkirmishPreset.parse(parts[1]),
                NpcDifficulty.parse(parts[2]), disabled, victoryConditionId, diplomacy);
    }

    List<NpcFaction> resolve(List<NpcFaction> baseFactions) {
        if (baseFactions == null || baseFactions.isEmpty()) return List.of();
        List<NpcFaction> out = new ArrayList<>(baseFactions.size());
        for (NpcFaction faction : baseFactions) out.add(resolve(faction));
        return List.copyOf(out);
    }

    private NpcFaction resolve(NpcFaction faction) {
        double timing = clamp(preset.timingMultiplier() * difficulty.timingMultiplier(), 0.35, 2.5);
        double force = clamp(preset.forceMultiplier() * difficulty.forceMultiplier(), 0.5, 2.0);
        double orderTiming = clamp(Math.sqrt(timing), 0.7, 1.4);
        return new NpcFaction(
                faction.id(), faction.name(), faction.rgb(),
                faction.enabled() && !disabledNpcFactionIds.contains(faction.id()), faction.behavior(),
                seconds(faction.firstSpawnSeconds(), timing, 1.0),
                seconds(faction.respawnSeconds(), timing, 2.0),
                seconds(faction.orderSeconds(), orderTiming, 0.5),
                faction.baseType(), scaleUnits(faction.startingUnits(), force),
                faction.workerUnitTypes(), faction.fleetUnitTypes(), faction.supportUnitTypes(),
                faction.stationPackageTypes(), faction.industryUnitTypes(), faction.researchTopicIds(),
                faction.craftableItemIds(), scaleLimit(faction.maxWorkers(), force),
                scaleLimit(faction.targetFleetSize(), force), scaleLimit(faction.raidFleetSize(), force),
                scaleLimit(faction.harassFleetSize(), force), scaleLimit(faction.maxSupportUnits(), force),
                scaleLimit(faction.maxStations(), Math.sqrt(force)),
                scaleLimit(faction.maxIndustryUnits(), force),
                seconds(faction.buildSeconds(), timing, 1.0),
                seconds(faction.stationBuildSeconds(), timing, 1.0),
                faction.defendRange(), seconds(faction.raidCooldownSeconds(), timing, 1.0),
                faction.retreatHpPercent(), faction.stationSpacing(), faction.fuelReserve(),
                faction.spawnDistance(), faction.spawnPadding(), faction.unitSpacing(),
                faction.targetMaterials(), faction.harvestNodeKinds(), faction.attackBases(),
                faction.attackUnits(), faction.attackNpcFactions(), faction.replaceWorkers(),
                faction.harassWorkers(), faction.preferWorkerTargets(), faction.requirePlayerCombatShips(),
                faction.minPlayerCombatShips(), faction.spawnMessage());
    }

    private static List<String> scaleUnits(List<String> units, double multiplier) {
        if (units == null || units.isEmpty()) return List.of();
        int target = Math.max(1, Math.min(32, (int)Math.round(units.size() * multiplier)));
        List<String> out = new ArrayList<>(target);
        for (int i = 0; i < target; i++) out.add(units.get(i % units.size()));
        return List.copyOf(out);
    }

    private static int scaleLimit(int value, double multiplier) {
        if (value <= 0) return 0;
        return Math.max(1, Math.min(128, (int)Math.round(value * multiplier)));
    }

    private static double seconds(double value, double multiplier, double minimum) {
        if (!Double.isFinite(value) || value <= 0) return Math.max(minimum, 0.25);
        return clamp(value * multiplier, minimum, 86_400.0);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Set<String> sanitizeDisabled(Set<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null && values.contains(Config.RAIDERS_ID)) out.add(Config.RAIDERS_ID);
        if (values != null && values.contains(Config.FREE_MINERS_ID)) out.add(Config.FREE_MINERS_ID);
        if (values != null && values.contains(Config.CORSAIRS_ID)) out.add(Config.CORSAIRS_ID);
        return Collections.unmodifiableSet(out);
    }
}

enum SkirmishPreset {
    PEACEFUL("peaceful", "Peaceful Economy", 1.35, 0.80,
            Set.of(Config.RAIDERS_ID, Config.CORSAIRS_ID)),
    STANDARD("standard", "Standard Skirmish", 1.0, 1.0, Set.of()),
    HOSTILE("hostile", "Hostile Sector", 0.72, 1.25, Set.of()),
    SANDBOX("sandbox", "Sandbox", 1.0, 1.0,
            Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID));

    private final String id;
    private final String label;
    private final double timingMultiplier;
    private final double forceMultiplier;
    private final Set<String> defaultDisabledFactionIds;

    SkirmishPreset(String id, String label, double timingMultiplier, double forceMultiplier,
                   Set<String> defaultDisabledFactionIds) {
        this.id = id;
        this.label = label;
        this.timingMultiplier = timingMultiplier;
        this.forceMultiplier = forceMultiplier;
        this.defaultDisabledFactionIds = new SkirmishSettings(this, NpcDifficulty.NORMAL,
                defaultDisabledFactionIds, VictoryConditionRules.defaultId()).disabledNpcFactionIds();
    }

    String id() { return id; }
    String label() { return label; }
    double timingMultiplier() { return timingMultiplier; }
    double forceMultiplier() { return forceMultiplier; }
    Set<String> defaultDisabledFactionIds() { return defaultDisabledFactionIds; }

    static SkirmishPreset parse(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (SkirmishPreset preset : values()) {
            if (preset.id.equals(clean) || preset.name().equalsIgnoreCase(clean)) return preset;
        }
        throw new IllegalArgumentException("Unknown skirmish preset: " + value
                + ". Expected peaceful, standard, hostile, or sandbox.");
    }

    @Override public String toString() { return label; }
}

enum NpcDifficulty {
    RELAXED("relaxed", "Relaxed", 1.35, 0.80),
    NORMAL("normal", "Normal", 1.0, 1.0),
    HARD("hard", "Hard", 0.75, 1.25),
    BRUTAL("brutal", "Brutal", 0.55, 1.55);

    private final String id;
    private final String label;
    private final double timingMultiplier;
    private final double forceMultiplier;

    NpcDifficulty(String id, String label, double timingMultiplier, double forceMultiplier) {
        this.id = id;
        this.label = label;
        this.timingMultiplier = timingMultiplier;
        this.forceMultiplier = forceMultiplier;
    }

    String id() { return id; }
    String label() { return label; }
    double timingMultiplier() { return timingMultiplier; }
    double forceMultiplier() { return forceMultiplier; }

    static NpcDifficulty parse(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (NpcDifficulty difficulty : values()) {
            if (difficulty.id.equals(clean) || difficulty.name().equalsIgnoreCase(clean)) return difficulty;
        }
        throw new IllegalArgumentException("Unknown NPC difficulty: " + value
                + ". Expected relaxed, normal, hard, or brutal.");
    }

    @Override public String toString() { return label; }
}
