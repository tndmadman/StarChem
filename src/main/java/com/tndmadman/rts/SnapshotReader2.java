package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class SnapshotReader2 {
    private SnapshotReader2() { }

    static List<ResourceState> resources(String[] parts) {
        List<ResourceState> out = new ArrayList<>();
        if (parts.length <= 4 || parts[4].isBlank()) return out;
        for (String row : parts[4].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length < 12) continue;
            int id = Integer.parseInt(c[0]);
            String name = c[1];
            String kind = c[2];
            String mat = c[3];
            double x = Double.parseDouble(c[4]);
            double y = Double.parseDouble(c[5]);
            double max = Double.parseDouble(c[6]);
            double rate = Double.parseDouble(c[7]);
            double radius = Double.parseDouble(c[8]);
            double amount = Double.parseDouble(c[9]);
            boolean active = Boolean.parseBoolean(c[10]);
            double timer = Double.parseDouble(c[11]);
            out.add(new ResourceState(id, name, kind, mat, x, y, max, rate, radius, amount, active, timer));
        }
        return out;
    }

    static List<BaseState> bases(String[] parts) {
        List<BaseState> out = new ArrayList<>();
        if (parts.length <= 5 || parts[5].isBlank()) return out;
        for (String row : parts[5].split(";")) {
            BaseState parsed = BaseStateParser.parse(row);
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    static List<StockState> stocks(String[] parts) {
        List<StockState> out = new ArrayList<>();
        if (parts.length <= 6 || parts[6].isBlank()) return out;
        for (String row : parts[6].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length >= 2) out.add(new StockState(c[0], CargoCodec.unsafed(c[1])));
        }
        return out;
    }

    static List<ShotState> shots(String[] parts) {
        List<ShotState> out = new ArrayList<>();
        if (parts.length <= 7 || parts[7].isBlank()) return out;
        for (String row : parts[7].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length >= 8) out.add(new ShotState(Integer.parseInt(c[0]), c[1], c[2], CargoCodec.unsafed(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7])));
        }
        return out;
    }

    static List<ItemState> items(String[] parts) {
        List<ItemState> out = new ArrayList<>();
        if (parts.length <= 8 || parts[8].isBlank()) return out;
        for (String row : parts[8].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length >= 9) out.add(new ItemState(Integer.parseInt(c[0]), c[1], Double.parseDouble(c[2]), Double.parseDouble(c[3]), Double.parseDouble(c[4]), Double.parseDouble(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7]), Double.parseDouble(c[8])));
        }
        return out;
    }
}
