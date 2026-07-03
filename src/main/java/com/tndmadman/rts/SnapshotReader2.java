package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class SnapshotReader2 {
    private SnapshotReader2() { }
    static List<ResourceState> resources(String[] parts) { return new ArrayList<>(); }

    static List<BaseState> bases(String[] parts) {
        List<BaseState> out = new ArrayList<>();
        if (parts.length <= 5 || parts[5].isBlank()) return out;
        for (String row : parts[5].split(";")) {
            String[] c = row.split(",", -1);
            if (c.length >= 5) out.add(new BaseState(c[0], c[1], c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4])));
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
}
