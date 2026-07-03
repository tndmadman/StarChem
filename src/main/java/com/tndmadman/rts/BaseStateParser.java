package com.tndmadman.rts;

final class BaseStateParser {
    private BaseStateParser() { }

    static BaseState parse(String row) {
        String[] c = row.split(",", -1);
        if (c.length < 5) return null;
        double hp = c.length >= 7 ? Double.parseDouble(c[5]) : Rules.base(c[2]).maxHp;
        String cargo = c.length >= 7 ? CargoCodec.unsafed(c[6]) : c.length >= 6 ? CargoCodec.unsafed(c[5]) : "";
        return new BaseState(c[0], c[1], c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4]), hp, cargo);
    }
}
