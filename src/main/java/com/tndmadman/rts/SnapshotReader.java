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
        if (p.length > 3 && !p[3].isBlank()) for (String row : p[3].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length >= 12) {
                double hp = c.length >= 13 ? Double.parseDouble(c[12]) : Rules.ship(c[2]).maxHp;
                String target = c.length >= 14 ? CargoCodec.unsafed(c[13]) : "";
                double flash = c.length >= 15 ? Double.parseDouble(c[14]) : 0;
                units.add(new UnitState(c[0], Integer.parseInt(c[1]), c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7]), c[8], Integer.parseInt(c[9]), CargoCodec.unsafed(c[10]), CargoCodec.unsafed(c[11]), hp, target, flash));
            }
        }
        return new Snapshot(seq, players, units, SnapshotReader2.resources(p), SnapshotReader2.bases(p), SnapshotReader2.stocks(p));
    }
}
