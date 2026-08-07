package com.tndmadman.rts;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ControlGroupManager {
    static final int GROUP_COUNT = 10;
    static final long DOUBLE_TAP_NANOS = 350_000_000L;

    private final Group[] groups = new Group[GROUP_COUNT];
    private int activeGroup = -1;
    private int lastTapGroup = -1;
    private long lastTapNanos;

    ControlGroupManager() {
        for (int i = 0; i < groups.length; i++) groups[i] = new Group();
    }

    void assign(int number, Collection<String> unitKeys, FleetFormation formation) {
        Group group = group(number);
        group.keys.clear();
        addCleanKeys(group.keys, unitKeys);
        group.formation = formation == null ? FleetFormation.GRID : formation;
        if (group.keys.isEmpty() && activeGroup == number) activeGroup = -1;
    }

    void add(int number, Collection<String> unitKeys) {
        addCleanKeys(group(number).keys, unitKeys);
    }

    void remove(int number, Collection<String> unitKeys) {
        Group group = group(number);
        if (unitKeys != null) group.keys.removeAll(unitKeys);
        if (group.keys.isEmpty() && activeGroup == number) activeGroup = -1;
    }

    void clear(int number) {
        group(number).keys.clear();
        if (activeGroup == number) activeGroup = -1;
    }

    void prune(Map<String, String> liveLocations) {
        Map<String, String> live = liveLocations == null ? Map.of() : liveLocations;
        for (int i = 0; i < groups.length; i++) {
            groups[i].keys.removeIf(key -> !live.containsKey(key));
            if (groups[i].keys.isEmpty() && activeGroup == i) activeGroup = -1;
        }
    }

    boolean empty(int number) { return group(number).keys.isEmpty(); }
    int size(int number) { return group(number).keys.size(); }
    int activeGroup() { return activeGroup; }
    FleetFormation formation(int number) { return group(number).formation; }
    Set<String> keys(int number) { return Set.copyOf(group(number).keys); }
    boolean contains(int number, String key) { return key != null && group(number).keys.contains(key); }

    void markActive(int number, String systemId) {
        Group group = group(number);
        activeGroup = group.keys.isEmpty() ? -1 : number;
        if (activeGroup >= 0 && systemId != null && !systemId.isBlank()) group.preferredSystemId = systemId;
    }

    boolean rememberFormationIfSelectionMatches(Collection<String> selectedKeys, String activeSystemId,
                                                 Map<String, String> locations, FleetFormation formation) {
        if (activeGroup < 0 || formation == null || activeSystemId == null || activeSystemId.isBlank()) return false;
        Set<String> expected = keysInSystem(activeGroup, activeSystemId, locations);
        Set<String> selected = cleanSet(selectedKeys);
        if (expected.isEmpty() || !expected.equals(selected)) return false;
        groups[activeGroup].formation = formation;
        groups[activeGroup].preferredSystemId = activeSystemId;
        return true;
    }

    GroupView view(int number, String activeSystemId, Map<String, String> locations) {
        Group group = group(number);
        Map<String, Integer> counts = systemCounts(group, locations);
        int living = 0;
        for (int count : counts.values()) living += count;
        int local = activeSystemId == null ? 0 : counts.getOrDefault(activeSystemId, 0);
        return new GroupView(number, living, local, counts.size(), group.formation,
                Collections.unmodifiableMap(counts));
    }

    Set<String> keysInSystem(int number, String systemId, Map<String, String> locations) {
        if (systemId == null || systemId.isBlank()) return Set.of();
        Map<String, String> safe = locations == null ? Map.of() : locations;
        Set<String> out = new LinkedHashSet<>();
        for (String key : group(number).keys) if (systemId.equals(safe.get(key))) out.add(key);
        return Set.copyOf(out);
    }

    String focusSystem(int number, String activeSystemId, Map<String, String> locations) {
        Group group = group(number);
        Map<String, Integer> counts = systemCounts(group, locations);
        if (counts.isEmpty()) return "";
        int largest = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (activeSystemId != null && counts.getOrDefault(activeSystemId, 0) == largest) {
            group.preferredSystemId = activeSystemId;
            return activeSystemId;
        }
        if (!group.preferredSystemId.isBlank() && counts.getOrDefault(group.preferredSystemId, 0) == largest) {
            return group.preferredSystemId;
        }
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) if (entry.getValue() == largest) candidates.add(entry.getKey());
        candidates.sort(Comparator.naturalOrder());
        String selected = candidates.isEmpty() ? "" : candidates.get(0);
        if (!selected.isBlank()) group.preferredSystemId = selected;
        return selected;
    }

    boolean registerTap(int number, long nowNanos) {
        group(number);
        boolean doubleTap = lastTapGroup == number && lastTapNanos > 0
                && nowNanos >= lastTapNanos && nowNanos - lastTapNanos <= DOUBLE_TAP_NANOS;
        if (doubleTap) {
            lastTapGroup = -1;
            lastTapNanos = 0;
        } else {
            lastTapGroup = number;
            lastTapNanos = nowNanos;
        }
        return doubleTap;
    }

    static int numberForKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9) return keyCode - KeyEvent.VK_0;
        if (keyCode >= KeyEvent.VK_NUMPAD0 && keyCode <= KeyEvent.VK_NUMPAD9) return keyCode - KeyEvent.VK_NUMPAD0;
        return -1;
    }

    private Map<String, Integer> systemCounts(Group group, Map<String, String> locations) {
        Map<String, String> safe = locations == null ? Map.of() : locations;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : group.keys) {
            String systemId = safe.get(key);
            if (systemId == null || systemId.isBlank()) continue;
            counts.merge(systemId, 1, Integer::sum);
        }
        return counts;
    }

    private Group group(int number) {
        if (number < 0 || number >= GROUP_COUNT) throw new IllegalArgumentException("Control group must be 0-9.");
        return groups[number];
    }

    private static void addCleanKeys(Set<String> target, Collection<String> unitKeys) {
        if (unitKeys == null) return;
        for (String key : unitKeys) if (key != null && !key.isBlank()) target.add(key);
    }

    private static Set<String> cleanSet(Collection<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        addCleanKeys(out, keys);
        return out;
    }

    record GroupView(int number, int livingShips, int shipsInActiveSystem, int systemCount,
                     FleetFormation formation, Map<String, Integer> shipsBySystem) { }

    private static final class Group {
        final LinkedHashSet<String> keys = new LinkedHashSet<>();
        FleetFormation formation = FleetFormation.GRID;
        String preferredSystemId = "";
    }
}
