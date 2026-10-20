package com.tndmadman.rts;

final class BaseStateParser {
    static final int MAX_LOGISTICS_STATUS_CHARS = 64_000;

    private BaseStateParser() { }

    static BaseState parse(String row) { return parse(row, 1); }

    static BaseState parse(String row, int rowIndex) {
        String[] c = SnapshotReader.columns(row, "bases", rowIndex);
        SnapshotReader.requireColumns(c.length, "bases", rowIndex, 5, 6, 7, 8, 9, 10, 14);
        String id = SnapshotReader.requiredText(c[0], 128, "bases", rowIndex, "base ID");
        String playerId = SnapshotReader.requiredText(c[1], 64, "bases", rowIndex, "player ID");
        String typeId = SnapshotReader.requiredText(c[2], 128, "bases", rowIndex, "station type ID");
        BaseType type = Rules.findBase(typeId);
        if (type == null) throw SnapshotReader.error("bases", rowIndex, "station type ID", "unknown value " + SnapshotReader.printable(typeId));

        double x = SnapshotReader.coordinate(c[3], "bases", rowIndex, "x");
        double y = SnapshotReader.coordinate(c[4], "bases", rowIndex, "y");
        double hp = c.length >= 7 ? SnapshotReader.finite(c[5], 0, SnapshotReader.MAX_SCALAR, "bases", rowIndex, "hp") : type.maxHp;
        double shield = c.length >= 8 ? SnapshotReader.finite(c[6], 0, SnapshotReader.MAX_SCALAR, "bases", rowIndex, "shield") : type.maxShield;
        String cargo = c.length >= 8 ? CargoCodec.unsafed(c[7])
                : c.length >= 7 ? CargoCodec.unsafed(c[6])
                : c.length >= 6 ? CargoCodec.unsafed(c[5]) : "";
        SnapshotReader.validateCargo(cargo, "bases", rowIndex, "cargo");
        String productionQueue = c.length >= 9
                ? SnapshotReader.text(CargoCodec.unsafed(c[8]), SnapshotReader.MAX_SNAPSHOT_CHARS, "bases", rowIndex, "production queue")
                : "";
        String logisticsStatus = c.length >= 10
                ? SnapshotReader.text(CargoCodec.unsafed(c[9]), MAX_LOGISTICS_STATUS_CHARS,
                        "bases", rowIndex, "logistics status")
                : "";
        String celestialAnchorBodyId = c.length == 14
                ? SnapshotReader.text(CargoCodec.unsafed(c[10]), 128, "bases", rowIndex, "celestial anchor body ID")
                : "";
        double celestialOrbitRadius = c.length == 14
                ? SnapshotReader.finite(c[11], 0, SnapshotReader.MAX_ABS_COORDINATE, "bases", rowIndex, "celestial orbit radius") : 0;
        double celestialOrbitAngle = c.length == 14
                ? SnapshotReader.finite(c[12], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "bases", rowIndex, "celestial orbit angle") : 0;
        double celestialOrbitSpeed = c.length == 14
                ? SnapshotReader.finite(c[13], -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "bases", rowIndex, "celestial orbit speed") : 0;
        return new BaseState(id, playerId, typeId, x, y, hp, shield, cargo, productionQueue, logisticsStatus,
                celestialAnchorBodyId, celestialOrbitRadius, celestialOrbitAngle, celestialOrbitSpeed);
    }
}
