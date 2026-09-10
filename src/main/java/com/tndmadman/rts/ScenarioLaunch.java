package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Startup-only scenario selection kept separate from the generic Config surface. */
final class ScenarioLaunch {
    private static volatile String selectedId = "";

    private ScenarioLaunch() { }

    static String[] configure(String[] suppliedArgs) {
        selectedId = "";
        String[] args = suppliedArgs == null ? new String[0] : suppliedArgs;
        List<String> out = new ArrayList<>(args.length);
        boolean join = false;
        boolean server = false;
        boolean newWorld = false;
        String requested = "";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--scenario".equals(arg)) {
                if (!requested.isBlank()) throw new IllegalArgumentException("--scenario may only be specified once.");
                if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].isBlank()
                        || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--scenario requires a scenario ID.");
                }
                requested = ScenarioRules.require(args[++i]).id();
                continue;
            }
            if ("--join".equals(arg)) join = true;
            if ("--server".equals(arg)) server = true;
            if ("--new-world".equals(arg)) newWorld = true;
            out.add(arg);
        }
        if (!requested.isBlank() && join) {
            throw new IllegalArgumentException("--scenario selects a server/solo world and cannot be used with --join.");
        }
        if (!requested.isBlank() && server && !newWorld) {
            throw new IllegalArgumentException("Dedicated-server --scenario requires --new-world so an existing save is never overwritten by scenario starting state.");
        }
        selectedId = requested;
        return out.toArray(String[]::new);
    }

    static boolean selected() { return !selectedId.isBlank(); }
    static String selectedId() { return selectedId; }
    static void consume() { selectedId = ""; }

    static ScenarioDefinition definition() {
        return selected() ? ScenarioRules.require(selectedId) : null;
    }

    static int galaxyCopies(int fallback) {
        ScenarioDefinition definition = definition();
        if (definition == null) return fallback;
        Object raw = definition.galaxySettings().get("copiesPerTemplate");
        if (raw == null) return fallback;
        int copies;
        if (raw instanceof Number number) copies = number.intValue();
        else {
            try { copies = Integer.parseInt(String.valueOf(raw).trim()); }
            catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Scenario " + definition.id()
                        + " galaxy copiesPerTemplate must be 1 or 2.");
            }
        }
        if (copies < 1 || copies > 2) {
            throw new IllegalArgumentException("Scenario " + definition.id()
                    + " galaxy copiesPerTemplate must be 1 or 2.");
        }
        return copies;
    }

    static SkirmishSettings adjustSkirmish(SkirmishSettings fallback) {
        SkirmishSettings base = fallback == null ? SkirmishSettings.standard() : fallback;
        ScenarioDefinition definition = definition();
        if (definition == null) return base;

        Set<String> enabled = new LinkedHashSet<>();
        for (String raw : definition.enabledNpcFactions()) enabled.add(canonicalFactionId(raw, definition.id()));
        Set<String> disabled = new LinkedHashSet<>(Set.of(
                Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID));
        disabled.removeAll(enabled);
        return new SkirmishSettings(base.preset(), base.difficulty(), disabled,
                base.victoryConditionId(), base.diplomacy(), base.diplomacyState());
    }

    static String ids() {
        StringBuilder out = new StringBuilder();
        for (ScenarioDefinition definition : ScenarioRules.all()) {
            if (!out.isEmpty()) out.append('|');
            out.append(definition.id());
        }
        return out.toString();
    }

    static String canonicalFactionId(String raw, String scenarioId) {
        String value = raw == null ? "" : raw.trim();
        String token = value.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (token) {
            case "raider", "raiders", "npc_raiders" -> Config.RAIDERS_ID;
            case "miner", "miners", "free_miner", "free_miners", "npc_miners" -> Config.FREE_MINERS_ID;
            case "corsair", "corsairs", "npc_corsairs" -> Config.CORSAIRS_ID;
            default -> throw new IllegalArgumentException("Scenario " + scenarioId
                    + " references unknown NPC faction " + value + ".");
        };
    }
}
