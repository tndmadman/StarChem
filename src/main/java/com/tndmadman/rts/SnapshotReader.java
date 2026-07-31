package com.tndmadman.rts;

import java.util.*;

final class SnapshotReader {
    static final int MAX_SNAPSHOT_CHARS = TcpFrameCodec.MAX_FRAME_BYTES;
    static final int MAX_PLAYERS = 256;
    static final int MAX_UNITS = 8192;
    static final int MAX_RESOURCES = 8192;
    static final int MAX_BASES = 4096;
    static final int MAX_STOCKS = 256;
    static final int MAX_SHOTS = 16384;
    static final int MAX_ITEMS = 16384;
    static final int MAX_RESEARCH_STATES = MAX_PLAYERS;
    static final int MAX_TEXT_LENGTH = 256;
    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_CARGO_LENGTH = 200_000;
    static final int MAX_SYSTEM_FIELD_LENGTH = 250_000;
    static final double MAX_ABS_COORDINATE = 10_000_000.0;
    static final double MAX_SCALAR = 1_000_000_000_000.0;

    private SnapshotReader() { }

    static Snapshot read(String message) {
        if (message == null || message.isBlank()) throw error("snapshot", 0, "message", "payload is empty");
        if (message.length() > MAX_SNAPSHOT_CHARS) {
            throw error("snapshot", 0, "message", "payload exceeds " + MAX_SNAPSHOT_CHARS + " characters");
        }

        String[] p = message.split("\\|", -1);
        boolean current = p.length > 0 && "SNAPSHOT".equals(p[0]);
        boolean legacy = p.length > 0 && "SNAP".equals(p[0]);
        if (!current && !legacy) throw error("snapshot", 0, "header", "expected SNAPSHOT");
        if (current && p.length != 12 && p.length != 13) {
            throw error("snapshot", 0, "sections", "expected 12 or 13 sections but found " + p.length);
        }
        if (legacy && (p.length < 4 || p.length > 11)) {
            throw error("snapshot", 0, "sections", "legacy frame must contain 4-11 sections");
        }

        long sequence = longNumber(p[1], 0, Long.MAX_VALUE, "snapshot", 0, "sequence");
        List<PlayerInfo> players = readPlayers(p.length > 2 ? p[2] : "");
        List<UnitState> units = readUnits(p.length > 3 ? p[3] : "");
        List<ResourceState> resources = SnapshotReader2.resources(p);
        List<BaseState> bases = SnapshotReader2.bases(p);
        List<StockState> stocks = SnapshotReader2.stocks(p);
        List<ShotState> shots = SnapshotReader2.shots(p);
        List<ItemState> items = SnapshotReader2.items(p);
        List<ResearchState> research = SnapshotReader2.research(p);
        ObjectiveState objective = SnapshotReader2.objective(p);

        String systemId = "";
        double systemTime = -1;
        if (p.length > 10) {
            systemId = text(p[9], MAX_SYSTEM_FIELD_LENGTH, "snapshot", 0, "system");
            if (!p[10].isBlank()) systemTime = finite(p[10], -1, MAX_SCALAR, "snapshot", 0, "system time");
        } else if (p.length > 9 && !p[9].isBlank()) {
            try {
                systemTime = finite(p[9], -1, MAX_SCALAR, "snapshot", 0, "system time");
            } catch (SnapshotDecodeException ex) {
                systemId = text(p[9], MAX_SYSTEM_FIELD_LENGTH, "snapshot", 0, "system");
            }
        }

        Snapshot snapshot = new Snapshot(sequence, List.copyOf(players), List.copyOf(units), List.copyOf(resources),
                List.copyOf(bases), List.copyOf(stocks), List.copyOf(shots), List.copyOf(items), systemId, systemTime,
                "", List.copyOf(research), objective);
        SnapshotValidator.validate(snapshot);
        return snapshot;
    }

    private static List<PlayerInfo> readPlayers(String section) {
        List<PlayerInfo> players = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String[] rows = rows(section, MAX_PLAYERS, "players");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = columns(rows[i], "players", rowIndex);
            requireColumns(c.length, "players", rowIndex, 3);
            String id = requiredText(c[0], 64, "players", rowIndex, "player ID");
            String name = requiredText(c[1], MAX_NAME_LENGTH, "players", rowIndex, "name");
            int rgb = integer(c[2], Integer.MIN_VALUE, Integer.MAX_VALUE, "players", rowIndex, "color");
            if (!ids.add(id)) throw error("players", rowIndex, "player ID", "duplicate value " + printable(id));
            players.add(new PlayerInfo(id, name, rgb, false));
        }
        return players;
    }

    private static List<UnitState> readUnits(String section) {
        List<UnitState> units = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        String[] rows = rows(section, MAX_UNITS, "units");
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] c = columns(rows[i], "units", rowIndex);
            requireColumns(c.length, "units", rowIndex, 12, 13, 14, 15, 16, 24, 25);

            String playerId = requiredText(c[0], 64, "units", rowIndex, "player ID");
            int unitId = integer(c[1], 0, Integer.MAX_VALUE, "units", rowIndex, "unit ID");
            String shipId = requiredText(c[2], 128, "units", rowIndex, "ship type ID");
            ShipType ship = Rules.findShip(shipId);
            if (ship == null) throw error("units", rowIndex, "ship type ID", "unknown value " + printable(shipId));
            String key = playerId + ':' + unitId;
            if (!keys.add(key)) throw error("units", rowIndex, "unit ID", "duplicate unit " + printable(key));

            double x = coordinate(c[3], "units", rowIndex, "x");
            double y = coordinate(c[4], "units", rowIndex, "y");
            double targetX = coordinate(c[5], "units", rowIndex, "target x");
            double targetY = coordinate(c[6], "units", rowIndex, "target y");
            double heading = finite(c[7], -MAX_SCALAR, MAX_SCALAR, "units", rowIndex, "heading");
            String task = enumName(c[8], UnitTask.class, "units", rowIndex, "task");
            int resourceId = integer(c[9], -1, Integer.MAX_VALUE, "units", rowIndex, "resource ID");
            String packageType = text(CargoCodec.unsafed(c[10]), 128, "units", rowIndex, "station package type");
            if (!packageType.isBlank() && Rules.findBase(packageType) == null) {
                throw error("units", rowIndex, "station package type ID", "unknown value " + printable(packageType));
            }
            String cargo = CargoCodec.unsafed(c[11]);
            validateCargo(cargo, "units", rowIndex, "cargo");

            boolean v2 = c.length == 16 || c.length == 24 || c.length == 25;
            boolean v3 = c.length == 24 || c.length == 25;
            double hp = c.length >= 13 ? finite(c[12], 0, MAX_SCALAR, "units", rowIndex, "hp") : ship.maxHp;
            double shield = v2 ? finite(c[13], 0, MAX_SCALAR, "units", rowIndex, "shield") : ship.maxShield;
            String attackTarget = v2 ? CargoCodec.unsafed(c[14]) : c.length >= 14 ? CargoCodec.unsafed(c[13]) : "";
            attackTarget = text(attackTarget, MAX_TEXT_LENGTH, "units", rowIndex, "attack target");
            double flash = v2 ? finite(c[15], 0, MAX_SCALAR, "units", rowIndex, "weapon flash")
                    : c.length >= 15 ? finite(c[14], 0, MAX_SCALAR, "units", rowIndex, "weapon flash") : 0;
            String orderType = v3 ? enumName(c[16], UnitOrderType.class, "units", rowIndex, "order type") : UnitOrderType.NONE.name();
            double orderX1 = v3 ? coordinate(c[17], "units", rowIndex, "order x1") : 0;
            double orderY1 = v3 ? coordinate(c[18], "units", rowIndex, "order y1") : 0;
            double orderX2 = v3 ? coordinate(c[19], "units", rowIndex, "order x2") : 0;
            double orderY2 = v3 ? coordinate(c[20], "units", rowIndex, "order y2") : 0;
            double orderRadius = v3 ? finite(c[21], 0, MAX_ABS_COORDINATE, "units", rowIndex, "order radius") : 0;
            String orderTarget = v3 ? text(CargoCodec.unsafed(c[22]), MAX_TEXT_LENGTH, "units", rowIndex, "order target") : "";
            int orderPhase = v3 ? integer(c[23], 0, 1_000_000, "units", rowIndex, "order phase") : 0;
            String loadoutId = c.length == 25
                    ? text(CargoCodec.unsafed(c[24]), 128, "units", rowIndex, "loadout ID")
                    : WeaponRules.defaultLoadoutId(shipId);
            ShipLoadoutDefinition loadout = WeaponRules.findLoadout(loadoutId);
            if (loadout == null || !shipId.equals(loadout.hullId())) {
                throw error("units", rowIndex, "loadout ID", "unknown or mismatched value " + printable(loadoutId));
            }

            units.add(new UnitState(playerId, unitId, shipId, x, y, targetX, targetY, heading, task, resourceId,
                    packageType, cargo, hp, shield, attackTarget, flash, orderType, orderX1, orderY1, orderX2,
                    orderY2, orderRadius, orderTarget, orderPhase, loadoutId));
        }
        return units;
    }

    static String[] rows(String section, int maxRows, String sectionName) {
        if (section == null || section.isBlank()) return new String[0];
        if (section.length() > MAX_SNAPSHOT_CHARS) throw error(sectionName, 0, "section", "section is too large");
        String[] rows = section.split(";", -1);
        if (rows.length > maxRows) throw error(sectionName, 0, "count", "contains more than " + maxRows + " rows");
        for (int i = 0; i < rows.length; i++) if (rows[i].isEmpty()) throw error(sectionName, i + 1, "row", "row is empty");
        return rows;
    }

    static String[] columns(String row, String section, int rowIndex) {
        if (row == null || row.isEmpty()) throw error(section, rowIndex, "row", "row is empty");
        if (row.length() > MAX_SNAPSHOT_CHARS) throw error(section, rowIndex, "row", "row is too large");
        return row.split(",", -1);
    }

    static void requireColumns(int actual, String section, int rowIndex, int... allowed) {
        for (int count : allowed) if (actual == count) return;
        throw error(section, rowIndex, "columns", "unexpected column count " + actual);
    }

    static int integer(String value, int min, int max, String section, int rowIndex, String field) {
        final int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (RuntimeException ex) { throw error(section, rowIndex, field, "must be an integer"); }
        if (parsed < min || parsed > max) throw error(section, rowIndex, field, "is outside the allowed range");
        return parsed;
    }

    static long longNumber(String value, long min, long max, String section, int rowIndex, String field) {
        final long parsed;
        try { parsed = Long.parseLong(value); }
        catch (RuntimeException ex) { throw error(section, rowIndex, field, "must be an integer"); }
        if (parsed < min || parsed > max) throw error(section, rowIndex, field, "is outside the allowed range");
        return parsed;
    }

    static double finite(String value, double min, double max, String section, int rowIndex, String field) {
        final double parsed;
        try { parsed = Double.parseDouble(value); }
        catch (RuntimeException ex) { throw error(section, rowIndex, field, "must be numeric"); }
        if (!Double.isFinite(parsed)) throw error(section, rowIndex, field, "must be finite");
        if (parsed < min || parsed > max) throw error(section, rowIndex, field, "is outside the allowed range");
        return parsed;
    }

    static double coordinate(String value, String section, int rowIndex, String field) {
        return finite(value, -MAX_ABS_COORDINATE, MAX_ABS_COORDINATE, section, rowIndex, field);
    }

    static boolean flag(String value, String section, int rowIndex, String field) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw error(section, rowIndex, field, "must be true or false");
    }

    static String text(String value, int maxLength, String section, int rowIndex, String field) {
        String checked = value == null ? "" : value;
        if (checked.length() > maxLength) throw error(section, rowIndex, field, "exceeds " + maxLength + " characters");
        for (int i = 0; i < checked.length(); i++) {
            if (Character.isISOControl(checked.charAt(i))) throw error(section, rowIndex, field, "contains a control character");
        }
        return checked;
    }

    static String requiredText(String value, int maxLength, String section, int rowIndex, String field) {
        String checked = text(value, maxLength, section, rowIndex, field);
        if (checked.isBlank() || "-".equals(checked)) throw error(section, rowIndex, field, "is required");
        return checked;
    }

    static <E extends Enum<E>> String enumName(String value, Class<E> type, String section, int rowIndex, String field) {
        try { return Enum.valueOf(type, value).name(); }
        catch (RuntimeException ex) { throw error(section, rowIndex, field, "has unknown value " + printable(value)); }
    }

    static void validateCargo(String cargo, String section, int rowIndex, String field) {
        if (cargo == null || cargo.isBlank() || "-".equals(cargo)) return;
        if (cargo.length() > MAX_CARGO_LENGTH) throw error(section, rowIndex, field, "exceeds " + MAX_CARGO_LENGTH + " characters");
        Set<Material> materials = EnumSet.noneOf(Material.class);
        String[] entries = cargo.split("~", -1);
        if (entries.length > Material.values().length) throw error(section, rowIndex, field, "contains too many material entries");
        for (String entry : entries) {
            String[] pair = entry.split(":", -1);
            if (pair.length != 2) throw error(section, rowIndex, field, "contains a malformed material entry");
            Material material;
            try { material = Material.valueOf(pair[0]); }
            catch (RuntimeException ex) { throw error(section, rowIndex, field, "contains unknown material " + printable(pair[0])); }
            if (!materials.add(material)) throw error(section, rowIndex, field, "contains duplicate material " + material.name());
            finite(pair[1], 0, MAX_SCALAR, section, rowIndex, field + " amount");
        }
    }

    static SnapshotDecodeException error(String section, int rowIndex, String field, String reason) {
        StringBuilder message = new StringBuilder("Snapshot rejected: malformed ").append(section);
        if (rowIndex > 0) message.append(" row ").append(rowIndex);
        if (field != null && !field.isBlank()) message.append(" field ").append(field);
        message.append(" - ").append(reason).append('.');
        return new SnapshotDecodeException(message.toString());
    }

    static String printable(String value) {
        if (value == null || value.isBlank()) return "<blank>";
        return value.length() <= 48 ? value : value.substring(0, 45) + "...";
    }
}
