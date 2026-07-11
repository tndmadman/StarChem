package com.tndmadman.rts;

import java.util.*;

final class SnapshotReader {
    private SnapshotReader() { }

    static Snapshot read(String message) {
        String[] p = message.split("\\|", -1);
        long seq = p.length > 1 ? Long.parseLong(p[1]) : 0;
        List<PlayerInfo> players = new ArrayList<>();
        if (p.length > 2 && !p[2].isBlank()) for (String row : p[2].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length >= 3) players.add(new PlayerInfo(c[0], c[1], Integer.parseInt(c[2]), false));
        }
        List<UnitState> units = new ArrayList<>();
        if (p.length > 3 && !p[3].isBlank()) {
            int rowIndex = 0;
            for (String row : p[3].split(";")) {
                rowIndex++;
                String[] c = row.split(",", -1);
                if (c.length >= 12) {
                    ShipType ship = requireShip(c[2], rowIndex);
                    boolean v2 = c.length >= 16;
                    boolean v3 = c.length >= 24;
                    double hp = c.length >= 13 ? Double.parseDouble(c[12]) : ship.maxHp;
                    double layer = v2 ? Double.parseDouble(c[13]) : ship.maxShield;
                    String target = v2 ? CargoCodec.unsafed(c[14]) : c.length >= 14 ? CargoCodec.unsafed(c[13]) : "";
                    double flash = v2 ? Double.parseDouble(c[15]) : c.length >= 15 ? Double.parseDouble(c[14]) : 0;
                    String orderType = v3 ? c[16] : UnitOrderType.NONE.name();
                    double orderX1 = v3 ? Double.parseDouble(c[17]) : 0;
                    double orderY1 = v3 ? Double.parseDouble(c[18]) : 0;
                    double orderX2 = v3 ? Double.parseDouble(c[19]) : 0;
                    double orderY2 = v3 ? Double.parseDouble(c[20]) : 0;
                    double orderRadius = v3 ? Double.parseDouble(c[21]) : 0;
                    String orderTarget = v3 ? CargoCodec.unsafed(c[22]) : "";
                    int orderPhase = v3 ? Integer.parseInt(c[23]) : 0;
                    units.add(new UnitState(c[0], Integer.parseInt(c[1]), c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                            Double.parseDouble(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7]), c[8], Integer.parseInt(c[9]),
                            CargoCodec.unsafed(c[10]), CargoCodec.unsafed(c[11]), hp, layer, target, flash, orderType,
                            orderX1, orderY1, orderX2, orderY2, orderRadius, orderTarget, orderPhase));
                }
            }
        }
        String systemId = "";
        double systemTime = -1;
        if (p.length > 10) {
            CelestialPacketCache.receive(p[9]);
            systemId = CelestialPacketCache.systemId(p[9]);
            try { systemTime = Double.parseDouble(p[10]); } catch (NumberFormatException ignored) { }
        } else if (p.length > 9 && !p[9].isBlank()) {
            try { systemTime = Double.parseDouble(p[9]); } catch (NumberFormatException ignored) { systemId = p[9]; }
        }
        return new Snapshot(seq, players, units, SnapshotReader2.resources(p), SnapshotReader2.bases(p), SnapshotReader2.stocks(p), SnapshotReader2.shots(p), SnapshotReader2.items(p), systemId, systemTime);
    }

    private static ShipType requireShip(String id, int rowIndex) {
        ShipType ship = Rules.findShip(id);
        if (ship == null) {
            throw new SnapshotDecodeException("Snapshot rejected: unknown ship type ID " + id + " in unit row " + rowIndex + ".");
        }
        return ship;
    }
}
