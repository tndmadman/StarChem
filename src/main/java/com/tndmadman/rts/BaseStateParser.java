package com.tndmadman.rts;

final class BaseStateParser {
    private BaseStateParser() { }

    static BaseState parse(String row) {
        String[] c = row.split(",", -1);
        if (c.length < 5) return null;
        BaseType type = Rules.findBase(c[2]);
        if (type == null) {
            throw new SnapshotDecodeException("Snapshot rejected: unknown station type ID " + c[2] + " for base " + c[0] + ".");
        }
        double hp = c.length >= 7 ? Double.parseDouble(c[5]) : type.maxHp;
        double shield = c.length >= 8 ? Double.parseDouble(c[6]) : type.maxShield;
        String cargo = c.length >= 8 ? CargoCodec.unsafed(c[7]) : c.length >= 7 ? CargoCodec.unsafed(c[6]) : c.length >= 6 ? CargoCodec.unsafed(c[5]) : "";
        String productionQueue = c.length >= 9 ? CargoCodec.unsafed(c[8]) : "";
        return new BaseState(c[0], c[1], c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4]), hp, shield, cargo, productionQueue);
    }
}
