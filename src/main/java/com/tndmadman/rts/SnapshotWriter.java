package com.tndmadman.rts;

final class SnapshotWriter {
    private SnapshotWriter() { }

    static String write(Snapshot snapshot) {
        StringBuilder players = new StringBuilder();
        for (PlayerInfo player : snapshot.players()) {
            if (!players.isEmpty()) players.append(';');
            players.append(player.id()).append(',').append(player.name()).append(',').append(player.rgb());
        }
        StringBuilder units = new StringBuilder();
        for (UnitState unit : snapshot.units()) {
            if (!units.isEmpty()) units.append(';');
            units.append(unit.playerId()).append(',').append(unit.unitId()).append(',').append(unit.shipTypeId()).append(',')
                    .append(Calc.round(unit.x())).append(',').append(Calc.round(unit.y())).append(',')
                    .append(Calc.round(unit.targetX())).append(',').append(Calc.round(unit.targetY())).append(',')
                    .append(Calc.round(unit.heading())).append(',').append(unit.task()).append(',').append(unit.resourceId()).append(',')
                    .append(CargoCodec.safe(unit.packageType())).append(',').append(CargoCodec.safe(unit.cargo()));
        }
        StringBuilder resources = new StringBuilder();
        for (ResourceState r : snapshot.resources()) {
            if (!resources.isEmpty()) resources.append(';');
            resources.append(r.id()).append(',').append(r.name()).append(',').append(r.kind()).append(',').append(r.material()).append(',')
                    .append(Calc.round(r.x())).append(',').append(Calc.round(r.y())).append(',')
                    .append(Calc.round(r.maxAmount())).append(',').append(Calc.round(r.harvestRate())).append(',')
                    .append(Calc.round(r.radius())).append(',').append(Calc.round(r.amount())).append(',')
                    .append(r.active()).append(',').append(Calc.round(r.respawnTimer()));
        }
        StringBuilder bases = new StringBuilder();
        for (BaseState base : snapshot.bases()) {
            if (!bases.isEmpty()) bases.append(';');
            bases.append(base.id()).append(',').append(base.playerId()).append(',').append(base.typeId()).append(',')
                    .append(Calc.round(base.x())).append(',').append(Calc.round(base.y())).append(',')
                    .append(CargoCodec.safe(base.cargo()));
        }
        StringBuilder stocks = new StringBuilder();
        for (StockState stock : snapshot.stocks()) {
            if (!stocks.isEmpty()) stocks.append(';');
            stocks.append(stock.playerId()).append(',').append(CargoCodec.safe(stock.cargo()));
        }
        return "SNAPSHOT|" + snapshot.sequence() + "|" + players + "|" + units + "|" + resources + "|" + bases + "|" + stocks;
    }
}
