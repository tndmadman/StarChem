package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SnapshotReader2 {
    private SnapshotReader2() { }

    static List<ResourceState> resources(String[] parts) {
        List<ResourceState> out = new ArrayList<>();
        if (parts.length <= 4) return out;
        Set<Integer> ids = new HashSet<>();
        String[] rows = SnapshotReader.rows(parts[4], SnapshotReader.MAX_RESOURCES, "resources");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = SnapshotReader.columns(rows[i], "resources", rowIndex);
            SnapshotReader.requireColumns(c.length, "resources", rowIndex, 12, 18);
            int id = SnapshotReader.integer(c[0], 0, Integer.MAX_VALUE, "resources", rowIndex, "resource ID");
            if (!ids.add(id)) throw SnapshotReader.error("resources", rowIndex, "resource ID", "duplicate value " + id);
            String name = SnapshotReader.requiredText(c[1], SnapshotReader.MAX_NAME_LENGTH, "resources", rowIndex, "name");
            String kind = SnapshotReader.enumName(c[2], NodeKind.class, "resources", rowIndex, "kind");
            String material = SnapshotReader.enumName(c[3], Material.class, "resources", rowIndex, "material");
            double x = SnapshotReader.coordinate(c[4], "resources", rowIndex, "x");
            double y = SnapshotReader.coordinate(c[5], "resources", rowIndex, "y");
            double max = SnapshotReader.finite(c[6], 0, SnapshotReader.MAX_SCALAR, "resources", rowIndex, "maximum amount");
            double rate = SnapshotReader.finite(c[7], 0, SnapshotReader.MAX_SCALAR, "resources", rowIndex, "harvest rate");
            double radius = SnapshotReader.finite(c[8], 0, SnapshotReader.MAX_ABS_COORDINATE, "resources", rowIndex, "radius");
            double amount = SnapshotReader.finite(c[9], 0, max, "resources", rowIndex, "amount");
            boolean active = SnapshotReader.flag(c[10], "resources", rowIndex, "active");
            double timer = SnapshotReader.finite(c[11], 0, SnapshotReader.MAX_SCALAR, "resources", rowIndex, "respawn timer");
            ResourceNetDebug.resourceSchema(c.length);
            if (c.length == 18) {
                out.add(new ResourceState(id, name, kind, material, x, y, max, rate, radius, amount, active, timer,
                        SnapshotReader.coordinate(c[12], "resources", rowIndex, "orbit center x"),
                        SnapshotReader.coordinate(c[13], "resources", rowIndex, "orbit center y"),
                        SnapshotReader.finite(c[14], 0, SnapshotReader.MAX_ABS_COORDINATE, "resources", rowIndex, "orbit radius"),
                        SnapshotReader.finite(c[15], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "resources", rowIndex, "orbit angle"),
                        SnapshotReader.finite(c[16], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "resources", rowIndex, "orbit speed"),
                        SnapshotReader.flag(c[17], "resources", rowIndex, "orbiting")));
            } else {
                out.add(new ResourceState(id, name, kind, material, x, y, max, rate, radius, amount, active, timer));
            }
        }
        return out;
    }

    static List<BaseState> bases(String[] parts) {
        List<BaseState> out = new ArrayList<>();
        if (parts.length <= 5) return out;
        Set<String> ids = new HashSet<>();
        String[] rows = SnapshotReader.rows(parts[5], SnapshotReader.MAX_BASES, "bases");
        for (int i = 0; i < rows.length; i++) {
            BaseState parsed = BaseStateParser.parse(rows[i], i + 1);
            if (!ids.add(parsed.id())) throw SnapshotReader.error("bases", i + 1, "base ID", "duplicate value " + SnapshotReader.printable(parsed.id()));
            out.add(parsed);
        }
        return out;
    }

    static List<StockState> stocks(String[] parts) {
        List<StockState> out = new ArrayList<>();
        if (parts.length <= 6) return out;
        Set<String> players = new HashSet<>();
        String[] rows = SnapshotReader.rows(parts[6], SnapshotReader.MAX_STOCKS, "stocks");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = SnapshotReader.columns(rows[i], "stocks", rowIndex);
            SnapshotReader.requireColumns(c.length, "stocks", rowIndex, 2);
            String playerId = SnapshotReader.requiredText(c[0], 64, "stocks", rowIndex, "player ID");
            if (!players.add(playerId)) throw SnapshotReader.error("stocks", rowIndex, "player ID", "duplicate value " + SnapshotReader.printable(playerId));
            String cargo = CargoCodec.unsafed(c[1]);
            SnapshotReader.validateCargo(cargo, "stocks", rowIndex, "cargo");
            out.add(new StockState(playerId, cargo));
        }
        return out;
    }

    static List<ShotState> shots(String[] parts) {
        List<ShotState> out = new ArrayList<>();
        if (parts.length <= 7) return out;
        Set<Integer> ids = new HashSet<>();
        String[] rows = SnapshotReader.rows(parts[7], SnapshotReader.MAX_SHOTS, "shots");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = SnapshotReader.columns(rows[i], "shots", rowIndex);
            SnapshotReader.requireColumns(c.length, "shots", rowIndex, 8);
            int id = SnapshotReader.integer(c[0], 0, Integer.MAX_VALUE, "shots", rowIndex, "shot ID");
            if (!ids.add(id)) throw SnapshotReader.error("shots", rowIndex, "shot ID", "duplicate value " + id);
            String owner = SnapshotReader.requiredText(c[1], 128, "shots", rowIndex, "owner ID");
            String weapon = SnapshotReader.requiredText(c[2], 128, "shots", rowIndex, "weapon ID");
            if (!WeaponRules.WEAPONS.containsKey(weapon)) throw SnapshotReader.error("shots", rowIndex, "weapon ID", "unknown value " + SnapshotReader.printable(weapon));
            String target = SnapshotReader.text(CargoCodec.unsafed(c[3]), SnapshotReader.MAX_TEXT_LENGTH, "shots", rowIndex, "target");
            out.add(new ShotState(id, owner, weapon, target,
                    SnapshotReader.coordinate(c[4], "shots", rowIndex, "x"),
                    SnapshotReader.coordinate(c[5], "shots", rowIndex, "y"),
                    SnapshotReader.coordinate(c[6], "shots", rowIndex, "last x"),
                    SnapshotReader.coordinate(c[7], "shots", rowIndex, "last y")));
        }
        return out;
    }

    static List<ItemState> items(String[] parts) {
        List<ItemState> out = new ArrayList<>();
        if (parts.length <= 8) return out;
        Set<Integer> ids = new HashSet<>();
        String[] rows = SnapshotReader.rows(parts[8], SnapshotReader.MAX_ITEMS, "items");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = SnapshotReader.columns(rows[i], "items", rowIndex);
            SnapshotReader.requireColumns(c.length, "items", rowIndex, 9);
            int id = SnapshotReader.integer(c[0], 0, Integer.MAX_VALUE, "items", rowIndex, "item ID");
            if (!ids.add(id)) throw SnapshotReader.error("items", rowIndex, "item ID", "duplicate value " + id);
            String material = SnapshotReader.enumName(c[1], Material.class, "items", rowIndex, "material");
            out.add(new ItemState(id, material,
                    SnapshotReader.finite(c[2], 0, SnapshotReader.MAX_SCALAR, "items", rowIndex, "amount"),
                    SnapshotReader.coordinate(c[3], "items", rowIndex, "x"),
                    SnapshotReader.coordinate(c[4], "items", rowIndex, "y"),
                    SnapshotReader.finite(c[5], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", rowIndex, "velocity x"),
                    SnapshotReader.finite(c[6], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", rowIndex, "velocity y"),
                    SnapshotReader.finite(c[7], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", rowIndex, "angle"),
                    SnapshotReader.finite(c[8], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", rowIndex, "spin")));
        }
        return out;
    }

    static List<ResearchState> research(String[] parts) {
        List<ResearchState> out = new ArrayList<>();
        if (parts.length <= 11) return out;
        Set<String> players = new HashSet<>();
        String[] rows = SnapshotReader.rows(parts[11], SnapshotReader.MAX_RESEARCH_STATES, "research");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = SnapshotReader.columns(rows[i], "research", rowIndex);
            SnapshotReader.requireColumns(c.length, "research", rowIndex, 2);
            String playerId = SnapshotReader.requiredText(c[0], 64, "research", rowIndex, "player ID");
            if (!players.add(playerId)) {
                throw SnapshotReader.error("research", rowIndex, "player ID", "duplicate value " + SnapshotReader.printable(playerId));
            }
            String packed = CargoCodec.unsafed(c[1]);
            List<String> topicIds = new ArrayList<>();
            Set<String> uniqueTopics = new HashSet<>();
            if (!packed.isBlank()) {
                for (String rawTopicId : packed.split("~", -1)) {
                    String topicId = SnapshotReader.requiredText(rawTopicId, 128, "research", rowIndex, "topic ID");
                    if (ResearchRules.topic(topicId) == null) {
                        throw SnapshotReader.error("research", rowIndex, "topic ID", "unknown value " + SnapshotReader.printable(topicId));
                    }
                    if (!uniqueTopics.add(topicId)) {
                        throw SnapshotReader.error("research", rowIndex, "topic ID", "duplicate value " + SnapshotReader.printable(topicId));
                    }
                    topicIds.add(topicId);
                }
            }
            out.add(new ResearchState(playerId, List.copyOf(topicIds)));
        }
        return out;
    }

    static ObjectiveState objective(String[] parts) {
        if (parts.length <= 12 || parts[12].isBlank()) return ObjectiveState.disabled();
        String[] c = SnapshotReader.columns(parts[12], "objective", 1);
        SnapshotReader.requireColumns(c.length, "objective", 1, 7);
        String conditionId = SnapshotReader.text(CargoCodec.unsafed(c[0]), 64,
                "objective", 1, "condition ID");
        ObjectiveStatus status;
        try { status = ObjectiveStatus.valueOf(c[1]); }
        catch (RuntimeException ex) {
            throw SnapshotReader.error("objective", 1, "status", "has unknown value " + SnapshotReader.printable(c[1]));
        }
        int current = SnapshotReader.integer(c[2], 0, Integer.MAX_VALUE,
                "objective", 1, "current progress");
        int target = SnapshotReader.integer(c[3], 0, Integer.MAX_VALUE,
                "objective", 1, "target");
        String leaderId = SnapshotReader.text(CargoCodec.unsafed(c[4]), 64,
                "objective", 1, "leader ID");
        String completedById = SnapshotReader.text(CargoCodec.unsafed(c[5]), 64,
                "objective", 1, "completed-by ID");
        double elapsed = SnapshotReader.finite(c[6], 0, SnapshotReader.MAX_SCALAR,
                "objective", 1, "elapsed seconds");
        return new ObjectiveState(conditionId, status, current, target, leaderId, completedById, elapsed);
    }
}
